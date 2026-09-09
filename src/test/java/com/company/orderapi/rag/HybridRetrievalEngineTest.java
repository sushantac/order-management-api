package com.company.orderapi.rag;

import com.company.orderapi.rag.support.HashEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HybridRetrievalEngine}: RRF fusion ordering is exact
 * math (given scripted sub-engines, the expected order is computed by hand
 * below), and MMR is exercised at its two extremes - lambda 0 (pure diversity)
 * and lambda 1 (pure relevance) - because intermediate lambdas depend on the
 * exact embedding, which is deliberately non-semantic in tests.
 */
class HybridRetrievalEngineTest {

    private static final int RRF_K = 60;

    private final HashEmbeddingModel embeddingModel = new HashEmbeddingModel(64);

    private RagProperties.RetrievalSettings settings(boolean mmrEnabled, double lambda) {
        return new RagProperties.RetrievalSettings(mmrEnabled, lambda, RRF_K);
    }

    @Test
    void rrfFusionOrdersByReciprocalRankWhenMmrDisabled() {
        Document a = doc("doc-a", "payment charge fails");
        Document b = doc("doc-b", "stock optimistic locking");
        Document c = doc("doc-c", "kafka outbox publish");
        Document d = doc("doc-d", "guarded write cancel");

        DenseRetrievalEngine dense = denseEngine();
        LexicalRetrievalEngine lexical = lexEngine();
        when(dense.retrieve(anyString(), anyInt())).thenReturn(List.of(a, b, c, d));
        when(lexical.retrieve(anyString(), anyInt())).thenReturn(List.of(b, d, a, c));

        HybridRetrievalEngine hybrid =
                new HybridRetrievalEngine(dense, lexical, embeddingModel, settings(false, 0.5));
        List<Document> topThree = hybrid.retrieve("fusion order", 3);

        // RRF score for each doc (1 / (60 + rank), summed across both lists):
        //   A = 1/61 + 1/63 = 0.0322665
        //   B = 1/62 + 1/61 = 0.0325225
        //   D = 1/64 + 1/62 = 0.0317540
        //   C = 1/63 + 1/64 = 0.0314980
        assertThat(ids(topThree)).containsExactly("doc-b", "doc-a", "doc-d");
        assertThat(ids(hybrid.retrieve("fusion order", 4))).containsExactly("doc-b", "doc-a", "doc-d", "doc-c");

        // Both engines are asked for a wider candidate pool than the final top-k.
        verify(dense, times(2)).retrieve("fusion order", 20);
        verify(lexical, times(2)).retrieve("fusion order", 20);
    }

    @Test
    void mmrAtLambdaZeroPrefersTheMostDiverseDocument() {
        Document first = doc("doc-dupe", "payment charge fails order rollback");
        Document duplicate = doc("doc-dupe-2", "payment charge fails order rollback");
        Document distinct = doc("doc-distinct", "stock optimistic locking inventory");

        DenseRetrievalEngine dense = denseEngine();
        LexicalRetrievalEngine lexical = lexEngine();
        when(dense.retrieve(anyString(), anyInt())).thenReturn(List.of(first, duplicate, distinct));
        when(lexical.retrieve(anyString(), anyInt())).thenReturn(List.of(distinct, duplicate, first));

        HybridRetrievalEngine hybrid =
                new HybridRetrievalEngine(dense, lexical, embeddingModel, settings(true, 0.0));
        List<Document> reranked = hybrid.retrieve("payment charge fails rollback", 3);

        // lambda = 0 ignores relevance entirely, so once `first` is selected the
        // duplicates are pushed to the back in favour of the dissimilar doc.
        assertThat(ids(reranked)).containsExactly("doc-dupe", "doc-distinct", "doc-dupe-2");
    }

    @Test
    void mmrAtLambdaOneKeepsRelevanceOrder() {
        Document verbose = doc("doc-verbose", "payment charge fails order rollback");
        Document crisp = doc("doc-crisp", "payment charge");
        Document unrelated = doc("doc-unrelated", "stock optimistic locking inventory");

        DenseRetrievalEngine dense = denseEngine();
        LexicalRetrievalEngine lexical = lexEngine();
        when(dense.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(verbose, crisp, unrelated));
        when(lexical.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(unrelated, crisp, verbose));

        HybridRetrievalEngine hybrid =
                new HybridRetrievalEngine(dense, lexical, embeddingModel, settings(true, 1.0));
        List<Document> reranked = hybrid.retrieve("payment charge", 3);

        // lambda = 1 keeps pure query-relevance: the crisp doc (query terms, no
        // noise) beats the verbose one; the unrelated doc comes last.
        assertThat(ids(reranked)).containsExactly("doc-crisp", "doc-verbose", "doc-unrelated");
    }

    private static DenseRetrievalEngine denseEngine() {
        return mock(DenseRetrievalEngine.class);
    }

    private static LexicalRetrievalEngine lexEngine() {
        return mock(LexicalRetrievalEngine.class);
    }

    private static Document doc(String id, String text) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("source", "test.md"))
                .build();
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream().map(Document::getId).toList();
    }
}