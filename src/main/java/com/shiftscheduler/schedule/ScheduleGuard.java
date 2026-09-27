package com.shiftscheduler.schedule;

import com.shiftscheduler.domain.Schedule;
import com.shiftscheduler.domain.ScheduleStatus;
import com.shiftscheduler.repository.ScheduleRepository;
import com.shiftscheduler.web.ConflictException;
import com.shiftscheduler.web.ErrorCode;
import com.shiftscheduler.web.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// The checks that run before any change to a schedule.
// Does the week exist, does its status allow the change, and is the manager
// still on the version he was shown.
@Service
public class ScheduleGuard {

    private final ScheduleRepository scheduleRepository;

    public ScheduleGuard(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    // Refuses a change while the solver is running, since it works from the copy
    // it read when it started and anything changed underneath comes back wrong
    // in the solution it saves.
    // Reads the weeks under a lock, so a solve cannot start between this check
    // and the change that follows it.
    public void requireNothingSolving() {
        boolean solving = scheduleRepository
                .lockByStatusIn(List.of(ScheduleStatus.DRAFT, ScheduleStatus.SOLVING)).stream()
                .anyMatch(schedule -> schedule.getStatus() == ScheduleStatus.SOLVING);

        if (solving) {
            throw new ConflictException(
                    "A schedule is being built right now. Wait for it to finish and try again.",
                    ErrorCode.WRONG_STATUS);
        }
    }

    // Loads the week, or 404 if there is no such id.
    public Schedule require(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule " + id + " not found"));
    }

    // Loads the week under a lock, or 404, so the checks after it see what a
    // change already running on the week saved.
    public Schedule lock(Long id) {
        return scheduleRepository.lockById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule " + id + " not found"));
    }

    // Allows the action only in the statuses listed.
    // The message names the status the week is in and the ones that would
    // have worked, so the manager can tell what went wrong.
    public void requireStatus(Schedule schedule, ScheduleStatus... allowed) {
        boolean ok = Arrays.asList(allowed).contains(schedule.getStatus());

        if (!ok) {
            String expected = Arrays.stream(allowed)
                    .map(Enum::name)
                    .collect(Collectors.joining(" or "));

            throw new ConflictException(
                    "This action needs the schedule to be " + expected
                            + ", but it is " + schedule.getStatus(),
                    ErrorCode.WRONG_STATUS);
        }
    }

    // The manager sends back the version he was looking at. If someone else
    // changed the week in between, the action is refused, and he is told to
    // reload.
    // This is for changes that come one after another. Two that arrive at the
    // same time both pass here, and @Version stops the second one at commit.
    public void requireVersion(Schedule schedule, Long expected) {
        if (expected != null && expected != schedule.getVersion()) {
            throw new ConflictException(
                    "The schedule was changed by someone else. Reload and try again.",
                    ErrorCode.STALE_VERSION);
        }
    }

    // Raises the version of the whole week.
    // Assignments and requirements sit in other tables, so Hibernate won't
    // see a change here unless a field on the week itself is touched.
    // Writes the row straight away, so the caller gets back the version the
    // database holds and a second change with the same version fails here.
    public void markChanged(Schedule schedule) {
        schedule.touch();
        scheduleRepository.flush();
    }
}