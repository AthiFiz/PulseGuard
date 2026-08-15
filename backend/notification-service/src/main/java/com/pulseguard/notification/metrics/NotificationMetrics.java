package com.pulseguard.notification.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for what this service exists to do: deliver incident email.
 *
 * <p>Three outcomes are worth distinguishing on a dashboard, because they mean
 * genuinely different things:
 *
 * <ul>
 *   <li>{@code sent} — SMTP accepted the message
 *   <li>{@code retrying} — an attempt failed but the delivery is still alive
 *   <li>{@code failed} — attempts are exhausted; nothing will retry it again
 * </ul>
 *
 * A rising {@code retrying} count with a flat {@code failed} count is a mail
 * server having a bad few minutes. A rising {@code failed} count is mail that
 * nobody is going to receive, which is the one worth noticing.
 *
 * <p>Recipient addresses are never used as labels. Beyond the cardinality
 * problem — one time series per address, forever — metrics are typically the
 * least access-controlled surface a system has, and email addresses are
 * personal data. The delivery row in the database already records who it was
 * for.
 *
 * <p>Incrementing these is an in-memory add. It performs no I/O and throws
 * nothing in normal operation, so it cannot turn a working delivery into a
 * failed one.
 */
@Component
public class NotificationMetrics {

    private final Counter sent;
    private final Counter retrying;
    private final Counter failed;

    public NotificationMetrics(MeterRegistry registry) {
        this.sent = counter(registry, "sent");
        this.retrying = counter(registry, "retrying");
        this.failed = counter(registry, "failed");
    }

    /**
     * All three are registered at startup so each series exists at zero. A
     * panel for a counter that has never incremented otherwise reads "No data",
     * which looks like a broken query rather than an absence of failures.
     */
    private static Counter counter(MeterRegistry registry, String status) {
        return Counter.builder("pulseguard.notification.delivery")
                .description("Notification email delivery attempts, by outcome")
                .tag("status", status)
                .register(registry);
    }

    public void sent() {
        sent.increment();
    }

    /** One attempt failed; the delivery will be tried again later. */
    public void retrying() {
        retrying.increment();
    }

    /** Attempts exhausted. This message will not be delivered. */
    public void failedPermanently() {
        failed.increment();
    }
}
