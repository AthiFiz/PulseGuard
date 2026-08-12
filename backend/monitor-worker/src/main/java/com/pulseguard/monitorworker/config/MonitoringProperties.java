package com.pulseguard.monitorworker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the worker polls, and what it is allowed to talk to.
 *
 * @param pollInterval how often to look for due monitors. This is not a
 *     monitor's own interval — the scheduler wakes on this cadence and checks
 *     only the monitors whose {@code nextCheckAt} has passed.
 * @param batchSize the most monitors one polling cycle will process, so a large
 *     backlog cannot be pulled into memory at once.
 * @param allowPrivateAddresses whether destinations on loopback or private
 *     networks may be monitored. False by default: the worker makes requests to
 *     user-supplied URLs, which would otherwise be a way to reach internal
 *     services. Enable it for local development only.
 */
@ConfigurationProperties(prefix = "app.monitoring")
public record MonitoringProperties(
        Duration pollInterval, int batchSize, boolean allowPrivateAddresses) {
}
