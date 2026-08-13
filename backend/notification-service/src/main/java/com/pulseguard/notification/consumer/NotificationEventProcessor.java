package com.pulseguard.notification.consumer;

import com.pulseguard.notification.domain.ConsumedEvent;
import com.pulseguard.notification.domain.NotificationDelivery;
import com.pulseguard.notification.email.EmailMessage;
import com.pulseguard.notification.email.IncidentEmailComposer;
import com.pulseguard.notification.enums.NotificationChannel;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import com.pulseguard.notification.recipient.ProjectRecipientRepository;
import com.pulseguard.notification.recipient.Recipient;
import com.pulseguard.notification.repository.ConsumedEventRepository;
import com.pulseguard.notification.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a consumed event into queued emails, exactly once.
 *
 * <p>Everything happens in one short database transaction, and
 * <strong>no mail server is contacted here</strong>. This class has no
 * {@code JavaMailSender}, which is what stops a broken SMTP host from making
 * Kafka replay incidents: once this commits, the event is dealt with as far as
 * Kafka is concerned, and sending is somebody else's problem on another
 * schedule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventProcessor {

    private final ConsumedEventRepository consumedEventRepository;
    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final ProjectRecipientRepository projectRecipientRepository;
    private final IncidentEmailComposer incidentEmailComposer;
    private final Clock clock;

    /**
     * Whether this event has already been dealt with.
     *
     * <p>Its own transaction, so it can be asked <em>after</em>
     * {@link #process} has rolled back — which is the one moment the answer is
     * interesting. See {@code IncidentEventConsumer} for why.
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        return consumedEventRepository.existsByEventId(eventId);
    }

    @Transactional
    public void process(IncidentLifecycleEvent event, ConsumerRecord<String, String> record) {
        // Kafka delivers at-least-once, so a repeat is expected rather than
        // exceptional. The unique constraint on event_id is the real guarantee;
        // this check just makes the common case a cheap read. Two consumers
        // racing on the same event both pass it, and one of them then loses at
        // commit — handled a level up, outside this transaction.
        if (consumedEventRepository.existsByEventId(event.eventId())) {
            log.info(
                    "Event already processed, ignoring redelivery: eventId={}, eventType={}, offset={}",
                    event.eventId(),
                    event.eventType(),
                    record.offset());
            return;
        }

        Instant now = Instant.now(clock);
        consumedEventRepository.save(new ConsumedEvent(
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.occurredAt(),
                event.incidentId(),
                event.projectId(),
                event.monitorId(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.value(),
                now));

        List<Recipient> recipients = projectRecipientRepository.findEnabledMembers(event.projectId());
        if (recipients.isEmpty()) {
            // Legitimate: the project may have been deleted, or every member
            // disabled, since the incident occurred. The event is still
            // recorded as processed — retrying forever would achieve nothing.
            log.warn(
                    "No enabled recipients for project {}; recording the event with no deliveries: eventId={}",
                    event.projectId(),
                    event.eventId());
            return;
        }

        // Composed once per event: the same message goes to everyone, and
        // storing it makes each delivery a snapshot of this moment.
        EmailMessage message = incidentEmailComposer.compose(event);

        for (Recipient recipient : recipients) {
            notificationDeliveryRepository.save(new NotificationDelivery(
                    event.eventId(),
                    event.incidentId(),
                    event.projectId(),
                    event.monitorId(),
                    recipient.email(),
                    NotificationChannel.EMAIL,
                    message.subject(),
                    message.body(),
                    now));
        }

        log.info(
                "Event consumed: eventId={}, eventType={}, incidentId={}, monitorId={}, deliveries={}",
                event.eventId(),
                event.eventType(),
                event.incidentId(),
                event.monitorId(),
                recipients.size());
    }
}
