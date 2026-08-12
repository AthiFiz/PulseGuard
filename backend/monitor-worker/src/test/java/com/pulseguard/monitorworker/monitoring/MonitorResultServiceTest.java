package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.repository.MonitorCheckRepository;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** State transitions and check persistence. Time is fixed; no database. */
@ExtendWith(MockitoExtension.class)
class MonitorResultServiceTest {

    private static final Long MONITOR_ID = 25L;
    private static final Instant CHECKED_AT = Instant.parse("2026-08-11T12:00:00Z");
    private static final int INTERVAL_SECONDS = 60;
    private static final int FAILURE_THRESHOLD = 3;

    @Mock
    private MonitorRepository monitorRepository;

    @Mock
    private MonitorCheckRepository monitorCheckRepository;

    @InjectMocks
    private MonitorResultService monitorResultService;

    // -------------------------------------------------------------- success

    @Test
    void aSuccessfulCheckBringsAnUnknownMonitorUp() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(monitor.getConsecutiveFailures()).isZero();
    }

    @Test
    void aSuccessfulCheckKeepsAnUpMonitorUp() {
        Monitor monitor = monitor(MonitorStatus.UP, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
    }

    /** Recovery. No incident or event is produced at this stage. */
    @Test
    void aSuccessfulCheckRecoversADownMonitor() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 7);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(monitor.getConsecutiveFailures()).isZero();
    }

    @Test
    void aSuccessUpdatesTheScheduleFromTheCheckStartTime() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getLastCheckedAt()).isEqualTo(CHECKED_AT);
        // Interval added to the start of the check, not to "now", so the
        // schedule does not drift by the duration of every request.
        assertThat(monitor.getNextCheckAt()).isEqualTo(CHECKED_AT.plusSeconds(INTERVAL_SECONDS));
    }

    // -------------------------------------------------------------- failure

    @Test
    void aFailureBelowTheThresholdPreservesTheHealthState() {
        Monitor monitor = monitor(MonitorStatus.UP, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());

        // One blip must not flip a healthy monitor.
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aFailureBelowTheThresholdLeavesAnUnknownMonitorUnknown() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void reachingTheThresholdTakesTheMonitorDown() {
        Monitor monitor = monitor(MonitorStatus.UP, FAILURE_THRESHOLD - 1);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getConsecutiveFailures()).isEqualTo(FAILURE_THRESHOLD);
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void aFurtherFailureKeepsTheMonitorDownAndKeepsCounting() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 5);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(6);
    }

    /** Walks a healthy monitor all the way down, one failure at a time. */
    @Test
    void theThresholdIsReachedOnlyAfterTheConfiguredNumberOfFailures() {
        Monitor monitor = monitor(MonitorStatus.UP, 0);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);

        monitorResultService.recordResult(MONITOR_ID, failure());
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);

        monitorResultService.recordResult(MONITOR_ID, failure());
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    // ----------------------------------------------------------------- races

    /**
     * The monitor was paused through the Control API while the request was in
     * flight. The pause is a deliberate instruction and must win.
     */
    @Test
    void aPausedMonitorIsNotRevivedByAnInFlightResult() {
        Monitor monitor = monitor(MonitorStatus.PAUSED, 0);
        monitor.setNextCheckAt(null);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(monitor.getNextCheckAt()).isNull();
        // The check itself still happened, so it is still recorded.
        verify(monitorCheckRepository).save(any(MonitorCheck.class));
    }

    @Test
    void aPausedMonitorDoesNotAccumulateFailures() {
        Monitor monitor = monitor(MonitorStatus.PAUSED, 0);
        monitor.setNextCheckAt(null);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getConsecutiveFailures()).isZero();
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
    }

    /** Deleted mid-request: the foreign key would reject the check row. */
    @Test
    void aResultForADeletedMonitorIsDiscarded() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        monitorResultService.recordResult(MONITOR_ID, success());

        verify(monitorCheckRepository, never()).save(any());
    }

    // ----------------------------------------------------------- persistence

    @Test
    void aSuccessfulCheckIsStoredWithNoErrorFields() {
        givenMonitorExists(monitor(MonitorStatus.UNKNOWN, 0));

        monitorResultService.recordResult(MONITOR_ID, success());

        MonitorCheck saved = capturedCheck();
        assertThat(saved.getMonitorId()).isEqualTo(MONITOR_ID);
        assertThat(saved.getCheckedAt()).isEqualTo(CHECKED_AT);
        assertThat(saved.getOutcome()).isEqualTo(MonitorCheckOutcome.SUCCESS);
        assertThat(saved.getHttpStatusCode()).isEqualTo(200);
        assertThat(saved.getResponseTimeMs()).isEqualTo(113);
        assertThat(saved.getErrorType()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void anUnexpectedStatusIsStoredWithTheStatusThatWasReceived() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, HealthCheckResult.failure(
                CHECKED_AT, 503, 205, MonitorCheckErrorType.UNEXPECTED_STATUS,
                "Expected HTTP 200 but received 503"));

        MonitorCheck saved = capturedCheck();
        assertThat(saved.getHttpStatusCode()).isEqualTo(503);
        assertThat(saved.getErrorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
    }

    /** No response arrived, so there is no status to record. */
    @ParameterizedTest
    @ValueSource(strings = {"DNS_ERROR", "CONNECTION_ERROR", "TIMEOUT", "BLOCKED_ADDRESS"})
    void aFailureWithoutAResponseStoresANullStatus(String errorType) {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, HealthCheckResult.failure(
                CHECKED_AT, null, 42, MonitorCheckErrorType.valueOf(errorType), "no response"));

        assertThat(capturedCheck().getHttpStatusCode()).isNull();
    }

    /** The column is VARCHAR(1000), so an over-long message must not blow up the insert. */
    @Test
    void anOverlongErrorMessageIsTruncated() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, HealthCheckResult.failure(
                CHECKED_AT, null, 1, MonitorCheckErrorType.UNKNOWN, "x".repeat(5000)));

        assertThat(capturedCheck().getErrorMessage()).hasSize(1000);
    }

    @Test
    void exactlyOneCheckIsStoredPerResult() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, success());

        verify(monitorCheckRepository).save(any(MonitorCheck.class));
    }

    // ----------------------------------------------------------------- setup

    private void givenMonitorExists(Monitor monitor) {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
    }

    private MonitorCheck capturedCheck() {
        ArgumentCaptor<MonitorCheck> captor = ArgumentCaptor.forClass(MonitorCheck.class);
        verify(monitorCheckRepository).save(captor.capture());
        return captor.getValue();
    }

    private static HealthCheckResult success() {
        return HealthCheckResult.success(CHECKED_AT, 200, 113);
    }

    private static HealthCheckResult failure() {
        return HealthCheckResult.failure(
                CHECKED_AT, 503, 205, MonitorCheckErrorType.UNEXPECTED_STATUS,
                "Expected HTTP 200 but received 503");
    }

    private static Monitor monitor(MonitorStatus status, int consecutiveFailures) {
        return MonitorTestFactory.monitor(
                MONITOR_ID,
                "https://api.example.com/health",
                status,
                consecutiveFailures,
                INTERVAL_SECONDS,
                5,
                FAILURE_THRESHOLD,
                CHECKED_AT);
    }
}
