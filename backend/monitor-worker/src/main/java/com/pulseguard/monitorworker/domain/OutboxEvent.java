package com.pulseguard.monitorworker.domain;

import com.pulseguard.monitorworker.enums.OutboxAggregateType;
import com.pulseguard.monitorworker.enums.OutboxEventType;
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


@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The logical event identity, stable across republication.
     *
     * <p>Delivery is at-least-once, so a consumer may see the same event twice.
     * This is what lets it recognise the repeat — the database id would not,
     * since it is an implementation detail of this table.
     */
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private OutboxEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private OutboxAggregateType aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    /** The Kafka message key. Keeps one monitor's events on one partition. */
    @Column(name = "partition_key", nullable = false, updatable = false, length = 128)
    private String partitionKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Null until a broker has acknowledged the send. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    public OutboxEvent(
            String eventId,
            OutboxEventType eventType,
            OutboxAggregateType aggregateType,
            Long aggregateId,
            String partitionKey,
            String payload,
            Instant occurredAt,
            Instant createdAt) {

        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
        this.attemptCount = 0;
    }

    /**
     * Called only after a broker has acknowledged the record.
     *
     * <p>The previous error is cleared: it described an attempt that has since
     * been superseded, and leaving it would make a delivered event look failed.
     */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    /**
     * Records a failed attempt. The event stays pending and will be retried on
     * a later cycle; {@code attemptCount} is kept as history rather than used
     * to give up, because nothing here drops events.
     */
    public void markFailed(Instant attemptedAt, String error) {
        this.attemptCount++;
        this.lastAttemptAt = attemptedAt;
        this.lastError = truncate(error);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEvent event)) {
            return false;
        }
        return id != null && id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }
}
