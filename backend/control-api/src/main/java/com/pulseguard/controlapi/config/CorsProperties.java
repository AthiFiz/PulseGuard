package com.pulseguard.controlapi.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CORS settings for the Control API.
 *
 * <p>Origins are configured through {@code app.cors.allowed-origins}, which is
 * backed by the {@code FRONTEND_ORIGIN} environment variable. Changing the
 * frontend origin therefore never requires a Java source change.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
