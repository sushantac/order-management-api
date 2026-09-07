package com.company.orderapi.api.dto;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * PR #21 - product view model.
 *
 * <p>PR #28 - {@code Serializable} because this DTO is stored in the Redis
 * cache (cache-aside on the catalogue read path).
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
