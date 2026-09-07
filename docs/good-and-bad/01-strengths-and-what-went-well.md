# 01 — Strengths: what is genuinely good

## 1. Behaviour is tested against real infrastructure
The suite runs against real Postgres, Redis and Kafka (Testcontainers /
@EmbeddedKafka). Not mocked "unit" tests pretending to be integration:
- Schema tests query `information_schema`/`pg_index` and assert table count, FK
  delete rules, index coverage, and that `payments` has no card data.
- Concurrency is *proven*: 100 concurrent buyers on the last unit → exactly one
  winner; circuit breaker OPEN/HALF_OPEN/CLOSED asserted under injected failures
  and latency; lease expiry reclaims a "crashed" Redisson holder.
- Kafka round-trip and dead-letter routing are asserted end-to-end.
- The test-isolation lesson (Ehcache `CacheManager` is JVM-wide ⇒ each class
  needs its own context + DB via `integration.database.tag`) removed a whole
  class of flaky tests early.

## 2. Teaching-quality documentation in code
Comments explain *why*, not just *what*: owning/inverse mapping, cascade vs
orphanRemoval, why the outbox exists, why retry sits inside the breaker, why
cache eviction must reach the stock writers, why the audit trail has no FK and
no PII. The README records decisions and answered questions per PR. This is the
single biggest reason the project teaches well.

## 3. Layering where it counts
- Controllers are thin; DTO records cross the boundary; entities never do.
- A single explicit `OrderMapper` defines the API contract.
- Validation groups (`Create` vs `Update`) + cross-field custom validators
  keep request rules in one declarative place.
- One `@RestControllerAdvice` maps every exception to RFC 7807 with stable
  `code`s; clients can branch programmatically.
- Idempotency keys on the riskiest write (order placement) store and replay
  responses instead of double-executing.

## 4. Security is layered and coherent
- Stateless OAuth2 resource server; `scope` → `SCOPE_*` → `@PreAuthorize`.
- Privilege separation for PII: `order_read` gets masked data; only `pii_read`/
  API key sees raw values - enforced in the serializer, in `/view`, everywhere.
- Log redaction is caller-independent (log-sink rights ≠ viewer rights).
- GDPR erasure distinguishes DELETE vs ANONYMIZE (Art. 17(3)) and keeps an
  audit trail that survives physical erasure and stores no raw PII.
- PCI-DSS handled the only correct way for a small team: don't store card data,
  and *prove* it with a schema test.

## 5. Reliability mechanisms are real and composed deliberately
- `SimulatedPaymentGateway` shows an explicit, testable composition:
  bulkhead → circuit breaker → retry → call, all configured via properties.
- Retry-inside-breaker behaviour was verified empirically (requests vs attempts)
  and is documented - a subtlety most tutorials get wrong.
- The outbox gives atomic "event exists iff order exists"; manual acks + a DLT
  handle poison; consumers are told to be idempotent.

## 6. Caching with invalidation discipline
- Cache-aside on DTOs (never entities), TTL everywhere, and - the hard part -
  *every* writer that can change a cached read evicts (order/locking services
  evict stock keys). Hit/miss gauges make cache health visible.

## 7. Config over code, with comments
`application.yml` is a commented reference: master switches (`app.security.enabled`,
`app.kafka.enabled`, feature flags), per-profile differences (Redis/JSON logs/
tracing in prod), resilience4j tuning by properties. Behaviour changes rarely
need a code change.

## 8. Observability is present from the start of the stack
Correlation ids in MDC, structured JSON logs (prod), Micrometer timers with p95,
Prometheus export, optional OTel/Jaeger tracing, split liveness/readiness
probes, SLO definitions and alert rules.

## 9. Platform artifacts exist as real files
Multi-stage Dockerfile, whole-stack docker-compose, K8s base + per-env Kustomize
overlays, SealedSecret samples, ArgoCD example, GitHub Actions CI + promotion
workflow, k6 script, Postman collection, OpenAPI snapshot.

## 10. The process produced learning
One concept per PR ⇒ reviewable diffs; the empirical findings section of the
learnings doc is genuinely rare material for interviews (orphanRemoval needs
cascade; `@Lazy` needs the injection point; Redisson brings a second JSR-107
provider; a catch-all Exception handler turns 403 into 500; etc.).

## Honest caveat on the list above
"Good" here means *good for learning and for demonstrating breadth*. Several of
these strengths are smaller than they look at production scale - the next part
is the necessary counterweight.
