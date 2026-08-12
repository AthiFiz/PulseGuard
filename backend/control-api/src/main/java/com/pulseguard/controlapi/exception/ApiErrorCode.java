package com.pulseguard.controlapi.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable {@code code} returned in every error response, paired
 * with the HTTP status it maps to.
 *
 * <p>Clients should branch on these rather than on message text.
 */
public enum ApiErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),

    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROJECT_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROJECT_MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT),
    PROJECT_REQUIRES_ADMIN(HttpStatus.CONFLICT),

    MONITOR_NOT_FOUND(HttpStatus.NOT_FOUND),
    MONITOR_VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    MONITORING_QUERY_INVALID(HttpStatus.BAD_REQUEST),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ApiErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
