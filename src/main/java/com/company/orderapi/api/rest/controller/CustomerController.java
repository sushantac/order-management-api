package com.company.orderapi.api.rest.controller;

import com.company.orderapi.api.dto.CustomerRequest;
import com.company.orderapi.api.dto.CustomerResponse;
import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PR #22 - REST CRUD for customers.
 *
 * <p>REST mapping: POST=create (201+Location), GET=read, PUT=full update,
 * DELETE=delete. Controllers only speak DTOs - never entities.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerRepository customers;

    public CustomerController(CustomerRepository customers) {
        this.customers = customers;
    }

    /** GET /api/v1/customers?page=0&size=10&sort=fullName,asc */
    @GetMapping
    public Page<CustomerResponse> list(Pageable pageable) {
        return customers.findAll(pageable).map(OrderMapper::toCustomerResponse);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable Long id) {
        return customers.findById(id)
                .map(OrderMapper::toCustomerResponse)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + id));
    }

    /**
     * PR #22 - field filtering (?fields=id,fullName) and resource inclusion
     * (?include=orders,addresses). Returns a map built from the requested keys
     * so the client receives exactly the payload it asked for.
     */
    @GetMapping("/{id}/view")
    @Transactional(readOnly = true)
    public Map<String, Object> view(
            @PathVariable Long id,
            @RequestParam Optional<String> fields,
            @RequestParam Optional<String> include) {
        Customer customer = customers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + id));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", customer.getId());
        payload.put("email", customer.getEmail());
        payload.put("fullName", customer.getFullName());
        payload.put("phoneNumber", customer.getPhoneNumber());

        if (include.orElse("").contains("orders")) {
            payload.put("orders", customer.getOrders().stream()
                    .map(o -> Map.of("orderNumber", String.valueOf(o.getOrderNumber()),
                            "status", o.getStatus().name(),
                            "totalAmount", o.getTotalAmount()))
                    .toList());
        }
        if (include.orElse("").contains("addresses")) {
            payload.put("addresses", customer.getAddresses().stream()
                    .map(a -> Map.of("city", a.getCity(), "street", a.getStreet()))
                    .toList());
        }

        if (fields.isPresent() && !fields.get().isBlank()) {
            Set<String> wanted = Arrays.stream(fields.get().split(","))
                    .map(String::trim).collect(Collectors.toSet());
            payload.keySet().removeIf(key -> !wanted.contains(key));
        }
        return payload;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@RequestBody CustomerRequest request) {
        Customer customer = customers.save(new Customer(request.email(), request.fullName()));
        customer.setPhoneNumber(request.phoneNumber());
        customers.flush();
        return ResponseEntity
                .created(URI.create("/api/v1/customers/" + customer.getId()))
                .body(OrderMapper.toCustomerResponse(customer));
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @RequestBody CustomerRequest request) {
        Customer customer = customers.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + id));
        customer.setEmail(request.email());
        customer.setFullName(request.fullName());
        customer.setPhoneNumber(request.phoneNumber());
        customers.flush();
        return OrderMapper.toCustomerResponse(customer);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            customers.deleteById(id);
        } catch (RuntimeException e) {
            // @PreRemove guard (wrapped by Spring Data): customer still has orders
            if (e.getMessage() != null && e.getMessage().contains("cannot be removed")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            throw e;
        }
        return ResponseEntity.noContent().build();
    }
}
