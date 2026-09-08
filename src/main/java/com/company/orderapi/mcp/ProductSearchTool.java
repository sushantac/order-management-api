package com.company.orderapi.mcp;

import com.company.orderapi.domain.Product;
import com.company.orderapi.domain.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PR #36 - MCP tool: search the product catalogue by name.
 *
 * <p>Read-only, no customer data. Search is a case-insensitive substring match
 * on the product name; results are ordered by name and capped by
 * {@code maxResults} (default 10, max 50).
 */
@Component
public class ProductSearchTool implements McpTool {

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public ProductSearchTool(ProductRepository productRepository, ObjectMapper objectMapper) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
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
    public JsonNode inputSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("query", objectMapper.createObjectNode()
                .put("type", "string")
                .put("description", "Substring to match against product names."));
        properties.set("maxResults", objectMapper.createObjectNode()
                .put("type", "integer")
                .put("description", "Maximum matches to return (1-50).")
                .put("default", 10));
        return objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", properties);
    }

    @Override
    public String execute(Map<String, JsonNode> arguments) {
        String query = optionalText(arguments, "query");
        int maxResults = clamp(optionalInt(arguments, "maxResults", 10), 1, 50);

        List<Product> products = productRepository.findAll(Sort.by("name"));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Product product : products) {
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

    private String optionalText(Map<String, JsonNode> arguments, String key) {
        JsonNode node = arguments.get(key);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private int optionalInt(Map<String, JsonNode> arguments, String key, int fallback) {
        JsonNode node = arguments.get(key);
        return node != null && node.isNumber() ? node.asInt() : fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
