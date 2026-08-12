package com.pulseguard.monitorworker.monitoring;

import com.pulseguard.monitorworker.domain.Monitor;
import com.pulseguard.monitorworker.domain.MonitorCheck;
import com.pulseguard.monitorworker.enums.MonitorStatus;
import com.pulseguard.monitorworker.repository.MonitorCheckRepository;
import com.pulseguard.monitorworker.repository.MonitorRepository;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a completed check and moves the monitor's state on.
 *
 * <p>Runs in one short transaction, entered only after the HTTP request has
 * already finished — a database transaction is never held open across a network
 * call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorResultService {

    private final MonitorRepository monitorRepository;
    private final MonitorCheckRepository monitorCheckRepository;

    @Transactional
    public void recordResult(Long monitorId, HealthCheckResult result) {
        // Re-read rather than trusting the snapshot: the monitor may have been
        // paused, reconfigured, or deleted while the request was in flight.
        Optional<Monitor> found = monitorRepository.findById(monitorId);
        if (found.isEmpty()) {
            // Inserting the check would violate the foreign key, and there is
            // no state left to update. Drop it and carry on.
            log.info("Monitor {} disappeared before its result could be stored; discarding", monitorId);
            return;
        }

        Monitor monitor = found.get();

        monitorCheckRepository.save(new MonitorCheck(
                monitorId,
                result.checkedAt(),
                result.outcome(),
                result.httpStatusCode(),
                result.responseTimeMs(),
                result.errorType(),
                result.errorMessage()));

        if (monitor.getCurrentStatus() == MonitorStatus.PAUSED) {
            // Someone paused it while the request was running. The check itself
            // is kept, because it genuinely happened, but the pause is a
            // deliberate instruction and must not be undone: leaving
            // nextCheckAt null is what keeps it out of the queue.
            log.info("Monitor {} was paused during its check; state left untouched", monitorId);
            return;
        }

        applyOutcome(monitor, result);
    }

    private void applyOutcome(Monitor monitor, HealthCheckResult result) {
        MonitorStatus previousStatus = monitor.getCurrentStatus();

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
}
