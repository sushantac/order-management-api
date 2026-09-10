package com.company.orderapi.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads project documentation from markdown files, chunks them into
 * overlapping segments, embeds them, and stores the vectors in the persistent
 * pgvector-backed vector store.
 *
 * <p>Only active when {@code app.rag.enabled=true}. Runs once on application
 * startup ({@link ApplicationReadyEvent}) and can be re-triggered later via the
 * guarded {@code reindex_docs} MCP write tool.
 *
 * <p>PR #42 - re-indexing is now *incremental and content-addressed*: every
 * chunk carries a deterministic id (UUID of {@code source + chunkIndex}) and
 * the sha-256 of its source file's content in metadata. {@link #reindex()}
 * compares the on-disk files against what the index already holds and:
 * <ul>
 *   <li>skips files whose content hash is unchanged (no re-embedding costs),</li>
 *   <li>deletes the stale chunks of a changed file and embeds them fresh,</li>
 *   <li>removes all chunks whose source file disappeared from the corpus.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private static final String CONTENT_HASH_KEY = "content-hash";
    private static final String SOURCE_KEY = "source";
    private static final String RESOURCE_PATH_KEY = "resource-path";

    /** A corpus file read off disk: name, full content, content hash. */
    public record SourceFile(String name, String content, String contentHash) {
    }

    /** What one {@link #reindex()} pass did, for the tool reply + tests. */
    public record ReindexReport(
            int filesReindexed,
            int chunksAdded,
            int chunksDeleted,
            int filesUnchanged,
            List<String> sourcesRemoved
    ) {
    }

    private final VectorIndexStore indexStore;
    private final ResourcePatternResolver resourceResolver;
    private final RagProperties ragProperties;

    private volatile boolean indexed = false;

    public DocumentIngestionService(
            VectorIndexStore indexStore,
            ResourceLoader resourceLoader,
            RagProperties ragProperties) {
        this.indexStore = indexStore;
        this.resourceResolver = new PathMatchingResourcePatternResolver(resourceLoader);
        this.ragProperties = ragProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void ingestDocuments() {
        if (indexed) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            ReindexReport report = reindex();
            if (report.filesReindexed() + report.filesUnchanged() == 0
                    && report.sourcesRemoved().isEmpty()) {
                log.warn("No documents found at '{}'. RAG search will return empty results.",
                        ragProperties.docsLocation());
                return;
            }
            indexed = true;
            long elapsed = System.currentTimeMillis() - start;
            log.info("RAG: indexed {} docs ({} chunks added, {} deleted, {} sources removed, {}ms)",
                    report.filesReindexed(), report.chunksAdded(), report.chunksDeleted(),
                    report.sourcesRemoved().size(), elapsed);
        } catch (IOException e) {
            log.error("RAG: failed to ingest documents from '{}': {}",
                    ragProperties.docsLocation(), e.getMessage());
        }
    }

    /**
     * Incremental re-index: re-embeds only changed/new files, deletes chunks of
     * removed files, and skips unchanged files entirely. Idempotent - calling it
     * twice with no changes touches nothing. Used by the guarded
     * {@code reindex_docs} MCP tool and by tests.
     */
    public ReindexReport reindex() throws IOException {
        List<SourceFile> files = loadFiles();
        return reindex(files);
    }

    ReindexReport reindex(List<SourceFile> files) {
        Set<String> loadedNames = new HashSet<>();
        for (SourceFile file : files) {
            loadedNames.add(file.name());
        }

        // Files that are still in the index but no longer on disk.
        List<String> removedSources = new ArrayList<>();
        for (String source : indexStore.sources()) {
            if (!loadedNames.contains(source)) {
                removedSources.add(source);
            }
        }

        int filesReindexed = 0;
        int chunksAdded = 0;
        int chunksDeleted = 0;
        int filesUnchanged = 0;

        TokenTextSplitter splitter = new TokenTextSplitter(
                ragProperties.chunkSize(),
                ragProperties.chunkOverlap(),
                10, 10000, true);

        for (SourceFile file : files) {
            List<VectorIndexStore.StoredChunk> existing = indexStore.chunksBySource(file.name());
            boolean unchanged = !existing.isEmpty()
                    && existing.stream().allMatch(c -> file.contentHash().equals(c.contentHash()));
            if (unchanged) {
                filesUnchanged++;
                continue;
            }

            indexStore.deleteChunks(existing.stream().map(VectorIndexStore.StoredChunk::id).toList());
            chunksDeleted += existing.size();

            List<Document> chunks = chunk(file, splitter);
            indexStore.addChunks(chunks);
            chunksAdded += chunks.size();
            filesReindexed++;
        }

        for (String removed : removedSources) {
            List<VectorIndexStore.StoredChunk> stale = indexStore.chunksBySource(removed);
            indexStore.deleteChunks(stale.stream().map(VectorIndexStore.StoredChunk::id).toList());
            chunksDeleted += stale.size();
        }

        return new ReindexReport(filesReindexed, chunksAdded, chunksDeleted, filesUnchanged, removedSources);
    }

    /**
     * Loads all matching resources and returns one {@link SourceFile} per
     * non-blank file. The {@code name} is the file's RELATIVE PATH within the
     * docs root (e.g. {@code business/04-payments.md}), not its bare filename:
     * basenames are not unique in this corpus ({@code README.md} appears many
     * times), and chunk ids are derived from the source name, so a collision
     * would silently merge chunks from different files.
     */
    List<SourceFile> loadFiles() throws IOException {
        Resource[] resources = resourceResolver.getResources(ragProperties.docsLocation());
        log.info("RAG: found {} markdown files at '{}'", resources.length, ragProperties.docsLocation());

        String patternDir = directoryRootOf(ragProperties.docsLocation());

        List<SourceFile> files = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            try {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                if (content.isBlank()) {
                    continue;
                }
                String name = relativeName(resource, patternDir, filename);
                files.add(new SourceFile(name, content, sha256Hex(content)));
            } catch (IOException e) {
                log.warn("RAG: skipping unreadable resource {}: {}", filename, e.getMessage());
            }
        }
        return files;
    }

    private List<Document> chunk(SourceFile file, TokenTextSplitter splitter) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(SOURCE_KEY, file.name());
        metadata.put(CONTENT_HASH_KEY, file.contentHash());

        Document doc = new Document(file.content(), metadata);
        List<Document> chunks = splitter.apply(List.of(doc));
        List<Document> tagged = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String id = UUID.nameUUIDFromBytes((file.name() + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            Document withId = new Document(id, chunk.getText(), chunk.getMetadata());
            withId.getMetadata().put(SOURCE_KEY, file.name());
            withId.getMetadata().put(CONTENT_HASH_KEY, file.contentHash());
            tagged.add(withId);
        }
        return tagged;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Directory part of the pattern (the {@code docs/} in
     * {@code classpath:docs/**&#47;*.md}), used to compute each file's relative
     * path.
     */
    private static String directoryRootOf(String location) {
        String after = location.startsWith("classpath:")
                ? location.substring("classpath:".length())
                : location;
        int firstStar = after.indexOf('*');
        String dir = (firstStar >= 0 ? after.substring(0, firstStar) : after)
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        return dir.isEmpty() ? dir : dir + "/";
    }

    /**
     * Relative path of a resource within the docs root, falling back to the bare
     * filename if the root marker cannot be located (e.g. for jars).
     */
    private static String relativeName(Resource resource, String patternDir, String fallback) {
        try {
            String marker = "/" + patternDir;
            String path = resource.getURL().getPath();
            int idx = path.lastIndexOf(marker);
            if (idx >= 0) {
                return path.substring(idx + marker.length());
            }
        } catch (IOException ignored) {
            // fall through to the filenames
        }
        return fallback;
    }

    /**
     * Returns true if documents have been successfully indexed.
     */
    public boolean isIndexed() {
        return indexed;
    }
}