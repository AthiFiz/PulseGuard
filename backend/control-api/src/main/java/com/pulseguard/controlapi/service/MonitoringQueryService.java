package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.monitoring.MonitorCheckResponse;
import com.pulseguard.controlapi.dto.monitoring.MonitorStatisticsResponse;
import com.pulseguard.controlapi.dto.monitoring.PageResponse;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;


public interface MonitoringQueryService {

    PageResponse<MonitorCheckResponse> getCheckHistory(Long monitorId, TimeWindow window, MonitorCheckOutcome outcome, int page, int size);

    MonitorStatisticsResponse getStatistics(Long monitorId, TimeWindow window);
}
