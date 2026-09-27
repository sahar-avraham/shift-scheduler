package com.shiftscheduler.schedule;

import com.shiftscheduler.auth.CurrentUserProvider;
import com.shiftscheduler.domain.Assignment;
import com.shiftscheduler.domain.JobPosition;
import com.shiftscheduler.domain.Schedule;
import com.shiftscheduler.domain.ScheduleStatus;
import com.shiftscheduler.domain.Shift;
import com.shiftscheduler.domain.ShiftPreference;
import com.shiftscheduler.domain.ShiftRequirement;
import com.shiftscheduler.domain.ShiftType;
import com.shiftscheduler.repository.AssignmentRepository;
import com.shiftscheduler.repository.JobPositionRepository;
import com.shiftscheduler.repository.RosterChangeRepository;
import com.shiftscheduler.repository.ScheduleRepository;
import com.shiftscheduler.repository.ShiftPreferenceRepository;
import com.shiftscheduler.repository.ShiftRepository;
import com.shiftscheduler.repository.ShiftRequirementRepository;
import com.shiftscheduler.repository.ShiftTypeRepository;
import com.shiftscheduler.web.ConflictException;
import com.shiftscheduler.web.ErrorCode;
import com.shiftscheduler.web.ResourceNotFoundException;
import com.shiftscheduler.web.ValidationException;
import org.springframework.data.domain.Sort;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.shiftscheduler.notification.RosterChangeNotifier;
import com.shiftscheduler.notification.ScheduleNotifier;
import org.springframework.beans.factory.annotation.Value;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

@Service
public class ScheduleService {

    private static final int DAYS_IN_WEEK = 7;

