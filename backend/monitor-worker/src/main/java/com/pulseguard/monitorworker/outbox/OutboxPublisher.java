package com.pulseguard.monitorworker.outbox;

import com.pulseguard.monitorworker.config.KafkaProperties;
import com.pulseguard.monitorworker.domain.OutboxEvent;
import com.pulseguard.monitorworker.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.pulseguard.monitorworker.metrics.WorkerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends pending outbox events to Kafka.
 *
 * <p>Deliberately separate from the monitoring engine. Writing an event and
 * delivering it are different problems with different failure modes: a check
 * result must be recorded now, while an event can arrive late without anyone
 * being misled.
 *
 * <p><strong>No transaction spans the Kafka send.</strong> The pending rows are
 * read in their own short transaction and come back detached; the network wait
 * happens with no database connection held; the result is written in another
 * short transaction. A broker that takes ten seconds to answer therefore costs
 * nothing but ten seconds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final WorkerMetrics metrics;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties kafkaProperties;
    private final Clock clock;

    /**
     * One publishing cycle.
     *
     * <p>Stops at the first failure rather than skipping past it. Events for one
     * monitor are a sequence — opened, then resolved — and delivering the
     * resolution of an outage whose beginning never arrived would tell a
     * consumer something untrue. Waiting is the safer half of that trade, and
     * the cost is head-of-line blocking, which is documented rather than hidden.
     */
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(
                Limit.of(kafkaProperties.outboxBatchSize()));

        if (pending.isEmpty()) {
            return;
        }

        log.debug("Outbox publish cycle: {} pending event(s)", pending.size());

        for (OutboxEvent event : pending) {
            if (!publish(event)) {
                log.warn(
                        "Stopping this outbox cycle at event {} to preserve ordering; {} event(s) still pending",
                        event.getEventId(),
                        pending.size() - pending.indexOf(event));
                return;
            }
        }
    }

    /** @return true if the broker acknowledged the record. */
    private boolean publish(OutboxEvent event) {
        try {
            // Waiting for the acknowledgement is the point: an event is only
            // published once a broker says so, never merely because send() was
            // called and returned a future.
            kafkaTemplate
                    .send(kafkaProperties.incidentTopic(), event.getPartitionKey(), event.getPayload())
                    .get(kafkaProperties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);

            markPublished(event);
            return true;

        } catch (InterruptedException ex) {
            // Shutting down. Restore the flag and leave the event pending —
            // it will be picked up by the next run of the process.
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing event {}; leaving it pending", event.getEventId());
            return false;

        } catch (Exception ex) {
            markFailed(event, ex);
            return false;
        }
    }

    private void markPublished(OutboxEvent event) {
        event.markPublished(Instant.now(clock));
        outboxEventRepository.save(event);
        metrics.outboxPublished(true);

        log.info(
                "Outbox event published: eventId={}, eventType={}, aggregateId={}, key={}",
                event.getEventId(),
                event.getEventType(),
                event.getAggregateId(),
                event.getPartitionKey());
    }

    /**
     * The event stays pending. Nothing is dropped and nothing gives up after N
     * attempts: the payload is generated entirely by PulseGuard, so a permanent
     * failure means something is wrong that a human should see rather than
     * something to discard quietly.
     */
    private void markFailed(OutboxEvent event, Exception cause) {
        event.markFailed(Instant.now(clock), describe(cause));
        outboxEventRepository.save(event);
        metrics.outboxPublished(false);

        log.warn(
                "Outbox publish failed: eventId={}, eventType={}, attempt={}, reason={}",
                event.getEventId(),
                event.getEventType(),
                event.getAttemptCount(),
                describe(cause));
    }

    /**
     * A short, safe description. Stack traces are never stored — they are
     * verbose, they can name internal classes and hosts, and the column is
     * bounded anyway.
     */
    private static String describe(Exception cause) {
        Throwable root = cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null
                ? cause.getCause()
                : cause;

        if (root instanceof TimeoutException) {
            return "Timed out waiting for the broker to acknowledge the record";
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : "%s: %s".formatted(root.getClass().getSimpleName(), message);
    }
}
