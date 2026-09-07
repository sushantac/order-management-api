package com.company.orderapi.domain.service;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR #8 - optimistic locking + retry on a contended row.
 *
 * <p>Scenario: N workers race to decrement a product's stock. Without locking,
 * two workers can read stock=1 and both write 0 (one decrement is lost - a
 * customer is oversold). With {@code @Version}, the second UPDATE matches 0
 * rows and Hibernate throws {@link OptimisticLockingFailureException} - the
 * write is simply rejected.
 *
 * <p>{@code @Retryable} turns that rejection into a retry: the whole method
 * re-runs, reads the newest version and tries again. Because only a FEW of the
 * 100 concurrent calls can collide, a bounded number of attempts with a small
 * backoff makes every call eventually succeed - without any pessimistic locks
 * or serialisation.
 */
@Service
public class ProductStockService {

    private final ProductRepository productRepository;
    private final ProductCatalogueService catalogue;

    public ProductStockService(ProductRepository productRepository,
                               ProductCatalogueService catalogue) {
        this.productRepository = productRepository;
        this.catalogue = catalogue;
    }

    @Transactional
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 50,
            backoff = @Backoff(delay = 5))
    public void decreaseStock(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + productId));

        if (product.getStockQuantity() < quantity) {
            // A business rejection must NOT be retried - it will never succeed.
            throw new IllegalStateException("Insufficient stock for product " + productId);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        // PR #28 - invalidate the cached catalogue entry for this product.
        catalogue.evict(productId);
        // Dirty checking: at commit Hibernate runs
        //   UPDATE products SET stock_quantity=?, version=? WHERE id=? AND version=?
        // 0 rows matched => optimistic lock conflict => @Retryable re-runs.
    }
}
