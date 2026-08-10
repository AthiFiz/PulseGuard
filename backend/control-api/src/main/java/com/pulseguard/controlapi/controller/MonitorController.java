package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.dto.monitor.UpdateMonitorRequest;
import com.pulseguard.controlapi.service.MonitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/monitors/{monitorId}")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    /** Any member of the owning project. */
    @GetMapping
    public MonitorResponse getMonitor(@PathVariable Long monitorId) {
        return monitorService.getMonitor(monitorId);
    }

    /** PROJECT_ADMIN or system administrator. Configuration only. */
    @PutMapping
    public MonitorResponse updateMonitor(
            @PathVariable Long monitorId, @Valid @RequestBody UpdateMonitorRequest request) {
        return monitorService.updateMonitor(monitorId, request);
    }

    /** PROJECT_ADMIN or system administrator. Idempotent. */
    @PostMapping("/pause")
    public MonitorResponse pauseMonitor(@PathVariable Long monitorId) {
        return monitorService.pauseMonitor(monitorId);
    }

    /** PROJECT_ADMIN or system administrator. Only affects a paused monitor. */
    @PostMapping("/resume")
    public MonitorResponse resumeMonitor(@PathVariable Long monitorId) {
        return monitorService.resumeMonitor(monitorId);
    }

    /** PROJECT_ADMIN or system administrator. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMonitor(@PathVariable Long monitorId) {
        monitorService.deleteMonitor(monitorId);
    }
}
