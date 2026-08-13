package com.pulseguard.notification.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.notification.event.IncidentEventParser;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import com.pulseguard.notification.event.UnsupportedIncidentEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the listener does when the database refuses a write.
 *
 * <p>The inbox check inside {@link NotificationEventProcessor} catches the
 * ordinary redelivery. What it cannot catch is two consumers holding the same
 * event at once: both read "not present", both insert, and one loses on the
 * unique constraint. That loss means the work was done — but the same exception
 * type also covers real integrity faults, which must not be swallowed.
 *
 * <p>The parser is real rather than mocked; it is pure and its output is
 * exactly what the code under test is meant to pass along.
 */
@ExtendWith(MockitoExtension.class)
class IncidentEventConsumerTest {

    private static final String EVENT_ID = "20e04086-2497-4e77-a380-8437e2277bbd";

    private static final String PAYLOAD =
            """
            {"schemaVersion":1,"eventId":"%s","eventType":"INCIDENT_OPENED",\
            "occurredAt":"2026-08-13T06:30:00Z","incidentId":41,"projectId":10,\
            "monitorId":25,"monitorName":"Payment API",\
            "incidentOpenedAt":"2026-08-13T06:30:00Z","incidentResolvedAt":null,\
            "triggeringCheckId":1501,"monitorStatus":"DOWN","httpStatusCode":503,\
            "responseTimeMs":210,"errorType":"UNEXPECTED_STATUS",\
            "errorMessage":"Expected HTTP 200 but received 503"}"""
                    .formatted(EVENT_ID);

    @Mock
    private NotificationEventProcessor notificationEventProcessor;

    private final IncidentEventParser parser = new IncidentEventParser(JsonMapper.builder().build());

    private IncidentEventConsumer consumer() {
        return new IncidentEventConsumer(parser, notificationEventProcessor);
    }

    // ------------------------------------------------------------ ordinary

    @Test
    void aWellFormedEventIsHandedToTheProcessor() {
        ConsumerRecord<String, String> record = record();

        consumer().onIncidentEvent(record);

        verify(notificationEventProcessor).process(any(IncidentLifecycleEvent.class), eq(record));
    }

    /**
     * A payload this service cannot read is not made retryable by trying again,
     * and nothing should reach the processor on the strength of a guess.
     */
    @Test
    void anUnreadablePayloadNeverReachesTheProcessor() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("pulseguard.incident-events.v1", 0, 7L, "25", "not json");

        assertThatThrownBy(() -> consumer().onIncidentEvent(record))
                .isInstanceOf(UnsupportedIncidentEventException.class);

        verify(notificationEventProcessor, never()).process(any(), any());
    }

    // ---------------------------------------------------------------- race

    /**
     * Losing the race is success by another route: the row is there, so the
     * event has been dealt with and the offset may be committed.
     */
    @Test
    void aLostRaceIsTreatedAsAlreadyProcessed() {
        doThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_consumed_event_event_id'"))
                .when(notificationEventProcessor)
                .process(any(), any());
        when(notificationEventProcessor.isAlreadyProcessed(EVENT_ID)).thenReturn(true);

        consumer().onIncidentEvent(record());

        // Returning normally is the assertion: the container commits the offset
        // instead of replaying the event forever.
    }

    /**
     * The distinguishing case. A constraint failure with no recorded event is
     * not a duplicate — a subject line too long for its column would look
     * identical from here — and swallowing it would lose the notification
     * silently.
     */
    @Test
    void anIntegrityFailureThatIsNotADuplicateIsRethrown() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("Data too long for column 'subject'");
        doThrow(failure).when(notificationEventProcessor).process(any(), any());
        when(notificationEventProcessor.isAlreadyProcessed(EVENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> consumer().onIncidentEvent(record())).isSameAs(failure);
    }

    /** Anything else is untouched, and must reach the container's retry handler. */
    @Test
    void anUnrelatedFailureIsNotInspectedAtAll() {
        doThrow(new IllegalStateException("connection pool exhausted"))
                .when(notificationEventProcessor)
                .process(any(), any());

        assertThatThrownBy(() -> consumer().onIncidentEvent(record()))
                .isInstanceOf(IllegalStateException.class);

        verify(notificationEventProcessor, never()).isAlreadyProcessed(any());
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("pulseguard.incident-events.v1", 0, 42L, "25", PAYLOAD);
    }
}
