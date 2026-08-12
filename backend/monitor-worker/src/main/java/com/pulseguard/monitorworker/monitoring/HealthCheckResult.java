package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import java.time.Instant;

/**
 * What one check attempt produced. Internal to the worker — never serialised or
 * exposed over HTTP.
 *
 * @param checkedAt the instant the attempt began, used for both the stored
 *     check and the monitor's {@code lastCheckedAt}
 * @param outcome success or failure
 * @param httpStatusCode the status received, or null when no response arrived
 * @param responseTimeMs measured duration, or null when nothing was sent
 * @param errorType why it failed, null on success
 * @param errorMessage a short, safe explanation, null on success
 */
public record HealthCheckResult(
        Instant checkedAt,
        MonitorCheckOutcome outcome,
        Integer httpStatusCode,
        Integer responseTimeMs,
        MonitorCheckErrorType errorType,
        String errorMessage) {

    public static HealthCheckResult success(Instant checkedAt, int httpStatusCode, int responseTimeMs) {
        return new HealthCheckResult(
                checkedAt, MonitorCheckOutcome.SUCCESS, httpStatusCode, responseTimeMs, null, null);
    }

    public static HealthCheckResult failure(
            Instant checkedAt,
            Integer httpStatusCode,
            Integer responseTimeMs,
            MonitorCheckErrorType errorType,
            String errorMessage) {
        return new HealthCheckResult(
                checkedAt, MonitorCheckOutcome.FAILURE, httpStatusCode, responseTimeMs, errorType, errorMessage);
    }

    public boolean isSuccess() {
        return outcome == MonitorCheckOutcome.SUCCESS;
    }
}
