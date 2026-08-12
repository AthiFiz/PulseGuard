package com.pulseguard.controlapi.service.impl;

import static com.pulseguard.controlapi.service.impl.UptimeCalculator.averageResponseTime;
import static com.pulseguard.controlapi.service.impl.UptimeCalculator.orZero;
import static com.pulseguard.controlapi.service.impl.UptimeCalculator.uptimePercentage;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.MonitorCheck;
import com.pulseguard.controlapi.dto.monitoring.MonitorCheckResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorCheckRepository;
import com.pulseguard.controlapi.repository.projection.MonitorStatisticsProjection;
import com.pulseguard.controlapi.service.MonitorAccessService;
import com.pulseguard.controlapi.service.MonitoringQueryService;
import com.pulseguard.controlapi.service.TimeWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MonitoringQueryServiceImpl implements MonitoringQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final MonitorCheckRepository monitorCheckRepository;
    private final MonitorAccessService monitorAccessService;


    @Override
    @Transactional(readOnly = true)
    public PageResponse<MonitorCheckResponse> getCheckHistory(
            Long monitorId, TimeWindow window, MonitorCheckOutcome outcome, int page, int size) {

        // Access first: an inaccessible monitor must look missing, not empty.
        Monitor monitor = monitorAccessService.requireReadableMonitor(monitorId);

        validatePagination(page, size);

        // Sorting is fixed rather than caller-supplied. Newest-first is what a
        // history view wants, and an arbitrary sort column would invite queries
        // the (monitor_id, checked_at) index cannot serve.
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkedAt"));

        Page<MonitorCheck> checks = monitorCheckRepository.findHistory(
                        monitor.getId(), window.from(), window.to(), outcome, pageRequest);

        return PageResponse.from(checks, MonitorCheckResponse::from);
    }

    /**
     * Aggregate figures for one monitor.
     * With no range supplied this covers all recorded history
     */
    @Override
    @Transactional(readOnly = true)
    public MonitorStatisticsResponse getStatistics(Long monitorId, TimeWindow window) {
        Monitor monitor = monitorAccessService.requireReadableMonitor(monitorId);

        MonitorStatisticsProjection stats = monitorCheckRepository.aggregateForMonitor(
                monitor.getId(), window.from(), window.to(), MonitorCheckOutcome.SUCCESS);

        long total = orZero(stats.getTotalChecks());
        long successful = orZero(stats.getSuccessfulChecks());

        return new MonitorStatisticsResponse(
                monitor.getId(),
                window.from(),
                window.to(),
                total,
                successful,
                total - successful,
                uptimePercentage(successful, total),
                averageResponseTime(stats.getAverageResponseTimeMs()),
                stats.getMinimumResponseTimeMs(),
                stats.getMaximumResponseTimeMs(),
                stats.getLastCheckedAt(),
                monitor.getCurrentStatus());
    }

    /**
     * Page numbers and sizes are rejected rather than clamped.
     *
     * <p>Silently turning {@code size=100000} into 100 would leave a client
     * convinced it had received everything.
     */
    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw ApiException.monitoringQueryInvalid("'page' must not be negative");
        }
        if (size < 1) {
            throw ApiException.monitoringQueryInvalid("'size' must be at least 1");
        }
        if (size > MAX_PAGE_SIZE) {
            throw ApiException.monitoringQueryInvalid(
                    "'size' must not exceed %d".formatted(MAX_PAGE_SIZE));
        }
    }
}
