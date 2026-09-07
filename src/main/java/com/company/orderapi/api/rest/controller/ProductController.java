package com.company.orderapi.api.rest.controller;

import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.api.dto.ProductRequest;
import com.company.orderapi.api.dto.ProductResponse;
import com.company.orderapi.domain.repository.ProductRepository;
import com.company.orderapi.domain.service.ProductCatalogueService;
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
 * PR #22 - REST CRUD for products (catalogue is read-heavy; price/stock live
 * here). PR #28 - reads and writes go through {@link ProductCatalogueService},
 * where {@code @Cacheable}/{@code @CacheEvict} implement the cache-aside
 * pattern; the list stays on the repository (paged and uncached on purpose).
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductRepository products;
    private final ProductCatalogueService catalogue;

    public ProductController(ProductRepository products,
                             ProductCatalogueService catalogue) {
        this.products = products;
        this.catalogue = catalogue;
    }

    @GetMapping
    public Page<ProductResponse> list(Pageable pageable) {
        return products.findAll(pageable).map(OrderMapper::toProductResponse);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return catalogue.get(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = catalogue.create(request.name(), request.price(),
                request.stockQuantity(), request.description());
        return ResponseEntity
                .created(URI.create("/api/v1/products/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id,
                                  @Valid @RequestBody ProductRequest request) {
        return catalogue.update(id, request.name(), request.price(),
                request.stockQuantity(), request.description());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        catalogue.delete(id);
        return ResponseEntity.noContent().build();
    }
}
