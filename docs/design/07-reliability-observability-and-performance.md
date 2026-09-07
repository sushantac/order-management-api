# 07 — Reliability, Observability & Performance

## 7.1 Reliability design (downstream protection)
The payment gateway (and any similar downstream) is wrapped in an explicit
composed stack - outermost first:

1. **Thread-pool bulkhead**: gateway work on its own small pool
   (1 core / 2 max / queue 5) so slow gateway calls cannot starve DB/HTTP.
2. **Circuit breaker** (`paymentGateway`): CLOSED → OPEN on failure rate or
   **slow-call** threshold → HALF_OPEN probe after a wait → CLOSED on success.
   While OPEN, calls fail fast.
3. **Retry with exponential backoff** (200→400→800 ms, max 3) **inside** the
   breaker: one logical request = one breaker record; attempts retry within.

Config-driven via `resilience4j.*`; all failure modes normalize to
`PaymentFailedException` (→ 502 with rollback of the order transaction).

## 7.2 Abuse/load protection
Per-API-key rate limiting (bucket per key; 429 + `Retry-After` +
`X-RateLimit-Remaining/Reset`). v1.0 buckets are per-process (Resilience4j);
a shared/Redis limiter is forward plan for multi-instance quotas.

## 7.3 Observability (three pillars + health)
- **Logs**: structured JSON in prod (`logback-spring.xml`), human console in
  dev; MDC `correlationId` from the `X-Correlation-Id` filter; PII redacted
  from logged bodies in all profiles.
- **Metrics**: Micrometer `@Timed` on `order.place`/`product.get` (p95);
  HTTP request metrics (Boot); Redis keyspace hit/miss gauges; Prometheus
  scrape at `/actuator/prometheus`.
- **Traces**: OpenTelemetry bridge + OTLP exporter (prod); local Jaeger via
  compose; trace context propagated across HTTP.
- **Health**: `/actuator/health` (+ component details), `/liveness`,
  `/readiness` and startup probe endpoints for orchestration.

## 7.4 SLOs & alerting
| SLI | SLO (30d) | Alert (burn) |
|---|---|---|
| Order placement success | ≥ 99.5% | < 99.0% / 5m |
| Order placement p95 | < 500 ms | > 1 s / 5m |
| Product read p95 | < 300 ms | > 750 ms / 5m |
| Availability (5xx ratio) | ≥ 99.9% | > 0.1% / 5m |
| Gateway circuit opens | < 1/day | OPEN > 2 min |

Alert rules and SLO table live in `docs/slo` and `docs/monitoring`.

## 7.5 Performance design & budgets
- **Catalogue reads**: Redis cache-aside; default profile in-memory for tests.
- **List/dashboard reads**: projections + batch fetching (`default_batch_fetch_size=20`);
  dashboard uses virtual-thread fan-out for independent aggregates.
- **Writes**: optimistic concurrency + bounded retry (no unbounded spin);
  bulk orders execute per-element transactions.
- **Concurrency**: virtual threads for request handling; load-test script
  (`scripts/k6-load-test.js`) defines thresholds - v1.0 has not yet produced
  measured numbers (see §9 execution plan).
- DB resources: Hikari pool (size 10 default), indexed FKs, paged queries.

## 7.6 Failure scenarios (expected behaviour)
| Scenario | System behaviour |
|---|---|
| Gateway declines | retry ×3 → 502, order rolled back, nothing charged |
| Gateway slow/open | breaker opens, calls fail fast, half-open recovery |
| Redis down (prod) | cache reads degrade to DB (cache-aside); metrics show misses (health reflects Redis DOWN) |
| Kafka down | orders still succeed; outbox rows wait PENDING and publish later (at-least-once) |
| Poison Kafka message | bounded retry → DLT; group keeps consuming |
| Postgres down | readiness fails; API refuses work rather than corrupting state |
| Double click (retry) | idempotency key replays first response |
