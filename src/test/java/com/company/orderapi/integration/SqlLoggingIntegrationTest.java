package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.repository.CustomerRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #15 integration tests: SQL logging & statistics configuration is live in
 * the default (development) profile.
 *
 * <p>The actual log lines (pretty-printed SQL, SQL comments, bound parameters)
 * are visible in every test run thanks to the DEBUG/TRACE levels; the PROD
 * profile turns all of it off (see application-prod.yml).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=SqlLoggingIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Testcontainers
class SqlLoggingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private Environment environment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void sqlLoggingPropertiesAreEnabledByDefault() {
        assertThat(environment.getProperty("spring.jpa.show-sql")).isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.format_sql"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.use_sql_comments"))
                .isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.generate_statistics"))
                .isEqualTo("true");
    }

    @Test
    void statisticsAreReallyCollected() {
        Statistics stats =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        customerRepository.findAll(); // any query counts a statement

        assertThat(stats.getPrepareStatementCount()).isPositive();
        assertThat(stats.getQueryExecutionCount()).isPositive();
    }
}
