package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseguard.controlapi.config.JwtConfig;
import com.pulseguard.controlapi.config.JwtProperties;
import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.enums.SystemRole;
import com.pulseguard.controlapi.security.TokenClaims;
import com.pulseguard.controlapi.service.impl.TokenServiceImpl;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.util.ReflectionTestUtils;

/** Token generation and validation, with a secret used only by these tests. */
class TokenServiceTest {

    private static final String TEST_SECRET = base64Of("pulseguard-test-only-signing-secret-value");
    private static final String ISSUER = "pulseguard-test-issuer";

    @Test
    void generatedTokenCarriesTheExpectedIdentityClaims() {
        JwtProperties properties = properties(Duration.ofHours(1), ISSUER);
        Jwt jwt = decode(properties, tokenFor(properties, user(15L, "user@example.com", SystemRole.USER)));

        assertThat(jwt.getSubject()).isEqualTo("15");
        assertThat(jwt.getClaimAsString(TokenClaims.EMAIL)).isEqualTo("user@example.com");
        assertThat(jwt.getClaimAsString(TokenClaims.SYSTEM_ROLE)).isEqualTo("USER");
        // Read as a string: the issuer is an application identifier, not a URL,
        // so Jwt#getIssuer() would fail trying to convert it.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void adminRoleIsCarriedThrough() {
        JwtProperties properties = properties(Duration.ofHours(1), ISSUER);
        Jwt jwt = decode(properties, tokenFor(properties, user(1L, "admin@example.com", SystemRole.ADMIN)));

        assertThat(jwt.getClaimAsString(TokenClaims.SYSTEM_ROLE)).isEqualTo("ADMIN");
    }

    @Test
    void tokenNeverCarriesThePasswordHash() {
        JwtProperties properties = properties(Duration.ofHours(1), ISSUER);
        User user = user(15L, "user@example.com", SystemRole.USER);
        Jwt jwt = decode(properties, tokenFor(properties, user));

        assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "password_hash");
        assertThat(jwt.getClaims().values()).doesNotContain(user.getPasswordHash());
    }

    /**
     * Built directly with the encoder, because a claims set may not have an
     * expiry earlier than its issue time — the token has to be issued in the
     * past and already lapsed.
     */
    @Test
    void expiredTokenIsRejected() {
        JwtProperties properties = properties(Duration.ofHours(1), ISSUER);
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));

        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("15")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(1)))
                .claim(TokenClaims.EMAIL, "user@example.com")
                .claim(TokenClaims.SYSTEM_ROLE, "USER")
                .build();

        String expired = new JwtConfig(properties)
                .jwtEncoder()
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), expiredClaims))
                .getTokenValue();

        assertThatThrownBy(() -> decode(properties, expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties issuing = properties(Duration.ofHours(1), "some-other-issuer");
        String foreign = tokenFor(issuing, user(15L, "user@example.com", SystemRole.USER));

        JwtProperties ours = properties(Duration.ofHours(1), ISSUER);
        assertThatThrownBy(() -> decode(ours, foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtProperties attacker = new JwtProperties(
                base64Of("a-completely-different-signing-secret-value"), Duration.ofHours(1), ISSUER);
        String forged = tokenFor(attacker, user(15L, "user@example.com", SystemRole.USER));

        assertThatThrownBy(() -> decode(properties(Duration.ofHours(1), ISSUER), forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void startupFailsWhenTheSecretIsTooShortForHs256() {
        JwtProperties tooShort =
                new JwtProperties(base64Of("only-16-bytes-ab"), Duration.ofHours(1), ISSUER);

        assertThatThrownBy(() -> new JwtConfig(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void startupFailsWhenTheSecretIsNotBase64() {
        JwtProperties notBase64 = new JwtProperties("not valid base64 !!!", Duration.ofHours(1), ISSUER);

        assertThatThrownBy(() -> new JwtConfig(notBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    private static String tokenFor(JwtProperties properties, User user) {
        return new TokenServiceImpl(new JwtConfig(properties).jwtEncoder(), properties)
                .generateAccessToken(user);
    }

    private static Jwt decode(JwtProperties properties, String token) {
        return new JwtConfig(properties).jwtDecoder().decode(token);
    }

    private static JwtProperties properties(Duration expiration, String issuer) {
        return new JwtProperties(TEST_SECRET, expiration, issuer);
    }

    private static User user(Long id, String email, SystemRole role) {
        User user = new User(email, "{bcrypt}hashed-value", "Example User");
        user.setSystemRole(role);
        // The id is database-generated, so it is injected directly here rather
        // than persisting a row.
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static String base64Of(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
