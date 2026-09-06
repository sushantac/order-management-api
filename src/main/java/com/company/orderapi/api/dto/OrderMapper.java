package com.company.orderapi.api.dto;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderItem;
import com.company.orderapi.domain.Product;

import java.util.List;

/**
 * PR #21 - converts domain entities to API view models (DTOs).
 *
 * <p>Manual & explicit (no reflection/magic): every field the API exposes is
 * listed here, so the API contract is reviewable in one place. Entity internals
 * (version, audit users, lazy graph) never leak to clients.
 */
public final class OrderMapper {

    private OrderMapper() {
        // static utility
    }

    public static CustomerResponse toCustomerResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getFullName(),
                customer.getPhoneNumber(),
                customer.getCreatedAt());
    }

    public static ProductResponse toProductResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStockQuantity());
    }

    public static OrderResponse toOrderResponse(Order order) {
        String customerEmail = order.getCustomer() == null ? null : order.getCustomer().getEmail();
        List<OrderResponse.OrderItemResponse> items =
                order.getItems().stream().map(OrderMapper::toOrderItemResponse).toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getOrderDate(),
                order.getStatus().name(),
                order.getTotalAmount(),
                customerEmail,
                items);
    }

    private static OrderResponse.OrderItemResponse toOrderItemResponse(OrderItem item) {
        return new OrderResponse.OrderItemResponse(
                item.getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getTotalPrice());
    }
}
