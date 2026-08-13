package com.pulseguard.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How notifications are delivered.
 *
 * @param fromAddress the envelope sender. Whether a mail server accepts it is a
 *     matter for that server's configuration, not this application's.
 * @param frontendBaseUrl used to build the incident link in each email, so the
 *     address is configured once rather than hardcoded through the composer.
 * @param deliveryInterval how often to look for deliveries that are due.
 * @param batchSize the most deliveries one cycle will attempt, so a backlog
 *     cannot be pulled into memory at once.
 * @param maxAttempts how many times a single email may be attempted before it
 *     is left as FAILED. Finite on purpose: an address that will never accept
 *     mail should stop consuming attempts and start being visible.
 * @param retryDelay how long to wait before retrying a failed delivery.
 */
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        String fromAddress,
        String frontendBaseUrl,
        Duration deliveryInterval,
        int batchSize,
        int maxAttempts,
        Duration retryDelay) {
}
