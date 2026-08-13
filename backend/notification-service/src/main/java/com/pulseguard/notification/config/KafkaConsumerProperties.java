package com.pulseguard.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which topic to consume, and as whom.
 *
 * <p>Broker addresses and consumer tuning stay under {@code spring.kafka},
 * where Boot's own auto-configuration reads them.
 *
 * @param incidentTopic the versioned incident topic the Monitor Worker
 *     publishes to. The {@code .v1} suffix is part of the contract.
 * @param groupId the consumer group. Stable across restarts, because Kafka uses
 *     it to remember how far this service has read — a changing group id would
 *     replay the entire topic and re-notify everyone.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaConsumerProperties(String incidentTopic, String groupId) {
}
