package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.Monitor;
import com.pulseguard.controlapi.domain.Project;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.monitor.CreateMonitorRequest;
import com.pulseguard.controlapi.dto.monitor.MonitorResponse;
import com.pulseguard.controlapi.dto.monitor.UpdateMonitorRequest;
import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import com.pulseguard.controlapi.enums.MonitorStatus;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.MonitorRepository;
import com.pulseguard.controlapi.service.impl.MonitorServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Monitor configuration behaviour, with time pinned so scheduling fields can be
 * asserted exactly. No database is involved.
 */
@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MONITOR_ID = 25L;
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Mock
    private MonitorRepository monitorRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    private MonitorServiceImpl monitorService;

    @BeforeEach
    void setUp() {
        monitorService = new MonitorServiceImpl(
                monitorRepository, projectAccessService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- create

    @Test
    void createStoresTheSubmittedConfiguration() {
        givenManageableProject();
        givenRepositoryEchoesSavedMonitor();

        monitorService.createMonitor(PROJECT_ID, validCreateRequest());

        Monitor saved = capturedMonitor();
        assertThat(saved.getName()).isEqualTo("Payment API");
        assertThat(saved.getDescription()).isEqualTo("Payment health endpoint");
        assertThat(saved.getUrl()).isEqualTo("https://api.example.com/health");
        assertThat(saved.getHttpMethod()).isEqualTo(MonitorHttpMethod.GET);
        assertThat(saved.getExpectedStatusCode()).isEqualTo(200);
        assertThat(saved.getIntervalSeconds()).isEqualTo(60);
        assertThat(saved.getTimeoutSeconds()).isEqualTo(5);
        assertThat(saved.getFailureThreshold()).isEqualTo(3);
    }

    /** A brand-new monitor has never been checked, and must not claim otherwise. */
    @Test
    void createSetsTheOperationalStateItself() {
        givenManageableProject();
        givenRepositoryEchoesSavedMonitor();

        MonitorResponse response = monitorService.createMonitor(PROJECT_ID, validCreateRequest());

        Monitor saved = capturedMonitor();
        assertThat(saved.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(saved.getConsecutiveFailures()).isZero();
        assertThat(saved.getLastCheckedAt()).isNull();
        // Due immediately, so the future worker picks it up on its first pass.
        assertThat(saved.getNextCheckAt()).isEqualTo(NOW);
        assertThat(response.currentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
    }

    @Test
    void createRequiresManagePermission() {
        when(projectAccessService.requireManageableProject(PROJECT_ID))
                .thenThrow(ApiException.accessDenied());

        assertThatThrownBy(() -> monitorService.createMonitor(PROJECT_ID, validCreateRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ACCESS_DENIED);

        verify(monitorRepository, never()).save(any());
    }

    @Test
    void blankDescriptionBecomesNull() {
        givenManageableProject();
        givenRepositoryEchoesSavedMonitor();

        monitorService.createMonitor(PROJECT_ID, createRequestWithDescription("   "));

        assertThat(capturedMonitor().getDescription()).isNull();
    }

    // ------------------------------------------------------------ validation

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/health", "https://example.com/health"})
    void webUrlsAreAccepted(String url) {
        givenManageableProject();
        givenRepositoryEchoesSavedMonitor();

        monitorService.createMonitor(PROJECT_ID, createRequestWithUrl(url));

        assertThat(capturedMonitor().getUrl()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/health",
        "file:///etc/passwd",
        "jar:file:///tmp/a.jar!/b",
        "data:text/plain;base64,aGk=",
        "javascript:alert(1)"
    })
    void nonWebSchemesAreRejected(String url) {
        givenManageableProject();

        assertThatThrownBy(() -> monitorService.createMonitor(PROJECT_ID, createRequestWithUrl(url)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_VALIDATION_ERROR);

        verify(monitorRepository, never()).save(any());
    }

    @Test
    void urlWithoutAHostIsRejected() {
        givenManageableProject();

        assertThatThrownBy(() -> monitorService.createMonitor(PROJECT_ID, createRequestWithUrl("https:///health")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("host");
    }

    /**
     * The two fields can only be compared against each other, which no Bean
     * Validation annotation can do — so the rule lives in the service.
     */
    @Test
    void timeoutEqualToTheIntervalIsRejected() {
        givenManageableProject();

        assertThatThrownBy(() -> monitorService.createMonitor(PROJECT_ID, createRequestWithTiming(60, 60)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_VALIDATION_ERROR);
    }

    @Test
    void timeoutLongerThanTheIntervalIsRejected() {
        givenManageableProject();

        assertThatThrownBy(() -> monitorService.createMonitor(PROJECT_ID, createRequestWithTiming(90, 60)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_VALIDATION_ERROR);
    }

    @Test
    void timeoutShorterThanTheIntervalIsAccepted() {
        givenManageableProject();
        givenRepositoryEchoesSavedMonitor();

        monitorService.createMonitor(PROJECT_ID, createRequestWithTiming(30, 31));

        assertThat(capturedMonitor().getTimeoutSeconds()).isEqualTo(30);
    }

    // ------------------------------------------------------------------ read

    @Test
    void listingRequiresOnlyReadAccess() {
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());
        when(monitorRepository.findAllByProjectIdOrderByCreatedAtAsc(PROJECT_ID)).thenReturn(List.of());

        assertThat(monitorService.listMonitors(PROJECT_ID)).isEmpty();
        verify(projectAccessService).requireReadableProject(PROJECT_ID);
    }

    @Test
    void listingAnInaccessibleProjectPropagatesNotFound() {
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> monitorService.listMonitors(PROJECT_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    void gettingAMonitorRequiresOnlyReadAccess() {
        givenExistingMonitor(monitor(MonitorStatus.UNKNOWN));
        when(projectAccessService.requireReadableProject(PROJECT_ID)).thenReturn(project());

        assertThat(monitorService.getMonitor(MONITOR_ID).id()).isEqualTo(MONITOR_ID);
    }

    @Test
    void aMissingMonitorIsNotFound() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monitorService.getMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_NOT_FOUND);
    }

    /**
     * A non-member must not be able to tell an existing monitor from a missing
     * one, so the project-level 404 is re-shaped into a monitor 404.
     */
    @Test
    void aNonMemberCannotDiscoverThatAMonitorExists() {
        givenExistingMonitor(monitor(MonitorStatus.UP));
        when(projectAccessService.requireReadableProject(PROJECT_ID))
                .thenThrow(ApiException.projectNotFound());

        assertThatThrownBy(() -> monitorService.getMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_NOT_FOUND);
    }

    /** A VIEWER can already see the monitor, so a write is an honest 403. */
    @Test
    void aViewerAttemptingAWriteGetsAccessDenied() {
        givenExistingMonitor(monitor(MonitorStatus.UNKNOWN));
        when(projectAccessService.requireManageableProject(PROJECT_ID))
                .thenThrow(ApiException.accessDenied());

        assertThatThrownBy(() -> monitorService.pauseMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.ACCESS_DENIED);
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateChangesConfigurationOnly() {
        Monitor existing = monitor(MonitorStatus.UP);
        existing.setConsecutiveFailures(2);
        existing.setLastCheckedAt(Instant.parse("2026-08-09T09:00:00Z"));
        existing.setNextCheckAt(Instant.parse("2026-08-09T09:01:00Z"));

        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.updateMonitor(MONITOR_ID, new UpdateMonitorRequest(
                "Renamed", "New description", "https://api.example.com/v2/health",
                MonitorHttpMethod.GET, 204, 120, 10, 5));

        assertThat(existing.getName()).isEqualTo("Renamed");
        assertThat(existing.getUrl()).isEqualTo("https://api.example.com/v2/health");
        assertThat(existing.getExpectedStatusCode()).isEqualTo(204);
        assertThat(existing.getIntervalSeconds()).isEqualTo(120);
        assertThat(existing.getFailureThreshold()).isEqualTo(5);

        // Reconfiguring a monitor says nothing about its health.
        assertThat(existing.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(existing.getConsecutiveFailures()).isEqualTo(2);
        assertThat(existing.getLastCheckedAt()).isEqualTo(Instant.parse("2026-08-09T09:00:00Z"));
        assertThat(existing.getNextCheckAt()).isEqualTo(Instant.parse("2026-08-09T09:01:00Z"));
    }

    @Test
    void updateKeepsTheMonitorInItsOriginalProject() {
        Monitor existing = monitor(MonitorStatus.UNKNOWN);
        Project original = existing.getProject();
        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.updateMonitor(MONITOR_ID, new UpdateMonitorRequest(
                "Renamed", null, "https://example.com/health",
                MonitorHttpMethod.GET, 200, 60, 5, 3));

        assertThat(existing.getProject()).isSameAs(original);
    }

    @Test
    void updateAppliesTheSameValidationAsCreation() {
        givenExistingMonitor(monitor(MonitorStatus.UNKNOWN));
        givenManageableProject();

        assertThatThrownBy(() -> monitorService.updateMonitor(MONITOR_ID, new UpdateMonitorRequest(
                        "Renamed", null, "ftp://example.com/health",
                        MonitorHttpMethod.GET, 200, 60, 5, 3)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.MONITOR_VALIDATION_ERROR);
    }

    // ----------------------------------------------------------- pause/resume

    @Test
    void pauseTakesTheMonitorOutOfTheSchedule() {
        Monitor existing = monitor(MonitorStatus.UP);
        existing.setConsecutiveFailures(2);
        existing.setLastCheckedAt(Instant.parse("2026-08-09T09:00:00Z"));
        existing.setNextCheckAt(Instant.parse("2026-08-09T09:01:00Z"));

        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.pauseMonitor(MONITOR_ID);

        assertThat(existing.getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(existing.getNextCheckAt()).isNull();
        assertThat(existing.getConsecutiveFailures()).isZero();
        // Real history is never discarded.
        assertThat(existing.getLastCheckedAt()).isEqualTo(Instant.parse("2026-08-09T09:00:00Z"));
    }

    @Test
    void pausingAnAlreadyPausedMonitorIsSafe() {
        Monitor existing = monitor(MonitorStatus.PAUSED);
        existing.setNextCheckAt(null);
        givenExistingMonitor(existing);
        givenManageableProject();

        MonitorResponse response = monitorService.pauseMonitor(MONITOR_ID);

        assertThat(response.currentStatus()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(existing.getNextCheckAt()).isNull();
    }

    @Test
    void resumeSchedulesAPausedMonitorAsUnknown() {
        Monitor existing = monitor(MonitorStatus.PAUSED);
        existing.setConsecutiveFailures(4);
        existing.setNextCheckAt(null);
        existing.setLastCheckedAt(Instant.parse("2026-08-09T09:00:00Z"));

        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.resumeMonitor(MONITOR_ID);

        // UNKNOWN, not UP: no check has run, so health is genuinely unknown.
        assertThat(existing.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(existing.getNextCheckAt()).isEqualTo(NOW);
        assertThat(existing.getConsecutiveFailures()).isZero();
        assertThat(existing.getLastCheckedAt()).isEqualTo(Instant.parse("2026-08-09T09:00:00Z"));
    }

    /**
     * Resuming something that was never paused must not throw away an observed
     * state — an accidental call should be harmless, not destructive.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UP", "DOWN", "UNKNOWN"})
    void resumingANonPausedMonitorChangesNothing(String status) {
        MonitorStatus initial = MonitorStatus.valueOf(status);
        Monitor existing = monitor(initial);
        existing.setConsecutiveFailures(2);
        existing.setNextCheckAt(Instant.parse("2026-08-09T09:01:00Z"));

        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.resumeMonitor(MONITOR_ID);

        assertThat(existing.getCurrentStatus()).isEqualTo(initial);
        assertThat(existing.getConsecutiveFailures()).isEqualTo(2);
        assertThat(existing.getNextCheckAt()).isEqualTo(Instant.parse("2026-08-09T09:01:00Z"));
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteRemovesTheMonitor() {
        Monitor existing = monitor(MonitorStatus.UNKNOWN);
        givenExistingMonitor(existing);
        givenManageableProject();

        monitorService.deleteMonitor(MONITOR_ID);

        verify(monitorRepository).delete(existing);
    }

    @Test
    void deleteRequiresManagePermission() {
        givenExistingMonitor(monitor(MonitorStatus.UNKNOWN));
        when(projectAccessService.requireManageableProject(PROJECT_ID))
                .thenThrow(ApiException.accessDenied());

        assertThatThrownBy(() -> monitorService.deleteMonitor(MONITOR_ID))
                .isInstanceOf(ApiException.class);

        verify(monitorRepository, never()).delete(any());
    }

    // ----------------------------------------------------------------- setup

    private void givenManageableProject() {
        when(projectAccessService.requireManageableProject(PROJECT_ID)).thenReturn(project());
    }

    private void givenRepositoryEchoesSavedMonitor() {
        when(monitorRepository.save(any(Monitor.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void givenExistingMonitor(Monitor monitor) {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
    }

    private Monitor capturedMonitor() {
        ArgumentCaptor<Monitor> captor = ArgumentCaptor.forClass(Monitor.class);
        verify(monitorRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Project project() {
        Project project = new Project("Production APIs", new User("owner@example.com", "{bcrypt}h", "Owner"));
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private static Monitor monitor(MonitorStatus status) {
        Monitor monitor = new Monitor(
                project(), "Payment API", "https://api.example.com/health", 200, 60, 5, 3);
        monitor.setCurrentStatus(status);
        ReflectionTestUtils.setField(monitor, "id", MONITOR_ID);
        return monitor;
    }

    private static CreateMonitorRequest validCreateRequest() {
        return new CreateMonitorRequest(
                "Payment API", "Payment health endpoint", "https://api.example.com/health",
                MonitorHttpMethod.GET, 200, 60, 5, 3);
    }

    private static CreateMonitorRequest createRequestWithUrl(String url) {
        return new CreateMonitorRequest(
                "Payment API", null, url, MonitorHttpMethod.GET, 200, 60, 5, 3);
    }

    private static CreateMonitorRequest createRequestWithDescription(String description) {
        return new CreateMonitorRequest(
                "Payment API", description, "https://api.example.com/health",
                MonitorHttpMethod.GET, 200, 60, 5, 3);
    }

    private static CreateMonitorRequest createRequestWithTiming(int timeoutSeconds, int intervalSeconds) {
        return new CreateMonitorRequest(
                "Payment API", null, "https://api.example.com/health",
                MonitorHttpMethod.GET, 200, intervalSeconds, timeoutSeconds, 3);
    }
}
