package com.company.orderapi.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PR #46 - exposes the project's documentation corpus as MCP <em>resources</em>.
 *
 * <p>Until now the docs were only reachable through the RAG {@code docs_search}
 * tool: the assistant had to <em>ask</em> and trust the generated answer. A
 * resource is different - a client can enumerate the docs ({@code resources/list})
 * and read any file verbatim ({@code resources/read}), which is the right tool
 * when the assistant wants the <em>source document itself</em>, not a summary.
 * The two surfaces stay deliberately complementary: RAG for Dense Q&A, resources
 * for listable, citable reference.
 *
 * <p>Discovery scans the same classpath glob used by the RAG ingestion service
 * ({@code docs/**&#47;*.md}) plus the OpenAPI spec, so a new doc file is exposed
 * automatically the next time the app boots. Markdown files get URI
 * {@code doc://&lt;relative-path&gt;}; the OpenAPI spec gets a fixed
 * {@code openapi://spec}.
 */
@Component
public class McpDocsResourceCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpDocsResourceCatalog.class);

    private static final String DOCS_GLOB = "docs/**/*.md";
    private static final String OPENAPI_PATH = "docs/api/openapi.yaml";

    private final ResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    private final List<AbstractMcpResource> resources = new ArrayList<>();

    public McpDocsResourceCatalog() {
        discover();
    }

    private void discover() {
        try {
            scan(DOCS_GLOB);
        } catch (IOException e) {
            log.warn("MCP resources: could not scan '{}': {}", DOCS_GLOB, e.getMessage());
        }
        registerOpenApiSpec();
        log.info("MCP resources: exposed {} documentation resources",
                resources.size() == 0 ? "no" : resources.size());
    }

    private void scan(String pattern) throws IOException {
        Resource[] files = resolver.getResources(pattern);
        for (Resource file : files) {
            if (!file.isReadable()) {
                continue;
            }
            String path = filePath(file);
            resources.add(MarkdownResource.of(path, file));
        }
        resources.sort((a, b) -> a.uri().compareTo(b.uri()));
    }

    /**
     * Sibling of {@link AbstractMcpResource} that reads a classpath markdown file
     * lazily at read time so edits are reflected without a restart.
     */
    static final class MarkdownResource extends AbstractMcpResource {
        private final String relativePath;
        private final Resource file;

        private MarkdownResource(String relativePath, Resource file) {
            this.relativePath = relativePath;
            this.file = file;
        }

        static MarkdownResource of(String path, Resource file) {
            return new MarkdownResource(path, file);
        }

        @Override
        public String uri() {
            return "doc://" + relativePath;
        }

        @Override
        public String name() {
            return "docs/" + relativePath;
        }

        @Override
        public String description() {
            return "Order Management API documentation: " + relativePath;
        }

        @Override
        public String mimeType() {
            return "text/markdown";
        }

        @Override
        public String read() {
            try {
                return file.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot read documentation file " + relativePath, e);
            }
        }
    }

    private void registerOpenApiSpec() {
        Resource openApi = resolver.getResource("classpath:" + OPENAPI_PATH);
        resources.add(new AbstractMcpResource() {
            @Override
            public String uri() {
                return "openapi://spec";
            }

            @Override
            public String name() {
                return "OpenAPI specification";
            }

            @Override
            public String description() {
                return "The full OpenAPI specification (YAML) of the Order Management API "
                        + "REST surface - endpoints, schemas, security requirements.";
            }

            @Override
            public String mimeType() {
                return "application/yaml";
            }

            @Override
            public String read() {
                try {
                    return openApi.getContentAsString(StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read OpenAPI spec", e);
                }
            }
        });
    }

    private String filePath(Resource file) throws IOException {
        String path = file.getURI().toString();
        int idx = path.indexOf("/docs/");
        String relative = idx >= 0 ? path.substring(idx + 1) : path;
        return relative;
    }

    /**
     * The SDK resource specifications registered on the MCP server.
     */
    public List<McpStatelessServerFeatures.SyncResourceSpecification> specifications() {
        return resources.stream()
                .map(AbstractMcpResource::specification)
                .toList();
    }

    /**
     * Resource list for {@code resources/list}: every resource's contract.
     */
    public List<McpSchema.Resource> descriptors() {
        List<McpSchema.Resource> descriptors = new ArrayList<>();
        for (AbstractMcpResource r : resources) {
            descriptors.add(McpSchema.Resource.builder()
                    .uri(r.uri())
                    .name(r.name())
                    .description(r.description())
                    .mimeType(r.mimeType())
                    .build());
        }
        return descriptors;
    }
}