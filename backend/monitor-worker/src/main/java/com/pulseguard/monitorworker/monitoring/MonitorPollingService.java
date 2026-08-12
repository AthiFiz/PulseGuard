package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.config.MonitoringProperties;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * One polling cycle: find the monitors that are due, check each one, store the
 * results.
 *
 * <p>The three phases are kept apart deliberately. Reading the due monitors is
 * a short read-only transaction that produces detached snapshots; the HTTP
 * requests then happen with no transaction open at all; each result is written
 * in its own short transaction afterwards. Checking a slow endpoint therefore
 * never holds a database connection hostage.
 *
 * <p>Monitors are processed sequentially. A single worker instance is assumed
 * at this stage — there is no locking, so running two workers against the same
 * database would double-check monitors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorPollingService {

    private final MonitorRepository monitorRepository;
    private final HttpHealthChecker httpHealthChecker;
    private final MonitorResultService monitorResultService;
    private final MonitoringProperties monitoringProperties;
    private final Clock clock;

    public void pollOnce() {
        List<MonitorSnapshot> dueMonitors = findDueMonitors();
        if (dueMonitors.isEmpty()) {
            return;
        }

        log.debug("Polling cycle: {} monitor(s) due", dueMonitors.size());

        for (MonitorSnapshot monitor : dueMonitors) {
            processSafely(monitor);
        }
    }

    /**
     * Reads the due monitors and copies them into snapshots straight away, so
     * nothing managed by a persistence context survives into the HTTP phase.
     *
     * <p>No {@code @Transactional} here on purpose. The repository call is
     * already transactional in its own right, and it would be misleading
     * anyway: this method is invoked from {@link #pollOnce()} inside the same
     * bean, and self-invocation never passes through the proxy that would apply
     * the annotation. Every mapped field is eager, so the returned entities are
     * fully populated once detached.
     */
    private List<MonitorSnapshot> findDueMonitors() {
        return monitorRepository
                .findDueMonitors(
                        MonitorStatus.PAUSED,
                        Instant.now(clock),
                        Limit.of(monitoringProperties.batchSize()))
                .stream()
                .map(MonitorSnapshot::of)
                .toList();
    }

    /**
     * One misbehaving monitor must not stop the rest of the cycle, and must not
     * kill the scheduled task permanently.
     */
    private void processSafely(MonitorSnapshot monitor) {
        try {
            HealthCheckResult result = httpHealthChecker.check(monitor);

            log.debug(
                    "Monitor check completed: monitorId={}, name={}, outcome={}, status={}, responseTimeMs={}",
                    monitor.id(),
                    monitor.name(),
                    result.outcome(),
                    result.httpStatusCode(),
                    result.responseTimeMs());

            monitorResultService.recordResult(monitor.id(), result);
        } catch (Exception ex) {
            log.error("Failed to process monitor {}; continuing with the rest", monitor.id(), ex);
        }
    }
}
