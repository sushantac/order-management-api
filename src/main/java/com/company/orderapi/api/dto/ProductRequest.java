package com.company.orderapi.api.dto;

import com.company.orderapi.api.dto.validation.ValidStock;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PR #21/#23 - create/update product request with Bean Validation.
 *
 * <p>Note the class-level custom constraint {@link ValidStock}: it inspects the
 * whole request (a data-entry guard against absurd stock levels).
 */
@ValidStock
public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity) {
}
