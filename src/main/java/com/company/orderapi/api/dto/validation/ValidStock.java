package com.company.orderapi.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PR #23 - custom class-level constraint for stock sanity on a product request:
 * stock may not exceed {@value #MAX_STOCK} (an obvious data-entry/typo guard).
 */
@Documented
@Constraint(validatedBy = ValidStockValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidStock {

    int MAX_STOCK = 1_000_000;

    String message() default "stockQuantity must be between 0 and " + MAX_STOCK;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
