package com.company.orderapi.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * A customer: the root of the graph (no foreign keys pointing out of it).
 *
 * <p>PR #3 teaches the two sides of a one-to-many mapping:
 * <ul>
 *   <li>{@code addresses} are owned by {@link Address} (the child holds the
 *       {@code customer_id} FK via {@code @ManyToOne} + {@code @JoinColumn}).</li>
 *   <li>{@code orders} are owned by {@link Order} ({@code mappedBy = "customer"}).</li>
 * </ul>
 * {@code mappedBy} says "look at the OTHER side's field for the join column";
 * the FK column is written exactly once, in the owning (many-to-one) entity.
 *
 * <p>PR #6 - {@code @NamedEntityGraph} declares a reusable fetch recipe that
 * repository methods opt into with {@code @EntityGraph("Customer.addresses")}.
 */
@Entity
@Table(name = "customers")
@NamedEntityGraph(
        name = "Customer.addresses",
        attributeNodes = @NamedAttributeNode("addresses"))
public class Customer extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    /**
     * Bidirectional one-to-many; the owning side is {@code Address.customer}
     * which declares the actual {@code customer_id} join column.
     * PR #4: {@code cascade = PERSIST} - saving a new customer saves its
     * address book in the same flush.
     */
    // No explicit @BatchSize here on purpose: this role is used by the naive
    // N+1 demonstrations (PR #5/#6), which tune default_batch_fetch_size=1 to
    // show per-owner queries. The global default_batch_fetch_size=20 (PR #7)
    // applies to it everywhere else.
    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST)
    private List<Address> addresses = new ArrayList<>();

    /**
     * Bidirectional one-to-many; the owning side is {@code Order.customer}.
     * PR #4: {@code cascade = PERSIST} - saving a new customer saves its orders
     * in the same flush (items/payment then follow via Order's own cascade).
     */
    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST)
    @BatchSize(size = 20)
    private List<Order> orders = new ArrayList<>();

    protected Customer() {
        // for JPA
    }

    public Customer(String email, String fullName) {
        this.email = email;
        this.fullName = fullName;
    }

    /** Links both sides of the Customer-Address relation at once. */
    public void addAddress(Address address) {
        addresses.add(address);
        address.setCustomer(this);
    }

    /** Links both sides of the Customer-Order relation at once. */
    public void addOrder(Order order) {
        orders.add(order);
        order.setCustomer(this);
    }

    /**
     * PR #13 {@code @PreRemove}: guard business rules on deletion. Deleting a
     * customer who still has orders would silently destroy their history (and,
     * thanks to DB CASCADE, their addresses too) - this callback refuses.
     * Note the DB would happily CASCADE; the callback is the BUSINESS rule.
     */
    @PreRemove
    void assertRemovalAllowed() {
        if (orders != null && !orders.isEmpty()) {
            throw new IllegalStateException(
                    "Customer " + getId() + " cannot be removed: still has "
                            + orders.size() + " order(s)");
        }
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public List<Address> getAddresses() {
        return addresses;
    }

    public List<Order> getOrders() {
        return orders;
    }
}
