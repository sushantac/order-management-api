package com.company.orderapi.domain.service;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR #9 - pessimistic locking on the product stock counter.
 *
 * <p>Where PR #8 (optimistic) lets conflicts happen and retries, pessimistic
 * locking PREVENTS them: {@code SELECT ... FOR UPDATE} takes an exclusive row
 * lock up front, so competing transactions queue on the row instead of
 * aborting. Choice depends on contention and read/write mix - see
 * {@code LockingPerformanceComparisonTest}.
 */
@Service
public class ProductInventoryService {

    private final ProductRepository productRepository;
    private final ProductCatalogueService catalogue;

    public ProductInventoryService(ProductRepository productRepository,
                                   ProductCatalogueService catalogue) {
        this.productRepository = productRepository;
        this.catalogue = catalogue;
    }

    /** PESSIMISTIC_WRITE: serializes stock decrements on the same row. */
    @Transactional
    public void decrementStockPessimistic(Long productId, int quantity) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + productId));
        if (product.getStockQuantity() < quantity) {
            throw new IllegalStateException("Insufficient stock for product " + productId);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        catalogue.evict(productId); // PR #28 - refresh the cached catalogue view
    }

    /**
     * PESSIMISTIC_READ (PostgreSQL: {@code FOR SHARE}): a shared-lock peek used
     * to "read and decide" safely against a concurrent writer. Writers block
     * while any share lock is held; other readers do not.
     *
     * <p>Note: NOT read-only - PostgreSQL refuses {@code FOR SHARE} inside a
     * read-only transaction because taking a lock is itself a write action.
     */
    @Transactional
    public int peekStockPessimisticRead(Long productId) {
        Product product = productRepository.findByIdForShare(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + productId));
        return product.getStockQuantity();
    }

    /**
     * DEMONSTRATION ONLY: holds the exclusive row lock for {@code holdMillis}
     * before writing, so a test can prove a second transaction really blocks
     * until this one commits. Never sleep inside a real transaction in
     * production.
     */
    @Transactional
    public void decrementStockHoldingLock(Long productId, int quantity, long holdMillis) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + productId));
        sleep(holdMillis);
        if (product.getStockQuantity() < quantity) {
            throw new IllegalStateException("Insufficient stock for product " + productId);
        }
        product.setStockQuantity(product.getStockQuantity() - quantity);
        catalogue.evict(productId); // PR #28 - refresh the cached catalogue view
    }

    /**
     * PR #9 - deadlock handling demonstration.
     *
     * <p>Locks {@code from} then {@code to}. Two threads calling this in
     * OPPOSITE orders (A->B and B->A) create the classic deadlock: PostgreSQL
     * detects it, aborts the victim, and the database translates the abort to
     * {@link DeadlockLoserDataAccessException}. {@code @Retryable} re-runs the
     * whole method in a fresh transaction against the now-uncontended rows.
     *
     * <p>The sleep between the two locks deliberately widens the race window so
     * the deadlock actually happens in the test.
     */
    @Transactional
    @Retryable(
            retryFor = {DeadlockLoserDataAccessException.class, CannotAcquireLockException.class},
            maxAttempts = 5,
            backoff = @Backoff(delay = 150))
    public void moveStockPessimistic(Long fromProductId, Long toProductId, int quantity) {
        Product from = productRepository.findByIdForUpdate(fromProductId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + fromProductId));
        sleep(150); // widen the deadlock window (demonstration only)
        Product to = productRepository.findByIdForUpdate(toProductId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product " + toProductId));

        if (from.getStockQuantity() < quantity) {
            throw new IllegalStateException("Insufficient stock on product " + fromProductId);
        }
        from.setStockQuantity(from.getStockQuantity() - quantity);
        to.setStockQuantity(to.getStockQuantity() + quantity);
        catalogue.evict(fromProductId); // PR #28 - both products changed stock
        catalogue.evict(toProductId);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding lock", e);
        }
    }
}
