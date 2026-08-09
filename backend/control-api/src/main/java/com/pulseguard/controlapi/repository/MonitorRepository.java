package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Monitor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorRepository extends JpaRepository<Monitor, Long> {
}
