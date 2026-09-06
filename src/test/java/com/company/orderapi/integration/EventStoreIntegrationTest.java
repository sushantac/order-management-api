package com.company.orderapi.integration;

import com.company.orderapi.domain.event.DomainEvent;
import com.company.orderapi.domain.event.OrderConfirmedEvent;
import com.company.orderapi.domain.event.OrderPlacedEvent;
import com.company.orderapi.domain.eventstore.EventStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #19 integration tests: the append-only event store.
 *
 * <p>Events are written once, read back in version order and deserialised into
 * their original record types. A duplicate (aggregate, version) is rejected by
 * the schema's UNIQUE constraint - the guarantee event sourcing relies on.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=EventStoreIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class EventStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EventStoreService eventStoreService;

    @Test
    void appendsAndReadsBackFullEventHistoryInOrder() {
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime placedAt = LocalDateTime.now();
        LocalDateTime confirmedAt = placedAt.plusMinutes(5);

        eventStoreService.append(new OrderPlacedEvent(
                aggregateId, 1, placedAt, "ORD-ABC123", new BigDecimal("99.00")));
        eventStoreService.append(new OrderConfirmedEvent(
                aggregateId, 2, confirmedAt, "ops-user"));

        List<DomainEvent> history = eventStoreService.readHistory(aggregateId);

        assertThat(history).hasSize(2);

        OrderPlacedEvent placed = (OrderPlacedEvent) history.get(0);
        assertThat(placed.aggregateId()).isEqualTo(aggregateId);
        assertThat(placed.version()).isEqualTo(1);
        assertThat(placed.orderNumber()).isEqualTo("ORD-ABC123");
        assertThat(placed.totalAmount()).isEqualByComparingTo("99.00");

        OrderConfirmedEvent confirmed = (OrderConfirmedEvent) history.get(1);
        assertThat(confirmed.version()).isEqualTo(2);
        assertThat(confirmed.confirmedBy()).isEqualTo("ops-user");
    }

    @Test
    void duplicateAggregateVersionIsRejected() {
        UUID aggregateId = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.now();

        eventStoreService.append(new OrderPlacedEvent(
                aggregateId, 1, at, "ORD-DUP-1", new BigDecimal("10.00")));

        // Same (aggregateId, version=1) again -> schema UNIQUE constraint fires.
        assertThatThrownBy(() -> eventStoreService.append(new OrderPlacedEvent(
                aggregateId, 1, at.plusSeconds(1), "ORD-DUP-2", new BigDecimal("20.00"))))
                .isInstanceOf(RuntimeException.class);
    }
}
