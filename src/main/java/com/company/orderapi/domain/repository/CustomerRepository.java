package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Customer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Customer}.
 *
 * <p>PR #6 - this repository carries three equivalent "fetch the addresses with
 * the customers" queries plus the deliberately naive one used to demonstrate
 * the N+1 problem:
 * <ul>
 *   <li>{@code findAll()} (inherited)            - N+1: lazy addresses fire per
 *       customer when traversed.</li>
 *   <li>{@code findAllWithAddressesJoinFetch()}  - one JPQL {@code JOIN FETCH}.</li>
 *   <li>{@code findAllWithAddressesEntityGraph()}- programmatic
 *       {@code @EntityGraph(attributePaths)} on the query.</li>
 *   <li>{@code findAllWithAddressesNamedEntityGraph()} - reusable
 *       {@code @NamedEntityGraph} declared on the entity.</li>
 * </ul>
 *
 * <p>PR #12 - {@link JpaSpecificationExecutor} adds {@code findAll(Specification)},
 * {@code count(Specification)} and friends for dynamic, type-safe queries; the
 * building blocks live in {@link CustomerSpecifications}.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long>,
        JpaSpecificationExecutor<Customer> {

    /**
     * PR #17 - interface-based projection: Spring Data generates a proxy whose
     * getters are fed from the selected columns (aliases must match getters).
     * Only the listed columns are fetched - the whole entity is NOT loaded.
     */
    interface CustomerNameProjection {
        String getEmail();

        String getFullName();
    }

    Optional<Customer> findByEmail(String email);

    @Query("""
            select c.email as email, c.fullName as fullName
            from Customer c
            order by c.fullName
            """)
    List<CustomerNameProjection> findAllCustomerNameProjections();

    /**
     * Fix 1 - JPQL JOIN FETCH.
     * {@code join fetch} is imperative SQL-level control: one query, addresses
     * joined and marked as fetched. {@code distinct} removes the row duplication
     * a collection join introduces.
     */
    @Query("select distinct c from Customer c join fetch c.addresses")
    List<Customer> findAllWithAddressesJoinFetch();

    /**
     * Fix 2 - declarative {@code @EntityGraph} with attribute paths.
     * No JPQL join needed: the repository method tells Hibernate which
     * attributes to fetch. {@code attributePaths} can be nested
     * (e.g. "orders.items").
     */
    @EntityGraph(attributePaths = {"addresses"})
    @Query("select distinct c from Customer c")
    List<Customer> findAllWithAddressesEntityGraph();

    /**
     * Fix 3 - a named entity graph declared once on the entity
     * ({@code @NamedEntityGraph(name = "Customer.addresses")}) and referenced by
     * name, so several repository methods / services can reuse the same fetch
     * recipe without repeating attribute lists.
     */
    @EntityGraph("Customer.addresses")
    @Query("select distinct c from Customer c")
    List<Customer> findAllWithAddressesNamedEntityGraph();
}
