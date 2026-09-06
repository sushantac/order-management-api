package com.company.orderapi.api.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PR #23 - custom CROSS-FIELD constraint for an order request: no product may
 * appear twice, and every quantity must be within [1, 100000].
 */
@Documented
@Constraint(validatedBy = ValidOrderRequestValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrderRequest {

    String message() default "order is invalid: duplicate product or bad quantity";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
