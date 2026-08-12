package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.service.MonitorAccessService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MonitorAccessServiceImpl implements MonitorAccessService {

    private final MonitorRepository monitorRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public Monitor requireReadableMonitor(Long monitorId) {
        Monitor monitor = monitorRepository.findById(monitorId).orElseThrow(ApiException::monitorNotFound);
        requireProjectAccess(monitor, false);
        return monitor;
    }

    @Override
    @Transactional(readOnly = true)
    public Monitor requireManageableMonitor(Long monitorId) {
        Monitor monitor = monitorRepository.findById(monitorId).orElseThrow(ApiException::monitorNotFound);
        requireProjectAccess(monitor, true);
        return monitor;
    }

    /**
     * Defers to the project rules, translating only the "this project is
     * invisible to you" case into a monitor-shaped 404 — otherwise a non-member
     * could tell an existing monitor from a missing one by the error code alone.
     *
     * <p>A VIEWER attempting a write still gets the plain ACCESS_DENIED, because
     * they can already see that the monitor exists.
     */
    private void requireProjectAccess(Monitor monitor, boolean requiresManage) {
        Long projectId = monitor.getProject().getId();
        try {
            if (requiresManage) {
                projectAccessService.requireManageableProject(projectId);
            } else {
                projectAccessService.requireReadableProject(projectId);
            }
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ApiErrorCode.PROJECT_NOT_FOUND) {
                throw ApiException.monitorNotFound();
            }
            throw ex;
        }
    }
}
