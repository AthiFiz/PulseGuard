package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.domain.Monitor;

/**
 * Resolves a monitor the caller is allowed to touch.
 *
 * <p>Monitor permissions are entirely derived from the owning project, so this
 * is a thin translation over {@link ProjectAccessService} rather than a second
 * authorization system. It exists because monitor configuration, check history
 * and statistics all need the same two-step lookup, and a copy of it in each
 * would be three places for the rules to drift apart.
 */
public interface MonitorAccessService {

    /** Any member of the owning project, or a system administrator. */
    Monitor requireReadableMonitor(Long monitorId);

    /** A PROJECT_ADMIN of the owning project, or a system administrator. */
    Monitor requireManageableMonitor(Long monitorId);
}
