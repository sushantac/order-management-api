package com.company.orderapi.api.dto;

import com.company.orderapi.security.pii.MaskedPii;
import com.company.orderapi.security.pii.PiiType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PR #21 - order view model returned by the API.
 *
 * <p>PR #27 - {@code customerEmail} is personal data and is masked by the
 * serialization layer for callers without full PII access.
 */
public record OrderResponse(
        Long id,
        String orderNumber,
        LocalDateTime orderDate,
        String status,
        BigDecimal totalAmount,
        @MaskedPii(PiiType.EMAIL) String customerEmail,
        List<OrderItemResponse> items) {

    /** One rendered order line (product name snapshot + money). */
    public record OrderItemResponse(
            Long id,
            Long productId,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice) {
    }
}
