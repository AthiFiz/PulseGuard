package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/** One polling cycle: what it queries, what it processes, and how it isolates failures. */
@ExtendWith(MockitoExtension.class)
class MonitorPollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final int BATCH_SIZE = 50;

    @Mock
    private MonitorRepository monitorRepository;

    @Mock
    private HttpHealthChecker httpHealthChecker;

    @Mock
    private MonitorResultService monitorResultService;

    private MonitorPollingService pollingService;

    @BeforeEach
    void setUp() {
        pollingService = new MonitorPollingService(
                monitorRepository,
                httpHealthChecker,
                monitorResultService,
                new MonitoringProperties(Duration.ofSeconds(5), BATCH_SIZE, false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void itAsksForMonitorsDueNowExcludingPausedOnesAndLimitedToTheBatchSize() {
        when(monitorRepository.findDueMonitors(any(), any(), any())).thenReturn(List.of());

        pollingService.pollOnce();

        ArgumentCaptor<MonitorStatus> status = ArgumentCaptor.forClass(MonitorStatus.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(monitorRepository).findDueMonitors(status.capture(), now.capture(), limit.capture());

        assertThat(status.getValue()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(now.getValue()).isEqualTo(NOW);
        assertThat(limit.getValue().max()).isEqualTo(BATCH_SIZE);
    }

    @Test
    void nothingHappensWhenNoMonitorsAreDue() {
        when(monitorRepository.findDueMonitors(any(), any(), any())).thenReturn(List.of());

        pollingService.pollOnce();

        verify(httpHealthChecker, never()).check(any());
        verify(monitorResultService, never()).recordResult(any(), any());
    }

    @Test
    void everyDueMonitorIsCheckedAndItsResultRecorded() {
        when(monitorRepository.findDueMonitors(any(), any(), any()))
                .thenReturn(List.of(monitor(1L), monitor(2L), monitor(3L)));
        when(httpHealthChecker.check(any())).thenReturn(HealthCheckResult.success(NOW, 200, 100));

        pollingService.pollOnce();

        verify(httpHealthChecker, times(3)).check(any());
        verify(monitorResultService).recordResult(eq(1L), any());
        verify(monitorResultService).recordResult(eq(2L), any());
        verify(monitorResultService).recordResult(eq(3L), any());
    }

    /** A single broken monitor must not take the whole cycle down with it. */
    @Test
    void oneMonitorBlowingUpDoesNotStopTheRest() {
        when(monitorRepository.findDueMonitors(any(), any(), any()))
                .thenReturn(List.of(monitor(1L), monitor(2L), monitor(3L)));
        when(httpHealthChecker.check(any()))
                .thenReturn(HealthCheckResult.success(NOW, 200, 100))
                .thenThrow(new IllegalStateException("something went badly wrong"))
                .thenReturn(HealthCheckResult.success(NOW, 200, 100));

        pollingService.pollOnce();

        verify(httpHealthChecker, times(3)).check(any());
        verify(monitorResultService).recordResult(eq(1L), any());
        verify(monitorResultService, never()).recordResult(eq(2L), any());
        verify(monitorResultService).recordResult(eq(3L), any());
    }

    @Test
    void aFailureWhileStoringOneResultDoesNotStopTheRest() {
        when(monitorRepository.findDueMonitors(any(), any(), any()))
                .thenReturn(List.of(monitor(1L), monitor(2L)));
        when(httpHealthChecker.check(any())).thenReturn(HealthCheckResult.success(NOW, 200, 100));
        org.mockito.Mockito.doThrow(new IllegalStateException("database blew up"))
                .when(monitorResultService)
                .recordResult(eq(1L), any());

        pollingService.pollOnce();

        verify(monitorResultService).recordResult(eq(2L), any());
    }

    /** The HTTP call must receive a detached snapshot, not a managed entity. */
    @Test
    void theCheckerIsGivenTheMonitorsConfiguration() {
        when(monitorRepository.findDueMonitors(any(), any(), any())).thenReturn(List.of(monitor(7L)));
        when(httpHealthChecker.check(any())).thenReturn(HealthCheckResult.success(NOW, 200, 100));

        pollingService.pollOnce();

        ArgumentCaptor<MonitorSnapshot> snapshot = ArgumentCaptor.forClass(MonitorSnapshot.class);
        verify(httpHealthChecker).check(snapshot.capture());

        assertThat(snapshot.getValue().id()).isEqualTo(7L);
        assertThat(snapshot.getValue().url()).isEqualTo("https://api.example.com/health");
        assertThat(snapshot.getValue().expectedStatusCode()).isEqualTo(200);
        assertThat(snapshot.getValue().timeoutSeconds()).isEqualTo(5);
    }

    private static Monitor monitor(Long id) {
        return MonitorTestFactory.monitor(
                id, "https://api.example.com/health", MonitorStatus.UP, 0, 60, 5, 3, NOW);
    }
}
