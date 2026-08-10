package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.project.CreateProjectRequest;
import com.pulseguard.controlapi.dto.project.ProjectResponse;
import com.pulseguard.controlapi.dto.project.UpdateProjectRequest;
import java.util.List;

public interface ProjectService {

    ProjectResponse createProject(CreateProjectRequest request);

    List<ProjectResponse> listProjects();

    ProjectResponse getProject(Long projectId);

    ProjectResponse updateProject(Long projectId, UpdateProjectRequest request);

    void deleteProject(Long projectId);
}
