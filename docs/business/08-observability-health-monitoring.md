# 08 — Observability, Health & Monitoring

## Health endpoints

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall status + component details (db, redis, disk, ping, appInfo) |
| `/actuator/health/liveness` | "Am I alive?" - for Kubernetes liveness probes |
| `/actuator/health/readiness` | "Can I take traffic?" - for Kubernetes readiness probes |
| `/actuator/info` | App name + description |

`/actuator/health` and `/actuator/info` are **public** (no auth) so load
balancers and orchestrators can poll them; detailed health is enabled in dev
and should be restricted in production per environment policy.

## Metrics

Micrometer metrics are published in Prometheus text format at
**`/actuator/prometheus`** (and browsable via `/actuator/metrics`).

Business-focused timers you should know:

| Metric | What it tracks |
|---|---|
| `order_place_seconds` | Time to place an order (p95 published) |
| `product_get_seconds` | Time to read a product (p95 published) |
| `redis.keyspace.hits` / `redis.keyspace.misses` | Redis cache effectiveness (hit/miss counts) |
| `http_server_requests_seconds` | HTTP endpoint latency/status (Boot built-in) |

Use them for the SLOs and alerts defined in `docs/slo/order-api-slo.md` and
`docs/monitoring/prometheus/alerts.yml`:
- Order placement success ≥ 99.5%; latency p95 < 500 ms.
- Product read latency p95 < 300 ms.
- Availability ≥ 99.9% (no >0.1% 5xx over 5 minutes).
- Payment circuit opens < 1/day (alerts page after 2 minutes OPEN).

## Logs

- Dev default: human-readable console lines with a `correlationId`.
- **Prod profile: structured JSON** - one object per event with `level`,
  `logger`, `message`, `thread`, stack traces and MDC fields (correlation id),
  ready for log aggregators.
- SQL/log noise is turned off in prod; PII is scrubbed from logged bodies in
  every profile.

## Tracing (distributed)

- OpenTelemetry traces are enabled by the `prod` profile (and any profile that
  sets `management.tracing.enabled=true`); spans export over OTLP.
- A local **Jaeger** all-in-one runs via `docker compose up -d jaeger`
  (UI: http://localhost:16686) for development tracing.
- Every trace shares the correlation id story: request headers (`traceparent`
  + `X-Correlation-Id`) join logs, metrics and traces for one operation.

## What operators should monitor on day one
1. `/actuator/health/readiness` on the load balancer; `/actuator/health/liveness`
   in the orchestrator.
2. Prometheus scrape of `/actuator/prometheus`.
3. The SLO alerts (order latency/errors, 5xx ratio, cache effectiveness).
4. Correlation ids in support tickets - ask customers for `X-Correlation-Id`.
