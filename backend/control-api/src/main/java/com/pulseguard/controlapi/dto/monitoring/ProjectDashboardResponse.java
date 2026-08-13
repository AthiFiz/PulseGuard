package com.pulseguard.controlapi.dto.monitoring;

import java.time.Instant;
import java.util.List;

/**
 * A project's operational snapshot.
 *
 * <p>Two different kinds of information sit side by side, and the nesting keeps
 * them apart: {@code monitors} and {@code openIncidents} are the state right
 * now, while {@code checks} and {@code recentFailures} describe the
 * {@code window}.
 */
public record ProjectDashboardResponse(
        Long projectId,
        Instant generatedAt,
        TimeWindowResponse window,
        MonitorStatusCountsResponse monitors,
        /**
         * Outages that have not ended yet, whenever they began. Narrowing the
         * window must not hide an ongoing incident, so this count ignores it.
         */
        long openIncidents,
        ProjectCheckStatisticsResponse checks,
        List<RecentFailureResponse> recentFailures) {
}
