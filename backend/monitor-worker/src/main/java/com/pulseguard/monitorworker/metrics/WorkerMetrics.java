package com.pulseguard.monitorworker.metrics;

import com.pulseguard.monitorworker.enums.MonitorCheckOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The handful of PulseGuard-specific counters worth putting on a dashboard.
 *
 * <p>Micrometer already supplies HTTP, JVM, GC and connection-pool metrics
 * without any code here. What it cannot know is what this application is
 * <em>for</em>: how many checks ran, whether they passed, and whether outages
 * were detected and announced. These four counters answer that, and nothing
 * more — a metric that nobody would look at during an incident is a metric
 * that costs cardinality and attention for nothing.
 *
 * <h2>Why the labels are so plain</h2>
 *
 * Every label value here is drawn from a small fixed set: two outcomes, two
 * publish results. Deliberately absent are {@code monitorId}, {@code projectId},
 * incident ids, URLs and email addresses. Prometheus creates one time series
 * per distinct label combination, so labelling by monitor id would mean a new
 * series per monitor forever — the classic cardinality explosion that makes a
 * Prometheus instance fall over. Those identifiers already live in the database
 * and in the logs, which is the right place to answer "which one?".
 *
 * <h2>Why these calls cannot break monitoring</h2>
 *
 * Incrementing a Micrometer counter is a lock-free add to an in-memory number.
 * It does no I/O and throws nothing in normal operation, so these calls sit
 * safely inside the same transaction as the business writes without adding a
 * failure mode. Nothing here is allowed to change what PulseGuard records.
 */
@Component
public class WorkerMetrics {

    private final Counter checksSuccess;
    private final Counter checksFailure;
    private final Counter incidentsOpened;
    private final Counter incidentsResolved;
    private final Counter outboxPublishSuccess;
    private final Counter outboxPublishFailure;

    /**
     * Counters are registered once at startup rather than looked up per event.
     * A counter that is only created on first use is missing from the scrape
     * until it happens, and a panel querying it shows "No data" — which reads
     * as a broken dashboard rather than as "this has not occurred yet".
     * Registering here means every series exists at zero from the start.
     */
    public WorkerMetrics(MeterRegistry registry) {
        this.checksSuccess = Counter.builder("pulseguard.monitor.checks")
                .description("Monitor HTTP checks executed, by outcome")
                .tag("result", "SUCCESS")
                .register(registry);
        this.checksFailure = Counter.builder("pulseguard.monitor.checks")
                .description("Monitor HTTP checks executed, by outcome")
                .tag("result", "FAILURE")
                .register(registry);

        this.incidentsOpened = Counter.builder("pulseguard.incidents.opened")
                .description("Incidents opened after a monitor crossed its failure threshold")
                .register(registry);
        this.incidentsResolved = Counter.builder("pulseguard.incidents.resolved")
                .description("Incidents resolved after a monitor recovered")
                .register(registry);

        this.outboxPublishSuccess = Counter.builder("pulseguard.outbox.publish")
                .description("Outbox events published to Kafka, by result")
                .tag("result", "success")
                .register(registry);
        this.outboxPublishFailure = Counter.builder("pulseguard.outbox.publish")
                .description("Outbox events published to Kafka, by result")
                .tag("result", "failure")
                .register(registry);
    }

    public void checkRecorded(MonitorCheckOutcome outcome) {
        if (outcome == MonitorCheckOutcome.SUCCESS) {
            checksSuccess.increment();
        } else {
            checksFailure.increment();
        }
    }

    public void incidentOpened() {
        incidentsOpened.increment();
    }

    public void incidentResolved() {
        incidentsResolved.increment();
    }

    public void outboxPublished(boolean success) {
        if (success) {
            outboxPublishSuccess.increment();
        } else {
            outboxPublishFailure.increment();
        }
    }
}
