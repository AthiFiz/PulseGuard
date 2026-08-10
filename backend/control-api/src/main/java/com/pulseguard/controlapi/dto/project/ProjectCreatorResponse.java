package com.pulseguard.controlapi.dto.project;

import com.pulseguard.controlapi.domain.User;

/** The subset of the creator's details that a project response exposes. */
public record ProjectCreatorResponse(Long id, String email, String displayName) {

    public static ProjectCreatorResponse from(User user) {
        return new ProjectCreatorResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
