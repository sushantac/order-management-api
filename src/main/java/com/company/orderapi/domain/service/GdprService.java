package com.company.orderapi.domain.service;

import com.company.orderapi.api.dto.ErasureResponse;
import com.company.orderapi.api.dto.PortabilityResponse;
import com.company.orderapi.api.dto.PortabilityResponse.AddressExport;
import com.company.orderapi.api.dto.PortabilityResponse.OrderExport;
import com.company.orderapi.api.dto.PortabilityResponse.OrderItemExport;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.audit.AuditLog;
import com.company.orderapi.domain.audit.AuditLogRepository;
import com.company.orderapi.domain.repository.CustomerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PR #27 - the GDPR subject-rights operations: erasure (Art. 17) and data
 * portability (Art. 20), each backed by a compliance audit trail entry.
 *
 * <p>Erasure is NOT a blind DELETE: a customer with order history must keep
 * those rows for legal/accounting retention, so this service erases where the
 * law allows (no orders) and ANONYMIZES where it does not (orders exist) -
 * Article 17(3) allows refusing erasure when retention is legally required.
 */
@Service
public class GdprService {

    /** Deterministic, unique, non-identifying placeholder for erased e-mails. */
    static final String ERASED_EMAIL_SUFFIX = "@erased.invalid";

    private final CustomerRepository customers;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public GdprService(CustomerRepository customers,
                       AuditLogRepository auditLogs,
                       ObjectMapper objectMapper) {
        this.customers = customers;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ErasureResponse erase(Long customerId) {
        Customer customer = requireCustomer(customerId);
        String actor = currentActor();
        int orderCount = customer.getOrders().size();
        int addressCount = customer.getAddresses().size();

        if (orderCount == 0) {
            // Nothing to retain: physically remove the customer. Addresses are
            // cascade-deleted by the DB; the audit row survives (no FK).
            auditLogs.saveAndFlush(AuditLog.of(customerId, "CUSTOMER_ERASED", actor,
                    detail("ERASED", orderCount, addressCount)));
            customers.delete(customer);
            customers.flush();
            return new ErasureResponse(customerId, "DELETED", LocalDateTime.now(),
                    "Customer and " + addressCount + " address(es) removed.");
        }

        // Order history must be kept - overwrite every identifying field with a
        // placeholder so the row can never be linked back to the person.
        customer.setEmail("erased-" + customerId + ERASED_EMAIL_SUFFIX);
        customer.setFullName("Erased User");
        customer.setPhoneNumber(null);
        customers.flush();
        auditLogs.saveAndFlush(AuditLog.of(customerId, "CUSTOMER_ANONYMIZED", actor,
                detail("ANONYMIZED", orderCount, addressCount)));
        return new ErasureResponse(customerId, "ANONYMIZED", LocalDateTime.now(),
                orderCount + " order(s) retained for legal purposes; personal fields anonymized.");
    }

    @Transactional
    public PortabilityResponse exportData(Long customerId) {
        Customer customer = requireCustomer(customerId);

        List<AddressExport> addresses = customer.getAddresses().stream()
                .map(a -> new AddressExport(a.getStreet(), a.getCity(), a.getState(),
                        a.getPostalCode(), a.getCountry(), a.isDefault(),
                        a.getAddressType() == null ? null : a.getAddressType().name()))
                .toList();

        List<OrderExport> orders = customer.getOrders().stream()
                .map(o -> new OrderExport(o.getOrderNumber(), o.getOrderDate(),
                        o.getStatus().name(), o.getTotalAmount(),
                        o.getPayment() == null || o.getPayment().getPaymentMethod() == null
                                ? null : o.getPayment().getPaymentMethod().name(),
                        o.getItems().stream()
                                .map(i -> new OrderItemExport(i.getProduct().getName(),
                                        i.getQuantity(), i.getUnitPrice(), i.getTotalPrice()))
                                .toList()))
                .toList();

        auditLogs.saveAndFlush(AuditLog.of(customerId, "PORTABILITY_EXPORTED",
                currentActor(), "{}"));

        return new PortabilityResponse(LocalDateTime.now(), customer.getId(),
                customer.getEmail(), customer.getFullName(), customer.getPhoneNumber(),
                addresses, orders,
                "Export under GDPR Art. 20 - machine-readable, structured, common format.");
    }

    private Customer requireCustomer(Long customerId) {
        return customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown customer " + customerId));
    }

    /** Who is acting? Falls back to "system" outside a real security context. */
    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return auth.getName() == null ? "system" : auth.getName();
    }

    /** Audit detail = non-PII facts only; raw values never reach the log. */
    private String detail(String mode, int orderCount, int addressCount) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "mode", mode,
                    "orderCount", orderCount,
                    "addressCount", addressCount));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}

