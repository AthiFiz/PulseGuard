package com.pulseguard.controlapi.repository.projection;

import java.time.Instant;

/**
 * Aggregate figures for one monitor, computed by the database.
 *
 * <p>Every value is boxed because SQL aggregates return NULL when no rows match:
 * a monitor that has never been checked has no average, no minimum, and no last
 * check. Primitives would unbox those nulls into an exception.
 */

//Typed projections instead of Object[]
public interface MonitorStatisticsProjection {

    Long getTotalChecks();

    Long getSuccessfulChecks();

    /** Null when no check in range recorded a response time. */
    Double getAverageResponseTimeMs();

    Integer getMinimumResponseTimeMs();

    Integer getMaximumResponseTimeMs();

    /** The most recent check inside the requested window, not the monitor's own field. */
    Instant getLastCheckedAt();
}
