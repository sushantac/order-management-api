package com.company.orderapi.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PR #27 - GDPR data-portability export (GET /api/v1/customers/{id}/portability,
 * Art. 20). A structured, machine-readable JSON copy of everything the API
 * holds about the data subject - their profile, addresses and order history.
 * Unlike regular API responses this export is intentionally NOT masked: it IS
 * the subject's own data, handed to them (or to a controller they name).
 */
public record PortabilityResponse(
        LocalDateTime exportedAt,
        Long customerId,
        String email,
        String fullName,
        String phoneNumber,
        List<AddressExport> addresses,
        List<OrderExport> orders,
        String statement) {

    public record AddressExport(
            String street,
            String city,
            String state,
            String postalCode,
            String country,
            boolean isDefault,
            String addressType) {
    }

    public record OrderExport(
            String orderNumber,
            LocalDateTime orderDate,
            String status,
            BigDecimal totalAmount,
            String paymentMethod,
            List<OrderItemExport> items) {
    }

    public record OrderItemExport(
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice) {
    }
}
