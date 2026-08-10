package com.pulseguard.controlapi.dto.auth;

import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.enums.SystemRole;
import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        SystemRole systemRole,
        boolean enabled,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getSystemRole(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
