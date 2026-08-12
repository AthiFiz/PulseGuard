package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.service.impl.MonitorAccessServiceImpl;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The shared monitor lookup, including the resource-hiding rule that every
 * monitor endpoint depends on.
 */
@ExtendWith(MockitoExtension.class)
class MonitorAccessServiceTest {

    private static final Long MONITOR_ID = 25L;
    private static final Long PROJECT_ID = 10L;

    @Mock
    private MonitorRepository monitorRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @InjectMocks
    private MonitorAccessServiceImpl monitorAccessService;

    @Test
    void aMemberCanReadAMonitor() {
        Monitor monitor = monitor();
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(monitor.getProject());

        assertThat(monitorAccessService.requireReadableMonitor(MONITOR_ID)).isSameAs(monitor);
    }

    @Test
    void aProjectAdminCanManageAMonitor() {
        Monitor monitor = monitor();
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(monitor.getProject());

        assertThat(monitorAccessService.requireManageableMonitor(MONITOR_ID)).isSameAs(monitor);
    }

    @Test
    void aMissingMonitorIsNotFound() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monitorAccessService.requireReadableMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_NOT_FOUND);
    }

    /**
     * The project-level 404 is reshaped into a monitor 404, so walking monitor
     * ids tells a non-member nothing about other projects.
     */
    @Test
    void aNonMemberSeesTheMonitorAsMissingRatherThanForbidden() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor()));
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> monitorAccessService.requireReadableMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_NOT_FOUND);
    }

    /** A VIEWER already knows the monitor exists, so this stays an honest 403. */
    @Test
    void aViewerAttemptingToManageGetsAccessDenied() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor()));
        when(projectAccessService.requireManageableProject(PROJECT_ID))
                .thenThrow(ApiException.accessDenied());

        assertThatThrownBy(() -> monitorAccessService.requireManageableMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ACCESS_DENIED);
    }

    private static Monitor monitor() {
        Project project = new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);

        Monitor monitor = new Monitor(project, "Payment API", "https://api.example.com/health", 200, 60, 5, 3);
        ReflectionTestUtils.setField(monitor, "id", MONITOR_ID);
        return monitor;
    }
}
