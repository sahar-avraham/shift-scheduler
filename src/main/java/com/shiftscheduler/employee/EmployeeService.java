package com.shiftscheduler.employee;

import com.shiftscheduler.domain.Employee;
import com.shiftscheduler.domain.JobPosition;
import com.shiftscheduler.domain.Role;
import com.shiftscheduler.domain.RosterChange;
import com.shiftscheduler.repository.EmployeeRepository;
import com.shiftscheduler.repository.JobPositionRepository;
import com.shiftscheduler.web.ConflictException;
import com.shiftscheduler.web.ErrorCode;
import com.shiftscheduler.web.ResourceNotFoundException;
import com.shiftscheduler.web.ValidationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.shiftscheduler.domain.Assignment;
import com.shiftscheduler.domain.Schedule;
import com.shiftscheduler.domain.ScheduleStatus;
import com.shiftscheduler.repository.AssignmentRepository;
import com.shiftscheduler.repository.RosterChangeRepository;
import com.shiftscheduler.schedule.ScheduleGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Staff: adding people, editing them, and activating or deactivating them.
@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final JobPositionRepository jobPositionRepository;
    private final PasswordEncoder passwordEncoder;

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final AssignmentRepository assignmentRepository;
    private final RosterChangeRepository rosterChangeRepository;
    private final ScheduleGuard guard;

    public EmployeeService(EmployeeRepository employeeRepository,
                           JobPositionRepository jobPositionRepository,
                           PasswordEncoder passwordEncoder,
                           AssignmentRepository assignmentRepository,
                           RosterChangeRepository rosterChangeRepository,
                           ScheduleGuard guard) {
        this.employeeRepository = employeeRepository;
        this.jobPositionRepository = jobPositionRepository;
        this.passwordEncoder = passwordEncoder;
        this.assignmentRepository = assignmentRepository;
        this.rosterChangeRepository = rosterChangeRepository;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> findAll() {
        // Active first, then by name.
        return employeeRepository.findAll(
                        Sort.by(Sort.Order.desc("active"), Sort.Order.asc("fullName")))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(Long id) {
        return toResponse(require(id));
    }

    // The password is hashed here, never stored as typed.
    @Transactional
    public EmployeeResponse create(EmployeeCreateRequest request) {
        String username = request.username().trim();

        if (employeeRepository.existsByUsernameIgnoreCase(username)) {
            throw new ConflictException("Username '" + username + "' is already taken");
        }

        Employee employee = new Employee();
        employee.setUsername(username);
        employee.setPasswordHash(passwordEncoder.encode(request.password()));
        employee.setFullName(request.fullName().trim());
        employee.setRole(request.role());
        employee.setMaxWeeklyHours(request.maxWeeklyHours());
        // The request has no active field. A new employee is always active.
        employee.setActive(true);
        employee.setJobPosition(requirePosition(request.jobPositionId()));

        return toResponse(employeeRepository.save(employee));
    }

    // Name, role, hours and job position.
    @Transactional
    public EmployeeResponse update(Long id, EmployeeUpdateRequest request) {
        Employee employee = require(id);

        requireCurrentVersion(employee, request.version());

        // Blocks the change only when the position or the hours moved, since
        // those are the fields the solver reads.
        boolean solverFieldChanged = !employee.getJobPosition().getId().equals(request.jobPositionId())
                || employee.getMaxWeeklyHours() != request.maxWeeklyHours();

        if (solverFieldChanged) {
            guard.requireNothingSolving();
        }

        // Only the role can cost us a manager here now - being switched off is a
        // separate request, and that one does its own check.
        if (employee.getRole() == Role.MANAGER && request.role() != Role.MANAGER) {
            guardLastManager(employee.getId());
        }

        employee.setFullName(request.fullName().trim());
        employee.setRole(request.role());
        employee.setMaxWeeklyHours(request.maxWeeklyHours());
        employee.setJobPosition(requirePosition(request.jobPositionId()));

        // Writes the row now, so the response carries the version the database holds.
        employeeRepository.flush();

        return toResponse(employee);
    }

    // Brings a disabled employee back.
    @Transactional
    public EmployeeResponse activate(Long id, Long version) {
        Employee employee = require(id);
        requireCurrentVersion(employee, version);

        // Already active, so there is nothing to change.
        if (employee.isActive()) {
            return toResponse(employee);
        }

        // Activating is blocked as well as deactivating, so both directions
        // follow one rule.
        guard.requireNothingSolving();

        // Their old job may have been retired while they were away. Bringing them
        // back onto it would leave an active employee doing a job no shift asks
        // for, so the manager has to give them a current one first.
        // Reads the position under a lock, so one deleted at the same moment is seen as deleted.
        boolean positionActive = jobPositionRepository.lockById(employee.getJobPosition().getId())
                .map(JobPosition::isActive)
                .orElse(false);

        if (!positionActive) {
            throw new ValidationException(
                    "The job position '" + employee.getJobPosition().getName()
                            + "' is no longer in use. Give this employee a current one first.");
        }

        employee.setActive(true);

        // Writes the row now, so the response carries the version the database holds.
        employeeRepository.flush();

        return toResponse(employee);
    }

    @Transactional
    public void deactivate(Long id, Long version) {
        // Locks the weeks first, so the assignments read below include one made at the same moment.
        guard.requireNothingSolving();

        Employee employee = require(id);
        requireCurrentVersion(employee, version);

        // Already inactive, so there is nothing to change.
        if (!employee.isActive()) {
            return;
        }

        if (employee.getRole() == Role.MANAGER) {
            guardLastManager(employee.getId());
        }

        // Set inactive before the shifts are removed, so no other request can
        // assign him meanwhile.
        employee.setActive(false);
        releaseFromSchedules(employee);
    }

    // A new password for an employee.
    @Transactional
    public void changePassword(Long id, PasswordChangeRequest request) {
        Employee employee = require(id);
        employee.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    // A PUT sends every field, including ones the user didn't touch.
    // The check stops a name fix from writing back the old hours and undoing
    // someone else's change.
    private void requireCurrentVersion(Employee employee, Long expected) {
        if (expected != employee.getVersion()) {
            throw new ConflictException(
                    "This employee was changed by someone else. Reload and try again.",
                    ErrorCode.STALE_VERSION);
        }
    }

    // Reads under a write lock so a second request demoting another manager
    // waits and then sees the real count.
    private void guardLastManager(Long excludedId) {
        long remaining = employeeRepository.lockActiveByRole(Role.MANAGER).stream()
                .filter(manager -> !manager.getId().equals(excludedId))
                .count();

        if (remaining == 0) {
            throw new ValidationException(
                    "The system must keep at least one active manager",
                    ErrorCode.LAST_MANAGER);
        }
    }

    // Loads the employee, or 404.
    private Employee require(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee " + id + " not found"));
    }

    // Loads an active job position under a lock, or 404, so one deleted at the
    // same moment is seen as deleted.
    private JobPosition requirePosition(Long id) {
        return jobPositionRepository.lockById(id)
                .filter(JobPosition::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Job position " + id + " not found"));
    }

    // Includes the version, which the next edit has to send back.
    private EmployeeResponse toResponse(Employee employee) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getFullName(),
                employee.getUsername(),
                employee.getRole().name(),
                employee.getMaxWeeklyHours(),
                employee.isActive(),
                employee.getJobPosition().getId(),
                employee.getJobPosition().getName(),
                employee.getVersion());
    }

    // Takes a disabled employee off the weeks still being worked on.
    // A published week keeps the shifts it was published with. Only changes made
    // after it went out are undone.
    private void releaseFromSchedules(Employee employee) {
        List<Assignment> assignments = new ArrayList<>(assignmentRepository
                .findByEmployeeIdAndShiftScheduleStatusIn(employee.getId(),
                        List.of(ScheduleStatus.DRAFT, ScheduleStatus.SOLVING)));

        int fromDrafts = assignments.size();

        // The shifts they gained or lost after the week was published.
        // Only the gained ones have an assignment to remove.
        List<RosterChange> waiting = rosterChangeRepository.findByEmployeeId(employee.getId());

        List<Long> addedShiftIds = waiting.stream()
                .filter(RosterChange::isAdded)
                .map(change -> change.getShift().getId())
                .toList();

        if (!addedShiftIds.isEmpty()) {
            assignments.addAll(assignmentRepository
                    .findByEmployeeIdAndShiftIdIn(employee.getId(), addedShiftIds));
        }

        // Dropped whichever way it went: there is nobody left to tell.
        rosterChangeRepository.deleteAll(waiting);

        if (assignments.isEmpty()) {
            return;
        }

        // Touches each week once and not once per assignment, so the version goes up by one.
        // Ordered by id, so two requests that touch the same weeks lock them in the same order.
        assignments.stream()
                .map(assignment -> assignment.getShift().getSchedule())
                .collect(Collectors.toMap(Schedule::getId, schedule -> schedule, (a, b) -> a,
                        TreeMap::new))
                .values()
                .forEach(guard::markChanged);

        assignmentRepository.deleteAll(assignments);

        log.info("Disabled {} - removed {} assignment(s) from draft weeks and undid {} "
                        + "change(s) made after a week was published",
                employee.getFullName(), fromDrafts, assignments.size() - fromDrafts);
    }
}