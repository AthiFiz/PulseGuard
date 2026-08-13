package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One incident, addressed by its own id.
 *
 * <p>The project is not in the path: an incident belongs to a monitor, which
 * belongs to a project, and the service resolves that chain itself rather than
 * trusting a project id supplied by the caller.
 */
@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    @GetMapping("/{incidentId}")
    public IncidentResponse getIncident(@PathVariable Long incidentId) {
        return incidentService.getIncident(incidentId);
    }
}
