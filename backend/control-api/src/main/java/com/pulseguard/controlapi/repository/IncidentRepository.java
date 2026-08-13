package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Incident;
import com.pulseguard.controlapi.enums.IncidentStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    /**
     * A project's incident history, newest first.
     *
     * <p>The monitor is fetched eagerly because every row shows its name;
     * without it a page of twenty incidents would cost twenty extra queries.
     * The count query deliberately omits the fetch — counting rows does not
     * need the joined entity, and a fetch join is not permitted there.
     */
    @Query(
            value = """
                    select i from Incident i
                    join fetch i.monitor m
                    where m.project.id = :projectId
                      and (:status is null or i.status = :status)
                      and (:from is null or i.openedAt >= :from)
                      and (:to is null or i.openedAt <= :to)
                    """,
            countQuery = """
                    select count(i) from Incident i
                    where i.monitor.project.id = :projectId
                      and (:status is null or i.status = :status)
                      and (:from is null or i.openedAt >= :from)
                      and (:to is null or i.openedAt <= :to)
                    """)
    Page<Incident> findProjectIncidents(
            @Param("projectId") Long projectId,
            @Param("status") IncidentStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * One incident with its monitor and project, for the detail endpoint.
     *
     * <p>Both are needed immediately: the project to decide whether the caller
     * may see the incident at all, the monitor to name it in the response.
     */
    @Query("""
            select i from Incident i
            join fetch i.monitor m
            join fetch m.project
            where i.id = :incidentId
            """)
    Optional<Incident> findDetailById(@Param("incidentId") Long incidentId);

    /**
     * How many outages a project has open right now.
     *
     * <p>One aggregate, deliberately not one query per monitor: the dashboard's
     * cost must not grow with the size of the project. This is current state and
     * is not filtered by the dashboard's check-history window — an outage that
     * started last week is still an outage today.
     */
    @Query("""
            select count(i) from Incident i
            where i.monitor.project.id = :projectId
              and i.status = :status
            """)
    long countByProjectAndStatus(
            @Param("projectId") Long projectId, @Param("status") IncidentStatus status);
}
