package com.pulseguard.controlapi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies the configured CORS policy to the public API.
 *
 * <p>Only the origins listed in {@link CorsProperties} are permitted; the
 * wildcard origin is deliberately not used.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebCorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebCorsConfig.class);

    private final CorsProperties corsProperties;

    public WebCorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsProperties.allowedOrigins().isEmpty()) {
            log.warn("No CORS origins configured; browser clients on other origins will be blocked");
            return;
        }

        log.info("Enabling CORS for origins: {}", corsProperties.allowedOrigins());
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
