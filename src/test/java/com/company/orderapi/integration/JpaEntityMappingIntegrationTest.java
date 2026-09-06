package com.company.orderapi.integration;

import org.springframework.test.context.TestPropertySource;

import com.company.orderapi.domain.Address;
import com.company.orderapi.domain.Category;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #3 integration tests: prove the JPA foreign-key mappings really work
 * against the schema that Liquibase created in PR #2.
 *
 * <p>Why these tests matter: {@code ddl-auto: validate} already proves the
 * ENTITY METADATA matches the database, but it says nothing about whether
 * Hibernate WRITES and READS those foreign keys correctly. These tests flush
 * real entities and assert the FK values on disk.
 *
 * <p>Assertions intentionally use the EntityManager's native queries (same
 * transaction/connection) so they observe the uncommitted rows of the current
 * test transaction.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "integration.database.tag=JpaEntityMappingIntegrationTest",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
@Transactional // each test rolls back -> no cleanup code needed
class JpaEntityMappingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EntityManager em;

    @Test
    void addressPersistsCustomerForeignKeyViaJoinColumn() {
        Customer customer = new Customer("ada@example.com", "Ada Lovelace");
        em.persist(customer);
        em.flush();

        // Owning side: Address.customer holds the @ManyToOne + @JoinColumn.
        Address address = new Address(customer, "1 Analytical Engine Way", "London", "UK");
        customer.addAddress(address); // keeps both sides of the relation in sync
        em.persist(address);
        em.flush();

        Long writtenFk = (Long) em.createNativeQuery(
                        "SELECT customer_id FROM addresses WHERE id = ?1")
                .setParameter(1, address.getId())
                .getSingleResult();
        assertThat(writtenFk).isEqualTo(customer.getId());

        // Read back through the inverse (@OneToMany mappedBy) side.
        em.clear();
        Customer reloaded = em.find(Customer.class, customer.getId());
        assertThat(reloaded.getAddresses())
                .extracting(Address::getStreet)
                .containsExactly("1 Analytical Engine Way");
    }

    @Test
    void orderAndItemWriteTheirForeignKeysAndReadBack() {
        Customer customer = new Customer("grace@example.com", "Grace Hopper");
        em.persist(customer);
        Product product = new Product("Notebook", new BigDecimal("9.99"), 50);
        em.persist(product);
        em.flush();

        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("19.98"));
        OrderItem line = new OrderItem(product, 2, new BigDecimal("9.99"));
        order.addItem(line); // sets line.order + adds to order.items
        em.persist(order);
        em.persist(line);
        em.flush();

        // Order row: customer_id written by Order.customer (@ManyToOne).
        Long orderCustomerFk = (Long) em.createNativeQuery(
                        "SELECT customer_id FROM orders WHERE id = ?1")
                .setParameter(1, order.getId())
                .getSingleResult();
        assertThat(orderCustomerFk).isEqualTo(customer.getId());

        // Item row: order_id + product_id written by the OrderItem owning side.
        Object[] itemFks = (Object[]) em.createNativeQuery(
                        "SELECT order_id, product_id FROM order_items WHERE id = ?1")
                .setParameter(1, line.getId())
                .getSingleResult();
        assertThat(((Number) itemFks[0]).longValue()).isEqualTo(order.getId());
        assertThat(((Number) itemFks[1]).longValue()).isEqualTo(product.getId());

        // Read back through the inverse (@OneToMany mappedBy) side.
        em.clear();
        Order reloaded = em.find(Order.class, order.getId());
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().get(0).getProduct().getName()).isEqualTo("Notebook");
    }

    @Test
    void productAndCategoryManyToManyPopulatesJoinTable() {
        Category stationery = new Category("Stationery");
        em.persist(stationery);
        em.flush();

        Product pencil = new Product("Pencil", new BigDecimal("0.50"), 500);
        pencil.addCategory(stationery); // owning side of the many-to-many
        em.persist(pencil);
        em.flush();

        Object[] joinRow = (Object[]) em.createNativeQuery(
                        "SELECT product_id, category_id FROM product_categories WHERE product_id = ?1")
                .setParameter(1, pencil.getId())
                .getSingleResult();
        assertThat(((Number) joinRow[0]).longValue()).isEqualTo(pencil.getId());
        assertThat(((Number) joinRow[1]).longValue()).isEqualTo(stationery.getId());

        // Both directions of the many-to-many read back.
        em.clear();
        Product reloadedProduct = em.find(Product.class, pencil.getId());
        assertThat(reloadedProduct.getCategories())
                .extracting(Category::getName)
                .containsExactly("Stationery");

        Category reloadedCategory = em.find(Category.class, stationery.getId());
        assertThat(reloadedCategory.getProducts())
                .extracting(Product::getName)
                .containsExactly("Pencil");
    }
}
