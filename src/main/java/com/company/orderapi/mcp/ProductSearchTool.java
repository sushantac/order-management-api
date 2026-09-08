package com.company.orderapi.mcp;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PR #37 - MCP tool: search the product catalogue by name.
 *
 * <p>Read-only, no customer data. Search is a case-insensitive substring match
 * on the product name; results are ordered by name and capped by
 * {@code maxResults} (default 10, max 50).
 */
@Component
public class ProductSearchTool extends AbstractMcpReadOnlyTool {

    private final ProductRepository productRepository;

    public ProductSearchTool(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public String name() {
        return "product_search";
    }

    @Override
    public String description() {
        return "Search the product catalogue by name. Returns product id, name, "
                + "price and available stock for up to maxResults matches.";
    }

    @Override
    public JsonSchema inputSchema() {
        return objectSchema(Map.of(
                "query", Map.of("type", "string",
                        "description", "Substring to match against product names."),
                "maxResults", Map.of("type", "integer",
                        "description", "Maximum matches to return (1-50).",
                        "default", 10)),
                List.of());
    }

    @Override
    protected String run(Map<String, Object> arguments) {
        String query = optionalText(arguments, "query");
        int maxResults = clamp(optionalInt(arguments, "maxResults", 10), 1, 50);

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Product product : productRepository.findAll(Sort.by("name"))) {
            if (shown >= maxResults) {
                break;
            }
            if (query != null && !query.isBlank()
                    && !product.getName().toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (shown > 0) {
                out.append('\n');
            }
            out.append(product.getId()).append(" | ")
                    .append(product.getName()).append(" | price ")
                    .append(product.getPrice().toPlainString()).append(" | stock ")
                    .append(product.getStockQuantity());
            shown++;
        }
        return shown == 0
                ? "No products found matching '" + query + "'."
                : out.toString();
    }
}
