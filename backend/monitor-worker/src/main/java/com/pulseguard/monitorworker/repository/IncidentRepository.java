package com.pulseguard.monitorworker.repository;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.enums.IncidentStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    /**
     * The monitor's current outage, if it has one.
     *
     * <p>{@code findFirst} rather than a plain unique lookup: the "at most one
     * open incident per monitor" rule is enforced by this worker being the only
     * writer, not by a database constraint. Should a second row ever appear —
     * from an interrupted run, or a future second worker — returning the oldest
     * is the useful answer, and it keeps the query total rather than throwing.
     */
    Optional<Incident> findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(
            Long monitorId, IncidentStatus status);
}
