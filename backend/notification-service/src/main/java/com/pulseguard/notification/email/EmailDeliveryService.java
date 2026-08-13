package com.pulseguard.notification.email;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.domain.NotificationDelivery;
import com.pulseguard.notification.enums.NotificationDeliveryStatus;
import com.pulseguard.notification.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the emails that are waiting.
 *
 * <p>Separated from the Kafka listener on purpose. A mail server is the least
 * reliable thing PulseGuard talks to, and its problems must not become Kafka's
 * problems: by the time a delivery reaches this class, the event that caused it
 * has already been committed and acknowledged.
 *
 * <p><strong>No transaction spans the send.</strong> Due deliveries are read in
 * their own short transaction and come back detached; the SMTP conversation
 * happens with no database connection held; the outcome is written afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailDeliveryService {

    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final JavaMailSender mailSender;
    private final NotificationProperties notificationProperties;
    private final Clock clock;

    /**
     * One delivery cycle.
     *
     * <p>Unlike the Monitor Worker's outbox publisher, this does <em>not</em>
     * stop at the first failure. These messages are independent — one bad
     * address must not delay everyone else's notification — and there is no
     * ordering to preserve between recipients.
     */
    public void deliverPending() {
        Instant now = Instant.now(clock);

        List<NotificationDelivery> due =
                notificationDeliveryRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        NotificationDeliveryStatus.PENDING,
                        now,
                        Limit.of(notificationProperties.batchSize()));

        if (due.isEmpty()) {
            return;
        }

        log.debug("Email delivery cycle: {} delivery/deliveries due", due.size());

        for (NotificationDelivery delivery : due) {
            deliver(delivery);
        }
    }

    private void deliver(NotificationDelivery delivery) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(notificationProperties.fromAddress());
            message.setTo(delivery.getRecipientEmail());
            message.setSubject(delivery.getSubject());
            message.setText(delivery.getBody());

            mailSender.send(message);

            // The mail server accepted it. That is the strongest statement SMTP
            // permits — not that anyone received or read it.
            delivery.markSent(Instant.now(clock));
            notificationDeliveryRepository.save(delivery);

            log.info(
                    "Notification email sent: deliveryId={}, eventId={}, incidentId={}, attempt={}",
                    delivery.getId(),
                    delivery.getEventId(),
                    delivery.getIncidentId(),
                    delivery.getAttemptCount());

        } catch (Exception ex) {
            recordFailure(delivery, ex);
        }
    }

    private void recordFailure(NotificationDelivery delivery, Exception cause) {
        delivery.recordFailedAttempt(
                Instant.now(clock),
                describe(cause),
                notificationProperties.maxAttempts(),
                notificationProperties.retryDelay());
        notificationDeliveryRepository.save(delivery);

        if (delivery.getStatus() == NotificationDeliveryStatus.FAILED) {
            // Out of attempts. The row stays for inspection rather than being
            // deleted, and nothing will retry it automatically again.
            log.error(
                    "Notification email failed permanently after {} attempts: deliveryId={}, eventId={}, reason={}",
                    delivery.getAttemptCount(),
                    delivery.getId(),
                    delivery.getEventId(),
                    describe(cause));
        } else {
            log.warn(
                    "Notification email attempt {} failed, retrying at {}: deliveryId={}, reason={}",
                    delivery.getAttemptCount(),
                    delivery.getNextAttemptAt(),
                    delivery.getId(),
                    describe(cause));
        }
    }

    /**
     * A short, safe description. Stack traces are never stored, and a mail
     * server's message can quote the credentials it rejected — so only the
     * exception type and its own message are kept, bounded by the column.
     */
    private static String describe(Exception cause) {
        Throwable root = cause.getCause() != null ? cause.getCause() : cause;
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : "%s: %s".formatted(root.getClass().getSimpleName(), message);
    }
}
