package com.company.orderapi.rag;

import com.company.orderapi.rag.eval.RagRetrievalEvaluator;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configuration for the RAG (Retrieval-Augmented Generation) feature.
 *
 * <p>Only active when {@code app.rag.enabled=true}.
 *
 * <p>PR #42: the vector store moved from {@code SimpleVectorStore} (in-memory,
 * lost on restart) to {@link PgVectorStore} — persistent pgvector storage on
 * the app's own PostgreSQL, with the identical {@link VectorStore} interface.
 * The schema (vector extension, table, HNSW index) is created idempotently on
 * first use via {@code initializeSchema(true)}. The {@link VectorIndexStore}
 * bean is the read-side lens that the incremental re-indexer needs.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, DataSource dataSource,
                                   RagProperties ragProperties) {
        return PgVectorStore.builder(new JdbcTemplate(dataSource), embeddingModel)
                .vectorTableName(ragProperties.vectorTable())
                .dimensions(ragProperties.embeddingDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }

    @Bean
    public VectorIndexStore vectorIndexStore(JdbcTemplate jdbcTemplate, VectorStore vectorStore,
                                             RagProperties ragProperties) {
        return new PgVectorIndexStore(jdbcTemplate, vectorStore, ragProperties.vectorTable());
    }

    @Bean
    public RagRetrievalEvaluator ragRetrievalEvaluator() {
        return new RagRetrievalEvaluator();
    }
}