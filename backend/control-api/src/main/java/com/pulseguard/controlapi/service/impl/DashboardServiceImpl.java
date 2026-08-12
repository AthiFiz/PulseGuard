package com.pulseguard.controlapi.service.impl;

import static com.pulseguard.controlapi.service.impl.UptimeCalculator.averageResponseTime;
import static com.pulseguard.controlapi.service.impl.UptimeCalculator.orZero;
import static com.pulseguard.controlapi.service.impl.UptimeCalculator.uptimePercentage;

import com.pulseguard.controlapi.dto.monitoring.MonitorStatusCountsResponse;
import com.pulseguard.controlapi.dto.monitoring.ProjectCheckStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.ProjectDashboardResponse;
import com.pulseguard.controlapi.dto.monitoring.RecentFailureResponse;
import com.pulseguard.controlapi.dto.monitoring.TimeWindowResponse;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.repository.MonitorCheckRepository;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.repository.projection.MonitorStatusCountProjection;
import com.pulseguard.controlapi.repository.projection.ProjectCheckStatisticsProjection;
import com.pulseguard.controlapi.service.DashboardService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import com.pulseguard.controlapi.service.TimeWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** Enough to see a pattern without turning the dashboard into a log viewer. */
    private static final int RECENT_FAILURE_LIMIT = 10;

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final ProjectAccessService projectAccessService;
    private final Clock clock;

    /**
     * A project's operational snapshot: what state its monitors are in right
     * now, and how they behaved over a window.
     *
     * <p>Three queries regardless of how many monitors the project has — a
     * grouped status count, one project-wide aggregate, and the recent
     * failures. Nothing loops over monitors issuing a query each, because a
     * project with 500 monitors would then cost 1500 round trips.
     */
    @Override
    @Transactional(readOnly = true)
    public ProjectDashboardResponse getProjectDashboard(Long projectId, Instant from, Instant to) {
        projectAccessService.requireReadableProject(projectId);

        TimeWindow window = TimeWindow.forDashboard(from, to, clock);

        return new ProjectDashboardResponse(
                projectId,
                Instant.now(clock),
                new TimeWindowResponse(window.from(), window.to()),
                currentStatusCounts(projectId),
                checkStatistics(projectId, window),
                recentFailures(projectId, window));
    }

    /**
     * Current state, deliberately outside the time window.
     *
     * <p>Read from {@code monitors.current_status}, which the worker maintains,
     * rather than derived from the latest check — a paused monitor has no recent
     * check to derive anything from, and a monitor checked an hour ago is still
     * whatever the worker last decided.
     */
    private MonitorStatusCountsResponse currentStatusCounts(Long projectId) {
        Map<MonitorStatus, Long> counts = new EnumMap<>(MonitorStatus.class);
        for (MonitorStatusCountProjection row : monitorRepository.countByStatusForProject(projectId)) {
            counts.put(row.getStatus(), row.getCount());
        }

        // Statuses with no monitors simply do not come back as rows.
        long up = counts.getOrDefault(MonitorStatus.UP, 0L);
        long down = counts.getOrDefault(MonitorStatus.DOWN, 0L);
        long unknown = counts.getOrDefault(MonitorStatus.UNKNOWN, 0L);
        long paused = counts.getOrDefault(MonitorStatus.PAUSED, 0L);

        return new MonitorStatusCountsResponse(up + down + unknown + paused, up, down, unknown, paused);
    }

    /**
     * Project-wide figures, aggregated over individual checks.
     *
     * <p>Emphatically not the mean of each monitor's uptime percentage. Take a
     * monitor with 1000 successful checks and another with a single failure:
     * by check count that is 99.90%, but averaging the two percentages gives
     * 50% — a number that describes nothing real.
     */
    private ProjectCheckStatisticsResponse checkStatistics(Long projectId, TimeWindow window) {
        ProjectCheckStatisticsProjection stats = monitorCheckRepository.aggregateForProject(
                projectId, window.from(), window.to(), MonitorCheckOutcome.SUCCESS);

        long total = orZero(stats.getTotalChecks());
        long successful = orZero(stats.getSuccessfulChecks());

        return new ProjectCheckStatisticsResponse(
                total,
                successful,
                total - successful,
                uptimePercentage(successful, total),
                averageResponseTime(stats.getAverageResponseTimeMs()));
    }

    /**
     * The window's most recent failures.
     *
     * <p>Taken from failed checks, not from monitors currently sitting at DOWN:
     * a monitor that is healthy now may have failed twenty minutes ago, and a
     * monitor that is DOWN may not have failed inside a narrow window.
     */
    private List<RecentFailureResponse> recentFailures(Long projectId, TimeWindow window) {
        return monitorCheckRepository
                .findRecentFailures(
                        projectId,
                        window.from(),
                        window.to(),
                        MonitorCheckOutcome.FAILURE,
                        Limit.of(RECENT_FAILURE_LIMIT))
                .stream()
                .map(RecentFailureResponse::from)
                .toList();
    }
}
