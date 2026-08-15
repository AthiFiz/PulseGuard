package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.domain.Incident;
import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.enums.IncidentStatus;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.outbox.IncidentEventRecorder;
import com.pulseguard.monitorworker.repository.IncidentRepository;
import com.pulseguard.monitorworker.repository.MonitorCheckRepository;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Duration;
import java.util.Optional;
import com.pulseguard.monitorworker.metrics.WorkerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a completed check, moves the monitor's state on, and maintains the
 * incident that represents the current outage.
 *
 * <p>Runs in one short transaction, entered only after the HTTP request has
 * already finished — a database transaction is never held open across a network
 * call. The check row, the monitor's new state, the incident change and the
 * outbox event announcing it are therefore committed together or not at all.
 *
 * <p>Nothing here talks to Kafka. An incident transition writes an outbox row
 * and stops; delivery is a separate concern with a separate schedule, so a
 * broker outage cannot stop a monitor being marked DOWN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorResultService {

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentEventRecorder incidentEventRecorder;
    private final WorkerMetrics metrics;

    @Transactional
    public void recordResult(Long monitorId, HealthCheckResult result) {
        // Re-read rather than trusting the snapshot: the monitor may have been
        // paused, reconfigured, or deleted while the request was in flight.
        Optional<Monitor> found = monitorRepository.findById(monitorId);
        if (found.isEmpty()) {
            // Inserting the check would violate the foreign key, and there is
            // no state left to update. Drop it and carry on. Any incident the
            // monitor had went with it when the row was deleted.
            log.info("Monitor {} disappeared before its result could be stored; discarding", monitorId);
            return;
        }

        Monitor monitor = found.get();

        MonitorCheck check = monitorCheckRepository.save(new MonitorCheck(
                monitorId,
                result.checkedAt(),
                result.outcome(),
                result.httpStatusCode(),
                result.responseTimeMs(),
                result.errorType(),
                result.errorMessage()));

        // Counted here rather than at the HTTP call, so the number always
        // matches what is actually in monitor_checks.
        metrics.checkRecorded(result.outcome());

        if (monitor.getCurrentStatus() == MonitorStatus.PAUSED) {
            // Someone paused it while the request was running. The check itself
            // is kept, because it genuinely happened, but the pause is a
            // deliberate instruction and must not be undone: leaving
            // nextCheckAt null is what keeps it out of the queue.
            //
            // An open incident is left open too. Pausing monitoring is not
            // evidence that the monitored service recovered, so only a genuine
            // successful check after the monitor is resumed may resolve it.
            log.info("Monitor {} was paused during its check; state left untouched", monitorId);
            return;
        }

        MonitorStatus previousStatus = monitor.getCurrentStatus();
        applyOutcome(monitor, result, previousStatus);
        applyIncidentLifecycle(monitor, previousStatus, result, check);
    }

    private void applyOutcome(Monitor monitor, HealthCheckResult result, MonitorStatus previousStatus) {
        if (result.isSuccess()) {
            monitor.setConsecutiveFailures(0);
            monitor.setCurrentStatus(MonitorStatus.UP);
        } else {
            int failures = monitor.getConsecutiveFailures() + 1;
            monitor.setConsecutiveFailures(failures);

            // Below the threshold the previous state is preserved, so a single
            // blip does not flip a healthy monitor to DOWN.
            if (failures >= monitor.getFailureThreshold()) {
                monitor.setCurrentStatus(MonitorStatus.DOWN);
            }
        }

        monitor.setLastCheckedAt(result.checkedAt());
        // Measured from the start of the check, not from now, so the schedule
        // does not drift by the duration of every request.
        monitor.setNextCheckAt(result.checkedAt().plus(Duration.ofSeconds(monitor.getIntervalSeconds())));

        if (previousStatus != monitor.getCurrentStatus()) {
            log.info(
                    "Monitor status changed {} -> {}: monitorId={}, name={}",
                    previousStatus,
                    monitor.getCurrentStatus(),
                    monitor.getId(),
                    monitor.getName());
        }
    }

    /**
     * Opens or resolves the monitor's incident.
     *
     * <p>An incident represents one continuous outage, so it is keyed to the
     * monitor's state rather than to individual checks: reaching DOWN opens
     * one, every further failure leaves it alone, and the next success ends it.
     */
    private void applyIncidentLifecycle(
            Monitor monitor, MonitorStatus previousStatus, HealthCheckResult result, MonitorCheck check) {

        if (result.isSuccess()) {
            resolveOpenIncident(monitor, check);
            return;
        }

        if (monitor.getCurrentStatus() == MonitorStatus.DOWN) {
            openIncidentUnlessAlreadyOpen(monitor, previousStatus, check);
        }
        // A failure below the threshold leaves the monitor UP or UNKNOWN. No
        // outage has been declared, so there is nothing to record.
    }

    /**
     * Ends the outage on any successful check, not only on a DOWN to UP move.
     *
     * <p>The wider rule matters because a monitor can leave DOWN without
     * recovering: pausing and resuming it lands on UNKNOWN, and the incident
     * opened by the original outage is still the truth until something actually
     * responds. Tying resolution to the success itself keeps that case correct.
     */
    private void resolveOpenIncident(Monitor monitor, MonitorCheck check) {
        Optional<Incident> open = findOpenIncident(monitor.getId());
        if (open.isEmpty()) {
            return;
        }

        Incident incident = open.get();
        incident.resolve(check.getCheckedAt(), check.getId());
        incidentRepository.save(incident);
        incidentEventRecorder.recordIncidentResolved(monitor, incident, check);
        metrics.incidentResolved();

        log.info(
                "Incident resolved: incidentId={}, monitorId={}, name={}, openedAt={}, resolvedAt={}",
                incident.getId(),
                monitor.getId(),
                monitor.getName(),
                incident.getOpenedAt(),
                incident.getResolvedAt());
    }

    private void openIncidentUnlessAlreadyOpen(
            Monitor monitor, MonitorStatus previousStatus, MonitorCheck check) {

        if (findOpenIncident(monitor.getId()).isPresent()) {
            // Already DOWN with its incident recorded. Further failures belong
            // to the same outage and must not create a second one.
            return;
        }

        if (previousStatus == MonitorStatus.DOWN) {
            // The monitor was already DOWN yet had no open incident. That
            // should not happen while this worker is the only writer, so it
            // points at data from an interrupted run or an older environment.
            // Opening one now is better than leaving the outage unrecorded.
            log.warn(
                    "Monitor {} was already DOWN with no open incident; opening one from check {}",
                    monitor.getId(),
                    check.getId());
        }

        Incident incident = incidentRepository.save(
                new Incident(monitor.getId(), check.getCheckedAt(), check.getId()));
        incidentEventRecorder.recordIncidentOpened(monitor, incident, check);
        metrics.incidentOpened();

        log.info(
                "Incident opened: incidentId={}, monitorId={}, name={}, openedAt={}",
                incident.getId(),
                monitor.getId(),
                monitor.getName(),
                incident.getOpenedAt());
    }

    private Optional<Incident> findOpenIncident(Long monitorId) {
        return incidentRepository.findFirstByMonitorIdAndStatusOrderByOpenedAtAsc(
                monitorId, IncidentStatus.OPEN);
    }
}
