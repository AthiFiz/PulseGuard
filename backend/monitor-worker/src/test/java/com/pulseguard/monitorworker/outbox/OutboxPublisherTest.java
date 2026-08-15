package com.pulseguard.monitorworker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pulseguard.monitorworker.config.KafkaProperties;
import com.pulseguard.monitorworker.domain.OutboxEvent;
import com.pulseguard.monitorworker.enums.OutboxAggregateType;
import com.pulseguard.monitorworker.enums.OutboxEventType;
import com.pulseguard.monitorworker.repository.OutboxEventRepository;
import com.pulseguard.monitorworker.metrics.WorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The publisher, with a mocked {@link KafkaTemplate}. No broker is started and
 * none is needed — these tests are about what the publisher does with success
 * and failure, not about Kafka itself.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "pulseguard.incident-events.v1";
    private static final Instant NOW = Instant.parse("2026-08-13T07:00:00Z");

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        // A real registry rather than a mock: the counters are cheap, and a
        // real one would surface a duplicate-registration mistake here instead
        // of at runtime.
        publisher = new OutboxPublisher(
                outboxEventRepository,
                new WorkerMetrics(new SimpleMeterRegistry()),
                kafkaTemplate,
                new KafkaProperties(TOPIC, 3, (short) 1, Duration.ofSeconds(5), 50, Duration.ofSeconds(10)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -------------------------------------------------------------- success

    @Test
    void anAcknowledgedEventIsMarkedPublished() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        givenKafkaAccepts();

        publisher.publishPending();

        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        assertThat(event.getLastError()).isNull();
        verify(outboxEventRepository).save(event);
    }

    /** The record must carry the monitor id as its key, not the event id. */
    @Test
    void theRecordIsSentToTheConfiguredTopicKeyedByMonitor() {
        givenPending(pendingEvent(1L, OutboxEventType.INCIDENT_OPENED));
        givenKafkaAccepts();

        publisher.publishPending();

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topic.capture(), key.capture(), value.capture());

        assertThat(topic.getValue()).isEqualTo(TOPIC);
        assertThat(key.getValue()).isEqualTo("25");
        // The stored payload, published verbatim rather than rebuilt.
        assertThat(value.getValue()).isEqualTo("{\"eventId\":\"e-1\"}");
    }

    @Test
    void everyPendingEventIsPublishedOldestFirst() {
        givenPending(
                pendingEvent(1L, OutboxEventType.INCIDENT_OPENED),
                pendingEvent(2L, OutboxEventType.INCIDENT_RESOLVED),
                pendingEvent(3L, OutboxEventType.INCIDENT_OPENED));
        givenKafkaAccepts();

        publisher.publishPending();

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), values.capture());
        assertThat(values.getAllValues())
                .containsExactly("{\"eventId\":\"e-1\"}", "{\"eventId\":\"e-2\"}", "{\"eventId\":\"e-3\"}");
    }

    @Test
    void theBatchSizeLimitsHowMuchIsLoaded() {
        when(outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                .thenReturn(List.of());

        publisher.publishPending();

        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(outboxEventRepository).findByPublishedAtIsNullOrderByIdAsc(limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(50);
    }

    @Test
    void nothingPendingMeansNothingIsSent() {
        when(outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                .thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).save(any());
    }

    // -------------------------------------------------------------- failure

    @Test
    void aRejectedEventStaysPendingAndRecordsTheAttempt() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        givenKafkaFailsWith(new IllegalStateException("Broker unavailable"));

        publisher.publishPending();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(event.getLastError()).contains("Broker unavailable");
        verify(outboxEventRepository).save(event);
    }

    @Test
    void repeatedFailuresKeepCountingWithoutDroppingTheEvent() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        givenKafkaFailsWith(new IllegalStateException("Broker unavailable"));

        publisher.publishPending();
        publisher.publishPending();
        publisher.publishPending();

        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.isPublished()).isFalse();
    }

    /**
     * The important one. Events for a monitor are a sequence, so publishing #3
     * after #2 failed could deliver the end of an outage whose beginning never
     * arrived.
     */
    @Test
    void theCycleStopsAtTheFirstFailureRatherThanSkippingPastIt() {
        OutboxEvent first = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        OutboxEvent second = pendingEvent(2L, OutboxEventType.INCIDENT_RESOLVED);
        OutboxEvent third = pendingEvent(3L, OutboxEventType.INCIDENT_OPENED);
        givenPending(first, second, third);

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(acknowledged())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));

        publisher.publishPending();

        // The first went out, the second failed, the third was never attempted.
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isFalse();
        assertThat(third.isPublished()).isFalse();
        assertThat(third.getAttemptCount()).isZero();
    }

    /**
     * A broker problem must not kill the scheduled task, or publishing would
     * stop permanently long after the broker recovered.
     */
    @Test
    void aBrokerFailureNeverEscapesThePublisher() {
        givenPending(pendingEvent(1L, OutboxEventType.INCIDENT_OPENED));
        givenKafkaFailsWith(new IllegalStateException("Broker unavailable"));

        assertThatCode(() -> publisher.publishPending()).doesNotThrowAnyException();
    }

    /** Even a synchronous throw from send() is contained. */
    @Test
    void aFailureRaisedBySendItselfIsAlsoContained() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("No resolvable bootstrap urls"));

        assertThatCode(() -> publisher.publishPending()).doesNotThrowAnyException();
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.isPublished()).isFalse();
    }

    /** Stack traces are never stored; the column is bounded at 1000 characters. */
    @Test
    void aVeryLongFailureMessageIsTruncated() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        givenKafkaFailsWith(new IllegalStateException("x".repeat(5000)));

        publisher.publishPending();

        assertThat(event.getLastError()).hasSize(1000);
    }

    /** A retry after a failure clears the stale error rather than leaving it. */
    @Test
    void aSuccessfulRetryClearsThePreviousError() {
        OutboxEvent event = pendingEvent(1L, OutboxEventType.INCIDENT_OPENED);
        givenPending(event);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")))
                .thenReturn(acknowledged());

        publisher.publishPending();
        assertThat(event.getLastError()).isNotNull();

        publisher.publishPending();
        assertThat(event.isPublished()).isTrue();
        assertThat(event.getLastError()).isNull();
        // The attempt history is kept: it says the delivery was not first-time.
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- setup

    private void givenPending(OutboxEvent... events) {
        when(outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class)))
                .thenReturn(List.of(events));
    }

    private void givenKafkaAccepts() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(acknowledged());
    }

    private void givenKafkaFailsWith(Exception cause) {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(cause));
    }

    private static CompletableFuture<SendResult<String, String>> acknowledged() {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "25", "{}");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
    }

    private static OutboxEvent pendingEvent(Long id, OutboxEventType type) {
        OutboxEvent event = new OutboxEvent(
                "e-" + id,
                type,
                OutboxAggregateType.INCIDENT,
                41L,
                "25",
                "{\"eventId\":\"e-%d\"}".formatted(id),
                Instant.parse("2026-08-13T06:30:00Z"),
                Instant.parse("2026-08-13T06:30:01Z"));
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }
}
