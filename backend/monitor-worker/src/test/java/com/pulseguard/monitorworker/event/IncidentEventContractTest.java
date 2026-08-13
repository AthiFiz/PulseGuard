package com.pulseguard.monitorworker.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.domain.OutboxEvent;
import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.outbox.IncidentEventRecorder;
import com.pulseguard.monitorworker.repository.OutboxEventRepository;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * The producer half of the event contract.
 *
 * <p>Checks that what this application serialises still matches the shared
 * fixtures in {@code docs/contracts}, which the Notification Service tests
 * against independently. The two applications share no code, so this is what
 * stops one of them drifting silently.
 *
 * <p>Fields are compared after parsing. JSON property order is not part of the
 * contract, and asserting on it would break the moment Jackson changed its mind.
 */
@ExtendWith(MockitoExtension.class)
class IncidentEventContractTest {

    private static final Path CONTRACTS = Path.of("..", "..", "docs", "contracts");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    // ------------------------------------------------------------- opened

    @Test
    void theOpenedEventMatchesTheV1Contract() throws IOException {
        JsonNode expected = fixture("incident-opened-v1.json");

        JsonNode actual = serialiseOpenedEvent();

        assertSameShape(expected, actual);
    }

    @Test
    void theOpenedEventCarriesTheSameValuesAsTheContract() throws IOException {
        JsonNode expected = fixture("incident-opened-v1.json");

        JsonNode actual = serialiseOpenedEvent();

        assertThat(actual.get("schemaVersion").asInt()).isEqualTo(expected.get("schemaVersion").asInt());
        assertThat(actual.get("eventType").asString()).isEqualTo("INCIDENT_OPENED");
        assertThat(actual.get("occurredAt").asString()).isEqualTo(expected.get("occurredAt").asString());
        assertThat(actual.get("incidentId").asLong()).isEqualTo(expected.get("incidentId").asLong());
        assertThat(actual.get("projectId").asLong()).isEqualTo(expected.get("projectId").asLong());
        assertThat(actual.get("monitorId").asLong()).isEqualTo(expected.get("monitorId").asLong());
        assertThat(actual.get("monitorName").asString()).isEqualTo(expected.get("monitorName").asString());
        assertThat(actual.get("monitorStatus").asString()).isEqualTo("DOWN");
        assertThat(actual.get("triggeringCheckId").asLong())
                .isEqualTo(expected.get("triggeringCheckId").asLong());
        assertThat(actual.get("httpStatusCode").asInt()).isEqualTo(expected.get("httpStatusCode").asInt());
        assertThat(actual.get("errorType").asString()).isEqualTo(expected.get("errorType").asString());
        // An open incident has no resolution, and the contract says so with a
        // present null rather than an absent field.
        assertThat(actual.get("incidentResolvedAt").isNull()).isTrue();
    }

    // ----------------------------------------------------------- resolved

    @Test
    void theResolvedEventMatchesTheV1Contract() throws IOException {
        JsonNode expected = fixture("incident-resolved-v1.json");

        JsonNode actual = serialiseResolvedEvent();

        assertSameShape(expected, actual);
    }

    @Test
    void theResolvedEventCarriesBothTimestampsAndNoError() throws IOException {
        JsonNode expected = fixture("incident-resolved-v1.json");

        JsonNode actual = serialiseResolvedEvent();

        assertThat(actual.get("eventType").asString()).isEqualTo("INCIDENT_RESOLVED");
        assertThat(actual.get("monitorStatus").asString()).isEqualTo("UP");
        assertThat(actual.get("incidentOpenedAt").asString())
                .isEqualTo(expected.get("incidentOpenedAt").asString());
        assertThat(actual.get("incidentResolvedAt").asString())
                .isEqualTo(expected.get("incidentResolvedAt").asString());
        assertThat(actual.get("errorType").isNull()).isTrue();
        assertThat(actual.get("errorMessage").isNull()).isTrue();
    }

    // ------------------------------------------------------------ safety

