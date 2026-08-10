package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Monitor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {

    /**
     * A project's monitors, oldest first.
     *
     * <p>The ordering is explicit so the list is stable between calls — without
     * an ORDER BY the database is free to return rows in any order.
     */
    List<Monitor> findAllByProjectIdOrderByCreatedAtAsc(Long projectId);
}
