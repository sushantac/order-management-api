package com.company.orderapi.api.dto;

import java.math.BigDecimal;

/**
 * PR #21 - product view model.
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity) {
}
