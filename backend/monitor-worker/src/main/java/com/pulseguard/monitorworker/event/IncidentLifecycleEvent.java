package com.pulseguard.monitorworker.event;

import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.enums.OutboxEventType;
import java.time.Instant;

/**
 * The published shape of an incident lifecycle event.
 *
 * <p>This is a contract with consumers that do not exist yet, so it is
 * deliberately flat and self-describing: everything a notification needs to say
 * "Payment API went down at 06:30, and here is why" without calling back into
 * the Control API.
 *
 * <p>Fields that do not apply are null rather than omitted — an
 * {@code INCIDENT_OPENED} event has no resolution time, and saying so is more
 * useful than leaving a consumer to guess.
 *
 * <p><strong>The monitor's URL is deliberately absent.</strong> URLs can carry
 * query parameters, tokens and internal hostnames, and an event bus is a place
 * where data spreads. The name and the ids identify the monitor perfectly well
 * for anyone who is already entitled to look it up.
 *
 * @param schemaVersion the payload's version, carried inside the message rather
 *     than inferred from the topic name, so a consumer reading a stored or
 *     forwarded event can still tell how to read it
 * @param eventId globally unique and stable across republication, so a consumer
 *     can deduplicate — delivery is at-least-once
 * @param occurredAt when the business event happened: the incident's own
 *     opened or resolved timestamp, never the time it was published
 * @param triggeringCheckId the check that caused this transition — the failed
 *     one that opened the incident, or the successful one that closed it
 * @param monitorStatus the monitor's status after the transition
 */
public record IncidentLifecycleEvent(
        int schemaVersion,
        String eventId,
        OutboxEventType eventType,
        Instant occurredAt,
        Long incidentId,
        Long projectId,
        Long monitorId,
        String monitorName,
        Instant incidentOpenedAt,
        Instant incidentResolvedAt,
        Long triggeringCheckId,
        MonitorStatus monitorStatus,
        Integer httpStatusCode,
        Integer responseTimeMs,
        MonitorCheckErrorType errorType,
        String errorMessage) {

    /** The only schema version in existence. See {@code docs/architecture.md}. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
