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
 * <p>Two scheduled jobs run here — monitor polling and outbox publishing — so
 * the scheduler pool is sized for both. With the default single thread, a
 * publishing cycle waiting on an unresponsive broker would hold up the next
 * monitor check, which is exactly the coupling the outbox exists to avoid.
 *
 * <p>The {@link Clock} follows the pattern established in the Control API:
 * business code takes it rather than calling {@code Instant.now()}, so tests
 * can pin time and assert scheduling exactly.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({MonitoringProperties.class, KafkaProperties.class})
public class WorkerConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
