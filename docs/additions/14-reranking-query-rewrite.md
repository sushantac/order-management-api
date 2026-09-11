# 14. Semantic Reranking & Query Rewriting

> **PR #51** — Two lightweight RAG refinements: query rewriting expands a question into paraphrases, semantic reranking re-orders candidates by lexical overlap (placeholder for cross-encoder).

Disabled by default (`app.rag.query-rewriting.enabled`, `app.rag.reranking.enabled`). When on, `RagQueryRewriter` uses the chat model to generate variants, `SemanticReranker` scores docs.

*Next: PR #52 — A2A multi-agent.*
