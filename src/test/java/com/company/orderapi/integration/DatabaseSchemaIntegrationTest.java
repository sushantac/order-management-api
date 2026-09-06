package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #2 integration tests: verify the Liquibase schema was applied correctly.
 *
 * <p>These tests assert DATABASE-LEVEL facts (tables, FK delete rules, indexes)
 * by querying PostgreSQL's system catalogs - they do not trust "we wrote a
 * CREATE TABLE, therefore it exists". That is the difference between testing
 * the migration result and testing nothing.
 *
 * <p>The schema lives in {@code db/changelog/v1.0/01_create_tables.sql}:
 * 8 tables, 9 foreign keys (CASCADE / RESTRICT / SET NULL), and an index on
 * every FK column (explicit, or provided by a PK/UNIQUE constraint).
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=DatabaseSchemaIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
class DatabaseSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    /** (child table, child column, parent table, expected delete rule). */
    private record Fk(String childTable, String childColumn, String parentTable, String deleteRule) {
    }

    @Test
    void createsAllEightExpectedTables() {
        List<String> tables = jdbc.queryForList("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_type = 'BASE TABLE'
                          -- Liquibase bookkeeping tables are expected; they are
                          -- infrastructure, not domain tables, so exclude them.
                          -- (ILIKE: Postgres folds unquoted names to lowercase.)
                          AND table_name NOT ILIKE 'DATABASECHANGELOG%'
                        """,
                String.class);

        assertThat(tables)
                .contains("customers", "addresses", "orders", "order_items",
                        "products", "categories", "product_categories", "payments",
                        "event_store")
                .hasSize(9); // 8 domain tables + the PR #19 event store
    }

    @Test
    void foreignKeysCarryTheExpectedDeleteRules() {
        List<Fk> actual = jdbc.query("""
                        SELECT tc.table_name              AS child_table,
                               kcu.column_name             AS child_column,
                               ccu.table_name              AS parent_table,
                               rc.delete_rule              AS delete_rule
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                             ON tc.constraint_name = kcu.constraint_name
                            AND tc.table_schema    = kcu.table_schema
                        JOIN information_schema.referential_constraints rc
                             ON tc.constraint_name  = rc.constraint_name
                            AND tc.constraint_schema = rc.constraint_schema
                        JOIN information_schema.constraint_column_usage ccu
                             ON rc.unique_constraint_name  = ccu.constraint_name
                            AND rc.unique_constraint_schema = ccu.constraint_schema
                        WHERE tc.constraint_type = 'FOREIGN KEY'
                          AND tc.table_schema    = 'public'
                        """,
                (rs, rowNum) -> new Fk(
                        rs.getString("child_table"),
                        rs.getString("child_column"),
                        rs.getString("parent_table"),
                        rs.getString("delete_rule")));

        assertThat(actual).containsExactlyInAnyOrder(
                // CASCADE  - a customer's address book dies with the customer
                new Fk("addresses", "customer_id", "customers", "CASCADE"),
                // CASCADE  - a customer's orders die with the customer
                new Fk("orders", "customer_id", "customers", "CASCADE"),
                // SET NULL - an order survives the deletion of its addresses
                new Fk("orders", "shipping_address_id", "addresses", "SET NULL"),
                new Fk("orders", "billing_address_id", "addresses", "SET NULL"),
                // CASCADE  - order lines belong to exactly one order
                new Fk("order_items", "order_id", "orders", "CASCADE"),
                // RESTRICT - products referenced by history can never vanish
                new Fk("order_items", "product_id", "products", "RESTRICT"),
                new Fk("product_categories", "product_id", "products", "RESTRICT"),
                new Fk("product_categories", "category_id", "categories", "RESTRICT"),
                // CASCADE  - a payment belongs to exactly one order (1:1)
                new Fk("payments", "order_id", "orders", "CASCADE"));
    }

    @Test
    void everyForeignKeyColumnIsIndexed() {
        // Fetch every FK (table, column) pair from the catalog ...
        Set<Map.Entry<String, String>> foreignKeyColumns = jdbc.query("""
                        SELECT tc.table_name AS tbl, kcu.column_name AS col
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                             ON tc.constraint_name = kcu.constraint_name
                            AND tc.table_schema    = kcu.table_schema
                        WHERE tc.constraint_type = 'FOREIGN KEY'
                          AND tc.table_schema    = 'public'
                        """,
                (rs, i) -> Map.entry(
                        rs.getString("tbl"),
                        rs.getString("col"))).stream().collect(Collectors.toSet());

        assertThat(foreignKeyColumns).hasSize(9);

        // ... and prove EVERY one of them has an index whose leading column is
        // that FK column. The index may be an explicit CREATE INDEX, the PK,
        // or a UNIQUE constraint - all of them make FK lookups/checks fast.
        for (Map.Entry<String, String> fk : foreignKeyColumns) {
            assertThat(hasIndexStartingWith(fk.getKey(), fk.getValue()))
                    .as("FK column %s.%s must be indexed (leading column)",
                            fk.getKey(), fk.getValue())
                    .isTrue();
        }
    }

    private boolean hasIndexStartingWith(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_index i
                    JOIN pg_class t      ON t.oid = i.indrelid
                    JOIN pg_attribute a  ON a.attrelid = t.oid
                                       AND a.attnum = i.indkey[0]
                    WHERE t.relname = ?
                      AND a.attname = ?
                )
                """, Boolean.class, table, column));
    }
}
