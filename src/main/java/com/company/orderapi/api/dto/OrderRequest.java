package com.company.orderapi.api.dto;

import com.company.orderapi.api.dto.validation.ValidOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PR #21/#23 - request to place an order. The class-level
 * {@link ValidOrderRequest} constraint checks CROSS-FIELD rules (no duplicate
 * products, sane quantities) that field annotations cannot express.
 */
@ValidOrderRequest
public record OrderRequest(
        @NotNull Long customerId,
        @NotEmpty @Valid List<OrderItemRequest> items) {

    /** One line of the request: product + quantity (unit price comes from the catalogue). */
    public record OrderItemRequest(
            @NotNull Long productId,
            @NotNull Integer quantity) {
    }
}
