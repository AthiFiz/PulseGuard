package com.pulseguard.controlapi.dto.incident;

import com.pulseguard.controlapi.domain.Incident;
import com.pulseguard.controlapi.enums.IncidentStatus;
import java.time.Instant;

/**
 * One outage, with just enough of its monitor to be readable on its own.
 *
 * <p>No duration field: it is exactly {@code resolvedAt - openedAt} and storing
 * or computing it here would be a second copy of a fact the timestamps already
 * carry. A client that wants to show it can subtract.
 *
 * <p>For an OPEN incident {@code resolvedAt} and {@code resolutionCheckId} are
 * null. Nothing invents a resolution time for an outage that has not ended.
 */
public record IncidentResponse(
        Long id,
        Long projectId,
        Long monitorId,
        String monitorName,
        IncidentStatus status,
        Instant openedAt,
        Instant resolvedAt,
        Long openingCheckId,
        Long resolutionCheckId) {

    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getMonitor().getProject().getId(),
                incident.getMonitor().getId(),
                incident.getMonitor().getName(),
                incident.getStatus(),
                incident.getOpenedAt(),
                incident.getResolvedAt(),
                incident.getOpeningCheckId(),
                incident.getResolutionCheckId());
    }
}
