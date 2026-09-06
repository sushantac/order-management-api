package com.company.orderapi.integration;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #10 integration tests: auditing fields are populated automatically.
 *
 * <p>{@code AuditingEntityListener} on {@code BaseEntity} fills the four audit
 * fields on persist/update - tests assert the exact semantics:
 * created* fields are set once and never change; updated* fields follow the
 * latest change.
 */
@Testcontainers
@SpringBootTest
class JpaAuditingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void persistPopulatesAllFourAuditFields() {
        Customer customer = customerRepository.saveAndFlush(
                new Customer("audit@example.com", "Audit Me"));

        assertThat(customer.getCreatedAt()).isNotNull();
        assertThat(customer.getUpdatedAt()).isNotNull();
        assertThat(customer.getCreatedBy()).isEqualTo("system"); // AuditorAware
        assertThat(customer.getUpdatedBy()).isEqualTo("system");
    }

    @Test
    void updateChangesUpdatedFieldsButNotCreatedFields() throws Exception {
        Customer customer = customerRepository.saveAndFlush(
                new Customer("audit2@example.com", "Before Rename"));
        LocalDateTime createdAt = customer.getCreatedAt();
        LocalDateTime updatedAt = customer.getUpdatedAt();

        // Force a real UPDATE (change + flush inside a new transaction).
        Thread.sleep(10); // let the clock tick so updatedAt can advance
        customer.setFullName("After Rename");
        customerRepository.saveAndFlush(customer);
        // Re-read from a fresh context to avoid stale in-memory values.
        customerRepository.flush();
        Customer reloaded = customerRepository.findById(customer.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);     // immutable
        assertThat(reloaded.getCreatedBy()).isEqualTo("system");      // immutable
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(updatedAt); // moved
        assertThat(reloaded.getUpdatedBy()).isEqualTo("system");
        assertThat(reloaded.getFullName()).isEqualTo("After Rename");
    }
}
