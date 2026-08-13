package com.pulseguard.notification.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling and the service's source of time.
 *
 * <p>{@link Clock} follows the pattern the other two applications use: business
 * code takes it rather than calling {@code Instant.now()}, so retry schedules
 * can be asserted exactly in tests.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({NotificationProperties.class, KafkaConsumerProperties.class})
public class NotificationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
