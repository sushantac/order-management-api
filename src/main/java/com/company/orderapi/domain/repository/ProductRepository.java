package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository for {@link Product}.
 *
 * <p>Created in PR #8 (optimistic locking on the stock counter) and extended in
 * PR #9 with PESSIMISTIC lock variants:
 * <ul>
 *   <li>{@link #findByIdForUpdate} - {@code PESSIMISTIC_WRITE}: the row is
 *       {@code SELECT ... FOR UPDATE} - exclusively locked until commit.</li>
 *   <li>{@link #findByIdForShare}  - {@code PESSIMISTIC_READ}: PostgreSQL maps
 *       this to {@code SELECT ... FOR SHARE} - writers are excluded, readers
 *       are not.</li>
 * </ul>
 * Locking queries MUST run inside a transaction (the service layer provides it).
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * PR #9 - {@code PESSIMISTIC_WRITE}: generate {@code SELECT ... FOR UPDATE}.
     * The row is locked for this transaction the moment it is read; any other
     * transaction that tries to UPDATE it blocks until this one commits
     * (or the lock times out / a deadlock is detected).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * PR #9 - {@code PESSIMISTIC_READ}: PostgreSQL translates this to
     * {@code SELECT ... FOR SHARE}. Multiple readers may hold the share lock;
     * writers must wait. Use for "peek and decide" reads that must not race a
     * concurrent write.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForShare(@Param("id") Long id);
}
