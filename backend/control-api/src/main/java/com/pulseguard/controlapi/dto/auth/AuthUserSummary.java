package com.pulseguard.controlapi.dto.auth;

import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.enums.SystemRole;

/** Compact user details returned alongside a freshly issued token. */
public record AuthUserSummary(Long id, String email, String displayName, SystemRole systemRole) {

    public static AuthUserSummary from(User user) {
        return new AuthUserSummary(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getSystemRole());
    }
}
