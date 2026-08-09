package com.pulseguard.controlapi.domain;

import com.pulseguard.controlapi.enums.MonitorCheckErrorType;
import com.pulseguard.controlapi.enums.MonitorCheckOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The result of a single check against a {@link Monitor}.
 *
 * <p>The HTTP and timing fields are nullable on purpose: a DNS failure,
 * connection error, or timeout produces no HTTP status and often no meaningful
 * duration. {@code errorMessage} holds a short description only — stack traces
 * are never persisted.
 */
@Entity
@Table(name = "monitor_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitorCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @Setter
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private MonitorCheckOutcome outcome;

    @Setter
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Setter
    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 32)
    private MonitorCheckErrorType errorType;

    @Setter
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    public MonitorCheck(Monitor monitor, Instant checkedAt, MonitorCheckOutcome outcome) {
        this.monitor = monitor;
        this.checkedAt = checkedAt;
        this.outcome = outcome;
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
