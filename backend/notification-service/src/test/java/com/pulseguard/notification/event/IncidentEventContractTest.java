package com.pulseguard.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.enums.MonitorCheckErrorType;
import com.pulseguard.notification.enums.MonitorStatus;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer half of the event contract.
 *
 * <p>Reads the same fixtures in {@code docs/contracts} that the Monitor Worker
 * asserts its output against. Neither application depends on the other's code,
 * so those two files are the only thing holding the ends together — and if
 * either side drifts, its own build fails.
 *
 * <p>This is deliberately narrow. {@link IncidentEventParserTest} already covers
 * the full rejection matrix against payloads written by hand; what only a
 * fixture can show is that the bytes the <em>other application</em> promises to
 * send are understood here.
 */
class IncidentEventContractTest {

    private static final Path CONTRACTS = Path.of("..", "..", "docs", "contracts");

    private final IncidentEventParser parser = new IncidentEventParser(JsonMapper.builder().build());

    // ---------------------------------------------------------- understood

    @Test
    void theOpenedFixtureIsUnderstood() throws IOException {
        IncidentLifecycleEvent event = parser.parse(fixture("incident-opened-v1.json"));

        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo("5a60f403-e588-43df-9987-d9a3d2174ec9");
        assertThat(event.eventType()).isEqualTo(IncidentEventType.INCIDENT_OPENED);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-13T06:30:00Z"));
        assertThat(event.incidentId()).isEqualTo(41L);
        assertThat(event.projectId()).isEqualTo(10L);
        assertThat(event.monitorId()).isEqualTo(25L);
        assertThat(event.monitorName()).isEqualTo("Payment API");
        assertThat(event.incidentOpenedAt()).isEqualTo(Instant.parse("2026-08-13T06:30:00Z"));
        assertThat(event.incidentResolvedAt()).isNull();
        assertThat(event.triggeringCheckId()).isEqualTo(1501L);
        assertThat(event.monitorStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(event.httpStatusCode()).isEqualTo(503);
        assertThat(event.responseTimeMs()).isEqualTo(210);
        assertThat(event.errorType()).isEqualTo(MonitorCheckErrorType.UNEXPECTED_STATUS);
        assertThat(event.errorMessage()).isEqualTo("Expected HTTP 200 but received 503");
    }

    @Test
    void theResolvedFixtureIsUnderstood() throws IOException {
        IncidentLifecycleEvent event = parser.parse(fixture("incident-resolved-v1.json"));

        assertThat(event.eventType()).isEqualTo(IncidentEventType.INCIDENT_RESOLVED);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-13T06:42:00Z"));
        assertThat(event.incidentOpenedAt()).isEqualTo(Instant.parse("2026-08-13T06:30:00Z"));
        assertThat(event.incidentResolvedAt()).isEqualTo(Instant.parse("2026-08-13T06:42:00Z"));
        assertThat(event.triggeringCheckId()).isEqualTo(1525L);
        assertThat(event.monitorStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(event.httpStatusCode()).isEqualTo(200);
        assertThat(event.responseTimeMs()).isEqualTo(93);
        assertThat(event.errorType()).isNull();
        assertThat(event.errorMessage()).isNull();
    }

    /**
     * The two fixtures are the same incident seen twice, and each carries its
     * own identity — the property the inbox deduplicates on.
     */
    @Test
    void theTwoFixturesDescribeOneIncidentWithDistinctEventIds() throws IOException {
        IncidentLifecycleEvent opened = parser.parse(fixture("incident-opened-v1.json"));
        IncidentLifecycleEvent resolved = parser.parse(fixture("incident-resolved-v1.json"));

        assertThat(resolved.incidentId()).isEqualTo(opened.incidentId());
        assertThat(resolved.monitorId()).isEqualTo(opened.monitorId());
        assertThat(resolved.eventId()).isNotEqualTo(opened.eventId());
        UUID.fromString(opened.eventId());
        UUID.fromString(resolved.eventId());
    }

    // -------------------------------------------------------------- shape

    /**
     * The consumer's record must mirror the contract field for field.
     *
     * <p>Without this, dropping a component from {@link IncidentLifecycleEvent}
     * would be invisible: Jackson simply ignores what it has nowhere to put, and
     * the parse tests above would keep passing while the email quietly lost a
     * value.
     */
    @ParameterizedTest
    @ValueSource(strings = {"incident-opened-v1.json", "incident-resolved-v1.json"})
    void theConsumerRecordMirrorsTheContractFieldForField(String name) throws IOException {
        var contractFields = JsonMapper.builder().build().readTree(fixture(name)).propertyNames();

        var recordFields = Arrays.stream(IncidentLifecycleEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(recordFields)
                .as("every field the producer sends must have somewhere to land")
                .containsExactlyInAnyOrderElementsOf(contractFields);
    }

    // ------------------------------------------------------------- refused

    /**
     * The forward-compatibility guard, applied to the real fixture rather than a
     * hand-written payload: a v2 producer must not be read as though it were v1,
     * however familiar its field names look.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "99"})
    void theFixtureIsRefusedUnderAnyOtherSchemaVersion(String version) throws IOException {
        String payload = fixture("incident-opened-v1.json")
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": " + version);

        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOf(UnsupportedIncidentEventException.class)
                .hasMessageContaining("Unsupported event schema version");
    }

    // ------------------------------------------------------------- safety

    /**
     * A property-name check rather than a substring search, so an ordinary error
     * message mentioning a password cannot fail this spuriously.
     */
    @ParameterizedTest
    @ValueSource(strings = {"incident-opened-v1.json", "incident-resolved-v1.json"})
    void theContractCarriesNothingSensitive(String name) throws IOException {
        var json = JsonMapper.builder().build().readTree(fixture(name));

        for (String forbidden : new String[] {
                "url", "monitorUrl", "uri", "endpoint",
                "authorization", "headers", "responseBody", "body",
                "password", "secret", "token", "credentials"}) {
            assertThat(json.has(forbidden))
                    .as("the contract must not carry a '%s' property", forbidden)
                    .isFalse();
        }
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(CONTRACTS.resolve(name));
    }
}
