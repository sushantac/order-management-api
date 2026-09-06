package com.company.orderapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * PR #1 smoke test: proves the Spring application context can start.
 *
 * <p>If this test fails, the project skeleton is broken (bad wiring,
 * unresolvable auto-configuration, missing resource, etc.). It stays green
 * without a database because application.yml excludes DB auto-configuration
 * until PR #2 introduces PostgreSQL via docker-compose.
 */
@SpringBootTest
class OrderManagementApiApplicationTests {

    @Test
    void contextLoads() {
        // Empty on purpose: starting the context IS the assertion.
    }
}
