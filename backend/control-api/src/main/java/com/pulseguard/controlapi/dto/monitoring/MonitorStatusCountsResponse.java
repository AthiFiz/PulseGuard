package com.pulseguard.controlapi.dto.monitoring;

/**
 * How many monitors are currently in each state.
 *
 * <p>A snapshot of right now, unrelated to the dashboard's time window — the
 * counts do not change if you ask for a different range.
 */
public record MonitorStatusCountsResponse(
        long total, long up, long down, long unknown, long paused) {
}
