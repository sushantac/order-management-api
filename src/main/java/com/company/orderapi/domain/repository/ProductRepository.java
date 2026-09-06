package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Product}.
 *
 * <p>Created in PR #8: optimistic locking is demonstrated on the product's
 * stock counter, which multiple threads decrement concurrently.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
