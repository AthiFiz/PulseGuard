package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.ProjectRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place project access is decided.
 *
 * <p>Project roles depend on rows in {@code project_members}, which change
 * without a new login, so they cannot be expressed as URL-pattern rules or
 * token authorities — they must be read from the database at the moment of use.
 * Keeping both checks here stops the rules drifting apart across controllers.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessServiceImpl implements ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final CurrentUserService currentUserService;

    /**
     * Any member may read; a system administrator may read anything.
     *
     * <p>A non-member gets {@code PROJECT_NOT_FOUND} rather than
     * {@code ACCESS_DENIED}, so iterating over ids cannot be used to discover
     * which projects exist.
     */
    @Transactional(readOnly = true)
    @Override
    public Project requireReadableProject(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow(ApiException::projectNotFound);

        if (currentUserService.isSystemAdmin()) {
            return project;
        }

        Long userId = currentUserService.requireCurrentUserId();
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw ApiException.projectNotFound();
        }
        return project;
    }

    /**
     * Only a PROJECT_ADMIN member, or a system administrator, may modify a
     * project or its membership. A VIEWER who can see the project gets a plain
     * 403 here, since they already know it exists.
     */
    @Transactional(readOnly = true)
    @Override
    public Project requireManageableProject(Long projectId) {
        Project project = requireReadableProject(projectId);

        if (currentUserService.isSystemAdmin()) {
            return project;
        }

        ProjectMember membership = projectMemberRepository
                .findByProjectIdAndUserId(projectId, currentUserService.requireCurrentUserId())
                .orElseThrow(ApiException::accessDenied);

        if (membership.getRole() != ProjectRole.PROJECT_ADMIN) {
            throw ApiException.accessDenied();
        }
        return project;
    }
}
