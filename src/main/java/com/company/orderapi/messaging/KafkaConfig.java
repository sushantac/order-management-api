package com.company.orderapi.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * PR #31 - Kafka wiring: topics + the dead-letter error handler.
 *
 * <p>Everything here is conditional on {@code app.kafka.enabled=true}, so the
 * topics are only declared (and the admin only talks to the broker) in the
 * profiles/tests that actually use Kafka.
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    @Bean
    public NewTopic orderEventsTopic(
            @Value("${app.kafka.topics.order-events}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDltTopic(
            @Value("${app.kafka.topics.order-events-dlt}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    /**
     * After a short retry budget the record is published to the DLT topic
     * ({@code <topic>.DLT}) so a poison message never blocks the group.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(template,
                        (record, ex) -> new TopicPartition(record.topic() + ".DLT",
                                record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 1L));
    }
}
