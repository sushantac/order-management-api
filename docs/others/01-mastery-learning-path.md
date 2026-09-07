# 01 — Mastery Learning Path (12-week plan)

Goal: take you from "understand the READMEs" to "can design, debug, explain and
teach each aspect cold". Time budget: **~5-6 h/week**. Each week = one concept
cluster with (a) build, (b) break, (c) teach-back, (d) measure.

How to read week rows: **Build** = implement the mini-lab in `06` (or extend
this repo). **Break** = deliberately introduce the classic failure and observe
it (see `04`). **Teach** = explain to a rubber duck / write a 300-word
"explain it" note. **Score** = fill the relevant rows in `02`.

## Weeks 1-3 — Foundations: schema, JPA, data access
| Week | Cluster | Build | Break | Teach |
|---|---|---|---|---|
| 1 | Migrations + schema design | Design an `orders` schema from scratch; 3 migrations | Edit a shipped changeset (checksum failure) | Why Liquibase + validate |
| 2 | Mappings, cascades, orphan removal | Model a 1:N + N:N; cascade matrix table | orphanRemoval without cascade → orphans remain | Owning vs inverse, cascade vs FK |
| 3 | Fetching & N+1 | Reproduce N+1; fix 3 ways | Projection alias mismatch; lazy on inverse 1:1 | When fetch-join vs entity-graph vs batching |

## Weeks 4-6 — Transactions, concurrency, correctness
| Week | Cluster | Build | Break | Teach |
|---|---|---|---|---|
| 4 | Transactions & optimistic locking | Last-unit race, @Version + retry | Remove @Version → oversell | Why retry ≠ retry business errors |
| 5 | Pessimistic locking & deadlocks | FOR UPDATE path; deadlock repro | Two-lock deadlock, then retry | Optimistic vs pessimistic trade-offs |
| 6 | Caching | Cache-aside + eviction audit | Stale stock after order (missed evict) | Cache DTOs not entities; invalidation |

## Weeks 7-8 — API & security
| Week | Cluster | Build | Break | Teach |
|---|---|---|---|---|
| 7 | REST/DTO/validation/errors | One resource CRUD with groups + RFC7807 | Catch-all handler swallowing 403/404 | Error contract; last-resort semantics |
| 8 | OAuth2/JWT + PII/GDPR | Scopes → @PreAuthorize; masking | Missing scope returns 500 not 403 | Resource server vs IdP; scope model |

## Weeks 9-10 — Integration & reliability
| Week | Cluster | Build | Break | Teach |
|---|---|---|---|---|
| 9 | Outbox + Kafka | Outbox + consumer + DLT | Publish-in-tx then broker down | At-least-once; manual ack; poison |
| 10 | Resilience | Breaker/retry/bulkhead + rate limit | Retry outside breaker (count drift) | Failure taxonomy; bulkheads |

## Weeks 11-12 — Concurrency, observability, operations
| Week | Cluster | Build | Break | Teach |
|---|---|---|---|---|
| 11 | Virtual threads + locks | Fan-out + distributed lock | Pin a carrier; lock lease crash | Virtual threads; local vs dist locks |
| 12 | Observability + GitOps | Correlation ids, @Timed, probes; image+overlay | 404→500; artifact never run | Pillars; SLOs; GitOps loop |

## Cross-cutting habits (all 12 weeks)
1. **Teach-back daily (5 min)**: pick yesterday's concept, explain aloud until
   no "um".
2. **Break-to-understand rule**: for every feature you build, deliberately
   break it and watch the failure signature before fixing.
3. **One-page notes**: after each week, write one page from memory (the Feynman
   test), then diff against `docs/learnings`.
4. **Weekly self-score** in `02`; aim +1 level on at least two rows weekly.

## After week 12 (maintenance mode)
- Monthly: re-run a weak cluster's lab; quarterly: full `02` re-score.
- Keep a **war-story journal** (template in `06`): each real debugging session
  you survive becomes interview and teaching material.
