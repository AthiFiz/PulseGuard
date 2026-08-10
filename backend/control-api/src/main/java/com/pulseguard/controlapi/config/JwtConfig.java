package com.pulseguard.controlapi.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Builds the HS256 encoder and decoder from the configured secret.
 *
 * <p>Both share one {@link SecretKeySpec}, so tokens are signed and verified
 * with the same key. The decoder additionally enforces expiry and issuer.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    private final SecretKeySpec secretKey;
    private final JwtProperties jwtProperties;

    public JwtConfig(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = buildSecretKey(jwtProperties.secret());
    }

    /**
     * Decodes the Base64 secret and refuses anything shorter than 256 bits.
     * The exception message deliberately never contains the secret itself.
     */
    private static SecretKeySpec buildSecretKey(String configuredSecret) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredSecret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must be Base64 encoded. "
                            + "Generate one with: openssl rand -base64 32",
                    ex);
        }

        if (decoded.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) must decode to at least %d bytes (256 bits) for HS256, but was %d. "
                            .formatted(MINIMUM_SECRET_BYTES, decoded.length)
                            + "Generate one with: openssl rand -base64 32");
        }

        return new SecretKeySpec(decoded, ALGORITHM.getName());
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(ALGORITHM)
                .build();
        // Validates expiry, not-before, and that the issuer matches ours.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        return decoder;
    }
}
