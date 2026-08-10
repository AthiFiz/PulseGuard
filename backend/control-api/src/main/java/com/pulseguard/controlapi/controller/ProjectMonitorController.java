package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.monitor.CreateMonitorRequest;
import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.service.MonitorService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/monitors")
@RequiredArgsConstructor
public class ProjectMonitorController {

    private final MonitorService monitorService;

    /** PROJECT_ADMIN or system administrator. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MonitorResponse createMonitor(
            @PathVariable Long projectId, @Valid @RequestBody CreateMonitorRequest request) {
        return monitorService.createMonitor(projectId, request);
    }

    /** Any project member. Returns an empty list when the project has none. */
    @GetMapping
    public List<MonitorResponse> listMonitors(@PathVariable Long projectId) {
        return monitorService.listMonitors(projectId);
    }
}
