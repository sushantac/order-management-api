package com.company.orderapi.integration;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
 * PR #7 integration tests: batch fetching.
 *
 * <p>With {@code default_batch_fetch_size = 20} (application.yml) and explicit
 * {@code @BatchSize} on the collections, initializing a lazy collection for ONE
 * owner also loads it for every other already-loaded owner - in a single
 * {@code in (...)} query.
 *
 * <p>Data: 8 customers, each with 2 addresses. After loading the customers
 * (1 query), touching the addresses of every customer should cost ONE more
 * statement (the batch query), not 8:
 * <pre>
 *   1 (findAll) + 1 (addresses for all 8) = 2 statements
 *   vs 1 + 8 = 9 without batch fetching (naive N+1)
 * </pre>
 *
 * <p>Why orders are NOT used here: loading an Order initializes its optional
 * inverse {@code @OneToOne payment} (Hibernate cannot lazy-proxy a nullable
 * one-to-one), which would add per-order selects and hide the collection
 * batching this test isolates.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // NB: deliberately does NOT override default_batch_fetch_size, so this
        // class exercises the PR #7 global value (20) from application.yml.
        // Tests disable the second-level cache (shared JVM Ehcache) unless the
        // test under focus is SecondLevelCacheIntegrationTest itself.
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class BatchFetchIntegrationTest {

    private static final int CUSTOMER_COUNT = 8;
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
    void lazyCollectionsAreLoadedInBatchesNotOneByOne() {
        seedCustomers();
        Statistics stats = statistics();
        stats.clear();

        List<Customer> customers = customerRepository.findAll();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);

        // Touch every lazy addresses collection on every customer.
        for (Customer customer : customers) {
            customer.getAddresses().size();
        }
        long totalStatements = stats.getPrepareStatementCount();

        // Batch fetching: the FIRST addresses access loads addresses for ALL
        // owners in one "in (...)" query; the rest come from the same fill.
        assertThat(totalStatements)
                .as("1 findAll + 1 batched addresses load (not one per customer)")
                .isEqualTo(2);

        // Performance comparison: naive lazy loading would need one addresses
        // query per customer (1 + N); batching keeps it flat.
        long naiveStatements = 1L + CUSTOMER_COUNT;
        assertThat(totalStatements)
                .as("%d statements batched vs %d naive (1 + N)", totalStatements, naiveStatements)
                .isLessThan(naiveStatements);
    }

    /** Creates CUSTOMER_COUNT customers, each with ADDRESSES_PER_CUSTOMER. */
    private void seedCustomers() {
        for (int i = 1; i <= CUSTOMER_COUNT; i++) {
            Customer customer = new Customer("batch" + i + "@example.com", "Batch Customer " + i);
            for (int a = 1; a <= ADDRESSES_PER_CUSTOMER; a++) {
                customer.addAddress(new Address(customer, i + "A" + a + " Batch Street", "Perth", "AU"));
            }
            em.persist(customer);
        }
        em.flush();
        em.clear();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
