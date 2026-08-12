package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.domain.Monitor;

/**
 * The configuration a single check needs, copied out of the entity.
 *
 * <p>The HTTP request happens outside any database transaction, so it must not
 * hold a managed entity: a detached snapshot keeps the persistence context
 * short-lived and makes it obvious that nothing is being lazily loaded across a
 * network call.
 */
public record MonitorSnapshot(
        Long id, String name, String url, int expectedStatusCode, int timeoutSeconds) {

    public static MonitorSnapshot of(Monitor monitor) {
        return new MonitorSnapshot(
                monitor.getId(),
                monitor.getName(),
                monitor.getUrl(),
                monitor.getExpectedStatusCode(),
                monitor.getTimeoutSeconds());
    }
}
