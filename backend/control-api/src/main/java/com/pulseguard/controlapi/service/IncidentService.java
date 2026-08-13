package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.IncidentStatus;

/**
 * Reading incidents. There is deliberately no create, update or delete: an
 * incident is a record of something the Monitor Worker observed, and nothing a
 * user types can make a service go down or come back.
 */
public interface IncidentService {

    PageResponse<IncidentResponse> getProjectIncidents(
            Long projectId, IncidentStatus status, TimeWindow window, int page, int size);

    IncidentResponse getIncident(Long incidentId);
}
