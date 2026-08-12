package com.pulseguard.monitorworker.repository;

import com.pulseguard.monitorworker.domain.MonitorCheck;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorCheckRepository extends JpaRepository<MonitorCheck, Long> {
}
