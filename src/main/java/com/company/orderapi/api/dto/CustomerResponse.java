package com.company.orderapi.api.dto;

import com.company.orderapi.security.pii.MaskedPii;
import com.company.orderapi.security.pii.PiiType;

import java.time.LocalDateTime;

/**
 * PR #21 - customer view model: exactly what the API exposes, nothing else
 * (no entity internals like version or audit-entity references).
 *
 * <p>PR #27 - personal fields are tagged {@link MaskedPii}; the API masks them
 * for callers without full PII access while the raw value lives only in the
 * entity/DB layer.
 */
public record CustomerResponse(
        Long id,
        @MaskedPii(PiiType.EMAIL) String email,
        @MaskedPii(PiiType.NAME) String fullName,
        @MaskedPii(PiiType.PHONE) String phoneNumber,
        LocalDateTime createdAt) {
}
