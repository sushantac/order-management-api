package com.company.orderapi.domain.service;

import com.company.orderapi.api.dto.OrderMapper;
import com.company.orderapi.api.dto.ProductResponse;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import io.micrometer.core.annotation.Timed;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * PR #28 - the product CATALOGUE read/write path, cached with the cache-aside
 * pattern (Spring {@code @Cacheable}/{@code @CacheEvict}).
 *
 * <p>Why cache the {@link ProductResponse} DTO and not the {@link Product}
 * entity? The cached object leaves the JPA session: an entity can carry lazy
 * collections, version/audit state and session identity that must not be
 * serialised into a shared store. The immutable view model is safe to cache
 * and is exactly what the API returns.
 *
 * <p>Invalidation rules (write-through discipline):
 * <ul>
 *   <li>catalogue writes ({@code create}/{@code update}/{@code delete}) evict
 *       the whole "products" cache (allEntries - simple and always correct);</li>
 *   <li>stock mutations made OUTSIDE the catalogue (order placement, the
 *       locking services) call {@link #evict(Long)} so the single entry is
 *       dropped and the next read repopulates fresh stock.</li>
 * </ul>
 */
@Service
public class ProductCatalogueService {

    public static final String CACHE_NAME = "products";

    private final ProductRepository products;

    public ProductCatalogueService(ProductRepository products) {
        this.products = products;
    }

    /**
     * Cache-aside read: on a hit the DTO comes from the cache and no SQL runs;
     * on a miss the method body loads + maps the product and the result is
     * stored under {@code products::<id>} with the configured TTL.
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "#id")
    @Transactional(readOnly = true)
    @Timed(value = "product.get", description = "Time to read a product",
            percentiles = 0.95)
    public ProductResponse get(Long id) {
        return products.findById(id)
                .map(OrderMapper::toProductResponse)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + id));
    }

    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    @Transactional
    public ProductResponse create(String name, BigDecimal price, int stockQuantity,
                                  String description) {
        Product product = products.saveAndFlush(
                new Product(name, price, stockQuantity));
        product.setDescription(description);
        products.flush();
        return OrderMapper.toProductResponse(product);
    }

    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    @Transactional
    public ProductResponse update(Long id, String name, BigDecimal price,
                                  int stockQuantity, String description) {
        Product product = products.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + id));
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setStockQuantity(stockQuantity);
        products.flush();
        return OrderMapper.toProductResponse(product);
    }

    @CacheEvict(cacheNames = CACHE_NAME, allEntries = true)
    @Transactional
    public void delete(Long id) {
        products.deleteById(id);
    }

    /**
     * Single-entry eviction for stock writers. The method body is empty - the
     * {@code @CacheEvict} annotation is the whole point: calling this bean
     * method from another bean guarantees the eviction goes through the
     * Spring proxy (self-invocation would silently skip it).
     */
    @CacheEvict(cacheNames = CACHE_NAME, key = "#productId")
    public void evict(Long productId) {
        // annotation-driven eviction
    }
}
