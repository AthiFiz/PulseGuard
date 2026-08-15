package com.pulseguard.controlapi.config;

import com.pulseguard.controlapi.security.SecurityErrorResponder;
import com.pulseguard.controlapi.security.SystemRoleJwtAuthenticationConverter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless bearer-token security for the Control API.
 *
 * <p>Authentication is handled entirely by Spring Security's OAuth2 resource
 * server, which validates the JWT's signature, expiry, and issuer. No custom
 * request filter is written, because nothing here needs behaviour the built-in
 * support does not already provide.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorsProperties corsProperties;
    private final SecurityErrorResponder securityErrorResponder;
    private final SystemRoleJwtAuthenticationConverter jwtAuthenticationConverter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF protects against a browser automatically attaching ambient
                // credentials to a cross-site request. This API has none to attach:
                // it never issues an authentication cookie and never authenticates
                // from one. The bearer token must be set explicitly on each request
                // by JavaScript that already obeys the same-origin policy, so a
                // cross-site form post simply arrives unauthenticated.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
                        .permitAll()
                        // Exactly "/actuator/health" — nothing beneath it. The
                        // finer-grained /actuator/health/liveness and
                        // /actuator/health/readiness groups therefore answer 401,
                        // and a Kubernetes probe reads that as an unhealthy
                        // container rather than as an authentication problem.
                        // The k8s manifests point every probe at this exact path
                        // for that reason; widening this matcher is what a future
                        // change would need to do to use the groups instead.
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info", "/actuator/health")
                        .permitAll()
                        // CORS preflight carries no credentials and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Everything else is protected by default, so a new
                        // endpoint is never accidentally left public.
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(securityErrorResponder)
                        .accessDeniedHandler(securityErrorResponder))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorResponder)
                        .accessDeniedHandler(securityErrorResponder));

        return http.build();
    }

    /**
     * Reuses the same configurable origins as the rest of the application, so
     * the frontend origin is still driven by {@code FRONTEND_ORIGIN} and the
     * wildcard is never used.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        if (corsProperties.allowedOrigins().isEmpty()) {
            log.warn("No CORS origins configured; browser clients on other origins will be blocked");
        } else {
            log.info("Enabling CORS for origins: {}", corsProperties.allowedOrigins());
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
