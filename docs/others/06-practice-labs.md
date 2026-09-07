# 06 — Practice Labs

Purpose: **rebuild the concept from scratch** (no copy-paste from this repo) so
the knowledge becomes yours. Each lab states acceptance criteria so you can
self-grade. Difficulty: ◆ = an afternoon, ◆◆ = a weekend, ◆◆◆ = a week.

Rules: attempt without looking first; then diff against the reference in this
repo (the lab names the classes). Break it (see `04`) before you consider it
done.

## Lab 1 — Schema that teaches (◆)
Design an `orders` schema on paper, then implement with 3 Liquibase
changesets: customers, products+orders+items, payments. Every FK indexed with
an explicit delete rule; money as NUMERIC. Write the schema-truth test (tables,
FK rules, index coverage).
Acceptance: a fresh DB migrates clean; `ddl-auto: validate` passes against
entities you will write in Lab 2; a test fails if an FK rule changes.
Reference: `db/changelog/v1.0/*`, `DatabaseSchemaIntegrationTest`.

## Lab 2 — JPA mappings from scratch (◆)
Entities for Lab 1's schema: all four cardinalities, owning/inverse correct.
Prove orphan removal deletes only with cascade (test both ways).
Acceptance: mapping tests; cascade test that actually asserts the DELETE row
count. Reference: `domain/*`, `JpaCascadingIntegrationTest`.

## Lab 3 — Kill the N+1 (◆)
Load 50 orders with customers/items. First measure N+1 (statement count), then
fix with fetch join AND entity graph AND batch size; assert one query for the
collection case.
Acceptance: statement-count test green for each fix. Reference:
`CustomerRepository`, `NPlusOneDemoTest`, `BatchFetchIntegrationTest`.

## Lab 4 — Last-unit concurrency (◆)
Stock = 1; 100 threads/requests try to buy. Implement @Version + retry. Write
the "exactly one winner" test. Then write the pessimistic variant.
Acceptance: one-winner test; retry-not-on-insufficient-stock test.
Reference: `ProductStockService`, `OptimisticLockingTest`,
`PessimisticLockingIntegrationTest`.

## Lab 5 — Cache-aside with an eviction bug hunt (◆◆)
Wrap a read in @Cacheable; add a writer OUTSIDE the cached service that changes
stock; write a test that proves staleness; then add the eviction and prove
freshness. Add hit/miss gauges.
Acceptance: stale test fails before fix, passes after; metrics gauges exist.
Reference: `ProductCatalogueService`, `CachingRedisIntegrationTest`.

## Lab 6 — One clean resource (◆◆)
Pick customers: full CRUD, DTO records, mapper, validation groups, RFC 7807
handler with catalog codes, scope-matrix row for every endpoint, one negative
auth test each.
Acceptance: no entity on the wire; 404 for unknown id; unknown URL returns 404
not 500; guards match the matrix. Reference: `CustomerController`,
`GlobalExceptionHandler`, `RestControllerIntegrationTest`.

## Lab 7 — Reliable event publish (◆◆◆)
Order placement writes an outbox row in its transaction; a poller publishes to
Kafka; a consumer acks manually; a poison message ends on the DLT. Add a
consumer dedupe by event id.
Acceptance: embedded-Kafka test proves round trip + DLT + duplicate ignored.
Reference: `OutboxPublisher`, `OrderEventConsumer`, `KafkaOutboxIntegrationTest`.

## Lab 8 — Resilient gateway (◆◆)
Wrap a flaky downstream in bulkhead→breaker→retry. Chaos-inject failures and
latency; assert OPEN, fast-fail, HALF_OPEN recovery, and that retry sits inside
the breaker (request-count semantics).
Acceptance: chaos test suite; count-based breaker assertion.
Reference: `SimulatedPaymentGateway`, `ResilienceChaosIntegrationTest`.

## Lab 9 — Protect the data (◆◆)
Add masking to responses for non-privileged callers; redact bodies in logs; add
erasure (delete vs anonymize) and portability; audit without PII.
Acceptance: mask/raw matrix tests; log-scrub test; erasure idempotence; audit
survives deletion. Reference: `security/pii/*`, `GdprService`,
`GdprPiiIntegrationTest`.

## Lab 10 — Make it observable (◆◆)
Add correlation ids (MDC + echo), one @Timed metric, a health indicator, and
JSON logging in a prod profile; assert all four.
Acceptance: correlation header round-trip; timer appears in Prometheus text;
health component present. Reference: `CorrelationIdFilter`,
`ObservabilityConfig`, `ObservabilityIntegrationTest`.

## Lab 11 — Ship it (◆◆)
Multi-stage Dockerfile; compose with DB + your service; k8s Deployment with
three probes; a Kustomize overlay; run the image for real and apply manifests
to a local cluster.
Acceptance: image builds & runs; readiness/liveness OK in-cluster;
`kustomize build` green. Reference: `Dockerfile`, `k8s/base`,
`docs/next-time/06`.

## Self-grade after each lab
- Reached acceptance first try? → you are ≥ level 2 on that concept.
- Needed `04` to debug? → you are level 2 → 3.
- Could not finish without the reference? → re-read `docs/learnings` chapter,
  wait 48h, redo. This spacing is the point.
