package com.company.orderapi.rag.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One golden question in the retrieval-eval harness: a real question a user
 * might ask, and the source document that MUST be retrieved to answer it.
 *
 * <p>Serialised/deserialised as {@code {"question": "...", "expected-source":
 * "01-foo.md"}} so the golden file stays greppable.
 */
public record GoldenQuestion(
        String question,
        @JsonProperty("expected-source") String expectedSource
) {
}