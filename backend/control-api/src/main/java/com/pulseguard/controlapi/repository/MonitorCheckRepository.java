package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.MonitorCheck;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.repository.projection.MonitorStatisticsProjection;
import com.pulseguard.controlapi.repository.projection.ProjectCheckStatisticsProjection;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, Long> {

    @Query("""
            select c from MonitorCheck c
            where c.monitor.id = :monitorId
              and (:from is null or c.checkedAt >= :from)
              and (:to is null or c.checkedAt <= :to)
              and (:outcome is null or c.outcome = :outcome)
            """)
    Page<MonitorCheck> findHistory(
            @Param("monitorId") Long monitorId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("outcome") MonitorCheckOutcome outcome,
            Pageable pageable);

    /**
     * Counts and response-time figures for one monitor, aggregated in MySQL.
     *
     * <p>Deliberately not "load the checks and reduce them in Java" — a monitor
     * on a 30-second interval produces nearly three million rows a year.
     *
     * <p>{@code avg}, {@code min} and {@code max} skip NULL response times by
     * definition in SQL, which is exactly right: a DNS failure never measured a
     * response, and counting it as zero would flatter the average.
     */
    @Query("""
            select count(c) as totalChecks,
                   sum(case when c.outcome = :successOutcome then 1 else 0 end) as successfulChecks,
                   avg(c.responseTimeMs) as averageResponseTimeMs,
                   min(c.responseTimeMs) as minimumResponseTimeMs,
                   max(c.responseTimeMs) as maximumResponseTimeMs,
                   max(c.checkedAt) as lastCheckedAt
            from MonitorCheck c
            where c.monitor.id = :monitorId
              and (:from is null or c.checkedAt >= :from)
              and (:to is null or c.checkedAt <= :to)
            """)
    MonitorStatisticsProjection aggregateForMonitor(
            @Param("monitorId") Long monitorId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("successOutcome") MonitorCheckOutcome successOutcome);

    /**
     * The same aggregation across every monitor in a project, in one query.
     *
     * <p>This is what keeps the dashboard from running a query per monitor: a
     * project with 500 monitors still costs exactly one round trip.
     */
    @Query("""
            select count(c) as totalChecks,
                   sum(case when c.outcome = :successOutcome then 1 else 0 end) as successfulChecks,
                   avg(c.responseTimeMs) as averageResponseTimeMs
            from MonitorCheck c
            where c.monitor.project.id = :projectId
              and c.checkedAt >= :from
              and c.checkedAt <= :to
            """)
    ProjectCheckStatisticsProjection aggregateForProject(
            @Param("projectId") Long projectId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("successOutcome") MonitorCheckOutcome successOutcome);

    /**
     * The project's most recent failed checks inside the window.
     *
     * <p>Failures are read from the checks themselves rather than inferred from
     * {@code Monitor.currentStatus}: a monitor that is healthy right now may
     * still have failed twenty minutes ago, and that is worth showing.
     *
     * <p>The monitor is fetched eagerly so rendering each row's name does not
     * trigger a query per failure.
     */
    @Query("""
            select c from MonitorCheck c
            join fetch c.monitor m
            where m.project.id = :projectId
              and c.outcome = :failureOutcome
              and c.checkedAt >= :from
              and c.checkedAt <= :to
            order by c.checkedAt desc
            """)
    List<MonitorCheck> findRecentFailures(
            @Param("projectId") Long projectId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("failureOutcome") MonitorCheckOutcome failureOutcome,
            Limit limit);
}
