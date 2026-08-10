package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.config.JwtProperties;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.service.TokenService;
import com.pulseguard.controlapi.security.TokenClaims;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues signed access tokens.
 *
 * <p>The token carries only stable identity: who the user is and their
 * platform-wide role. Project memberships are excluded on purpose — they change
 * without a new login, so a token asserting them would go stale and grant
 * access that has since been revoked.
 */
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    @Override
    public String generateAccessToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtProperties.expiration());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(user.getId()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TokenClaims.EMAIL, user.getEmail())
                .claim(TokenClaims.SYSTEM_ROLE, user.getSystemRole().name())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Lifetime of a freshly issued token, for the {@code expiresIn} response field. */
    @Override
    public long accessTokenValiditySeconds() {
        return jwtProperties.expiration().toSeconds();
    }
}
