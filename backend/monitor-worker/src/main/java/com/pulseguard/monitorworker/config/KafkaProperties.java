package com.pulseguard.monitorworker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How incident events reach Kafka.
 *
 * <p>Broker addresses and producer tuning stay under {@code spring.kafka},
 * where Boot's own auto-configuration reads them. This covers only the parts
 * that are PulseGuard's decisions.
 *
 * @param incidentTopic where incident lifecycle events are published. The
 *     {@code .v1} suffix is deliberate: an incompatible schema change gets a
 *     new topic rather than silently breaking every consumer of the old one.
 * @param topicPartitions partitions to create the topic with. Events are keyed
 *     by monitor id, so one monitor's history stays ordered within a partition
 *     however many there are.
 * @param topicReplicationFactor replicas to create the topic with. One is right
 *     for a single-broker development machine and wrong for production; real
 *     replication is a deployment concern.
 * @param outboxPublishInterval how often to look for unpublished events. Not
 *     the same clock as monitor polling — a slow broker must not delay checks.
 * @param outboxBatchSize the most events one publishing cycle will send, so a
 *     long broker outage cannot pull an unbounded backlog into memory when it
 *     comes back.
 * @param sendTimeout how long to wait for a broker acknowledgement before
 *     treating the send as failed. Bounded so a hung broker cannot stall the
 *     publisher indefinitely.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaProperties(
        String incidentTopic,
        int topicPartitions,
        short topicReplicationFactor,
        Duration outboxPublishInterval,
        int outboxBatchSize,
        Duration sendTimeout) {
}
