package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.exception.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;


public record TimeWindow(Instant from, Instant to) {

    /** How far back the dashboard looks when the caller does not say. */
    private static final Duration DEFAULT_DASHBOARD_LOOKBACK = Duration.ofHours(24);

    public TimeWindow {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.monitoringQueryInvalid("'from' must not be after 'to'");
        }
    }

    public static TimeWindow of(Instant from, Instant to) {
        return new TimeWindow(from, to);
    }

    /**
     * A window that is always bounded, defaulting to the last 24 hours.
     *
     * <p>The dashboard differs from monitor statistics on purpose. A dashboard
     * answers "how are things right now", so aggregating a year of history would
     * be both slow and misleading — an outage last March should not colour
     * today's number. Statistics, by contrast, is where you go to ask about all
     * of history.
     */
    public static TimeWindow forDashboard(Instant from, Instant to, Clock clock) {
        Instant resolvedTo = to != null ? to : Instant.now(clock);
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_DASHBOARD_LOOKBACK);
        return new TimeWindow(resolvedFrom, resolvedTo);
    }
}