    /**
     * The contract deliberately excludes anything that could leak. A property
     * name check rather than a substring search, so an ordinary error message
     * mentioning "password expired" cannot fail the test spuriously.
     */
    @Test
    void noEventCarriesSensitivePropertyNames() throws IOException {
        for (JsonNode event : new JsonNode[] {serialiseOpenedEvent(), serialiseResolvedEvent()}) {
            for (String forbidden : new String[] {
                    "url", "monitorUrl", "uri", "endpoint",
                    "authorization", "headers", "responseBody", "body",
                    "password", "secret", "token", "credentials"}) {
                assertThat(event.has(forbidden))
                        .as("event must not carry a '%s' property", forbidden)
                        .isFalse();
            }
        }
    }

    /** The fixtures themselves must obey the same rule. */
    @Test
    void theFixturesCarryNoSensitivePropertyNames() throws IOException {
        for (String name : new String[] {"incident-opened-v1.json", "incident-resolved-v1.json"}) {
            assertThat(fixture(name).has("url")).isFalse();
            assertThat(fixture(name).has("monitorUrl")).isFalse();
        }
    }

    // ---------------------------------------------------------------- setup

    /** Same property names, and the same JSON types for each. */
    private static void assertSameShape(JsonNode expected, JsonNode actual) {
        assertThat(actual.propertyNames())
                .as("the published event must carry exactly the contract's fields")
                .containsExactlyInAnyOrderElementsOf(expected.propertyNames());

        expected.propertyNames().forEach(field ->
                assertThat(actual.get(field).getNodeType())
                        .as("field '%s' must keep its JSON type", field)
                        .isEqualTo(expected.get(field).getNodeType()));
    }

    private JsonNode fixture(String name) throws IOException {
        return objectMapper.readTree(Files.readString(CONTRACTS.resolve(name)));
    }

    private JsonNode serialiseOpenedEvent() {
        Instant openedAt = Instant.parse("2026-08-13T06:30:00Z");
        recorder(openedAt).recordIncidentOpened(
                monitor(MonitorStatus.DOWN),
                openIncident(openedAt),
                failedCheck(openedAt));
        return objectMapper.readTree(captured().getPayload());
    }

    private JsonNode serialiseResolvedEvent() {
        Instant openedAt = Instant.parse("2026-08-13T06:30:00Z");
        Instant resolvedAt = Instant.parse("2026-08-13T06:42:00Z");

        Incident incident = openIncident(openedAt);
        incident.resolve(resolvedAt, 1525L);

        recorder(resolvedAt).recordIncidentResolved(
                monitor(MonitorStatus.UP), incident, successfulCheck(resolvedAt));
        return objectMapper.readTree(captured().getPayload());
    }

    private IncidentEventRecorder recorder(Instant now) {
        return new IncidentEventRecorder(
                outboxEventRepository, objectMapper, Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * The most recently saved event. Uses {@code atLeastOnce} because one test
     * serialises both event types in a single method.
     */
    private OutboxEvent captured() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        org.mockito.Mockito.verify(outboxEventRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        return captor.getValue();
    }

    private static Monitor monitor(MonitorStatus status) {
        Monitor monitor = newInstance(Monitor.class);
        ReflectionTestUtils.setField(monitor, "id", 25L);
        ReflectionTestUtils.setField(monitor, "projectId", 10L);
        ReflectionTestUtils.setField(monitor, "name", "Payment API");
        ReflectionTestUtils.setField(monitor, "url", "https://api.example.com/health?token=secret");
        monitor.setCurrentStatus(status);
        return monitor;
    }

    private static Incident openIncident(Instant openedAt) {
        Incident incident = new Incident(25L, openedAt, 1501L);
        ReflectionTestUtils.setField(incident, "id", 41L);
        return incident;
    }

    private static MonitorCheck failedCheck(Instant at) {
        MonitorCheck check = new MonitorCheck(
                25L, at, MonitorCheckOutcome.FAILURE, 503, 210,
                MonitorCheckErrorType.UNEXPECTED_STATUS, "Expected HTTP 200 but received 503");
        ReflectionTestUtils.setField(check, "id", 1501L);
        return check;
    }

    private static MonitorCheck successfulCheck(Instant at) {
        MonitorCheck check = new MonitorCheck(25L, at, MonitorCheckOutcome.SUCCESS, 200, 93, null, null);
        ReflectionTestUtils.setField(check, "id", 1525L);
        return check;
    }

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
