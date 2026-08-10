package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.member.AddProjectMemberRequest;
import com.pulseguard.controlapi.dto.member.ProjectMemberResponse;
import com.pulseguard.controlapi.dto.member.UpdateProjectMemberRequest;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.util.EmailNormalizer;
import com.pulseguard.controlapi.service.ProjectAccessService;
import com.pulseguard.controlapi.service.ProjectMemberService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMemberServiceImpl implements ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(Long projectId) {
        projectAccessService.requireReadableProject(projectId);
        return projectMemberRepository.findAllByProjectIdWithUser(projectId).stream()
                .map(ProjectMemberResponse::from).toList();
    }

    @Override
    @Transactional
    public ProjectMemberResponse addMember(Long projectId, AddProjectMemberRequest request) {
        Project project = projectAccessService.requireManageableProject(projectId);
        User user = userRepository.findByEmail(EmailNormalizer.normalize(request.email()))
                .orElseThrow(ApiException::userNotFound);
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, user.getId())) {
            throw ApiException.projectMemberAlreadyExists();
        }
        ProjectMember member = projectMemberRepository.save(new ProjectMember(project, user, request.role()));
        log.info("Project member added: projectId={}, userId={}, role={}", projectId, user.getId(), request.role());
        return ProjectMemberResponse.from(member);
    }

    @Override
    @Transactional
    public ProjectMemberResponse updateMemberRole(Long projectId, Long memberId, UpdateProjectMemberRequest request) {
        projectAccessService.requireManageableProject(projectId);
        ProjectMember member = requireMemberOfProject(projectId, memberId);
        ProjectRole newRole = request.role();
        if (member.getRole() == ProjectRole.PROJECT_ADMIN && newRole != ProjectRole.PROJECT_ADMIN) {
            requireAnotherAdminRemains(projectId);
        }
        member.setRole(newRole);
        log.info("Project member role changed: projectId={}, memberId={}, role={}", projectId, memberId, newRole);
        return ProjectMemberResponse.from(member);
    }

    @Override
    @Transactional
    public void removeMember(Long projectId, Long memberId) {
        projectAccessService.requireManageableProject(projectId);
        ProjectMember member = requireMemberOfProject(projectId, memberId);
        if (member.getRole() == ProjectRole.PROJECT_ADMIN) requireAnotherAdminRemains(projectId);
        projectMemberRepository.delete(member);
        log.info("Project member removed: projectId={}, memberId={}", projectId, memberId);
    }

    private ProjectMember requireMemberOfProject(Long projectId, Long memberId) {
        return projectMemberRepository.findByIdAndProjectId(memberId, projectId)
                .orElseThrow(ApiException::projectMemberNotFound);
    }

    private void requireAnotherAdminRemains(Long projectId) {
        if (projectMemberRepository.countByProjectIdAndRole(projectId, ProjectRole.PROJECT_ADMIN) <= 1) {
            throw ApiException.projectRequiresAdmin();
        }
    }
}
