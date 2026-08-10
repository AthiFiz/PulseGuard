package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.member.AddProjectMemberRequest;
import com.pulseguard.controlapi.dto.member.UpdateProjectMemberRequest;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.service.impl.ProjectMemberServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MEMBER_ID = 22L;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @InjectMocks
    private ProjectMemberServiceImpl projectMemberService;

    @Test
    void addingAMemberRequiresManagePermission() {
        Project project = project();
        User invited = new User("viewer@example.com", "{bcrypt}h", "Viewer");
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project);
        when(userRepository.findByEmail("viewer@example.com")).thenReturn(Optional.of(invited));
        when(projectMemberRepository.existsByProjectIdAndUserId(any(), any())).thenReturn(false);
        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(call -> call.getArgument(0));

        projectMemberService.addMember(
                PROJECT_ID, new AddProjectMemberRequest("Viewer@Example.com", ProjectRole.VIEWER));

        verify(projectAccessService).requireManageableProject(PROJECT_ID);
        // The email is normalized before lookup, exactly as at registration.
        verify(userRepository).findByEmail("viewer@example.com");
    }

    @Test
    void addingAnUnknownUserIsNotFoundAndCreatesNobody() {
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectMemberService.addMember(
                        PROJECT_ID, new AddProjectMemberRequest("ghost@example.com", ProjectRole.VIEWER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.USER_NOT_FOUND);

        verify(projectMemberRepository, never()).save(any());
    }

    @Test
    void addingAnExistingMemberConflicts() {
        User existing = new User("viewer@example.com", "{bcrypt}h", "Viewer");
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(userRepository.findByEmail("viewer@example.com")).thenReturn(Optional.of(existing));
        when(projectMemberRepository.existsByProjectIdAndUserId(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> projectMemberService.addMember(
                        PROJECT_ID, new AddProjectMemberRequest("viewer@example.com", ProjectRole.VIEWER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_MEMBER_ALREADY_EXISTS);

        verify(projectMemberRepository, never()).save(any());
    }

    /** Guards against reaching another project's membership through this path. */
    @Test
    void membershipFromAnotherProjectIsNotFound() {
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectMemberService.removeMember(PROJECT_ID, MEMBER_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_MEMBER_NOT_FOUND);
    }

    @Test
    void theLastProjectAdminCannotBeDemoted() {
        ProjectMember onlyAdmin = membership(ProjectRole.PROJECT_ADMIN);
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.of(onlyAdmin));
        when(projectMemberRepository.countByProjectIdAndRole(PROJECT_ID, ProjectRole.PROJECT_ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> projectMemberService.updateMemberRole(
                        PROJECT_ID, MEMBER_ID, new UpdateProjectMemberRequest(ProjectRole.VIEWER)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_REQUIRES_ADMIN);

        assertThat(onlyAdmin.getRole()).isEqualTo(ProjectRole.PROJECT_ADMIN);
    }

    @Test
    void theLastProjectAdminCannotBeRemoved() {
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.of(membership(ProjectRole.PROJECT_ADMIN)));
        when(projectMemberRepository.countByProjectIdAndRole(PROJECT_ID, ProjectRole.PROJECT_ADMIN))
                .thenReturn(1L);

        assertThatThrownBy(() -> projectMemberService.removeMember(PROJECT_ID, MEMBER_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_REQUIRES_ADMIN);

        verify(projectMemberRepository, never()).delete(any());
    }

    @Test
    void anAdminCanBeDemotedWhileAnotherAdminRemains() {
        ProjectMember admin = membership(ProjectRole.PROJECT_ADMIN);
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.of(admin));
        when(projectMemberRepository.countByProjectIdAndRole(PROJECT_ID, ProjectRole.PROJECT_ADMIN))
                .thenReturn(2L);

        projectMemberService.updateMemberRole(
                PROJECT_ID, MEMBER_ID, new UpdateProjectMemberRequest(ProjectRole.VIEWER));

        assertThat(admin.getRole()).isEqualTo(ProjectRole.VIEWER);
    }

    /** Removing a VIEWER never threatens the last-admin rule. */
    @Test
    void aViewerCanAlwaysBeRemoved() {
        ProjectMember viewer = membership(ProjectRole.VIEWER);
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.of(viewer));

        projectMemberService.removeMember(PROJECT_ID, MEMBER_ID);

        verify(projectMemberRepository).delete(viewer);
    }

    @Test
    void promotingAViewerNeedsNoAdminCount() {
        ProjectMember viewer = membership(ProjectRole.VIEWER);
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findByIdAndProjectId(MEMBER_ID, PROJECT_ID))
                .thenReturn(Optional.of(viewer));

        projectMemberService.updateMemberRole(
                PROJECT_ID, MEMBER_ID, new UpdateProjectMemberRequest(ProjectRole.PROJECT_ADMIN));

        assertThat(viewer.getRole()).isEqualTo(ProjectRole.PROJECT_ADMIN);
    }

    @Test
    void listingMembersOnlyRequiresReadAccess() {
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());
        when(projectMemberRepository.findAllByProjectIdWithUser(PROJECT_ID)).thenReturn(java.util.List.of());

        projectMemberService.listMembers(PROJECT_ID);

        verify(projectAccessService).requireReadableProject(PROJECT_ID);
    }

    private static Project project() {
        return new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
    }

    private static ProjectMember membership(ProjectRole role) {
        return new ProjectMember(project(), new User("member@example.com", "{bcrypt}h", "Member"), role);
    }
}