    private final ScheduleRepository scheduleRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftRequirementRepository requirementRepository;
    private final ShiftTypeRepository shiftTypeRepository;
    private final JobPositionRepository jobPositionRepository;
    private final ShiftPreferenceRepository preferenceRepository;
    private final AssignmentRepository assignmentRepository;
    private final RosterChangeRepository rosterChangeRepository;
    private final ScheduleGuard guard;
    private final CurrentUserProvider currentUser;
    private final JmsTemplate jmsTemplate;
    private final int closesDaysBefore;
    private final LocalTime closesAtTime;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ShiftRepository shiftRepository,
                           ShiftRequirementRepository requirementRepository,
                           ShiftTypeRepository shiftTypeRepository,
                           JobPositionRepository jobPositionRepository,
                           ShiftPreferenceRepository preferenceRepository,
                           AssignmentRepository assignmentRepository,
                           RosterChangeRepository rosterChangeRepository,
                           ScheduleGuard guard,
                           CurrentUserProvider currentUser, JmsTemplate jmsTemplate,
                           @Value("${app.submission.closes-days-before}") int closesDaysBefore,
                           @Value("${app.submission.closes-at-time}") LocalTime closesAtTime) {
        this.scheduleRepository = scheduleRepository;
        this.shiftRepository = shiftRepository;
        this.requirementRepository = requirementRepository;
        this.shiftTypeRepository = shiftTypeRepository;
        this.jobPositionRepository = jobPositionRepository;
        this.preferenceRepository = preferenceRepository;
        this.assignmentRepository = assignmentRepository;
        this.rosterChangeRepository = rosterChangeRepository;
        this.guard = guard;
        this.currentUser = currentUser;
        this.jmsTemplate = jmsTemplate;
        this.closesDaysBefore = closesDaysBefore;
        this.closesAtTime = closesAtTime;
    }

    // Just the headings for the week picker. No shifts, no assignments.
    @Transactional(readOnly = true)
    public List<ScheduleSummaryResponse> findAll() {

        List<Schedule> schedules = scheduleRepository.findAllByOrderByWeekStartDesc();

        return schedules.stream()
                .map(schedule -> new ScheduleSummaryResponse(
                        schedule.getId(),
                        schedule.getWeekStart(),
                        weekEnd(schedule),
                        schedule.getStatus().name(),
                        schedule.getVersion()))
                .toList();
    }

    // The manager's build screen: every shift with what it needs.
    // Requirements come back in one query and are grouped here, instead of
    // asking per shift.
    @Transactional(readOnly = true)
    public ScheduleDetailResponse findById(Long id) {
        Schedule schedule = guard.require(id);

        List<Shift> shifts = shiftRepository.findByScheduleIdOrderByShiftDateAscIdAsc(id);

        Map<Long, List<ShiftRequirement>> byShift =
                requirementRepository.findByShiftScheduleIdOrderByIdAsc(id).stream()
                        .collect(Collectors.groupingBy(requirement -> requirement.getShift().getId()));

        List<ShiftResponse> shiftResponses = shifts.stream()
                .map(shift -> toShiftResponse(shift, byShift.getOrDefault(shift.getId(), List.of())))
                .toList();

        return new ScheduleDetailResponse(
                schedule.getId(),
                schedule.getWeekStart(),
                weekEnd(schedule),
                schedule.getStatus().name(),
                schedule.getVersion(),
                schedule.getSubmissionClosesAt(),
                // How many people are waiting to hear about a change. The screen
                // uses it to decide whether republishing does anything.
                rosterChangeRepository.countEmployeesByScheduleId(id),
                shiftResponses);
    }

    // One person's constraints. An employee always gets his own - the
    // employeeId parameter only works for a manager.
    @Transactional(readOnly = true)
    public MyWeekResponse myWeek(Long scheduleId, Long employeeId) {
        Schedule schedule = guard.require(scheduleId);

        // An employee only ever sees their own, whatever the request asks for.
        Long target = currentUser.isManager() && employeeId != null
                ? employeeId
                : currentUser.employeeId();

        Map<Long, ShiftPreference> preferences = preferenceRepository
                .findByShiftScheduleIdAndEmployeeIdOrderByShiftShiftDateAscIdAsc(
                        scheduleId, target)
                .stream()
                .collect(Collectors.toMap(
                        preference -> preference.getShift().getId(), Function.identity()));

        List<EmployeeShiftResponse> shifts =
                shiftRepository.findByScheduleIdOrderByShiftDateAscIdAsc(scheduleId).stream()
                        .map(shift -> toEmployeeShift(shift, preferences.get(shift.getId())))
                        .toList();

        return new MyWeekResponse(
                schedule.getId(),
                schedule.getWeekStart(),
                weekEnd(schedule),
                schedule.getStatus().name(),
                schedule.getStatus() == ScheduleStatus.COLLECTING,
                shifts);
    }

    @Transactional(readOnly = true)
    public RosterResponse roster(Long scheduleId) {
        Schedule schedule = guard.require(scheduleId);

        boolean published = schedule.getStatus() == ScheduleStatus.PUBLISHED;

        // Empty until the week is published, so the screen can say it isn't
        // out yet instead of showing an error.
        List<RosterShift> shifts = published ? buildRoster(scheduleId) : List.of();

        return new RosterResponse(
                schedule.getId(),
                schedule.getWeekStart(),
                weekEnd(schedule),
                schedule.getStatus().name(),
                published,
                shifts);
    }

    private List<RosterShift> buildRoster(Long scheduleId) {
        Long me = currentUser.employeeId();

        Map<Long, List<Assignment>> byShift = assignmentRepository
                .findByShiftScheduleIdOrderByShiftShiftDateAscIdAsc(scheduleId).stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getShift().getId()));

        return shiftRepository.findByScheduleIdOrderByShiftDateAscIdAsc(scheduleId).stream()
                .map(shift -> toRosterShift(shift, byShift.getOrDefault(shift.getId(), List.of()), me))
                .toList();
    }

    private RosterShift toRosterShift(Shift shift, List<Assignment> assignments, Long me) {
        ShiftType type = shift.getShiftType();

        List<RosterAssignment> people = assignments.stream()
                .map(assignment -> {
                    var employee = assignment.getEmployee();
                    return new RosterAssignment(
                            employee.getId(),
                            employee.getFullName(),
                            employee.getJobPosition().getName(),
                            employee.getId().equals(me));
                })
                .toList();

        boolean assignedToMe = people.stream().anyMatch(RosterAssignment::isMe);

        return new RosterShift(
                shift.getId(),
                shift.getShiftDate(),
                type.getName(),
                type.getStartTime(),
                type.getEndTime(),
                type.isCrossesMidnight(),
                assignedToMe,
                people);
    }

    // Builds the week: every day gets one shift of each active shift type,
    // and the staffing is copied from the week before.
    @Transactional
    public ScheduleDetailResponse create(ScheduleCreateRequest request) {
        LocalDate weekStart = request.weekStart();

        if (weekStart.getDayOfWeek() != DayOfWeek.SUNDAY) {
            throw new ValidationException("weekStart must be a Sunday");
        }

        if (scheduleRepository.existsByWeekStart(weekStart)) {
            throw new ConflictException(
                    "A schedule for the week starting " + weekStart + " already exists");
        }

        Schedule schedule = new Schedule();
        schedule.setWeekStart(weekStart);
        schedule.setStatus(ScheduleStatus.COLLECTING);
        schedule.setSubmissionClosesAt(request.submissionClosesAt() != null
                ? request.submissionClosesAt()
                : defaultDeadlineFor(weekStart));
        scheduleRepository.save(schedule);

        buildWeek(schedule);

        return findById(schedule.getId());
    }

    // Closes the submission window and opens the week for building.
    @Transactional
    public ScheduleDetailResponse lock(Long id, VersionedRequest request) {
        Schedule schedule = guard.require(id);
        guard.requireVersion(schedule, request.version());
        guard.requireStatus(schedule, ScheduleStatus.COLLECTING);

        schedule.setStatus(ScheduleStatus.DRAFT);
        guard.markChanged(schedule);

        return findById(id);
    }

    // The roster goes out and everyone gets a mail.
    @Transactional
    public ScheduleDetailResponse publish(Long id, VersionedRequest request) {
        Schedule schedule = guard.require(id);
        guard.requireVersion(schedule, request.version());
        guard.requireStatus(schedule, ScheduleStatus.DRAFT);

        schedule.setStatus(ScheduleStatus.PUBLISHED);
        guard.markChanged(schedule);
        jmsTemplate.convertAndSend(ScheduleNotifier.QUEUE, id);

        return findById(id);
    }

    // Sends the mails for changes made after the week went out. The status
    // stays PUBLISHED - the roster already shows the change, this only tells
    // people about it.
    @Transactional
    public ScheduleDetailResponse republish(Long id, VersionedRequest request) {
        Schedule schedule = guard.require(id);
        guard.requireVersion(schedule, request.version());
        guard.requireStatus(schedule, ScheduleStatus.PUBLISHED);

        if (rosterChangeRepository.countEmployeesByScheduleId(id) == 0) {
            throw new ConflictException("Nothing has changed since this week was published");
        }

        guard.markChanged(schedule);
        jmsTemplate.convertAndSend(RosterChangeNotifier.QUEUE, id);

        return findById(id);
    }

    @Transactional
    // Takes the full list for one shift and writes it over the old one.
    // The flush forces the delete out before the inserts to avoid a clash.
    public ShiftResponse replaceRequirements(Long scheduleId,
                                             Long shiftId,
                                             ShiftRequirementsUpdateRequest request) {
        Schedule schedule = guard.require(scheduleId);
        guard.requireVersion(schedule, request.version());
        guard.requireStatus(schedule, ScheduleStatus.COLLECTING, ScheduleStatus.DRAFT);

        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new ResourceNotFoundException("Shift " + shiftId + " not found"));

        if (!shift.getSchedule().getId().equals(scheduleId)) {
            throw new ValidationException(
                    "Shift " + shiftId + " does not belong to schedule " + scheduleId);
        }

        Set<Long> seen = new HashSet<>();
        for (RequirementSpec spec : request.requirements()) {
            if (!seen.add(spec.jobPositionId())) {
                throw new ValidationException(
                        "Job position " + spec.jobPositionId() + " appears more than once");
            }
        }

        Map<Long, JobPosition> positions = loadPositions(request.requirements());

        requirementRepository.deleteByShiftId(shiftId);
        requirementRepository.flush();

        List<ShiftRequirement> replacements = request.requirements().stream()
                .filter(spec -> spec.requiredCount() > 0)
                .map(spec -> newRequirement(
                        shift, positions.get(spec.jobPositionId()),
                        spec.requiredCount(), spec.essentialOrDefault()))
                .toList();

        requirementRepository.saveAll(replacements);
        guard.markChanged(schedule);

        return toShiftResponse(shift, replacements);
    }

    // Matches on day of the week and shift type, so a shift type that didn't
    // exist last week simply has nothing to copy and starts empty.
    private void copyRequirements(Schedule schedule, List<Shift> shifts) {
        Schedule source = scheduleRepository
                .findFirstByWeekStartLessThanOrderByWeekStartDesc(schedule.getWeekStart())
                .orElse(null);

        if (source == null) {
            return;
        }

        Map<String, List<ShiftRequirement>> previous =
                requirementRepository.findByShiftScheduleIdOrderByIdAsc(source.getId()).stream()
                        .collect(Collectors.groupingBy(
                                requirement -> key(source.getWeekStart(), requirement.getShift())));

        List<ShiftRequirement> copies = new ArrayList<>();

        for (Shift shift : shifts) {
            for (ShiftRequirement original :
                    previous.getOrDefault(key(schedule.getWeekStart(), shift), List.of())) {

                // copies only the active positions
                if (!original.getJobPosition().isActive()) {
                    continue;
                }

                copies.add(newRequirement(shift, original.getJobPosition(),
                        original.getRequiredCount(), original.isEssential()));
            }
        }

        requirementRepository.saveAll(copies);
    }

    private static String key(LocalDate weekStart, Shift shift) {
        long dayOffset = ChronoUnit.DAYS.between(weekStart, shift.getShiftDate());
        return dayOffset + "|" + shift.getShiftType().getName();
    }

    private ShiftRequirement newRequirement(Shift shift, JobPosition position,
                                            int count, boolean essential) {
        ShiftRequirement requirement = new ShiftRequirement();
        requirement.setShift(shift);
        requirement.setJobPosition(position);
        requirement.setRequiredCount(count);
        requirement.setEssential(essential);
        return requirement;
    }

    private Map<Long, JobPosition> loadPositions(List<RequirementSpec> specs) {
        Set<Long> ids = specs.stream()
                .map(RequirementSpec::jobPositionId)
                .collect(Collectors.toSet());

        // Drops a position that was removed, so the loop below refuses it as
        // not found.
        Map<Long, JobPosition> found = jobPositionRepository.findAllById(ids).stream()
                .filter(JobPosition::isActive)
                .collect(Collectors.toMap(JobPosition::getId, Function.identity()));

        for (Long id : ids) {
            if (!found.containsKey(id)) {
                throw new ResourceNotFoundException("Job position " + id + " not found");
            }
        }

        return found;
    }


    private LocalDate weekEnd(Schedule schedule) {
        return schedule.getWeekStart().plusDays(DAYS_IN_WEEK - 1L);
    }

    private EmployeeShiftResponse toEmployeeShift(Shift shift, ShiftPreference preference)
    {
        ShiftType type = shift.getShiftType();

        return new EmployeeShiftResponse(
                shift.getId(),
                shift.getShiftDate(),
                type.getName(),
                type.getStartTime(),
                type.getEndTime(),
                type.isCrossesMidnight(),
                preference == null ? null : preference.getId(),
                preference == null ? null : preference.getType().name(),
                preference == null ? null : preference.getReason(),
                preference == null ? null : preference.getVersion());
    }

    private ShiftResponse toShiftResponse(Shift shift, List<ShiftRequirement> requirements) {
        ShiftType type = shift.getShiftType();

        List<RequirementResponse> requirementResponses = requirements.stream()
                .map(requirement -> new RequirementResponse(
                        requirement.getId(),
                        requirement.getJobPosition().getId(),
                        requirement.getJobPosition().getName(),
                        requirement.getRequiredCount(),
                        requirement.isEssential()))
                .toList();

        return new ShiftResponse(
                shift.getId(),
                shift.getShiftDate(),
                type.getId(),
                type.getName(),
                type.getStartTime(),
                type.getEndTime(),
                type.isCrossesMidnight(),
                requirementResponses);
    }
    // Every week gets every shift type there is, so adding one later shows up
    // in the next week without anything special. What gets copied from the
    // week before is the staffing - how many of each position each shift needs.
    private void buildWeek(Schedule schedule) {
        List<ShiftType> types = shiftTypeRepository.findByActiveTrueOrderByStartTimeAsc();

        if (types.isEmpty()) {
            throw new ValidationException("No shift types are defined yet",
                    ErrorCode.NO_SHIFT_TYPES);
        }

        List<Shift> shifts = new ArrayList<>();

        for (int dayOffset = 0; dayOffset < DAYS_IN_WEEK; dayOffset++) {
            LocalDate date = schedule.getWeekStart().plusDays(dayOffset);

            for (ShiftType type : types) {
                Shift shift = new Shift();
                shift.setSchedule(schedule);
                shift.setShiftDate(date);
                shift.setShiftType(type);
                shifts.add(shift);
            }
        }

        shiftRepository.saveAll(shifts);
        copyRequirements(schedule, shifts);
    }

    // Enables updates to the deadline while the week is still collecting
    @Transactional
    // The manager setting the closing time himself.
    public ScheduleDetailResponse setSubmissionDeadline(Long id, DeadlineRequest request) {
        Schedule schedule = guard.require(id);
        guard.requireVersion(schedule, request.version());
        guard.requireStatus(schedule, ScheduleStatus.COLLECTING);

        schedule.setSubmissionClosesAt(request.submissionClosesAt());
        guard.markChanged(schedule);

        return findById(id);
    }

    // Empties the job requirements on the selected shifts.
    @Transactional
    public void clearRequirements(Long scheduleId, List<Long> shiftIds, Long version) {
        Schedule schedule = guard.require(scheduleId);
        guard.requireVersion(schedule, version);
        guard.requireStatus(schedule, ScheduleStatus.COLLECTING, ScheduleStatus.DRAFT);

        requirementRepository.deleteByShiftIdInAndShiftScheduleId(shiftIds, scheduleId);
        guard.markChanged(schedule);
    }

    // Weekly constraints submission period close independently a few days
    // before the week starts (exact timing set on application).
    // The manager can close them earlier via schedule builder.
    // Weeks added for a date that has already past by gets null
    // since otherwise it'll be closed immediately after.
    private Instant defaultDeadlineFor(LocalDate weekStart) {
        Instant closesAt = weekStart.minusDays(closesDaysBefore)
                .atTime(closesAtTime)
                .atZone(ZoneId.systemDefault())
                .toInstant();

        return closesAt.isAfter(Instant.now()) ? closesAt : null;
    }
}