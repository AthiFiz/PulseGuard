package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.incident.IncidentResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.IncidentStatus;
import com.pulseguard.controlapi.service.IncidentService;
import com.pulseguard.controlapi.service.TimeWindow;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A project's outage history, for any member of that project.
 *
 * <p>Read-only. Incidents are opened and resolved by the Monitor Worker from
 * observed checks, so there is nothing here to POST to.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/incidents")
@RequiredArgsConstructor
public class ProjectIncidentController {

    private final IncidentService incidentService;

    /**
     * Paginated incident history, newest first.
     *
     * <p>{@code from} and {@code to} are inclusive ISO-8601 instants and filter
     * on {@code openedAt} — the moment the outage began. An outage that started
     * before the window but is still open is therefore excluded, which is what
     * "incidents opened in this period" means.
     */
    @GetMapping
    public PageResponse<IncidentResponse> getProjectIncidents(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        return incidentService.getProjectIncidents(projectId, status, TimeWindow.of(from, to), page, size);
    }
}
