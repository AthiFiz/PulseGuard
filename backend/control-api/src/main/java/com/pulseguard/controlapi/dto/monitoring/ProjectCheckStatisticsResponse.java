package com.pulseguard.controlapi.dto.monitoring;

import java.math.BigDecimal;

/**
 * Check figures for a whole project over the dashboard window.
 *
 * <p>Aggregated across individual checks, not by averaging each monitor's own
 * percentage. Those give very different answers: a monitor with 1000 successes
 * and one with a single failure is 99.9% by check count, but 50% if you average
 * the two percentages — and the second number is meaningless.
 */
public record ProjectCheckStatisticsResponse(
        long total,
        long successful,
        long failed,
        BigDecimal uptimePercentage,
        BigDecimal averageResponseTimeMs) {
}
