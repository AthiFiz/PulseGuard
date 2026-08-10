package com.pulseguard.controlapi.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application's source of time.
 *
 * <p>Business code takes a {@link Clock} rather than calling
 * {@code Instant.now()} directly, so tests can pin time with
 * {@code Clock.fixed(...)} and assert scheduling fields exactly. The Monitor
 * Worker will lean on this harder once it starts deciding which monitors are
 * due.
 *
 * <p>UTC, matching how every timestamp is stored.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
