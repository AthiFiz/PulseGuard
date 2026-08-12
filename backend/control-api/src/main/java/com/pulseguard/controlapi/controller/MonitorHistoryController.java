package com.pulseguard.controlapi.controller;

import com.pulseguard.controlapi.dto.monitoring.MonitorCheckResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.service.MonitoringQueryService;
import com.pulseguard.controlapi.service.TimeWindow;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/monitors/{monitorId}")
@RequiredArgsConstructor
public class MonitorHistoryController {

    private final MonitoringQueryService monitoringQueryService;

    /**
     * Paginated check history, newest first.
     *
     * <p>{@code from} and {@code to} are inclusive ISO-8601 instants, for
     * example {@code 2026-08-01T00:00:00Z}. An unparseable value or an unknown
     * {@code outcome} is a 400, not a 500.
     */
    @GetMapping("/checks")
    public PageResponse<MonitorCheckResponse> getCheckHistory(
            @PathVariable Long monitorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) MonitorCheckOutcome outcome) {

        return monitoringQueryService.getCheckHistory(monitorId, TimeWindow.of(from, to), outcome, page, size);
    }

    @GetMapping("/statistics")
    public MonitorStatisticsResponse getStatistics(
            @PathVariable Long monitorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        return monitoringQueryService.getStatistics(monitorId, TimeWindow.of(from, to));
    }
}
