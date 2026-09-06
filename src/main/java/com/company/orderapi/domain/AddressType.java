package com.company.orderapi.domain;

/**
 * The kinds of address a customer can store.
 *
 * <p>Stored as {@code VARCHAR(32)} via {@code @Enumerated(STRING)} so values
 * stay readable in SQL reports. The constants MUST match the database CHECK
 * constraint created in PR #2 ({@code ck_addresses_address_type}); rename here
 * and in a new Liquibase changeset together.
 */
public enum AddressType {
    HOME,
    WORK,
    SHIPPING,
    BILLING
}
