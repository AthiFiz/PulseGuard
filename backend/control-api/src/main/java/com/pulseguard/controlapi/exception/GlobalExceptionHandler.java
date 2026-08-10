package com.pulseguard.controlapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns exceptions into the single {@link ApiErrorResponse} shape. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException ex, HttpServletRequest request) {
        ApiErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code, ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.status())
                .body(ApiErrorResponse.of(
                        ApiErrorCode.VALIDATION_ERROR,
                        "Request validation failed",
                        request.getRequestURI(),
                        fieldErrors));
    }

    /**
     * The body could not be parsed at all — malformed JSON, a missing body, or
     * a value that does not fit its type (an unknown enum constant, say).
     *
     * <p>Jackson's own message names Java classes and packages, so it is logged
     * rather than returned.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.status())
                .body(ApiErrorResponse.of(
                        ApiErrorCode.VALIDATION_ERROR,
                        "Request body is missing, malformed, or contains an invalid value",
                        request.getRequestURI()));
    }

    /** A path variable or query parameter of the wrong type, such as a non-numeric id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.status())
                .body(ApiErrorResponse.of(
                        ApiErrorCode.VALIDATION_ERROR,
                        "Parameter '%s' has an invalid value".formatted(ex.getName()),
                        request.getRequestURI(),
                        List.of(new ApiErrorResponse.FieldError(ex.getName(), "Invalid value"))));
    }

    /**
     * A URL that matches no controller.
     *
     * <p>Handled separately because {@code NoResourceFoundException} implements
     * {@code ErrorResponse} without extending {@code ErrorResponseException},
     * so the handler below does not catch it — and without this it would fall
     * through to the catch-all and be reported as 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        Instant.now(),
                        HttpStatus.NOT_FOUND.value(),
                        "NOT_FOUND",
                        "No endpoint matches this path",
                        request.getRequestURI(),
                        List.of()));
    }

    /**
     * Spring's other web exceptions already carry the right status — an
     * unsupported method, an unreadable media type. Passed through rather than
     * swallowed by the catch-all below.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiErrorResponse> handleSpringWebError(
            ErrorResponseException ex, HttpServletRequest request) {
        HttpStatusCode status = ex.getStatusCode();
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(
                        Instant.now(),
                        status.value(),
                        status.value() == 404 ? "NOT_FOUND" : "REQUEST_ERROR",
                        "The request could not be handled",
                        request.getRequestURI(),
                        List.of()));
    }

    /**
     * Last resort. The real cause is logged for operators but never returned to
     * the client, so internal details and stack traces stay server-side.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ApiErrorCode.INTERNAL_ERROR.status())
                .body(ApiErrorResponse.of(
                        ApiErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred",
                        request.getRequestURI()));
    }
}
