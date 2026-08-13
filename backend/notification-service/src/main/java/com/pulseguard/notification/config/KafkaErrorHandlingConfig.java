package com.pulseguard.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when processing a record fails.
 *
 * <p>Spring's default handler retries a few times and then <em>gives up on the
 * record</em>, committing its offset and moving on. That default is wrong here:
 * the usual reason for failure is the database being briefly unavailable, and
 * silently discarding an outage notification because MySQL was restarting is
 * not an acceptable outcome.
 *
 * <p>So retries are unlimited, with a fixed delay. The cost is stated plainly:
 * a record that can <em>never</em> be processed — a genuinely malformed payload
 * — will be retried forever and block its partition. There is no dead-letter
 * topic yet, and this is a known Task 10 limitation rather than an oversight.
 * Every failure is logged with its cause, so a stuck partition is visible.
 */
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

    /** Long enough not to spin, short enough to recover promptly. */
    private static final long RETRY_INTERVAL_MS = 5_000L;

    @Bean
    public CommonErrorHandler incidentEventErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Failed to process incident event; will retry: topic={}, partition={}, offset={}, reason={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception.getMessage()),
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));

        // The record is never handed to the recoverer as "done", because the
        // back-off never runs out.
        return errorHandler;
    }
}
