package com.pulseguard.controlapi.repository;

import com.pulseguard.controlapi.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
}
