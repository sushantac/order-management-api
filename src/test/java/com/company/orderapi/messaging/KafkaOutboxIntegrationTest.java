package com.company.orderapi.messaging;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.outbox.OutboxRepository;
import com.company.orderapi.domain.outbox.OutboxStatus;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.OrderService;
import com.company.orderapi.domain.service.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #31 - end-to-end Kafka outbox test against an @EmbeddedKafka broker:
 * place an order -> outbox row written in the same transaction -> polling
 * publisher forwards it -> consumer receives and acks it (manual offset) ->
 * row marked PUBLISHED. A poison message then lands on the dead-letter topic.
 */
@Testcontainers
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {
        "order-events", "order-events.DLT",
        "order.placed", "order.status.changed", "cart.checkout.initiated"})
@TestPropertySource(properties = {
        "integration.database.tag=KafkaOutboxIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.kafka.enabled=true",
        "app.outbox.scheduler-enabled=false"
})
class KafkaOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderService orderService;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private ProductRepository products;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OrderEventConsumer consumer;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentGateway paymentGateway() {
            return amount -> { };
        }
    }

    @Test
    void outboxToBrokerToConsumerRoundTripAndDeadLetterRouting() throws Exception {
        Customer customer = customers.saveAndFlush(
                new Customer("kafka@example.com", "Kafka Customer"));
        Product product = products.saveAndFlush(
                new Product("Kafka Widget", new BigDecimal("5.00"), 20));

        long processedBefore = consumer.processed();
        long deadLettersBefore = consumer.deadLetters();

        // 1) Place the order - outbox rows are written in the SAME tx.
        orderService.placeOrder(customer.getId(),
                List.of(new OrderService.OrderLine(product.getId(), 1)));
        assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);

        // 2) The polling publisher forwards ALL PENDING -> Kafka -> mark PUBLISHED.
        assertThat(publisher.publishPendingNow()).isEqualTo(2);
        assertThat(outbox.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(2);
        assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isZero();

        // 3) The consumer group receives it and acks (manual offset).
        await(() -> consumer.processed() > processedBefore);

        // 4) A POISON message (missing total) is rejected by the listener,
        //    never acked, and routed to the dead-letter topic after retries.
        kafkaTemplate.send("order-events", "poison",
                new OrderPlacedMessage(999L, "ORD-POISON", null,
                        java.time.LocalDateTime.now()));
        await(() -> consumer.deadLetters() > deadLettersBefore);
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 10s");
    }
}
