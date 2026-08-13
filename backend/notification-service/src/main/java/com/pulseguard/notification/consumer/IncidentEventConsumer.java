package com.pulseguard.notification.consumer;

import com.pulseguard.notification.event.IncidentEventParser;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The Kafka listener. Parses, delegates, and does nothing else.
 *
 * <p>It sends no email — deliberately, and visibly: there is no
 * {@code JavaMailSender} in this class or in anything it calls. Consuming an
 * event and delivering an email are separate jobs with separate failure modes,
 * and mixing them would let a mail outage turn into a Kafka replay.
 *
 * <p>Exceptions are allowed to escape. The container's error handler retries,
 * and the offset is not committed until this method returns normally — so a
 * database being briefly unavailable delays the event rather than losing it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEventConsumer {

    private final IncidentEventParser incidentEventParser;
    private final NotificationEventProcessor notificationEventProcessor;

    @KafkaListener(
            topics = "${app.kafka.incident-topic}",
            groupId = "${app.kafka.group-id}")
    public void onIncidentEvent(ConsumerRecord<String, String> record) {
        // The payload is deliberately consumed as a String and parsed here,
        // rather than by a typed deserializer: a failure inside the container's
        // deserializer is far harder to describe, and the raw text has to be
        // kept anyway.
        IncidentLifecycleEvent event = incidentEventParser.parse(record.value());

        log.debug(
                "Incident event received: eventId={}, eventType={}, topic={}, partition={}, offset={}",
                event.eventId(),
                event.eventType(),
                record.topic(),
                record.partition(),
                record.offset());

        processOnceEvenIfAnotherConsumerIsRacing(event, record);
    }

    /**
     * Handles the one duplicate the inbox check cannot catch.
     *
     * <p>{@code process} reads {@code consumed_event} and then writes to it. Two
     * consumers holding the same event — a rebalance replaying an uncommitted
     * offset, or a second instance starting mid-flight — can both read "not
     * present" before either has committed. One then loses at commit, on the
     * unique constraint that exists precisely for this.
     *
     * <p>Losing that race means the work was done, so it is not an error. But
     * the same exception type also covers real integrity faults — a column too
     * short for a subject line, say — and swallowing those would drop
     * notifications silently. So the event is looked up again: if the row is
     * genuinely there, this was a duplicate; if it is not, the failure was
     * something else and is rethrown for the container to retry.
     *
     * <p>The catch has to sit out here. Inside {@code process} the transaction
     * is already doomed, and with JPA the constraint may not even surface until
     * commit — which happens after that method returns.
     */
    private void processOnceEvenIfAnotherConsumerIsRacing(
            IncidentLifecycleEvent event, ConsumerRecord<String, String> record) {
        try {
            notificationEventProcessor.process(event, record);
        } catch (DataIntegrityViolationException ex) {
            if (!notificationEventProcessor.isAlreadyProcessed(event.eventId())) {
                throw ex;
            }
            log.info(
                    "Another consumer recorded this event first, ignoring duplicate: "
                            + "eventId={}, eventType={}, partition={}, offset={}",
                    event.eventId(),
                    event.eventType(),
                    record.partition(),
                    record.offset());
        }
    }
}
