package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.monitor.CreateMonitorRequest;
import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.dto.monitor.UpdateMonitorRequest;
import java.util.List;

public interface MonitorService {

    MonitorResponse createMonitor(Long projectId, CreateMonitorRequest request);

    List<MonitorResponse> listMonitors(Long projectId);

    MonitorResponse getMonitor(Long monitorId);

    MonitorResponse updateMonitor(Long monitorId, UpdateMonitorRequest request);

    MonitorResponse pauseMonitor(Long monitorId);

    MonitorResponse resumeMonitor(Long monitorId);

    void deleteMonitor(Long monitorId);
}
