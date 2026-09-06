package com.company.orderapi.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PR #21 - order view model returned by the API.
 */
public record OrderResponse(
        Long id,
        String orderNumber,
        LocalDateTime orderDate,
        String status,
        BigDecimal totalAmount,
        String customerEmail,
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
