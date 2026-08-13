package com.pulseguard.monitorworker.domain;

import com.pulseguard.monitorworker.enums.IncidentStatus;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One continuous outage of a monitor.
 *
 * <p>An incident is not a failed check — it spans however many failures occur
 * between the monitor going DOWN and its next success. The worker is the only
 * writer of this table; the Control API only reads it.
 *
 * <p>{@code monitorId} and the two check references are plain {@code Long}s
 * rather than associations, matching {@link MonitorCheck}. The ids are already
 * known when a row is written, and no navigation is ever needed in either
 * direction.
 */
@Entity
@Table(name = "incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "monitor_id", nullable = false, updatable = false)
    private Long monitorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IncidentStatus status;

    /**
     * The moment the outage began, taken from the check that caused it — not
     * the time this row was inserted. That keeps an incident's duration a
     * statement about the monitored service rather than about the worker.
     */
    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "opening_check_id", updatable = false)
    private Long openingCheckId;

    @Column(name = "resolution_check_id")
    private Long resolutionCheckId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Opens an incident from the failed check that took the monitor down. */
    public Incident(Long monitorId, Instant openedAt, Long openingCheckId) {
        this.monitorId = monitorId;
        this.status = IncidentStatus.OPEN;
        this.openedAt = openedAt;
        this.openingCheckId = openingCheckId;
    }

    /**
     * Ends the outage using the successful check that proved recovery.
     *
     * <p>The timestamp comes from the check rather than from the clock, so the
     * recorded duration matches the observations either side of it.
     */
    public void resolve(Instant resolvedAt, Long resolutionCheckId) {
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.resolutionCheckId = resolutionCheckId;
    }

    public boolean isOpen() {
        return status == IncidentStatus.OPEN;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Incident incident)) {
            return false;
        }
        return id != null && id.equals(incident.id);
    }

    @Override
    public int hashCode() {
        return Incident.class.hashCode();
    }
}
