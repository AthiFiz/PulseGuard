package com.pulseguard.notification.consumer;

import com.pulseguard.notification.event.IncidentEventParser;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

        notificationEventProcessor.process(event, record);
    }
}
