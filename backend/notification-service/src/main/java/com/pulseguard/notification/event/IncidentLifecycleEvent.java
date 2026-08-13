package com.pulseguard.notification.event;

import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.enums.MonitorCheckErrorType;
import com.pulseguard.notification.enums.MonitorStatus;
import java.time.Instant;

/**
 * The consumer's copy of the published event contract.
 *
 * <p>Deliberately a copy rather than a shared library. The two applications are
 * independent Maven projects with no common module, and a message on a topic is
 * a contract between separate systems — a producer and a consumer that share a
 * class are only pretending to be decoupled. The duplication is the honest
 * representation of what Kafka actually guarantees.
 *
 * <p>It mirrors the Monitor Worker's record field for field, including the
 * absence of the monitor's URL.
 */
public record IncidentLifecycleEvent(
        Integer schemaVersion,
        String eventId,
        IncidentEventType eventType,
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

    /** The only schema version this service knows how to read. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
}
