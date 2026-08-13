package com.pulseguard.monitorworker.outbox;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.domain.OutboxEvent;
import com.pulseguard.monitorworker.enums.OutboxAggregateType;
import com.pulseguard.monitorworker.enums.OutboxEventType;
import com.pulseguard.monitorworker.event.IncidentLifecycleEvent;
import com.pulseguard.monitorworker.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns an incident transition into a durable outbox row.
 *
 * <p>Called from inside the result-processing transaction, so the event and the
 * incident commit together. It knows nothing about Kafka: no broker is
 * contacted here, and none needs to be reachable for an incident to be
 * recorded.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEventRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** The outage has begun. Carries the failure that crossed the threshold. */
    public void recordIncidentOpened(
            Monitor monitor, Incident incident, MonitorCheck triggeringCheck) {

        record(monitor, incident, triggeringCheck, OutboxEventType.INCIDENT_OPENED, incident.getOpenedAt());
    }

    /** The outage has ended. Carries the success that proved recovery. */
    public void recordIncidentResolved(
            Monitor monitor, Incident incident, MonitorCheck triggeringCheck) {

        record(monitor, incident, triggeringCheck, OutboxEventType.INCIDENT_RESOLVED, incident.getResolvedAt());
    }

    private void record(
            Monitor monitor,
            Incident incident,
            MonitorCheck triggeringCheck,
            OutboxEventType eventType,
            Instant occurredAt) {

        String eventId = UUID.randomUUID().toString();

        IncidentLifecycleEvent event = new IncidentLifecycleEvent(
                IncidentLifecycleEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                eventType,
                occurredAt,
                incident.getId(),
                monitor.getProjectId(),
                monitor.getId(),
                monitor.getName(),
                incident.getOpenedAt(),
                incident.getResolvedAt(),
                triggeringCheck.getId(),
                monitor.getCurrentStatus(),
                triggeringCheck.getHttpStatusCode(),
                triggeringCheck.getResponseTimeMs(),
                triggeringCheck.getErrorType(),
                triggeringCheck.getErrorMessage());

        outboxEventRepository.save(new OutboxEvent(
                eventId,
                eventType,
                OutboxAggregateType.INCIDENT,
                incident.getId(),
                // The monitor id, so every event for one monitor keeps its
                // order on a single Kafka partition.
                String.valueOf(monitor.getId()),
                objectMapper.writeValueAsString(event),
                occurredAt,
                Instant.now(clock)));

        log.info(
                "Outbox event created: eventId={}, eventType={}, incidentId={}, monitorId={}",
                eventId,
                eventType,
                incident.getId(),
                monitor.getId());
    }
}
