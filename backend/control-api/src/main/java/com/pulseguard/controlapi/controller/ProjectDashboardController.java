package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.monitoring.ProjectDashboardResponse;
import com.pulseguard.controlapi.service.DashboardService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A project's monitoring snapshot, for any member of that project.
 *
 * <p>Unlike the monitor statistics endpoint, omitting the range does not mean
 * "all history" — the dashboard defaults to the last 24 hours.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/dashboard")
@RequiredArgsConstructor
public class ProjectDashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ProjectDashboardResponse getDashboard(
            @PathVariable Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        return dashboardService.getProjectDashboard(projectId, from, to);
    }
}
