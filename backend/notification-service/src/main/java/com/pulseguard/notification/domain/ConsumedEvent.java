package com.pulseguard.notification.domain;

import com.pulseguard.notification.enums.IncidentEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A record that one incident event has already been processed.
 *
 * <p>This is the consumer's inbox. Kafka delivers at-least-once, so the same
 * event can arrive more than once; a row here is what makes the second arrival
 * a no-op instead of a second email.
 *
 * <p>Immutable once written — it records something that happened.
 */
@Entity
@Table(name = "consumed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The logical identity, unique in the database. Not the Kafka offset. */
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private IncidentEventType eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    /** The business timestamp from the event, not the time it was consumed. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private Long incidentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "monitor_id", nullable = false, updatable = false)
    private Long monitorId;

    @Column(name = "kafka_topic", nullable = false, updatable = false, length = 255)
    private String kafkaTopic;

    @Column(name = "kafka_partition", nullable = false, updatable = false)
    private int kafkaPartition;

    /**
     * Where this particular delivery came from. Useful for tracing, never for
     * identity: the same event redelivered arrives at a different offset.
     */
    @Column(name = "kafka_offset", nullable = false, updatable = false)
    private long kafkaOffset;

    @Column(name = "raw_payload", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String rawPayload;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    public ConsumedEvent(
            String eventId,
            IncidentEventType eventType,
            int schemaVersion,
            Instant occurredAt,
            Long incidentId,
            Long projectId,
            Long monitorId,
            String kafkaTopic,
            int kafkaPartition,
            long kafkaOffset,
            String rawPayload,
            Instant consumedAt) {

        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.occurredAt = occurredAt;
        this.incidentId = incidentId;
        this.projectId = projectId;
        this.monitorId = monitorId;
        this.kafkaTopic = kafkaTopic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.rawPayload = rawPayload;
        this.consumedAt = consumedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConsumedEvent event)) {
            return false;
        }
        return id != null && id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return ConsumedEvent.class.hashCode();
    }
}
