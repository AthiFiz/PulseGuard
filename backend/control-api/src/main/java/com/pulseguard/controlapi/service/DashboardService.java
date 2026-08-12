package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.monitoring.ProjectDashboardResponse;
import java.time.Instant;

/** A project-level operational snapshot, not a full analytics system. */
public interface DashboardService {

    ProjectDashboardResponse getProjectDashboard(Long projectId, Instant from, Instant to);
}
