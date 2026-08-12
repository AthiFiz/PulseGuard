package com.pulseguard.monitorworker.repository;

import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    /**
     * Monitors that are ready to be checked.
     *
     * <p>Paused monitors are excluded by status, and a null {@code nextCheckAt}
     * excludes them a second time, since pausing clears it.
     *
     * <p>Ordered by {@code nextCheckAt} so the most overdue monitor goes first,
     * and limited so one polling cycle cannot pull an unbounded backlog into
     * memory.
     *
     * <p>No row locking: a single worker instance is assumed at this stage.
     */
    @Query("""
            select m from Monitor m
            where m.currentStatus <> :pausedStatus
              and m.nextCheckAt is not null
              and m.nextCheckAt <= :now
            order by m.nextCheckAt asc
            """)
    List<Monitor> findDueMonitors(MonitorStatus pausedStatus, Instant now, Limit limit);
}
