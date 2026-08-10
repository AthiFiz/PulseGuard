package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.ProjectMember;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.project.CreateProjectRequest;
import com.pulseguard.controlapi.enums.ProjectRole;
import com.pulseguard.controlapi.repository.ProjectMemberRepository;
import com.pulseguard.controlapi.repository.ProjectRepository;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.impl.ProjectServiceImpl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ProjectAccessService projectAccessService;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    void creatingAProjectMakesTheCreatorAProjectAdmin() {
        User creator = new User("owner@example.com", "{bcrypt}h", "Owner");
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(creator));
        when(projectRepository.save(any(Project.class))).thenAnswer(call -> call.getArgument(0));

        projectService.createProject(new CreateProjectRequest("Production APIs", "desc"));

        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberRepository).save(captor.capture());

        ProjectMember membership = captor.getValue();
        assertThat(membership.getRole()).isEqualTo(ProjectRole.PROJECT_ADMIN);
        assertThat(membership.getUser()).isSameAs(creator);
    }

    @Test
    void creatingAProjectRecordsTheCreatorAndTrimsInput() {
        User creator = new User("owner@example.com", "{bcrypt}h", "Owner");
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(creator));
        when(projectRepository.save(any(Project.class))).thenAnswer(call -> call.getArgument(0));

        projectService.createProject(new CreateProjectRequest("  Production APIs  ", "   "));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());

        assertThat(captor.getValue().getName()).isEqualTo("Production APIs");
        assertThat(captor.getValue().getCreatedBy()).isSameAs(creator);
        // Whitespace-only descriptions are stored as null rather than blanks.
        assertThat(captor.getValue().getDescription()).isNull();
    }

    /** A normal user must never see projects they are not a member of. */
    @Test
    void normalUserOnlyListsProjectsTheyBelongTo() {
        when(currentUserService.isSystemAdmin()).thenReturn(false);
        when(currentUserService.requireCurrentUserId()).thenReturn(USER_ID);
        when(projectRepository.findAllForMember(USER_ID)).thenReturn(List.of());

        projectService.listProjects();

        verify(projectRepository).findAllForMember(USER_ID);
    }

    @Test
    void systemAdminListsEveryProject() {
        when(currentUserService.isSystemAdmin()).thenReturn(true);
        when(projectRepository.findAllWithCreator()).thenReturn(List.of());

        projectService.listProjects();

        verify(projectRepository).findAllWithCreator();
    }

    @Test
    void updatingAProjectRequiresManagePermission() {
        Project project = new Project("Old", new User("owner@example.com", "{bcrypt}h", "Owner"));
        when(projectAccessService.requireManageableProject(10L)).thenReturn(project);

        projectService.updateProject(
                10L, new com.pulseguard.controlapi.dto.project.UpdateProjectRequest("New name", "New desc"));

        verify(projectAccessService).requireManageableProject(10L);
        assertThat(project.getName()).isEqualTo("New name");
        assertThat(project.getDescription()).isEqualTo("New desc");
    }

    @Test
    void deletingAProjectRequiresManagePermission() {
        Project project = new Project("Prod", new User("owner@example.com", "{bcrypt}h", "Owner"));
        when(projectAccessService.requireManageableProject(10L)).thenReturn(project);

        projectService.deleteProject(10L);

        verify(projectAccessService).requireManageableProject(10L);
        verify(projectRepository).delete(project);
    }
}
