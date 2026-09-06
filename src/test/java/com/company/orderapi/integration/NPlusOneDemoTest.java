package com.company.orderapi.integration;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #6 integration tests: the N+1 problem and its three fixes.
 *
 * <p>Data: 4 customers, each with 2 addresses. Hibernate {@link Statistics}
 * counts the real JDBC statements, so "before" and "after" are numbers, not
 * opinions:
 * <pre>
 *   naive findAll()          -> 1 (customers) + 4 (addresses)  = 5 statements
 *   JOIN FETCH               -> 1 statement, addresses included
 *   @EntityGraph(attributePaths) -> 1 statement, addresses included
 *   @NamedEntityGraph        -> 1 statement, addresses included
 * </pre>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // The naive test intentionally shows N+1, so isolate this class from
        // the global batch fetching that PR #7 adds (keep it at 1).
        "spring.jpa.properties.hibernate.default_batch_fetch_size=1",
        // Tests disable the second-level cache (shared JVM Ehcache) unless the
        // test under focus is SecondLevelCacheIntegrationTest itself.
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
@Transactional
class NPlusOneDemoTest {

    private static final int CUSTOMER_COUNT = 4;
    private static final int ADDRESSES_PER_CUSTOMER = 2;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void naiveFindAllCausesTheNPlusOneProblem() {
        seedCustomers();
        Statistics stats = statistics();
        stats.clear();

        // 1 statement: all customers (addresses are LAZY, not loaded).
        List<Customer> customers = customerRepository.findAll();
        assertThat(stats.getPrepareStatementCount())
                .as("the findAll itself is a single query")
                .isEqualTo(1);

        // N statements: one lazy addresses SELECT per customer.
        for (Customer customer : customers) {
            customer.getAddresses().size();
        }
        long total = stats.getPrepareStatementCount();

        assertThat(total)
                .as("1 query for the list + 1 per customer (N+1)")
                .isEqualTo(1 + CUSTOMER_COUNT);
    }

    @Test
    void joinFetchLoadsEverythingInOneQuery() {
        seedCustomers();
        Statistics stats = statistics();
        stats.clear();

        List<Customer> customers = customerRepository.findAllWithAddressesJoinFetch();

        assertThat(customers).hasSize(CUSTOMER_COUNT); // distinct: no dupes
        assertThat(stats.getPrepareStatementCount())
                .as("JOIN FETCH collapses N+1 into a single statement")
                .isEqualTo(1);

        // Traversal now triggers ZERO extra queries - addresses came with the row.
        for (Customer customer : customers) {
            assertThat(util().isLoaded(customer, "addresses")).isTrue();
            assertThat(customer.getAddresses()).hasSize(ADDRESSES_PER_CUSTOMER);
        }
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void entityGraphAttributePathsLoadsEverythingInOneQuery() {
        seedCustomers();
        Statistics stats = statistics();
        stats.clear();

        List<Customer> customers = customerRepository.findAllWithAddressesEntityGraph();

        assertThat(customers).hasSize(CUSTOMER_COUNT);
        assertThat(stats.getPrepareStatementCount())
                .as("@EntityGraph(attributePaths) fetches in a single statement")
                .isEqualTo(1);
        for (Customer customer : customers) {
            assertThat(customer.getAddresses()).hasSize(ADDRESSES_PER_CUSTOMER);
        }
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void namedEntityGraphLoadsEverythingInOneQuery() {
        seedCustomers();
        Statistics stats = statistics();
        stats.clear();

        List<Customer> customers = customerRepository.findAllWithAddressesNamedEntityGraph();

        assertThat(customers).hasSize(CUSTOMER_COUNT);
        assertThat(stats.getPrepareStatementCount())
                .as("@NamedEntityGraph referenced by name fetches in one statement")
                .isEqualTo(1);
        for (Customer customer : customers) {
            assertThat(customer.getAddresses()).hasSize(ADDRESSES_PER_CUSTOMER);
        }
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    /** Creates CUSTOMER_COUNT customers with ADDRESSES_PER_CUSTOMER each. */
    private void seedCustomers() {
        for (int i = 1; i <= CUSTOMER_COUNT; i++) {
            Customer customer = new Customer("n1." + i + "@example.com", "Customer " + i);
            for (int a = 1; a <= ADDRESSES_PER_CUSTOMER; a++) {
                customer.addAddress(
                        new Address(customer, i + "A" + a + " Demo Street", "Brisbane", "AU"));
            }
            em.persist(customer);
        }
        em.flush();
        em.clear();
    }

    private PersistenceUnitUtil util() {
        return entityManagerFactory.getPersistenceUnitUtil();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
