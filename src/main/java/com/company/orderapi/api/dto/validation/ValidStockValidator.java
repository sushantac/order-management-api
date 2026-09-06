package com.company.orderapi.api.dto.validation;

import com.company.orderapi.api.dto.ProductRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * PR #23 - validator for {@link ValidStock}.
 */
public class ValidStockValidator implements ConstraintValidator<ValidStock, ProductRequest> {

    @Override
    public boolean isValid(ProductRequest request, ConstraintValidatorContext context) {
        if (request == null || request.stockQuantity() == null) {
            return false;
        }
        return request.stockQuantity() >= 0 && request.stockQuantity() <= ValidStock.MAX_STOCK;
    }
}
