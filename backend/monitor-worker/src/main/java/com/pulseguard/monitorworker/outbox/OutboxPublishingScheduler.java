package com.pulseguard.monitorworker.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes the outbox publisher on its own cadence.
 *
 * <p>Kept separate from {@code MonitorPollingScheduler} so the two jobs have
 * separate schedules and separate failures. The scheduler pool is sized for
 * more than one thread (see {@code application.yml}), so a publishing cycle
 * waiting on a slow broker cannot delay the next monitor poll.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublishingScheduler {

    private final OutboxPublisher outboxPublisher;

    /**
     * An escaping exception would cancel the scheduled task for the life of the
     * process — a broker problem would then permanently stop publishing, long
     * after the broker recovered. Everything is caught so the next tick retries.
     */
    @Scheduled(
            fixedDelayString = "${app.kafka.outbox-publish-interval}",
            initialDelayString = "${app.kafka.outbox-publish-interval}")
    public void publishPendingEvents() {
        try {
            outboxPublisher.publishPending();
        } catch (Exception ex) {
            log.error("Outbox publishing cycle failed; will retry on the next tick", ex);
        }
    }
}
