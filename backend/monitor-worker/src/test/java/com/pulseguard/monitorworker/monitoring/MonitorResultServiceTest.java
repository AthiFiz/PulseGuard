package com.pulseguard.monitorworker.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.enums.IncidentStatus;
import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.outbox.IncidentEventRecorder;
import com.pulseguard.monitorworker.repository.IncidentRepository;
import com.pulseguard.monitorworker.repository.MonitorCheckRepository;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * State transitions, check persistence and the incident lifecycle. Time is
 * fixed; no database.
 *
 * <p>These are interaction tests. They prove the service asks the repositories
 * for the right things in the right order — not that the enclosing transaction
 * commits atomically, which only a real database could show.
 *
 * <p>The event recorder is mocked here: this file is about when an event is
 * recorded, while {@code IncidentEventRecorderTest} covers what the event
 * actually contains.
 */
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

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentEventRecorder incidentEventRecorder;

    @InjectMocks
    private MonitorResultService monitorResultService;

    /** Ids the database would assign, so the service can reference the rows. */
    private final AtomicLong nextCheckId = new AtomicLong(1000L);

    @BeforeEach
    void assignIdsOnSave() {
        // The real repositories return the persisted row with its generated id,
        // and the incident lifecycle depends on that id. Lenient because the
        // deleted-monitor test never reaches a save.
        lenient().when(monitorCheckRepository.save(any(MonitorCheck.class))).thenAnswer(call -> {
            MonitorCheck check = call.getArgument(0);
            ReflectionTestUtils.setField(check, "id", nextCheckId.getAndIncrement());
            return check;
        });
        lenient().when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> {
            Incident incident = call.getArgument(0);
            if (incident.getId() == null) {
                ReflectionTestUtils.setField(incident, "id", 500L);
            }
            return incident;
        });
    }

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

    // ------------------------------------------------------ incident opening

    /** An incident marks an outage, and one failure is not an outage. */
    @Test
    void aFailureBelowTheThresholdOpensNoIncident() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, failure());

        verify(incidentRepository, never()).save(any());
    }

    @Test
    void reachingTheThresholdOpensExactlyOneIncident() {
        Monitor monitor = monitor(MonitorStatus.UP, FAILURE_THRESHOLD - 1);
        givenMonitorExists(monitor);
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        Incident opened = capturedIncident();
        assertThat(opened.getMonitorId()).isEqualTo(MONITOR_ID);
        assertThat(opened.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(opened.getResolvedAt()).isNull();
        assertThat(opened.getResolutionCheckId()).isNull();
    }

    /** A monitor that has never succeeded can still go down. */
    @Test
    void anUnknownMonitorReachingTheThresholdOpensAnIncident() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        ReflectionTestUtils.setField(monitor, "failureThreshold", 1);
        givenMonitorExists(monitor);
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(capturedIncident().getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    /** The outage began when the service failed, not when the row was written. */
    @Test
    void theIncidentIsOpenedFromTheCheckThatCausedTheOutage() {
        givenMonitorExists(monitor(MonitorStatus.UP, FAILURE_THRESHOLD - 1));
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        Incident opened = capturedIncident();
        assertThat(opened.getOpenedAt()).isEqualTo(CHECKED_AT);
        assertThat(opened.getOpeningCheckId()).isEqualTo(capturedCheck().getId());
    }

    /**
     * A blocked destination is an ordinary failure. It counts toward the
     * threshold like any other and opens nothing on its own.
     */
    @Test
    void aBlockedAddressBelowTheThresholdOpensNoIncident() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, HealthCheckResult.failure(
                CHECKED_AT, null, 1, MonitorCheckErrorType.BLOCKED_ADDRESS, "blocked"));

        verify(incidentRepository, never()).save(any());
    }

    // -------------------------------------------- one incident per outage

    /**
     * The rule the whole design rests on: an incident spans an outage, not a
     * check. Ten failures in a row are one incident, not ten.
     */
    @Test
    void aFurtherFailureLeavesTheExistingIncidentAloneAndOpensNoSecond() {
        givenMonitorExists(monitor(MonitorStatus.DOWN, 5));
        Incident existing = openIncident();
        givenOpenIncident(existing);

        monitorResultService.recordResult(MONITOR_ID, failure());

        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(existing.getResolvedAt()).isNull();
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void repeatedFailuresThroughOneOutageOpenOnlyOneIncident() {
        Monitor monitor = monitor(MonitorStatus.UP, 0);
        givenMonitorExists(monitor);
        Incident[] opened = new Incident[1];
        // Once opened, the repository would return it on every later lookup.
        when(incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(MONITOR_ID, IncidentStatus.OPEN))
                .thenAnswer(call -> Optional.ofNullable(opened[0]));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> {
            opened[0] = call.getArgument(0);
            ReflectionTestUtils.setField(opened[0], "id", 500L);
            return opened[0];
        });

        for (int i = 0; i < 6; i++) {
            monitorResultService.recordResult(MONITOR_ID, failure());
        }

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(monitor.getConsecutiveFailures()).isEqualTo(6);
        verify(incidentRepository).save(any(Incident.class));
    }

    /**
     * Data from an interrupted run: the monitor says DOWN but nothing recorded
     * the outage. Opening one now is better than leaving it unrecorded.
     */
    @Test
    void aDownMonitorWithNoOpenIncidentGetsOneDefensively() {
        givenMonitorExists(monitor(MonitorStatus.DOWN, 9));
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        Incident opened = capturedIncident();
        assertThat(opened.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(opened.getOpenedAt()).isEqualTo(CHECKED_AT);
    }

    // --------------------------------------------------- incident resolution

    @Test
    void aSuccessfulCheckResolvesTheOpenIncident() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 4);
        givenMonitorExists(monitor);
        Incident existing = openIncident();
        givenOpenIncident(existing);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        // The recovery time is the check's, so the duration matches the
        // observations either side of it.
        assertThat(existing.getResolvedAt()).isEqualTo(CHECKED_AT);
        assertThat(existing.getResolutionCheckId()).isEqualTo(capturedCheck().getId());
        verify(incidentRepository).save(existing);
    }

    @Test
    void aSuccessfulCheckWithNoOpenIncidentWritesNothing() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, success());

        verify(incidentRepository, never()).save(any());
    }

    /**
     * The second outage is a new incident. The first one stays resolved — a
     * historical record must not be rewritten by a later failure.
     */
    @Test
    void aSecondOutageOpensASecondIncident() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 3);
        givenMonitorExists(monitor);
        Incident first = openIncident();
        // Open before the recovery, then nothing open until the next outage.
        when(incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.of(first), Optional.empty());

        monitorResultService.recordResult(MONITOR_ID, success());
        assertThat(first.getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        ReflectionTestUtils.setField(monitor, "failureThreshold", 1);
        monitorResultService.recordResult(MONITOR_ID, failure());

        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository, times(2)).save(saved.capture());
        Incident second = saved.getAllValues().get(1);
        assertThat(second).isNotSameAs(first);
        assertThat(second.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(first.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    }

    // ------------------------------------------------ pause, resume and races

    /**
     * Pausing monitoring is not evidence that the service recovered, so an
     * in-flight success arriving after the pause must not close the incident.
     */
    @Test
    void aSuccessArrivingAfterAPauseLeavesTheIncidentOpen() {
        Monitor monitor = monitor(MonitorStatus.PAUSED, 0);
        monitor.setNextCheckAt(null);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.PAUSED);
        verify(incidentRepository, never()).save(any());
        verify(incidentRepository, never())
                .findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(any(), any());
    }

    /**
     * The case that proves resolution follows recovery rather than the previous
     * status. Pause and resume leave the monitor UNKNOWN, not DOWN, yet the
     * incident opened by the original outage is still the one to close.
     */
    @Test
    void theFirstSuccessAfterAResumeResolvesTheIncidentOpenedBeforeThePause() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        givenMonitorExists(monitor);
        Incident fromBeforeThePause = openIncident();
        givenOpenIncident(fromBeforeThePause);

        monitorResultService.recordResult(MONITOR_ID, success());

        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(fromBeforeThePause.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(fromBeforeThePause.getResolvedAt()).isEqualTo(CHECKED_AT);
    }

    /** Deleted mid-request: its incidents went with it via the cascade. */
    @Test
    void aResultForADeletedMonitorTouchesNoIncident() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        monitorResultService.recordResult(MONITOR_ID, success());

        verify(incidentRepository, never()).save(any());
        verify(incidentRepository, never())
                .findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(any(), any());
    }

    // ------------------------------------------------------- outbox events

    /** No outage was declared, so there is nothing to announce. */
    @Test
    void aFailureBelowTheThresholdRecordsNoEvent() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));

        monitorResultService.recordResult(MONITOR_ID, failure());

        verifyNoInteractions(incidentEventRecorder);
    }

    @Test
    void openingAnIncidentRecordsExactlyOneOpenedEvent() {
        Monitor monitor = monitor(MonitorStatus.UP, FAILURE_THRESHOLD - 1);
        givenMonitorExists(monitor);
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        ArgumentCaptor<Incident> incident = ArgumentCaptor.forClass(Incident.class);
        ArgumentCaptor<MonitorCheck> check = ArgumentCaptor.forClass(MonitorCheck.class);
        verify(incidentEventRecorder).recordIncidentOpened(eq(monitor), incident.capture(), check.capture());
        verify(incidentEventRecorder, never()).recordIncidentResolved(any(), any(), any());

        // The event describes the incident that was just opened, and the check
        // that caused it — the same one written to monitor_checks.
        assertThat(incident.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(check.getValue().getId()).isEqualTo(capturedCheck().getId());
    }

    @Test
    void anUnknownMonitorGoingDownRecordsAnOpenedEvent() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        ReflectionTestUtils.setField(monitor, "failureThreshold", 1);
        givenMonitorExists(monitor);
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        verify(incidentEventRecorder).recordIncidentOpened(eq(monitor), any(), any());
    }

    /** The outage has already been announced. Saying it again would be noise. */
    @Test
    void aFurtherFailureDuringOneOutageRecordsNoSecondEvent() {
        givenMonitorExists(monitor(MonitorStatus.DOWN, 5));
        givenOpenIncident(openIncident());

        monitorResultService.recordResult(MONITOR_ID, failure());

        verifyNoInteractions(incidentEventRecorder);
    }

    /** A defensively opened incident is still a real one, and is announced. */
    @Test
    void aDefensivelyOpenedIncidentRecordsAnOpenedEvent() {
        givenMonitorExists(monitor(MonitorStatus.DOWN, 9));
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, failure());

        verify(incidentEventRecorder).recordIncidentOpened(any(), any(), any());
    }

    @Test
    void resolvingAnIncidentRecordsExactlyOneResolvedEvent() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 4);
        givenMonitorExists(monitor);
        Incident existing = openIncident();
        givenOpenIncident(existing);

        monitorResultService.recordResult(MONITOR_ID, success());

        ArgumentCaptor<MonitorCheck> check = ArgumentCaptor.forClass(MonitorCheck.class);
        verify(incidentEventRecorder).recordIncidentResolved(eq(monitor), eq(existing), check.capture());
        verify(incidentEventRecorder, never()).recordIncidentOpened(any(), any(), any());

        // Recorded after resolve(), so the event sees the resolution.
        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(check.getValue().getOutcome()).isEqualTo(MonitorCheckOutcome.SUCCESS);
    }

    @Test
    void aSuccessWithNothingOpenRecordsNoEvent() {
        givenMonitorExists(monitor(MonitorStatus.UP, 0));
        givenNoOpenIncident();

        monitorResultService.recordResult(MONITOR_ID, success());

        verifyNoInteractions(incidentEventRecorder);
    }

    /** Pausing is not recovery, so it announces nothing. */
    @Test
    void aSuccessArrivingAfterAPauseRecordsNoEvent() {
        Monitor monitor = monitor(MonitorStatus.PAUSED, 0);
        monitor.setNextCheckAt(null);
        givenMonitorExists(monitor);

        monitorResultService.recordResult(MONITOR_ID, success());

        verifyNoInteractions(incidentEventRecorder);
    }

    /** Resume leaves the monitor UNKNOWN; the first real success still resolves. */
    @Test
    void theFirstSuccessAfterAResumeRecordsTheResolvedEvent() {
        Monitor monitor = monitor(MonitorStatus.UNKNOWN, 0);
        givenMonitorExists(monitor);
        Incident fromBeforeThePause = openIncident();
        givenOpenIncident(fromBeforeThePause);

        monitorResultService.recordResult(MONITOR_ID, success());

        verify(incidentEventRecorder).recordIncidentResolved(eq(monitor), eq(fromBeforeThePause), any());
    }

    @Test
    void aResultForADeletedMonitorRecordsNoEvent() {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.empty());

        monitorResultService.recordResult(MONITOR_ID, success());

        verifyNoInteractions(incidentEventRecorder);
    }

    /** Each transition is announced once: two outages produce three events. */
    @Test
    void eachLifecycleTransitionIsAnnouncedExactlyOnce() {
        Monitor monitor = monitor(MonitorStatus.DOWN, 3);
        givenMonitorExists(monitor);
        Incident first = openIncident();
        when(incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.of(first), Optional.empty());

        monitorResultService.recordResult(MONITOR_ID, success());
        ReflectionTestUtils.setField(monitor, "failureThreshold", 1);
        monitorResultService.recordResult(MONITOR_ID, failure());

        verify(incidentEventRecorder).recordIncidentResolved(any(), any(), any());
        verify(incidentEventRecorder).recordIncidentOpened(any(), any(), any());
    }

    // ----------------------------------------------------------------- setup

    private void givenMonitorExists(Monitor monitor) {
        when(monitorRepository.findById(MONITOR_ID)).thenReturn(Optional.of(monitor));
    }

    private void givenOpenIncident(Incident incident) {
        when(incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(
                        MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.of(incident));
    }

    private void givenNoOpenIncident() {
        when(incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(
                        MONITOR_ID, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());
    }

    private static Incident openIncident() {
        Incident incident = new Incident(MONITOR_ID, CHECKED_AT.minusSeconds(600), 900L);
        ReflectionTestUtils.setField(incident, "id", 41L);
        return incident;
    }

    private Incident capturedIncident() {
        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        return captor.getValue();
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
