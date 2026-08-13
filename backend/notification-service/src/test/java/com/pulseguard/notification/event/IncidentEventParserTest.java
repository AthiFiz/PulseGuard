package com.pulseguard.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.enums.MonitorCheckErrorType;
import com.pulseguard.notification.enums.MonitorStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the contract, and refusing to guess at anything else.
 *
 * <p>Nothing here needs Kafka: the parser's input is a string.
 */
class IncidentEventParserTest {

    private static final String EVENT_ID = "20e04086-2497-4e77-a380-8437e2277bbd";

    private final IncidentEventParser parser = new IncidentEventParser(JsonMapper.builder().build());

    // ------------------------------------------------------------ accepted

    @Test
    void aWellFormedOpenedEventIsRead() {
        IncidentLifecycleEvent event = parser.parse(openedPayload());

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo(IncidentEventType.INCIDENT_OPENED);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-13T06:30:00Z"));
        assertThat(event.incidentId()).isEqualTo(41L);
        assertThat(event.projectId()).isEqualTo(10L);
        assertThat(event.monitorId()).isEqualTo(25L);
        assertThat(event.monitorName()).isEqualTo("Payment API");
        assertThat(event.triggeringCheckId()).isEqualTo(1501L);
        assertThat(event.monitorStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(event.httpStatusCode()).isEqualTo(503);
        assertThat(event.responseTimeMs()).isEqualTo(210);
        assertThat(event.errorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
        assertThat(event.errorMessage()).isEqualTo("Expected HTTP 200 but received 503");
    }

    /** An open incident has no resolution, and that must survive parsing. */
    @Test
    void absentFieldsAreReadAsNullRatherThanRejected() {
        IncidentLifecycleEvent event = parser.parse(openedPayload());

        assertThat(event.incidentResolvedAt()).isNull();
    }

    @Test
    void aResolvedEventIsRead() {
        IncidentLifecycleEvent event = parser.parse(resolvedPayload());

        assertThat(event.eventType()).isEqualTo(IncidentEventType.INCIDENT_RESOLVED);
        assertThat(event.incidentResolvedAt()).isEqualTo(Instant.parse("2026-08-13T06:42:00Z"));
        assertThat(event.monitorStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(event.errorType()).isNull();
    }

    // ------------------------------------------------------------ rejected

    /**
     * A future schema may mean something different by the same field names, so
     * nothing else in the payload can be trusted once the version is unknown.
     */
    @Test
    void anUnsupportedSchemaVersionIsRejected() {
        assertThatThrownBy(() -> parser.parse(openedPayload().replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("Unsupported event schema version: 2");
    }

    @Test
    void aMissingSchemaVersionIsRejected() {
        assertThatThrownBy(() -> parser.parse(openedPayload().replace("\"schemaVersion\":1,", "")))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("Unsupported event schema version");
    }

    /** Guessing at an unknown event type could send somebody the wrong email. */
    @Test
    void anUnknownEventTypeIsRejected() {
        assertThatThrownBy(() -> parser.parse(
                        openedPayload().replace("INCIDENT_OPENED", "INCIDENT_ACKNOWLEDGED")))
                .isInstanceOf(UnsupportedIncidentEventException.class);
    }

    @Test
    void aMissingEventIdIsRejected() {
        assertThatThrownBy(() -> parser.parse(openedPayload().replace("\"eventId\":\"" + EVENT_ID + "\",", "")))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("no eventId");
    }

    /** The id is the deduplication key, so it has to be the shape we expect. */
    @Test
    void aMalformedEventIdIsRejected() {
        assertThatThrownBy(() -> parser.parse(openedPayload().replace(EVENT_ID, "not-a-uuid")))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("malformed eventId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"incidentId", "projectId", "monitorId"})
    void aMissingIdentifierIsRejected(String field) {
        String payload = openedPayload().replaceAll("\"" + field + "\":\\d+,", "");

        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining(field);
    }

    @Test
    void aMissingMonitorNameIsRejected() {
        assertThatThrownBy(() -> parser.parse(openedPayload().replace("\"Payment API\"", "\"  \"")))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("monitorName");
    }

    @Test
    void malformedJsonIsRejectedWithoutLeakingParserInternals() {
        assertThatThrownBy(() -> parser.parse("{\"schemaVersion\": 1, this is not json"))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("not a readable incident event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anEmptyPayloadIsRejected(String payload) {
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void aNullPayloadIsRejected() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(UnsupportedIncidentEventException.class);
    }

    // ---------------------------------------------------------------- setup

    /** The exact shape Task 09 publishes. */
    static String openedPayload() {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"INCIDENT_OPENED",\
                "occurredAt":"2026-08-13T06:30:00Z","incidentId":41,"projectId":10,\
                "monitorId":25,"monitorName":"Payment API",\
                "incidentOpenedAt":"2026-08-13T06:30:00Z","incidentResolvedAt":null,\
                "triggeringCheckId":1501,"monitorStatus":"DOWN","httpStatusCode":503,\
                "responseTimeMs":210,"errorType":"UNEXPECTED_STATUS",\
                "errorMessage":"Expected HTTP 200 but received 503"}"""
                .formatted(EVENT_ID);
    }

    static String resolvedPayload() {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"INCIDENT_RESOLVED",\
                "occurredAt":"2026-08-13T06:42:00Z","incidentId":41,"projectId":10,\
                "monitorId":25,"monitorName":"Payment API",\
                "incidentOpenedAt":"2026-08-13T06:30:00Z","incidentResolvedAt":"2026-08-13T06:42:00Z",\
                "triggeringCheckId":1525,"monitorStatus":"UP","httpStatusCode":200,\
                "responseTimeMs":93,"errorType":null,"errorMessage":null}"""
                .formatted(EVENT_ID);
    }
}
