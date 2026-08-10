package com.pulseguard.controlapi.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The single error shape returned by every failure, whether it originates in a
 * controller, the service layer, or Spring Security.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> errors) {

    public record FieldError(String field, String message) {
    }

    public static ApiErrorResponse of(ApiErrorCode code, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(), code.status().value(), code.name(), message, path, List.of());
    }

    public static ApiErrorResponse of(
            ApiErrorCode code, String message, String path, List<FieldError> errors) {
        return new ApiErrorResponse(
                Instant.now(), code.status().value(), code.name(), message, path, errors);
    }
}
