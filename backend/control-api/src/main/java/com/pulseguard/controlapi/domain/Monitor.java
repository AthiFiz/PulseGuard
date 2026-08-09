package com.pulseguard.controlapi.domain;

import com.pulseguard.controlapi.enums.MonitorHttpMethod;
import com.pulseguard.controlapi.enums.MonitorStatus;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A monitored HTTP endpoint together with its checking configuration and
 * current state.
 *
 * <p>The scheduling and failure-tracking fields ({@code consecutiveFailures},
 * {@code lastCheckedAt}, {@code nextCheckAt}) are persisted now but nothing
 * maintains them yet — the Monitor Worker starts using them in a later stage.
 */
@Entity
@Table(name = "monitors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Monitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Setter
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Setter
    @Column(name = "description", length = 500)
    private String description;

    @Setter
    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 16)
    private MonitorHttpMethod httpMethod = MonitorHttpMethod.GET;

    @Setter
    @Column(name = "expected_status_code", nullable = false)
    private int expectedStatusCode;

    @Setter
    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Setter
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Setter
    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold;

    @Setter
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 32)
    private MonitorStatus currentStatus = MonitorStatus.UNKNOWN;

    /** Null until the monitor has been checked at least once. */
    @Setter
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** Null until scheduling is implemented in the worker stage. */
    @Setter
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Monitor(
            Project project,
            String name,
            String url,
            int expectedStatusCode,
            int intervalSeconds,
            int timeoutSeconds,
            int failureThreshold) {
        this.project = project;
        this.name = name;
        this.url = url;
        this.expectedStatusCode = expectedStatusCode;
        this.intervalSeconds = intervalSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.failureThreshold = failureThreshold;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Monitor monitor)) {
            return false;
        }
        return id != null && id.equals(monitor.id);
    }

    @Override
    public int hashCode() {
        return Monitor.class.hashCode();
    }
}
