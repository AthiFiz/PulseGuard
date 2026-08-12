package com.pulseguard.controlapi.dto.monitoring;

import java.time.Instant;
import java.util.List;

/**
 * A project's operational snapshot.
 *
 * <p>Two different kinds of information sit side by side, and the nesting keeps
 * them apart: {@code monitors} is the state right now, while {@code checks} and
 * {@code recentFailures} describe the {@code window}.
 */
public record ProjectDashboardResponse(
        Long projectId,
        Instant generatedAt,
        TimeWindowResponse window,
        MonitorStatusCountsResponse monitors,
        ProjectCheckStatisticsResponse checks,
        List<RecentFailureResponse> recentFailures) {
}
