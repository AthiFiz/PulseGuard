package com.pulseguard.monitorworker.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on scheduling and supplies the worker's source of time.
 *
 * <p>{@link EnableScheduling} is what makes {@code @Scheduled} methods run at
 * all — without it the polling method would be dead code.
 *
 * <p>The {@link Clock} follows the pattern established in the Control API:
 * business code takes it rather than calling {@code Instant.now()}, so tests
 * can pin time and assert scheduling exactly.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MonitoringProperties.class)
public class WorkerConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
