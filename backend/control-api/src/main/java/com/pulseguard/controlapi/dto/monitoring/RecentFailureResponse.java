package com.pulseguard.controlapi.dto.monitoring;

import com.pulseguard.controlapi.domain.MonitorCheck;
import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import java.time.Instant;

/**
 * A failed check, with just enough about its monitor to be actionable.
 *
 * <p>Carries the worker's own short error message. Response bodies and stack
 * traces are never stored, so there is nothing here to leak.
 */
public record RecentFailureResponse(
        Long monitorId,
        String monitorName,
        Instant checkedAt,
        Integer httpStatusCode,
        MonitorCheckErrorType errorType,
        String errorMessage) {

    public static RecentFailureResponse from(MonitorCheck check) {
        return new RecentFailureResponse(
                check.getMonitor().getId(),
                check.getMonitor().getName(),
                check.getCheckedAt(),
                check.getHttpStatusCode(),
                check.getErrorType(),
                check.getErrorMessage());
    }
}
