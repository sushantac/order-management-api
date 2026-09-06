package com.company.orderapi.api.rest.controller;

import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.api.dto.ProductRequest;
import com.company.orderapi.api.dto.ProductResponse;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * PR #22 - REST CRUD for products (catalogue is read-heavy; price/stock live here).
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public Page<ProductResponse> list(Pageable pageable) {
        return products.findAll(pageable).map(OrderMapper::toProductResponse);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return products.findById(id)
                .map(OrderMapper::toProductResponse)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        Product product = products.saveAndFlush(new Product(
                request.name(), request.price(), request.stockQuantity()));
        product.setDescription(request.description());
        products.flush();
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + product.getId()))
                .body(OrderMapper.toProductResponse(product));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        Product product = products.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + id));
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        products.flush();
        return OrderMapper.toProductResponse(product);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        products.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
