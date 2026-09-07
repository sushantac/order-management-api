package com.company.orderapi.security.pii;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * PR #27 - marks a DTO component (email, fullName, phoneNumber, ...) as
 * personal data. {@link PiiMaskingModule} finds the annotation on each
 * serialized property and swaps in a {@link SensitiveDataSerializer} that
 * masks the value for callers without full PII access.
 *
 * <p>Keep the annotation OFF the entity layer on purpose: it describes the API
 * *contract*, and only the API layer (DTOs) serializes.
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaskedPii {

    /** Which masking rule to apply. */
    PiiType value() default PiiType.NAME;
}
