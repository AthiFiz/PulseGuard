package com.pulseguard.monitorworker.config;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the incident topic so it exists before anything publishes to it.
 *
 * <p>Deliberately not relying on the broker's {@code auto.create.topics.enable}:
 * that is a broker-wide setting PulseGuard does not control, it is off by
 * default in many deployments, and a topic created implicitly takes whatever
 * partition count the broker happens to default to.
 *
 * <p>If no broker is reachable at startup, Boot's {@code KafkaAdmin} logs the
 * failure and carries on — creating a topic is not a precondition for
 * monitoring, and the worker must start regardless.
 */
@Configuration
@RequiredArgsConstructor
public class KafkaTopicConfig {

    private final KafkaProperties kafkaProperties;

    @Bean
    public NewTopic incidentEventsTopic() {
        return TopicBuilder.name(kafkaProperties.incidentTopic())
                .partitions(kafkaProperties.topicPartitions())
                .replicas(kafkaProperties.topicReplicationFactor())
                .build();
    }
}
