package com.company.orderapi;

import org.springframework.test.context.TestPropertySource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the full Spring context starts against a REAL PostgreSQL 16.
 *
 * <p>PR #1: proved the skeleton boots without a database.
 * <p>PR #2: the app is now wired to PostgreSQL via Liquibase, so the context
 * only starts if (a) a database is reachable and (b) Liquibase successfully
 * applies {@code db.changelog-master.xml}. Testcontainers gives us that real
 * database; {@link ServiceConnection} forwards the container's JDBC settings
 * to Spring Boot automatically.
 *
 * <p>If this test fails, the context itself is broken - not a single test's
 * assertion. That makes it a great early-warning signal on every build.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
class OrderManagementApiApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
        // Empty on purpose: starting the context (and running Liquibase) IS
        // the assertion.
    }
}
