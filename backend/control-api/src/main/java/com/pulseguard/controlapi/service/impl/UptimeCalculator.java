package com.pulseguard.controlapi.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns raw check counts into the numbers the API reports.
 *
 * <p>Shared by monitor statistics and the project dashboard so both compute
 * uptime the same way — two implementations of "percentage" would eventually
 * disagree at the second decimal place and nobody would know which was right.
 */
final class UptimeCalculator {

    private static final int PERCENTAGE_SCALE = 2;
    private static final int AVERAGE_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private UptimeCalculator() {
    }

    /**
     * Successful checks as a percentage of all checks, to two decimal places.
     *
     * <p>Returns <strong>null when there are no checks</strong>, not zero. A
     * monitor nobody has checked has unknown availability; reporting 0% would
     * claim it was down.
     *
     * <p>{@link BigDecimal} rather than double: {@code 1.0/3*100} in binary
     * floating point gives 33.33333333333333, and rounding it late produces the
     * 99.649999999 artefacts that make a dashboard look broken.
     */
    static BigDecimal uptimePercentage(long successfulChecks, long totalChecks) {
        if (totalChecks <= 0) {
            return null;
        }
        return BigDecimal.valueOf(successfulChecks)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(totalChecks), PERCENTAGE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Rounds an average response time for display.
     *
     * <p>Null in means null out: the database returns NULL from {@code avg()}
     * when no check in range recorded a duration, and that stays null rather
     * than becoming a misleading zero.
     */
    static BigDecimal averageResponseTime(Double average) {
        if (average == null) {
            return null;
        }
        return BigDecimal.valueOf(average).setScale(AVERAGE_SCALE, RoundingMode.HALF_UP);
    }

    /** SQL aggregates return NULL rather than 0 when nothing matched. */
    static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
