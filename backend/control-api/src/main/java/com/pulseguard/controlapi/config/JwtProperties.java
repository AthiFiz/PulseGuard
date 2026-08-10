package com.pulseguard.controlapi.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT signing and validation settings.
 *
 * <p>The secret is supplied through the {@code JWT_SECRET} environment variable
 * and has no default on purpose — a shipped default secret is the same as no
 * security at all. Its length is checked at startup by {@link JwtConfig}, so a
 * weak secret fails the application immediately rather than quietly producing
 * forgeable tokens.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration expiration,
        @NotBlank String issuer) {
}
