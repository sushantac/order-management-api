package com.company.orderapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Order Management API.
 *
 * <p>PR #1 - Project Setup. A single {@code @SpringBootApplication} is the
 * bootstrap: component scanning, auto-configuration and the Spring context
 * all start from this class. Every later PR will build around this skeleton.
 *
 * <p>Package layout follows the spec's target tree:
 * {@code domain/} (entities, value objects, events), {@code api/} (REST/GraphQL),
 * {@code security/}, {@code infrastructure/}, {@code observability/}.
 */
@SpringBootApplication
public class OrderManagementApiApplication {

    public static void main(String[] args) {
        // SpringApplication.run() starts the embedded server and the full
        // application context. The returned ApplicationContext is what the
        // contextLoads smoke test verifies in the test sourceset.
        SpringApplication.run(OrderManagementApiApplication.class, args);
    }
}
