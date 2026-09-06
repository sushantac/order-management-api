package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #14 integration tests: the JPA entity model and the real (Liquibase-created)
 * schema must agree - schema validation, checked from the running application.
 *
 * <p>Context startup with {@code ddl-auto: validate} already fails on any
 * mismatch; these tests additionally prove the CONTRACT explicitly:
 * <ul>
 *   <li>every {@code @Entity} maps to an existing table,</li>
 *   <li>every entity table carries the shared {@code version} + audit columns,
 *   <li>the Liquibase/join infrastructure exists too.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=SchemaValidationIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class SchemaValidationIntegrationTest {

    private static final Set<String> REQUIRED_SHARED_COLUMNS =
            Set.of("version", "created_at", "updated_at", "created_by", "updated_by");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyEntityMapsToAnExistingTable() {
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            String table = tableName(entity);
            assertThat(tableExists(table))
                    .as("entity %s must map to existing table %s",
                            entity.getJavaType().getSimpleName(), table)
                    .isTrue();
        }
    }

    @Test
    void everyEntityTableCarriesTheSharedVersionAndAuditColumns() {
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            String table = tableName(entity);
            for (String column : REQUIRED_SHARED_COLUMNS) {
                assertThat(columnExists(table, column))
                        .as("table %s must have shared column %s", table, column)
                        .isTrue();
            }
        }
    }

    @Test
    void infrastructureAndJoinTablesExistToo() {
        // Liquibase bookkeeping + the ManyToMany join table for Product<->Category.
        assertThat(tableExists("databasechangelog")).isTrue();
        assertThat(tableExists("product_categories")).isTrue();
    }

    private String tableName(EntityType<?> entity) {
        Table table = entity.getJavaType().getAnnotation(Table.class);
        return table != null ? table.name() : entity.getName();
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }
}
