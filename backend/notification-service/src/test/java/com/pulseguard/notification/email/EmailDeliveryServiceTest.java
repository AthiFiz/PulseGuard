package com.pulseguard.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.domain.NotificationDelivery;
import com.pulseguard.notification.enums.NotificationChannel;
import com.pulseguard.notification.enums.NotificationDeliveryStatus;
import com.pulseguard.notification.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sending, retrying, and giving up.
 *
 * <p>{@link JavaMailSender} is mocked, so no mail server is needed and no
 * message leaves the JVM.
 */
@ExtendWith(MockitoExtension.class)
class EmailDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T07:00:00Z");
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    @Mock
    private NotificationDeliveryRepository repository;

    @Mock
    private JavaMailSender mailSender;

    private EmailDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new EmailDeliveryService(
                repository,
                mailSender,
                new NotificationProperties(
                        "pulseguard@example.com",
                        "http://localhost:5173",
                        Duration.ofSeconds(10),
                        50,
                        MAX_ATTEMPTS,
                        RETRY_DELAY),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -------------------------------------------------------------- success

    @Test
    void anAcceptedMessageIsMarkedSent() {
        NotificationDelivery delivery = pending(1L, "ada@example.com");
        givenDue(delivery);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        service.deliverPending();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(delivery.getSentAt()).isEqualTo(NOW);
        assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).isNull();
        // Finished, so there is nothing to schedule.
        assertThat(delivery.getNextAttemptAt()).isNull();
        verify(repository).save(delivery);
    }

    @Test
    void theMessageCarriesTheStoredSubjectBodyAndRecipient() {
        givenDue(pending(1L, "ada@example.com"));
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        service.deliverPending();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("ada@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("pulseguard@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("[PulseGuard] Incident opened: Payment API");
        assertThat(sent.getValue().getText()).isEqualTo("PulseGuard detected an outage.");
    }

    @Test
    void nothingDueMeansNoMailIsSent() {
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any(), any()))
                .thenReturn(List.of());

        service.deliverPending();

        verifyNoInteractions(mailSender);
        verify(repository, never()).save(any());
    }

    /**
     * The query is what keeps a SENT message from being sent twice and a future
     * retry from being attempted early, so the arguments matter.
     */
    @Test
    void onlyPendingDeliveriesThatAreDueAreRequested() {
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any(), any()))
                .thenReturn(List.of());

        service.deliverPending();

        ArgumentCaptor<NotificationDeliveryStatus> status =
                ArgumentCaptor.forClass(NotificationDeliveryStatus.class);
        ArgumentCaptor<Instant> dueBy = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(repository)
                .findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        status.capture(), dueBy.capture(), limit.capture());

        assertThat(status.getValue()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(dueBy.getValue()).isEqualTo(NOW);
        assertThat(limit.getValue().max()).isEqualTo(50);
    }

    // -------------------------------------------------------------- failure

    @Test
    void aRejectedMessageStaysPendingAndIsRescheduled() {
        NotificationDelivery delivery = pending(1L, "ada@example.com");
        givenDue(delivery);
        givenMailFails("Mail server connection failed");

        service.deliverPending();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
        assertThat(delivery.getLastError()).contains("Mail server connection failed");
        assertThat(delivery.getSentAt()).isNull();
        verify(repository).save(delivery);
    }

    /** Out of attempts: left as FAILED, kept for inspection, never retried. */
    @Test
    void theLastAllowedAttemptTurnsIntoAPermanentFailure() {
        NotificationDelivery delivery = pending(1L, "ada@example.com");
        ReflectionTestUtils.setField(delivery, "attemptCount", MAX_ATTEMPTS - 1);
        givenDue(delivery);
        givenMailFails("Recipient rejected");

        service.deliverPending();

        assertThat(delivery.getAttemptCount()).isEqualTo(MAX_ATTEMPTS);
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        // Nothing will pick it up again: the due query only selects PENDING.
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastError()).contains("Recipient rejected");
    }

    @Test
    void anAttemptBeforeTheLastKeepsItRetryable() {
        NotificationDelivery delivery = pending(1L, "ada@example.com");
        ReflectionTestUtils.setField(delivery, "attemptCount", MAX_ATTEMPTS - 2);
        givenDue(delivery);
        givenMailFails("Temporary failure");

        service.deliverPending();

        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
    }

    /** Never a stack trace, and bounded by the column. */
    @Test
    void anEnormousErrorMessageIsTruncated() {
        NotificationDelivery delivery = pending(1L, "ada@example.com");
        givenDue(delivery);
        givenMailFails("x".repeat(5000));

        service.deliverPending();

        assertThat(delivery.getLastError()).hasSize(1000);
    }

    // ------------------------------------------------------ batch isolation

    /**
     * These messages are independent, so unlike the Monitor Worker's outbox
     * there is no ordering to protect — one bad address must not delay
     * everyone else's notification.
     */
    @Test
    void oneFailedRecipientDoesNotStopTheOthers() {
        NotificationDelivery first = pending(1L, "broken@example.com");
        NotificationDelivery second = pending(2L, "fine@example.com");
        NotificationDelivery third = pending(3L, "alsofine@example.com");
        givenDue(first, second, third);

        doThrow(new MailSendException("Recipient rejected"))
                .doNothing()
                .doNothing()
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        service.deliverPending();

        verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
        assertThat(first.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(second.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(third.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
    }

    /** A mail outage must not kill the scheduled task. */
    @Test
    void aMailFailureNeverEscapesTheService() {
        givenDue(pending(1L, "ada@example.com"));
        givenMailFails("Mail server connection failed");

        assertThatCode(() -> service.deliverPending()).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- setup

    private void givenDue(NotificationDelivery... deliveries) {
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any(), any()))
                .thenReturn(List.of(deliveries));
    }

    private void givenMailFails(String reason) {
        doThrow(new MailSendException(reason)).when(mailSender).send(any(SimpleMailMessage.class));
    }

    private static NotificationDelivery pending(Long id, String recipient) {
        NotificationDelivery delivery = new NotificationDelivery(
                "20e04086-2497-4e77-a380-8437e2277bbd",
                41L,
                10L,
                25L,
                recipient,
                NotificationChannel.EMAIL,
                "[PulseGuard] Incident opened: Payment API",
                "PulseGuard detected an outage.",
                Instant.parse("2026-08-13T06:59:00Z"));
        ReflectionTestUtils.setField(delivery, "id", id);
        return delivery;
    }
}
