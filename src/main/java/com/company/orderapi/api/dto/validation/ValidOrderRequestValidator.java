package com.company.orderapi.api.dto.validation;

import com.company.orderapi.api.dto.OrderRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.Set;

/**
 * PR #23 - validator for {@link ValidOrderRequest}: checks the whole request
 * across items (a single-item constraint cannot express "no duplicates").
 */
public class ValidOrderRequestValidator
        implements ConstraintValidator<ValidOrderRequest, OrderRequest> {

    private static final int MAX_QUANTITY = 100_000;

    @Override
    public boolean isValid(OrderRequest request, ConstraintValidatorContext context) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        for (OrderRequest.OrderItemRequest item : request.items()) {
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                return false;
            }
            if (!seen.add(item.productId())) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                                "duplicate productId " + item.productId() + " in order")
                        .addPropertyNode("items").addConstraintViolation();
                return false;
            }
        }
        return true;
    }
}
