package com.company.orderapi.dto;

import com.company.orderapi.api.dto.CustomerResponse;
import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.api.dto.OrderResponse;
import com.company.orderapi.api.dto.ProductResponse;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.OrderStatus;
import com.company.orderapi.domain.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #21 unit tests: entities map cleanly to the API view models.
 */
class OrderMapperTest {

    @Test
    void mapsCustomerWithoutEntityInternals() {
        Customer customer = new Customer("m@example.com", "Mapper");
        customer.setPhoneNumber("123");

        CustomerResponse response = OrderMapper.toCustomerResponse(customer);

        assertThat(response.id()).isNull(); // not persisted yet
        assertThat(response.email()).isEqualTo("m@example.com");
        assertThat(response.fullName()).isEqualTo("Mapper");
        assertThat(response.phoneNumber()).isEqualTo("123");
    }

    @Test
    void mapsProductFields() {
        Product product = new Product("Pencil", new BigDecimal("0.50"), 7);
        product.setDescription("HB");

        ProductResponse response = OrderMapper.toProductResponse(product);

        assertThat(response.name()).isEqualTo("Pencil");
        assertThat(response.price()).isEqualByComparingTo("0.50");
        assertThat(response.stockQuantity()).isEqualTo(7);
    }

    @Test
    void mapsOrderWithItemsAndCustomerEmail() {
        Customer customer = new Customer("buyer@example.com", "Buyer");
        Product product = new Product("Notebook", new BigDecimal("9.99"), 20);
        Order order = new Order(customer, OrderStatus.PLACED, new BigDecimal("19.98"));
        OrderItem line = new OrderItem(product, 2, new BigDecimal("9.99"));
        order.addItem(line);

        OrderResponse response = OrderMapper.toOrderResponse(order);

        assertThat(response.customerEmail()).isEqualTo("buyer@example.com");
        assertThat(response.status()).isEqualTo("PLACED");
        assertThat(response.totalAmount()).isEqualByComparingTo("19.98");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productName()).isEqualTo("Notebook");
        assertThat(response.items().get(0).quantity()).isEqualTo(2);
    }
}
