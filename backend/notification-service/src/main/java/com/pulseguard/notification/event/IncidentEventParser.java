package com.pulseguard.notification.event;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a Kafka record's payload into a validated event.
 *
 * <p>Parsing is done explicitly rather than by a typed deserializer so that
 * failures happen here, in application code, where they can be described and
 * logged usefully — a deserializer failing inside the container gives far less
 * to work with, and the raw payload still needs storing either way.
 */
@Component
@RequiredArgsConstructor
public class IncidentEventParser {

    private final ObjectMapper objectMapper;

    /**
     * @throws UnsupportedIncidentEventException if the payload cannot be read,
     *     or can be read but says something this service does not understand
     */
    public IncidentLifecycleEvent parse(String payload) {
        IncidentLifecycleEvent event = read(payload);
        validate(event);
        return event;
    }

    private IncidentLifecycleEvent read(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new UnsupportedIncidentEventException("Event payload is empty");
        }
        try {
            return objectMapper.readValue(payload, IncidentLifecycleEvent.class);
        } catch (Exception ex) {
            // The parser's own message names Java classes and offsets, so it is
            // summarised rather than passed through verbatim.
            throw new UnsupportedIncidentEventException(
                    "Event payload is not a readable incident event: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Everything an email needs is required. A partially-populated event would
     * produce a partially-populated notification, which is worse than a loud
     * failure.
     */
    private void validate(IncidentLifecycleEvent event) {
        // Version first: on an unknown version, nothing else can be trusted to
        // mean what it appears to mean.
        if (event.schemaVersion() == null
                || event.schemaVersion() != IncidentLifecycleEvent.SUPPORTED_SCHEMA_VERSION) {
            throw new UnsupportedIncidentEventException(
                    "Unsupported event schema version: %s (this service reads version %d)"
                            .formatted(event.schemaVersion(), IncidentLifecycleEvent.SUPPORTED_SCHEMA_VERSION));
        }

        requireUuid(event.eventId());

        if (event.eventType() == null) {
            throw new UnsupportedIncidentEventException("Event has no recognised eventType");
        }

        require(event.occurredAt() != null, "occurredAt");
        require(event.incidentId() != null, "incidentId");
        require(event.projectId() != null, "projectId");
        require(event.monitorId() != null, "monitorId");
        require(event.monitorName() != null && !event.monitorName().isBlank(), "monitorName");
    }

    private static void requireUuid(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new UnsupportedIncidentEventException("Event has no eventId");
        }
        try {
            UUID.fromString(eventId);
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedIncidentEventException("Event has a malformed eventId: " + eventId);
        }
    }

    private static void require(boolean present, String field) {
        if (!present) {
            throw new UnsupportedIncidentEventException("Event is missing " + field);
        }
    }
}
