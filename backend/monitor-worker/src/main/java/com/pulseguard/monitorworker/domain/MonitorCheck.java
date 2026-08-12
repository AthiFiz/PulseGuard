package com.pulseguard.monitorworker.domain;

import com.pulseguard.monitorworker.enums.MonitorCheckErrorType;
import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
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
 * One recorded check attempt.
 *
 * <p>The worker is the only writer of this table. {@code monitor_id} is a plain
 * {@code Long} rather than an association — the row is always written for a
 * monitor whose id is already known, and no navigation in either direction is
 * needed.
 *
 * <p>Immutable once written: a check records what happened at a moment in time,
 * so there are no setters.
 */
@Entity
@Table(name = "monitor_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitorCheck {

    /** The column is VARCHAR(1000); longer messages are truncated to fit. */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false)
    private Long monitorId;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private MonitorCheckOutcome outcome;

    /** Null when no HTTP response was received at all. */
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 32)
    private MonitorCheckErrorType errorType;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    public MonitorCheck(
            Long monitorId,
            Instant checkedAt,
            MonitorCheckOutcome outcome,
            Integer httpStatusCode,
            Integer responseTimeMs,
            MonitorCheckErrorType errorType,
            String errorMessage) {
        this.monitorId = monitorId;
        this.checkedAt = checkedAt;
        this.outcome = outcome;
        this.httpStatusCode = httpStatusCode;
        this.responseTimeMs = responseTimeMs;
        this.errorType = errorType;
        this.errorMessage = truncate(errorMessage);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MonitorCheck check)) {
            return false;
        }
        return id != null && id.equals(check.id);
    }

    @Override
    public int hashCode() {
        return MonitorCheck.class.hashCode();
    }
}
