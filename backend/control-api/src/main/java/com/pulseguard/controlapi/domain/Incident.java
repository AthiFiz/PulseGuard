package com.pulseguard.controlapi.domain;

import com.pulseguard.controlapi.enums.IncidentStatus;
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

/**
 * One continuous outage of a {@link Monitor}.
 *
 * <p>Read-only here. Incidents are opened and resolved by the Monitor Worker
 * from observed check results, so this entity has no setters and the Control
 * API exposes no endpoint that writes one.
 *
 * <p>The two check references are plain ids rather than associations: they
 * identify rows in a table that grows by one per check forever, and nothing in
 * the API needs to navigate to them. Their columns are nullable because the
 * foreign keys null them out on delete rather than blocking it.
 */
@Entity
@Table(name = "incidents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IncidentStatus status;

    /** The timestamp of the check that caused the outage, not the insert time. */
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "opening_check_id")
    private Long openingCheckId;

    @Column(name = "resolution_check_id")
    private Long resolutionCheckId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
