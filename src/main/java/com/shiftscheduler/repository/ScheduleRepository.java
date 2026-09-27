package com.shiftscheduler.repository;

import com.shiftscheduler.domain.Schedule;
import com.shiftscheduler.domain.ScheduleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;


// Weeks.
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    boolean existsByWeekStart(LocalDate weekStart);

    List<Schedule> findAllByOrderByWeekStartDesc();

    List<Schedule> findByStatusInOrderByWeekStartDesc(Collection<ScheduleStatus> statuses);

    Optional<Schedule> findFirstByWeekStartLessThanOrderByWeekStartDesc(LocalDate weekStart);

    List<Schedule> findByStatusAndSubmissionClosesAtBefore(
            ScheduleStatus status, Instant cutoff);

    List<Schedule> findByStatus(ScheduleStatus status);

    // Locks the weeks a solve can start on, so a solve that begins at the same
    // moment waits for the check and the change that follows it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.status in (:statuses)")
    List<Schedule> lockByStatusIn(@Param("statuses") Collection<ScheduleStatus> statuses);

    // Loads one week under a lock, so a change to it waits for any other change already running on it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Schedule s where s.id = :id")
    Optional<Schedule> lockById(@Param("id") Long id);
}