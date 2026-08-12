package com.pulseguard.monitorworker.domain;

import com.pulseguard.monitorworker.enums.MonitorHttpMethod;
import com.pulseguard.monitorworker.enums.MonitorStatus;
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
import lombok.Setter;

/**
 * The worker's view of the {@code monitors} table.
 *
 * <p>Deliberately not a copy of the Control API entity. The worker never
 * creates or deletes monitors — it only reads configuration and writes back
 * operational state — so it maps just the columns it needs and leaves
 * {@code description}, {@code created_at} and {@code updated_at} alone.
 *
 * <p>{@code project_id} is mapped as a plain {@code Long}: the worker performs
 * no project authorization, so a JPA association to a Project entity would buy
 * nothing and would drag the whole project-management model in with it.
 */
@Entity
@Table(name = "monitors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Monitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, insertable = false, updatable = false)
    private Long projectId;

    @Column(name = "name", nullable = false, length = 150, insertable = false, updatable = false)
    private String name;

    @Column(name = "url", nullable = false, length = 2048, insertable = false, updatable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 16, insertable = false, updatable = false)
    private MonitorHttpMethod httpMethod;

    @Column(name = "expected_status_code", nullable = false, insertable = false, updatable = false)
    private int expectedStatusCode;

    @Column(name = "interval_seconds", nullable = false, insertable = false, updatable = false)
    private int intervalSeconds;

    @Column(name = "timeout_seconds", nullable = false, insertable = false, updatable = false)
    private int timeoutSeconds;

    @Column(name = "failure_threshold", nullable = false, insertable = false, updatable = false)
    private int failureThreshold;

    // The four fields below are the only ones the worker owns.

    @Setter
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 32)
    private MonitorStatus currentStatus;

    @Setter
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Setter
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

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
