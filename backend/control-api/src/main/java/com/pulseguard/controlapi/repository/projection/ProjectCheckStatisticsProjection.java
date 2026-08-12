package com.pulseguard.controlapi.repository.projection;

/**
 * Aggregate figures across every monitor in a project.
 *
 * <p>Counted over individual checks rather than per monitor, so a monitor with a
 * thousand checks weighs a thousand times more than one with a single check —
 * which is what a project-wide availability figure should mean.
 */
public interface ProjectCheckStatisticsProjection {

    Long getTotalChecks();

    Long getSuccessfulChecks();

    Double getAverageResponseTimeMs();
}
