package com.pulseguard.notification.domain;

import com.pulseguard.notification.enums.NotificationChannel;
import com.pulseguard.notification.enums.NotificationDeliveryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One email PulseGuard intends to send, and what became of it.
 *
 * <p>Created when the event is consumed and delivered later, so a mail server
 * being down never causes a Kafka event to be reprocessed.
 *
 * <p>The recipient address and the message text are <strong>snapshots</strong>.
 * If the user changes their email afterwards, an already-queued notification
 * still goes where it was addressed; if the monitor is renamed, the message
 * still describes the incident as it was.
 */
@Entity
@Table(name = "notification_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery {

    /** Matches the {@code VARCHAR(1000)} column; longer messages are cut to fit. */
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private Long incidentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "monitor_id", nullable = false, updatable = false)
    private Long monitorId;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 255)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 32)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private NotificationDeliveryStatus status;

    @Column(name = "subject", nullable = false, updatable = false, length = 500)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** When to try next. Set only while PENDING; null once finished either way. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** A new delivery, due immediately. */
    public NotificationDelivery(
            String eventId,
            Long incidentId,
            Long projectId,
            Long monitorId,
            String recipientEmail,
            NotificationChannel channel,
            String subject,
            String body,
            Instant now) {

        this.eventId = eventId;
        this.incidentId = incidentId;
        this.projectId = projectId;
        this.monitorId = monitorId;
        this.recipientEmail = recipientEmail;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.status = NotificationDeliveryStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The mail server accepted the message.
     *
     * <p>That is as much as SMTP can tell us: accepted for delivery, not read,
     * and not necessarily delivered.
     */
    public void markSent(Instant sentAt) {
        this.attemptCount++;
        this.status = NotificationDeliveryStatus.SENT;
        this.sentAt = sentAt;
        this.lastAttemptAt = sentAt;
        this.nextAttemptAt = null;
        this.lastError = null;
        this.updatedAt = sentAt;
    }

    /**
     * An attempt failed. Stays PENDING and is rescheduled until the attempts
     * run out, at which point it becomes FAILED and is left alone — kept for
     * inspection rather than deleted, and never retried automatically again.
     */
    public void recordFailedAttempt(
            Instant attemptedAt, String error, int maxAttempts, Duration retryDelay) {

        this.attemptCount++;
        this.lastAttemptAt = attemptedAt;
        this.lastError = truncate(error);
        this.updatedAt = attemptedAt;

        if (attemptCount >= maxAttempts) {
            this.status = NotificationDeliveryStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = attemptedAt.plus(retryDelay);
        }
    }

    public boolean isSent() {
        return status == NotificationDeliveryStatus.SENT;
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
        if (!(other instanceof NotificationDelivery delivery)) {
            return false;
        }
        return id != null && id.equals(delivery.id);
    }

    @Override
    public int hashCode() {
        return NotificationDelivery.class.hashCode();
    }
}
