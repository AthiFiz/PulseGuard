package com.pulseguard.monitorworker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.domain.OutboxEvent;
import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.enums.OutboxAggregateType;
import com.pulseguard.monitorworker.enums.OutboxEventType;
import com.pulseguard.monitorworker.repository.OutboxEventRepository;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What ends up in the outbox row, and what the published payload says.
 *
 * <p>The payload is parsed back rather than compared as a string: field order
 * is not part of the contract, and asserting on it would break the moment
 * Jackson changed its mind.
 */
@ExtendWith(MockitoExtension.class)
class IncidentEventRecorderTest {

    private static final Long MONITOR_ID = 25L;
    private static final Long PROJECT_ID = 10L;
    private static final Long INCIDENT_ID = 41L;
    private static final Instant OPENED_AT = Instant.parse("2026-08-13T06:30:00Z");
    private static final Instant RESOLVED_AT = Instant.parse("2026-08-13T06:42:00Z");
    private static final Instant NOW = Instant.parse("2026-08-13T06:42:05Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private IncidentEventRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new IncidentEventRecorder(
                outboxEventRepository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------- the row

    @Test
    void anOpenedEventIsStoredAsAPendingOutboxRow() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        OutboxEvent row = captured();
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.INCIDENT_OPENED);
        assertThat(row.getAggregateType()).isEqualTo(OutboxAggregateType.INCIDENT);
        assertThat(row.getAggregateId()).isEqualTo(INCIDENT_ID);
        assertThat(row.isPublished()).isFalse();
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    /** The Kafka key. One monitor's events must stay on one partition. */
    @Test
    void theMonitorIdBecomesThePartitionKey() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        assertThat(captured().getPartitionKey()).isEqualTo("25");
    }

    /**
     * The business timestamp, not the publishing one. An event that sat in the
     * outbox for an hour still describes an outage that began when it began.
     */
    @Test
    void occurredAtIsTheLifecycleTimestampRatherThanNow() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());
        assertThat(captured().getOccurredAt()).isEqualTo(OPENED_AT).isNotEqualTo(NOW);
    }

    @Test
    void aResolvedEventUsesTheResolutionTimestamp() {
        recorder.recordIncidentResolved(monitor(MonitorStatus.UP), resolvedIncident(), successfulCheck());

        OutboxEvent row = captured();
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.INCIDENT_RESOLVED);
        assertThat(row.getOccurredAt()).isEqualTo(RESOLVED_AT);
    }

    // --------------------------------------------------------- the payload

    @Test
    void theOpenedPayloadCarriesEverythingAConsumerNeeds() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        JsonNode payload = payload();
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("eventType").asString()).isEqualTo("INCIDENT_OPENED");
        assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-08-13T06:30:00Z");
        assertThat(payload.get("incidentId").asLong()).isEqualTo(INCIDENT_ID);
        assertThat(payload.get("projectId").asLong()).isEqualTo(PROJECT_ID);
        assertThat(payload.get("monitorId").asLong()).isEqualTo(MONITOR_ID);
        assertThat(payload.get("monitorName").asString()).isEqualTo("Payment API");
        assertThat(payload.get("incidentOpenedAt").asString()).isEqualTo("2026-08-13T06:30:00Z");
        assertThat(payload.get("incidentResolvedAt").isNull()).isTrue();
        assertThat(payload.get("triggeringCheckId").asLong()).isEqualTo(1501L);
        assertThat(payload.get("monitorStatus").asString()).isEqualTo("DOWN");
    }

    /** The failure details are the reason anyone would read the event. */
    @Test
    void theOpenedPayloadCarriesTheFailureThatCausedTheOutage() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        JsonNode payload = payload();
        assertThat(payload.get("httpStatusCode").asInt()).isEqualTo(503);
        assertThat(payload.get("responseTimeMs").asInt()).isEqualTo(210);
        assertThat(payload.get("errorType").asString()).isEqualTo("UNEXPECTED_STATUS");
        assertThat(payload.get("errorMessage").asString())
                .isEqualTo("Expected HTTP 200 but received 503");
    }

    @Test
    void theResolvedPayloadCarriesBothTimestampsAndTheRecoveringCheck() {
        recorder.recordIncidentResolved(monitor(MonitorStatus.UP), resolvedIncident(), successfulCheck());

        JsonNode payload = payload();
        assertThat(payload.get("eventType").asString()).isEqualTo("INCIDENT_RESOLVED");
        assertThat(payload.get("incidentOpenedAt").asString()).isEqualTo("2026-08-13T06:30:00Z");
        assertThat(payload.get("incidentResolvedAt").asString()).isEqualTo("2026-08-13T06:42:00Z");
        assertThat(payload.get("triggeringCheckId").asLong()).isEqualTo(1525L);
        assertThat(payload.get("monitorStatus").asString()).isEqualTo("UP");
        assertThat(payload.get("httpStatusCode").asInt()).isEqualTo(200);
        // A success has nothing to explain.
        assertThat(payload.get("errorType").isNull()).isTrue();
        assertThat(payload.get("errorMessage").isNull()).isTrue();
    }

    /**
     * URLs can carry query parameters, tokens and internal hostnames, and an
     * event bus is where data spreads. The ids identify the monitor perfectly
     * well for anyone entitled to look it up.
     */
    @Test
    void thePayloadNeverCarriesTheMonitorUrl() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        OutboxEvent row = captured();
        assertThat(row.getPayload()).doesNotContain("https://");
        assertThat(row.getPayload()).doesNotContain("api.example.com");
        assertThat(payload().has("url")).isFalse();
        assertThat(payload().has("monitorUrl")).isFalse();
    }

    // --------------------------------------------------------- the event id

    @Test
    void theEventIdIsAValidUuidAndMatchesTheStoredRow() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());

        OutboxEvent row = captured();
        // Parsing is the assertion: an invalid UUID throws.
        UUID.fromString(row.getEventId());
        // The row and the payload must agree, or a consumer deduplicating on
        // the payload's id would not match what the outbox thinks it sent.
        assertThat(payload().get("eventId").asString()).isEqualTo(row.getEventId());
    }

    @Test
    void separateTransitionsGetSeparateEventIds() {
        recorder.recordIncidentOpened(monitor(MonitorStatus.DOWN), openIncident(), failedCheck());
        recorder.recordIncidentResolved(monitor(MonitorStatus.UP), resolvedIncident(), successfulCheck());

        ArgumentCaptor<OutboxEvent> rows = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(rows.capture());

        String first = rows.getAllValues().get(0).getEventId();
        String second = rows.getAllValues().get(1).getEventId();
        assertThat(first).isNotEqualTo(second);
    }

    // ---------------------------------------------------------------- setup

    private OutboxEvent captured() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private JsonNode payload() {
        return objectMapper.readTree(captured().getPayload());
    }

    private static Monitor monitor(MonitorStatus status) {
        Monitor monitor = newInstance(Monitor.class);
        ReflectionTestUtils.setField(monitor, "id", MONITOR_ID);
        ReflectionTestUtils.setField(monitor, "projectId", PROJECT_ID);
        ReflectionTestUtils.setField(monitor, "name", "Payment API");
        ReflectionTestUtils.setField(monitor, "url", "https://api.example.com/health?token=secret");
        monitor.setCurrentStatus(status);
        return monitor;
    }

    private static Incident openIncident() {
        Incident incident = new Incident(MONITOR_ID, OPENED_AT, 1501L);
        ReflectionTestUtils.setField(incident, "id", INCIDENT_ID);
        return incident;
    }

    private static Incident resolvedIncident() {
        Incident incident = openIncident();
        incident.resolve(RESOLVED_AT, 1525L);
        return incident;
    }

    private static MonitorCheck failedCheck() {
        MonitorCheck check = new MonitorCheck(
                MONITOR_ID, OPENED_AT, MonitorCheckOutcome.FAILURE, 503, 210,
                MonitorCheckErrorType.UNEXPECTED_STATUS, "Expected HTTP 200 but received 503");
        ReflectionTestUtils.setField(check, "id", 1501L);
        return check;
    }

    private static MonitorCheck successfulCheck() {
        MonitorCheck check = new MonitorCheck(
                MONITOR_ID, RESOLVED_AT, MonitorCheckOutcome.SUCCESS, 200, 93, null, null);
        ReflectionTestUtils.setField(check, "id", 1525L);
        return check;
    }

    /** The entities have protected constructors; the worker never builds them by hand. */
    private static <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not instantiate " + type.getSimpleName(), ex);
        }
    }
}
