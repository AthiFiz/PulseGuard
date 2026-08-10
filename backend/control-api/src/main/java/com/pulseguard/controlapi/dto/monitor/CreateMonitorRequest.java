package com.pulseguard.controlapi.dto.monitor;

import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Configuration for a new monitor.
 *
 * <p>The project comes from the URL path, and every operational field
 * ({@code currentStatus}, {@code consecutiveFailures}, {@code lastCheckedAt},
 * {@code nextCheckAt}) is owned by PulseGuard — none of them appear here, so a
 * client cannot assert that a monitor is healthy or manipulate its schedule.
 *
 * <p>Cross-field rules that annotations cannot express, such as the timeout
 * having to be shorter than the interval, are enforced in the service.
 */
public record CreateMonitorRequest(
        @NotBlank(message = "Monitor name is required")
        @Size(min = 2, max = 150, message = "Monitor name must be between 2 and 150 characters")
        String name,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        String description,

        @NotBlank(message = "URL is required")
        @Size(max = 2048, message = "URL must not exceed 2048 characters")
        String url,

        @NotNull(message = "HTTP method is required")
        MonitorHttpMethod httpMethod,

        @NotNull(message = "Expected status code is required")
        @Min(value = 100, message = "Expected status code must be between 100 and 599")
        @Max(value = 599, message = "Expected status code must be between 100 and 599")
        Integer expectedStatusCode,

        @NotNull(message = "Interval is required")
        @Min(value = 30, message = "Interval must be at least 30 seconds")
        @Max(value = 86400, message = "Interval must not exceed 86400 seconds")
        Integer intervalSeconds,

        @NotNull(message = "Timeout is required")
        @Min(value = 1, message = "Timeout must be at least 1 second")
        @Max(value = 30, message = "Timeout must not exceed 30 seconds")
        Integer timeoutSeconds,

        @NotNull(message = "Failure threshold is required")
        @Min(value = 1, message = "Failure threshold must be at least 1")
        @Max(value = 10, message = "Failure threshold must not exceed 10")
        Integer failureThreshold) {

    /**
     * Trims before validation runs, so a padded name is cleaned rather than
     * stored with its whitespace. The URL is only trimmed — never lower-cased,
     * because paths and query strings are case-sensitive.
     */
    public CreateMonitorRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
        url = url == null ? null : url.trim();
    }
}
