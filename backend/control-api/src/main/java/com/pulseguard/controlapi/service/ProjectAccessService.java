package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.domain.Project;

public interface ProjectAccessService {

    Project requireReadableProject(Long projectId);

    Project requireManageableProject(Long projectId);
}
