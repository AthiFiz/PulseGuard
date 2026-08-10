package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.project.CreateProjectRequest;
import com.pulseguard.controlapi.dto.project.ProjectResponse;
import com.pulseguard.controlapi.dto.project.UpdateProjectRequest;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.ProjectRepository;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import com.pulseguard.controlapi.service.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        User creator = userRepository.findById(currentUserService.requireCurrentUserId())
                .orElseThrow(ApiException::userNotFound);
        Project project = new Project(request.name().trim(), creator);
        project.setDescription(trimToNull(request.description()));
        Project saved = projectRepository.save(project);
        projectMemberRepository.save(new ProjectMember(saved, creator, ProjectRole.PROJECT_ADMIN));
        log.info("Project created: projectId={}, creatorUserId={}", saved.getId(), creator.getId());
        return ProjectResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects() {
        List<Project> projects = currentUserService.isSystemAdmin()
                ? projectRepository.findAllWithCreator()
                : projectRepository.findAllForMember(currentUserService.requireCurrentUserId());
        return projects.stream().map(ProjectResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse getProject(Long projectId) {
        return ProjectResponse.from(projectAccessService.requireReadableProject(projectId));
    }

    @Override
    @Transactional
    public ProjectResponse updateProject(Long projectId, UpdateProjectRequest request) {
        Project project = projectAccessService.requireManageableProject(projectId);
        project.setName(request.name().trim());
        project.setDescription(trimToNull(request.description()));
        log.info("Project updated: projectId={}", projectId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId) {
        Project project = projectAccessService.requireManageableProject(projectId);

        // Required, even though the database cascades project_members away:
        // the permission check above has already loaded the caller's membership
        // into the persistence context, and Hibernate would flush that row
        // against a parent it had just deleted, failing with
        // TransientPropertyValueException. Monitors and checks are safe to
        // leave to the cascade because nothing here loads them.
        projectMemberRepository.deleteAllByProjectId(projectId);
        projectRepository.delete(project);

        log.info("Project deleted: projectId={}", projectId);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
