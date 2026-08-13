package com.pulseguard.notification.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes the delivery service on its own cadence, independent of Kafka.
 *
 * <p>Everything is caught: an escaping exception would cancel the scheduled
 * task for the life of the process, so one unexpected failure would silently
 * stop all email long after the cause had gone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDeliveryScheduler {

    private final EmailDeliveryService emailDeliveryService;

    @Scheduled(
            fixedDelayString = "${app.notification.delivery-interval}",
            initialDelayString = "${app.notification.delivery-interval}")
    public void deliverPendingNotifications() {
        try {
            emailDeliveryService.deliverPending();
        } catch (Exception ex) {
            log.error("Email delivery cycle failed; will retry on the next tick", ex);
        }
    }
}
