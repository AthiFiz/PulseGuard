package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.ProjectRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.impl.ProjectAccessServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 1L;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private ProjectAccessServiceImpl projectAccessService;

    @Test
    void memberCanReadTheProject() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(currentUserService.isSystemAdmin()).thenReturn(false);
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, USER_ID)).thenReturn(true);

        assertThat(projectAccessService.requireReadableProject(PROJECT_ID)).isSameAs(project);
    }

    /** A non-member must not be able to tell an existing project from a missing one. */
    @Test
    void nonMemberSeesTheProjectAsNotFoundRatherThanForbidden() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project()));
        when(currentUserService.isSystemAdmin()).thenReturn(false);
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> projectAccessService.requireReadableProject(PROJECT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    void systemAdminReadsAnyProjectWithoutMembership() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(currentUserService.isSystemAdmin()).thenReturn(true);

        assertThat(projectAccessService.requireReadableProject(PROJECT_ID)).isSameAs(project);
    }

    @Test
    void projectAdminMayManageTheProject() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(currentUserService.isSystemAdmin()).thenReturn(false);
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(membership(project, ProjectRole.PROJECT_ADMIN)));

        assertThat(projectAccessService.requireManageableProject(PROJECT_ID)).isSameAs(project);
    }

    /** A VIEWER already knows the project exists, so this is a plain 403. */
    @Test
    void viewerMayNotManageTheProject() {
        Project project = project();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(currentUserService.isSystemAdmin()).thenReturn(false);
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(membership(project, ProjectRole.VIEWER)));

        assertThatThrownBy(() -> projectAccessService.requireManageableProject(PROJECT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ACCESS_DENIED);
    }

    @Test
    void missingProjectIsNotFound() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectAccessService.requireReadableProject(PROJECT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_NOT_FOUND);
    }

    private static Project project() {
        return new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
    }

    private static ProjectMember membership(Project project, ProjectRole role) {
        return new ProjectMember(project, new User("user@example.com", "{bcrypt}h", "User"), role);
    }
}
