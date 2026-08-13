package com.pulseguard.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.notification.domain.ConsumedEvent;
import com.pulseguard.notification.domain.NotificationDelivery;
import com.pulseguard.notification.email.EmailMessage;
import com.pulseguard.notification.email.IncidentEmailComposer;
import com.pulseguard.notification.enums.IncidentEventType;
import com.pulseguard.notification.enums.MonitorCheckErrorType;
import com.pulseguard.notification.enums.MonitorStatus;
import com.pulseguard.notification.enums.NotificationChannel;
import com.pulseguard.notification.enums.NotificationDeliveryStatus;
import com.pulseguard.notification.event.IncidentLifecycleEvent;
import com.pulseguard.notification.recipient.ProjectRecipientRepository;
import com.pulseguard.notification.recipient.Recipient;
import com.pulseguard.notification.repository.ConsumedEventRepository;
import com.pulseguard.notification.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Consuming an event exactly once, whatever Kafka does.
 *
 * <p>No Kafka, no database and no mail server: the processor's collaborators
 * are all mocked, which is enough because the questions here are about what it
 * decides, not about how anything is stored.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventProcessorTest {

    private static final String TOPIC = "pulseguard.incident-events.v1";
    private static final String EVENT_ID = "20e04086-2497-4e77-a380-8437e2277bbd";
    private static final Instant NOW = Instant.parse("2026-08-13T06:30:05Z");

    @Mock
    private ConsumedEventRepository consumedEventRepository;

    @Mock
    private NotificationDeliveryRepository notificationDeliveryRepository;

    @Mock
    private ProjectRecipientRepository projectRecipientRepository;

    @Mock
    private IncidentEmailComposer incidentEmailComposer;

    private NotificationEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationEventProcessor(
                consumedEventRepository,
                notificationDeliveryRepository,
                projectRecipientRepository,
                incidentEmailComposer,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------- the happy path

    @Test
    void aNewEventIsRecordedInTheInbox() {
        givenNotYetConsumed();
        givenRecipients(recipient("ada@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 0, 42L));

        ConsumedEvent saved = capturedEvent();
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getEventType()).isEqualTo(IncidentEventType.INCIDENT_OPENED);
        assertThat(saved.getSchemaVersion()).isEqualTo(1);
        assertThat(saved.getIncidentId()).isEqualTo(41L);
        assertThat(saved.getProjectId()).isEqualTo(10L);
        assertThat(saved.getMonitorId()).isEqualTo(25L);
        assertThat(saved.getConsumedAt()).isEqualTo(NOW);
        // The business timestamp, not the consumption time.
        assertThat(saved.getOccurredAt()).isEqualTo(Instant.parse("2026-08-13T06:30:00Z"));
    }

    /** Kept verbatim, so an old notification can be explained by what arrived. */
    @Test
    void theRawPayloadIsStoredAsReceived() {
        givenNotYetConsumed();
        givenRecipients(recipient("ada@example.com"));
        givenComposedMessage();

        ConsumerRecord<String, String> record = record(EVENT_ID, 0, 42L);
        processor.process(openedEvent(), record);

        assertThat(capturedEvent().getRawPayload()).isEqualTo(record.value());
    }

    @Test
    void theKafkaCoordinatesAreRecordedForTraceability() {
        givenNotYetConsumed();
        givenRecipients(recipient("ada@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 2, 907L));

        ConsumedEvent saved = capturedEvent();
        assertThat(saved.getKafkaTopic()).isEqualTo(TOPIC);
        assertThat(saved.getKafkaPartition()).isEqualTo(2);
        assertThat(saved.getKafkaOffset()).isEqualTo(907L);
    }

    @Test
    void oneDeliveryIsQueuedPerRecipient() {
        givenNotYetConsumed();
        givenRecipients(
                recipient("admin@example.com"),
                recipient("viewer@example.com"),
                recipient("third@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 0, 1L));

        List<NotificationDelivery> deliveries = capturedDeliveries(3);
        assertThat(deliveries).extracting(NotificationDelivery::getRecipientEmail)
                .containsExactly("admin@example.com", "viewer@example.com", "third@example.com");
        assertThat(deliveries).allSatisfy(delivery -> {
            assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
            assertThat(delivery.getChannel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(delivery.getAttemptCount()).isZero();
            // Due immediately: the scheduler picks it up on its next tick.
            assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(delivery.getSentAt()).isNull();
        });
    }

    /**
     * Everyone in the project is notified regardless of role — a viewer is
     * still someone who cares that the service is down.
     */
    @Test
    void bothProjectAdminsAndViewersAreNotified() {
        givenNotYetConsumed();
        givenRecipients(recipient("project-admin@example.com"), recipient("viewer@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 0, 1L));

        assertThat(capturedDeliveries(2)).extracting(NotificationDelivery::getRecipientEmail)
                .containsExactlyInAnyOrder("project-admin@example.com", "viewer@example.com");
    }

    /** Composed once per event, not once per recipient. */
    @Test
    void theSameMessageGoesToEveryone() {
        givenNotYetConsumed();
        givenRecipients(recipient("a@example.com"), recipient("b@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 0, 1L));

        verify(incidentEmailComposer, times(1)).compose(any());
        assertThat(capturedDeliveries(2)).allSatisfy(delivery -> {
            assertThat(delivery.getSubject()).isEqualTo("[PulseGuard] Incident opened: Payment API");
            assertThat(delivery.getBody()).contains("PulseGuard detected an outage.");
        });
    }

    // ------------------------------------------------------- idempotency

    /**
     * The central Task 10 case. Kafka delivers at-least-once, so a repeat is
     * expected rather than exceptional.
     */
    @Test
    void aRedeliveredEventCreatesNothing() {
        when(consumedEventRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        processor.process(openedEvent(), record(EVENT_ID, 0, 42L));

        verify(consumedEventRepository, never()).save(any());
        verify(notificationDeliveryRepository, never()).save(any());
        // Not even looked up: nothing about a duplicate needs recipients.
        verify(projectRecipientRepository, never()).findEnabledMembers(any());
        verify(incidentEmailComposer, never()).compose(any());
    }

    /**
     * The redelivery arrives at a different offset, which proves identity comes
     * from the event and not from its position in the log.
     */
    @Test
    void theSameEventAtADifferentOffsetIsStillADuplicate() {
        when(consumedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false, true);
        givenRecipients(recipient("ada@example.com"));
        givenComposedMessage();

        processor.process(openedEvent(), record(EVENT_ID, 0, 42L));
        processor.process(openedEvent(), record(EVENT_ID, 0, 128L));

        verify(consumedEventRepository, times(1)).save(any());
        verify(notificationDeliveryRepository, times(1)).save(any());
    }

    /** A resolution is a different event, and gets its own notification. */
    @Test
    void aDifferentEventForTheSameIncidentIsNotADuplicate() {
        // A different eventId, even though the incident is the same one.
        when(consumedEventRepository.existsByEventId("0ff79604-749f-4cbb-b832-cd5d36b85712"))
                .thenReturn(false);
        givenRecipients(recipient("ada@example.com"));
        when(incidentEmailComposer.compose(any()))
                .thenReturn(new EmailMessage("[PulseGuard] Incident resolved: Payment API", "recovered"));

        processor.process(resolvedEvent(), record("0ff79604-749f-4cbb-b832-cd5d36b85712", 0, 43L));

        assertThat(capturedEvent().getEventType()).isEqualTo(IncidentEventType.INCIDENT_RESOLVED);
        verify(notificationDeliveryRepository).save(any());
    }

    // ---------------------------------------------------- no recipients

    /**
     * Legitimate: the project may have been deleted, or every member disabled,
     * since the incident happened. Recording the event still matters — retrying
     * forever would achieve nothing.
     */
    @Test
    void anEventWithNoRecipientsIsStillRecordedAndDoesNotFail() {
        givenNotYetConsumed();
        givenRecipients();

        processor.process(openedEvent(), record(EVENT_ID, 0, 42L));

        verify(consumedEventRepository).save(any());
        verify(notificationDeliveryRepository, never()).save(any());
        // Nothing to send, so nothing is composed.
        verify(incidentEmailComposer, never()).compose(any());
    }

    // ---------------------------------------------------------------- setup

    private void givenNotYetConsumed() {
        when(consumedEventRepository.existsByEventId(EVENT_ID)).thenReturn(false);
    }

    private void givenRecipients(Recipient... recipients) {
        when(projectRecipientRepository.findEnabledMembers(10L)).thenReturn(List.of(recipients));
    }

    private void givenComposedMessage() {
        when(incidentEmailComposer.compose(any())).thenReturn(new EmailMessage(
                "[PulseGuard] Incident opened: Payment API", "PulseGuard detected an outage.\n"));
    }

    private ConsumedEvent capturedEvent() {
        ArgumentCaptor<ConsumedEvent> captor = ArgumentCaptor.forClass(ConsumedEvent.class);
        verify(consumedEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private List<NotificationDelivery> capturedDeliveries(int expected) {
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(notificationDeliveryRepository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private static Recipient recipient(String email) {
        return new Recipient(email, "Someone");
    }

    private static ConsumerRecord<String, String> record(String eventId, int partition, long offset) {
        return new ConsumerRecord<>(TOPIC, partition, offset, "25", payloadFor(eventId));
    }

    private static String payloadFor(String eventId) {
        return "{\"schemaVersion\":1,\"eventId\":\"%s\",\"eventType\":\"INCIDENT_OPENED\"}".formatted(eventId);
    }

    private static IncidentLifecycleEvent openedEvent() {
        return new IncidentLifecycleEvent(
                1, EVENT_ID, IncidentEventType.INCIDENT_OPENED,
                Instant.parse("2026-08-13T06:30:00Z"),
                41L, 10L, 25L, "Payment API",
                Instant.parse("2026-08-13T06:30:00Z"), null,
                1501L, MonitorStatus.DOWN,
                503, 210, MonitorCheckErrorType.UNEXPECTED_STATUS, "Expected HTTP 200 but received 503");
    }

    private static IncidentLifecycleEvent resolvedEvent() {
        return new IncidentLifecycleEvent(
                1, "0ff79604-749f-4cbb-b832-cd5d36b85712", IncidentEventType.INCIDENT_RESOLVED,
                Instant.parse("2026-08-13T06:42:00Z"),
                41L, 10L, 25L, "Payment API",
                Instant.parse("2026-08-13T06:30:00Z"), Instant.parse("2026-08-13T06:42:00Z"),
                1525L, MonitorStatus.UP,
                200, 93, null, null);
    }
}
