package com.company.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A product category.
 *
 * <p>PR #3 focus: the INVERSE side of the many-to-many. {@code mappedBy =
 * "categories"} refers to {@code Product.categories} - the field on the owning
 * side that maps this Set. Hibernate only reads from here; all join-table
 * writes happen through the owning {@link Product} side.
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @ManyToMany(mappedBy = "categories")
    private Set<Product> products = new LinkedHashSet<>();

    protected Category() {
        // for JPA
    }

    public Category(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Product> getProducts() {
        return products;
    }
}
