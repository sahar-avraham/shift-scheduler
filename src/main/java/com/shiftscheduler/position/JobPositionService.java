package com.shiftscheduler.position;

import com.shiftscheduler.domain.JobPosition;
import com.shiftscheduler.domain.ScheduleStatus;
import com.shiftscheduler.repository.EmployeeRepository;
import com.shiftscheduler.repository.JobPositionRepository;
import com.shiftscheduler.repository.ShiftRequirementRepository;
import com.shiftscheduler.schedule.ScheduleGuard;
import com.shiftscheduler.web.ConflictException;
import com.shiftscheduler.web.ResourceNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// Job positions.
// A position is only a name, so renaming applies everywhere.
// Removing is refused while anyone still holds it.
@Service
public class JobPositionService {

    private final JobPositionRepository jobPositionRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRequirementRepository requirementRepository;
    private final ScheduleGuard guard;

    public JobPositionService(JobPositionRepository jobPositionRepository,
                              EmployeeRepository employeeRepository,
                              ShiftRequirementRepository requirementRepository,
                              ScheduleGuard guard) {
        this.jobPositionRepository = jobPositionRepository;
        this.employeeRepository = employeeRepository;
        this.requirementRepository = requirementRepository;
        this.guard = guard;
    }

    // The live positions, by name.
    @Transactional(readOnly = true)
    public List<JobPositionResponse> findAll() {
        return jobPositionRepository.findByActiveTrue(Sort.by("name")).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobPositionResponse findById(Long id) {
        return toResponse(require(id));
    }

    // A removed position with the same name is reused.
    @Transactional
    public JobPositionResponse create(JobPositionRequest request) {
        String name = request.name().trim();

        requireNameFree(name, null);

        // Recreating a removed position brings the old row back, so published
        // weeks still point at it.
        JobPosition position = jobPositionRepository
                .findByNameIgnoreCaseAndActiveFalse(name)
                .orElseGet(JobPosition::new);

        position.setName(name);
        position.setActive(true);

        return toResponse(position.getId() == null
                ? jobPositionRepository.save(position)
                : position);
    }

    // Renames it. Nothing else to change.
    @Transactional
    public JobPositionResponse update(Long id, JobPositionRequest request) {
        JobPosition position = require(id);
        String name = request.name().trim();

        requireNameFree(name, id);
        position.setName(name);

        return toResponse(position);
    }

    // Hides the position and drops it from the weeks still being planned.
    @Transactional
    public void delete(Long id) {
        guard.requireNothingSolving();

        // Locks the position first, so a change to one of its employees waits for the delete to finish.
        JobPosition position = jobPositionRepository.lockById(id)
                .filter(JobPosition::isActive)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Job position " + id + " not found"));

        // Locked read, so somebody being moved into this position finishes first.
        if (!employeeRepository.lockActiveByJobPosition(id).isEmpty()) {
            throw new ConflictException("Employees are still assigned to this position");
        }

        requirementRepository.deleteByJobPositionIdAndShiftScheduleStatusNot(
                id, ScheduleStatus.PUBLISHED);

        position.setActive(false);
    }

    // A name may only be used by one live position at a time. It can't be a
    // unique key, because a removed position keeps its name.
    // The write lock stops two requests both finding nothing and both saving.
    private void requireNameFree(String name, Long excludeId) {
        jobPositionRepository.lockActive().stream()
                .filter(other -> other.getName().equalsIgnoreCase(name))
                .filter(other -> !other.getId().equals(excludeId))
                .findFirst()
                .ifPresent(other -> {
                    throw new ConflictException(
                            "A job position named '" + name + "' already exists");
                });
    }

    // Loads a live position, or 404.
    private JobPosition require(Long id) {
        return jobPositionRepository.findById(id)
                .filter(JobPosition::isActive)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Job position " + id + " not found"));
    }

    private JobPositionResponse toResponse(JobPosition position) {
        return new JobPositionResponse(position.getId(), position.getName());
    }
}