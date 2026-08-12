package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.repository.projection.MonitorStatusCountProjection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    /**
     * A project's monitors, oldest first.
     *
     * <p>The ordering is explicit so the list is stable between calls — without
     * an ORDER BY the database is free to return rows in any order.
     */
    List<Monitor> findAllByProjectIdOrderByCreatedAtAsc(Long projectId);

    /**
     * How many monitors a project currently holds in each status.
     *
     * <p>One grouped query rather than four counts, and read from
     * {@code monitors.current_status} rather than derived from the latest check
     * — the worker already maintains that column, and a paused monitor has no
     * recent check to derive anything from.
     *
     * <p>Statuses with no monitors simply do not appear as rows; the caller
     * fills in the zeroes.
     */
    @Query("""
            select m.currentStatus as status, count(m) as count
            from Monitor m
            where m.project.id = :projectId
            group by m.currentStatus
            """)
    List<MonitorStatusCountProjection> countByStatusForProject(@Param("projectId") Long projectId);
}
