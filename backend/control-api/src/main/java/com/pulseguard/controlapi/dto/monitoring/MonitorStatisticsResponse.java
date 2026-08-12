package com.pulseguard.controlapi.dto.monitoring;

import com.pulseguard.controlapi.enums.MonitorStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregate figures for one monitor over a chosen range.
 *
 * <p>Several fields are deliberately nullable, and the distinction matters:
 * {@code null} means "there is no data to answer this", which is not the same
 * as zero. A monitor that has never been checked has an unknown uptime, not a
 * 0% uptime.
 *
 * @param uptimePercentage successful checks as a percentage of all checks —
 *     <strong>check-based, not duration-based</strong>. Null when no checks
 *     exist in the range.
 * @param averageResponseTimeMs computed only over checks that actually recorded
 *     a duration; null when none did
 * @param lastCheckedAt the newest check <em>inside the requested range</em>,
 *     which is not necessarily the monitor's own last check
 * @param currentStatus the monitor's status right now, which describes the
 *     present rather than the range above
 */
public record MonitorStatisticsResponse(
        Long monitorId,
        Instant from,
        Instant to,
        long totalChecks,
        long successfulChecks,
        long failedChecks,
        BigDecimal uptimePercentage,
        BigDecimal averageResponseTimeMs,
        Integer minimumResponseTimeMs,
        Integer maximumResponseTimeMs,
        Instant lastCheckedAt,
        MonitorStatus currentStatus) {
}
