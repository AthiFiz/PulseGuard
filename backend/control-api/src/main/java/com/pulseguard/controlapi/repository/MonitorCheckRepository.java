package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.MonitorCheck;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, Long> {
}
