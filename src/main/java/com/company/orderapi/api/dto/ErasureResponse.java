package com.company.orderapi.api.dto;

import java.time.LocalDateTime;

/**
 * PR #27 - result of a GDPR right-to-erasure request (DELETE
 * /api/v1/customers/{id}/data). The action tells the caller what physically
 * happened: "DELETED" (no order history - rows removed) or "ANONYMIZED"
 * (order history must be retained for legal reasons, so the personal fields
 * were overwritten with non-identifying placeholders instead).
 */
public record ErasureResponse(
        Long customerId,
        String action,
        LocalDateTime completedAt,
        String note) {
}
