package com.company.orderapi.domain.repository;

import com.company.orderapi.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Category} (first used by the REST API in
 * PR #22).
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {
}
