package com.pulseguard.controlapi.security;

import com.pulseguard.controlapi.enums.SystemRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * The one place that reads the security context.
 *
 * <p>Services ask this for the caller's identity instead of each reaching into
 * {@link SecurityContextHolder} and re-parsing the token, so the JWT claim
 * layout is known in exactly one class.
 */
@Service
public class CurrentUserService {

    /** @return the authenticated user's database id */
    public Long requireCurrentUserId() {
        Jwt jwt = currentJwt();
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("JWT subject is not a valid user id", ex);
        }
    }

    public SystemRole requireCurrentSystemRole() {
        String role = currentJwt().getClaimAsString(TokenClaims.SYSTEM_ROLE);
        try {
            return SystemRole.valueOf(role);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("JWT does not carry a valid system role", ex);
        }
    }

    public boolean isSystemAdmin() {
        return requireCurrentSystemRole() == SystemRole.ADMIN;
    }

    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT in the security context");
        }
        return jwt;
    }
}
