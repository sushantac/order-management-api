package com.company.orderapi.api.dto;

import java.time.LocalDateTime;

/**
 * PR #21 - customer view model: exactly what the API exposes, nothing else
 * (no entity internals like version or audit-entity references).
 */
public record CustomerResponse(
        Long id,
        String email,
        String fullName,
        String phoneNumber,
        LocalDateTime createdAt) {
}
