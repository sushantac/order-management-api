package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Customer;
import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * PR #12 - reusable, composable {@link Specification}s for {@link Customer}.
 *
 * <p>Each static method returns one predicate builder. Combining is done by the
 * caller ({@code where(...).and(...)}), so search screens stay declarative and
 * no SQL/JPQL string is ever concatenated at runtime.
 *
 * <p>All joins call {@code query.distinct(true)} so a customer matching several
 * orders is never returned twice.
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
        // utility class
    }

    /** Case-insensitive substring match on the full name (null-safe: no filter). */
    public static Specification<Customer> nameContains(String fragment) {
        return (root, query, cb) -> {
            if (fragment == null || fragment.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + fragment.toLowerCase() + "%";
            return cb.like(cb.lower(root.get("fullName")), pattern);
        };
    }

    /** Customers that have at least one order in the given status. */
    public static Specification<Customer> hasOrderStatus(OrderStatus status) {
        return (root, query, cb) -> {
            if (status == null) {
                return cb.conjunction();
            }
            if (Long.class != query.getResultType()) {
                query.distinct(true);
            }
            Join<Customer, Order> orders = root.join("orders");
            return cb.equal(orders.get("status"), status);
        };
    }

    /** Customers that have at least one order with a total >= minTotal. */
    public static Specification<Customer> hasOrderTotalAtLeast(BigDecimal minTotal) {
        return (root, query, cb) -> {
            if (minTotal == null) {
                return cb.conjunction();
            }
            if (Long.class != query.getResultType()) {
                query.distinct(true);
            }
            Join<Customer, Order> orders = root.join("orders");
            return cb.greaterThanOrEqualTo(orders.get("totalAmount"), minTotal);
        };
    }
}
