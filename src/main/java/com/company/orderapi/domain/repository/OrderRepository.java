package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Order;
import com.company.orderapi.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * Spring Data repository for {@link Order}.
 *
 * <p>PR #11 - every flavour of {@code @Query} on one repository:
 * <ul>
 *   <li>complex JPQL (joins/conditions/ordering),</li>
 *   <li>native SQL with an interface projection,</li>
 *   <li>pagination ({@code Pageable}/{@code Page}),</li>
 *   <li>SpEL expressions (accessing method-argument objects / null-safe
 *       dynamic filters).</li>
 * </ul>
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Dynamic amount-range filter, passed as a single object via SpEL. */
    record AmountRange(BigDecimal minTotal, BigDecimal maxTotal) {
    }

    /** PR #30 - grand total of every order (used by the fan-out dashboard). */
    @Query("select coalesce(sum(o.totalAmount), 0) from Order o")
    BigDecimal totalRevenue();

    /** Native-SQL projection: customer email + total money spent. */
    interface CustomerSpend {
        String getEmail();

        BigDecimal getTotalSpent();
    }

    /**
     * Complex JPQL: join through the customer, several predicates, ordering.
     * Note the association navigation {@code o.customer.id} - no explicit join
     * needed for a simple FK equality.
     */
    @Query("""
            select o from Order o
            where o.customer.id = :customerId
              and o.status = :status
              and o.totalAmount >= :minAmount
            order by o.orderDate desc
            """)
    List<Order> findRecentOrdersByCustomer(
            @Param("customerId") Long customerId,
            @Param("status") OrderStatus status,
            @Param("minAmount") BigDecimal minAmount);

    /**
     * Native SQL: runs on the database as-is. Results are mapped onto the
     * {@link CustomerSpend} projection (column aliases -> getters).
     */
    @Query(value = """
            SELECT c.email AS email,
                   SUM(o.total_amount) AS totalSpent
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            GROUP BY c.email
            ORDER BY totalSpent DESC
            """, nativeQuery = true)
    List<CustomerSpend> findCustomerSpendNative();

    /**
     * Pagination with {@code @Query}: Spring Data derives the count query and
     * slices with LIMIT/OFFSET (dialect-specific SQL).
     */
    @Query("select o from Order o")
    Page<Order> findOrdersPaged(Pageable pageable);

    /**
     * PR #17 - class-based projection via a JPQL constructor expression:
     * {@code new com.company.orderapi.domain.repository.CustomerOrderTotal(...)}.
     * Class projections can carry aggregates that interface projections cannot.
     */
    @Query("""
            select new com.company.orderapi.domain.repository.CustomerOrderTotal(
                       o.customer.email, sum(o.totalAmount))
            from Order o
            group by o.customer.email
            order by o.customer.email
            """)
    List<CustomerOrderTotal> findCustomerOrderTotals();

    /**
     * SpEL in {@code @Query}: a whole parameter object is dereferenced with
     * {@code :#{#range.minTotal()}}. Each component may be null; the guard
     * {@code :param is null or ...} makes every predicate optional, so this one
     * method covers 4 filter combinations without string concatenation.
     */
    @Query("""
            select o from Order o
            where (:#{#range.minTotal()} is null or o.totalAmount >= :#{#range.minTotal()})
              and (:#{#range.maxTotal()} is null or o.totalAmount <= :#{#range.maxTotal()})
            """)
    List<Order> findByAmountRange(@Param("range") AmountRange range);
}
