package com.company.orderapi.api.rest.controller;

import com.company.orderapi.domain.Category;
import com.company.orderapi.domain.repository.CategoryRepository;
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
import java.util.Map;

/**
 * PR #22 - REST CRUD for categories. Categories are deliberately thin: only a
 * name (immutable) and a description are exposed; the products relationship is
 * managed from the Product side.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categories;

    public CategoryController(CategoryRepository categories) {
        this.categories = categories;
    }

    @GetMapping
    public Page<CategoryView> list(Pageable pageable) {
        return categories.findAll(pageable).map(CategoryView::from);
    }

    @GetMapping("/{id}")
    public CategoryView get(@PathVariable Long id) {
        return categories.findById(id)
                .map(CategoryView::from)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category " + id));
    }

    @PostMapping
    public ResponseEntity<CategoryView> create(@RequestBody CategoryRequest request) {
        Category category = new Category(request.name());
        category.setDescription(request.description());
        categories.saveAndFlush(category);
        return ResponseEntity
                .created(URI.create("/api/v1/categories/" + category.getId()))
                .body(CategoryView.from(category));
    }

    @PutMapping("/{id}")
    public CategoryView update(@PathVariable Long id, @RequestBody CategoryRequest request) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category " + id));
        category.setDescription(request.description());
        categories.flush();
        return CategoryView.from(category);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categories.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record CategoryRequest(String name, String description) {
    }

    public record CategoryView(Long id, String name, String description) {
        static CategoryView from(Category category) {
            return new CategoryView(category.getId(), category.getName(), category.getDescription());
        }
    }
}
