package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import java.lang.reflect.Constructor;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds {@link Monitor} instances for tests.
 *
 * <p>The entity has no public constructor and no setters for its configuration
 * fields, because the worker only ever reads them — rows are created by the
 * Control API. Rather than widening that on the entity purely for tests, the
 * instance is created reflectively here and populated field by field.
 */
final class MonitorTestFactory {

    private MonitorTestFactory() {
    }

    static Monitor monitor(
            Long id,
            String url,
            MonitorStatus status,
            int consecutiveFailures,
            int intervalSeconds,
            int timeoutSeconds,
            int failureThreshold,
            Instant nextCheckAt) {

        Monitor monitor = newInstance();
        ReflectionTestUtils.setField(monitor, "id", id);
        ReflectionTestUtils.setField(monitor, "projectId", 10L);
        ReflectionTestUtils.setField(monitor, "name", "Payment API");
        ReflectionTestUtils.setField(monitor, "url", url);
        ReflectionTestUtils.setField(monitor, "expectedStatusCode", 200);
        ReflectionTestUtils.setField(monitor, "intervalSeconds", intervalSeconds);
        ReflectionTestUtils.setField(monitor, "timeoutSeconds", timeoutSeconds);
        ReflectionTestUtils.setField(monitor, "failureThreshold", failureThreshold);
        monitor.setCurrentStatus(status);
        monitor.setConsecutiveFailures(consecutiveFailures);
        monitor.setNextCheckAt(nextCheckAt);
        return monitor;
    }

    private static Monitor newInstance() {
        try {
            Constructor<Monitor> constructor = Monitor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not instantiate Monitor for tests", ex);
        }
    }
}
