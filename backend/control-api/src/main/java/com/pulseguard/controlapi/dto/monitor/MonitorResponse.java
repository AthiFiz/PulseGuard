package com.pulseguard.controlapi.dto.monitor;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import com.pulseguard.controlapi.enums.MonitorStatus;
import java.time.Instant;

/**
 * A monitor as exposed by the API.
 *
 * <p>Carries the owning project's id rather than the project itself, so no JPA
 * entity is serialised and no lazy association is triggered.
 */
public record MonitorResponse(
        Long id,
        Long projectId,
        String name,
        String description,
        String url,
        MonitorHttpMethod httpMethod,
        int expectedStatusCode,
        int intervalSeconds,
        int timeoutSeconds,
        int failureThreshold,
        int consecutiveFailures,
        MonitorStatus currentStatus,
        Instant lastCheckedAt,
        Instant nextCheckAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MonitorResponse from(Monitor monitor) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getProject().getId(),
                monitor.getName(),
                monitor.getDescription(),
                monitor.getUrl(),
                monitor.getHttpMethod(),
                monitor.getExpectedStatusCode(),
                monitor.getIntervalSeconds(),
                monitor.getTimeoutSeconds(),
                monitor.getFailureThreshold(),
                monitor.getConsecutiveFailures(),
                monitor.getCurrentStatus(),
                monitor.getLastCheckedAt(),
                monitor.getNextCheckAt(),
                monitor.getCreatedAt(),
                monitor.getUpdatedAt());
    }
}
