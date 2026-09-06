package com.company.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An order placed by a customer.
 *
 * <p>PR #3 mapping summary:
 * <ul>
 *   <li>{@code customer}    - owning @ManyToOne -> customers.id (NOT NULL)</li>
 *   <li>{@code shippingAddress} / {@code billingAddress} - @ManyToOne
 *       -> addresses.id, both nullable: an order may be created before the
 *       addresses are chosen, and deleting an address SET NULLs these FKs.</li>
 *   <li>{@code items}       - owned by {@link OrderItem}; orphanRemoval=true
 *       so removing an item from the list deletes the row (no orphans).</li>
 * </ul>
 *
 * <p>Note on column types: amounts are {@code NUMERIC(19,2)} in the DB, which
 * maps to {@link BigDecimal}. Money is NEVER a floating-point type.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_address_id")
    private Address shippingAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_address_id")
    private Address billingAddress;

    /**
     * Order lines. {@code mappedBy = "order"} means OrderItem owns the FK;
     * {@code orphanRemoval = true} declares that an item removed from this
     * list must never survive as an "orphan" row (no item without its order).
     *
     * <p>Verified behaviour (Hibernate 6.4 / Spring Boot 3.2.1): the DELETE
     * for an orphaned item is actually issued once the association carries a
     * cascade (ALL/REMOVE) - which PR #4 adds together with the cascade tests.
     */
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // for JPA
    }

    public Order(Customer customer, OrderStatus status, BigDecimal totalAmount) {
        this.customer = customer;
        this.orderDate = LocalDateTime.now();
        this.status = status;
        this.totalAmount = totalAmount;
    }

    /** Links both sides of the Order-OrderItem relation at once. */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public LocalDateTime getOrderDate() {
        return orderDate;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Address getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(Address shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public Address getBillingAddress() {
        return billingAddress;
    }

    public void setBillingAddress(Address billingAddress) {
        this.billingAddress = billingAddress;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
