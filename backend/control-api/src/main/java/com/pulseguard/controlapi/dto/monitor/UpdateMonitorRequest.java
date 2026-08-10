package com.pulseguard.controlapi.dto.monitor;

import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Replacement configuration for an existing monitor.
 *
 * <p>Identical to {@link CreateMonitorRequest} on purpose: the same rules apply
 * whether a monitor is being created or reconfigured.
 *
 * <p>There is deliberately no {@code projectId}. A monitor cannot be moved
 * between projects, because access is derived entirely from its project — moving
 * one would silently transfer who can see it.
 */
public record UpdateMonitorRequest(
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

    public UpdateMonitorRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
        url = url == null ? null : url.trim();
    }
}
