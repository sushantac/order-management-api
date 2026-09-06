package com.company.orderapi.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PR #21 - request to place an order: who orders, and what.
 */
public record OrderRequest(
        @NotNull Long customerId,
        @NotEmpty @Valid List<OrderItemRequest> items) {

    /** One line of the request: product + quantity (unit price comes from the catalogue). */
    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull Integer quantity) {
    }
}
