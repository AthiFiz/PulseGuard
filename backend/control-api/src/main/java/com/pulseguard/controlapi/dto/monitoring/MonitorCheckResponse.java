package com.pulseguard.controlapi.dto.monitoring;

import com.pulseguard.controlapi.domain.MonitorCheck;
import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import java.time.Instant;


public record MonitorCheckResponse(
        Long id,
        Instant checkedAt,
        MonitorCheckOutcome outcome,
        Integer httpStatusCode,
        Integer responseTimeMs,
        MonitorCheckErrorType errorType,
        String errorMessage) {

    public static MonitorCheckResponse from(MonitorCheck check) {
        return new MonitorCheckResponse(
                check.getId(),
                check.getCheckedAt(),
                check.getOutcome(),
                check.getHttpStatusCode(),
                check.getResponseTimeMs(),
                check.getErrorType(),
                check.getErrorMessage());
    }
}
