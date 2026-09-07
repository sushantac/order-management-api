package com.company.orderapi.api.rest.controller;

import com.company.orderapi.config.FeatureFlags;
import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

/**
 * PR #35 - CSV export of the product catalogue, gated behind the
 * {@code csvExport} feature flag ({@code app.features.csv-export}).
 */
@RestController
@RequestMapping("/api/v1/products")
public class ExportController {

    private final ProductRepository products;
    private final FeatureFlags flags;

    public ExportController(ProductRepository products, FeatureFlags flags) {
        this.products = products;
        this.flags = flags;
    }

    @PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_read', 'ROLE_API_KEY')")
    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv() {
        if (!flags.isCsvExport()) {
            return ResponseEntity.notFound().build(); // feature disabled
        }
        String csv = products.findAll().stream()
                .map(ExportController::toCsvRow)
                .collect(Collectors.joining("\n", "id,name,price,stockQuantity\n", "\n"));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    /** Minimal CSV escaping: wrap the name in quotes when it contains commas. */
    private static String toCsvRow(Product p) {
        String name = p.getName().contains(",") ? "\"" + p.getName() + "\"" : p.getName();
        return p.getId() + "," + name + "," + p.getPrice() + "," + p.getStockQuantity();
    }
}
