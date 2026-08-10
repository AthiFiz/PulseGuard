package com.pulseguard.controlapi.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns the {@code system_role} claim into a Spring Security authority, so
 * {@code USER} becomes {@code ROLE_USER} and {@code ADMIN} becomes
 * {@code ROLE_ADMIN}.
 *
 * <p>Only the platform-wide role is mapped. Project roles (PROJECT_ADMIN,
 * VIEWER) are intentionally absent: they differ per project and change without
 * a new login, so they are read from the database at the point of use.
 */
@Component
public class SystemRoleJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String systemRole = jwt.getClaimAsString(TokenClaims.SYSTEM_ROLE);

        List<GrantedAuthority> authorities = systemRole == null
                ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + systemRole));

        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
