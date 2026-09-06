package com.company.orderapi.domain.repository;

import java.math.BigDecimal;

/**
 * PR #17 - class-based DTO used by a JPQL constructor expression:
 *
 * <pre>{@code select new com.company.orderapi.domain.repository.CustomerOrderTotal(...) }</pre>
 *
 * Unlike interface projections, class projections support arbitrary SELECT
 * lists (aggregations, expressions) because the query controls the constructor
 * arguments. Plain class on purpose: JPQL {@code new} needs a real constructor;
 * Java records arrive as DTOs in PR #21.
 */
public class CustomerOrderTotal {

    private final String email;
    private final BigDecimal totalSpent;

    public CustomerOrderTotal(String email, BigDecimal totalSpent) {
        this.email = email;
        this.totalSpent = totalSpent;
    }

    public String getEmail() {
        return email;
    }

    public BigDecimal getTotalSpent() {
        return totalSpent;
    }
}
