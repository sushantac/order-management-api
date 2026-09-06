package com.company.orderapi.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
     * Order lines. {@code mappedBy = "order"} means OrderItem owns the FK.
     *
     * <p>PR #4 adds {@code cascade = CascadeType.ALL}: persisting (or removing)
     * an Order carries its items, and - as verified in PR #3 - the cascade is
     * what makes {@code orphanRemoval = true} actually DELETE a removed item.
     */
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * The (optional, 1:1) payment for this order. Inverse side: {@link Payment}
     * owns the {@code order_id} column. {@code cascade = CascadeType.ALL}
     * (PR #4) makes the payment lifecycle follow the order's lifecycle.
     */
    @OneToOne(mappedBy = "order", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, optional = true)
    private Payment payment;

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

    /** Links both sides of the Order-Payment 1:1 at once. */
    public void setPayment(Payment payment) {
        this.payment = payment;
        if (payment != null) {
            payment.setOrder(this);
        }
    }

    public Payment getPayment() {
        return payment;
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
