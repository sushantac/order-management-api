package com.company.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A postal address belonging to one customer.
 *
 * <p>PR #3 focus: this is the OWNING side of the Customer-Address relation.
 * {@code @ManyToOne} + {@code @JoinColumn(name = "customer_id")} tells
 * Hibernate that the {@code customers} table is referenced through the
 * {@code addresses.customer_id} foreign key column. That column is written
 * exactly once (here) and the {@code Customer.addresses} side simply refers
 * back with {@code mappedBy}.
 *
 * <p>Many-to-one (NOT nullable) is the correct JPA default: every address must
 * belong to a customer, which matches the database's NOT NULL + CASCADE FK.
 */
@Entity
@Table(name = "addresses")
public class Address extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "street", nullable = false, length = 255)
    private String street;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 100)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_type", nullable = false, length = 32)
    private AddressType addressType = AddressType.HOME;

    protected Address() {
        // for JPA
    }

    public Address(Customer customer, String street, String city, String country) {
        this.customer = customer;
        this.street = street;
        this.city = city;
        this.country = country;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public AddressType getAddressType() {
        return addressType;
    }

    public void setAddressType(AddressType addressType) {
        this.addressType = addressType;
    }
}
