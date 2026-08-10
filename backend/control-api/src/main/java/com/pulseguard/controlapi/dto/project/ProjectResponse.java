package com.pulseguard.controlapi.dto.project;

import com.pulseguard.controlapi.domain.Project;
import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        ProjectCreatorResponse createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                ProjectCreatorResponse.from(project.getCreatedBy()),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}
