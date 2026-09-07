# 08 — Observability & SLOs

## The three pillars (know the difference cold)
| Pillar | Answers | Tooling here |
|---|---|---|
| Logs | What happened on THIS event | Logback, JSON in prod, MDC correlation id |
| Metrics | How is it behaving over time (rates, latencies) | Micrometer + Prometheus |
| Traces | How did ONE request flow across services | OpenTelemetry → Jaeger |

## Structured logging
- One JSON object per event (level, logger, message, **MDC fields**) so
  aggregators can query; dev keeps human-readable logs (profile switch).
- Correlation ID: honour or generate `X-Correlation-Id`, echo on the response, and
  put it in the MDC → every log line of a request links together; propagate the same
  header to downstream services for distributed correlation.
- Security: scrub PII before logging (caller rights ≠ log-sink rights).

## Metrics
- Micrometer `@Timed` on business methods (`order.place`, `product.get`) via a
  `TimedAspect`; Prometheus text format at `/actuator/prometheus`.
- Add domain gauges (Redis keyspace hit/miss) so you can *see* a cache working.
- Use timers with percentiles (p95) for latency, counters for rates.

## Tracing
- Propagate trace context (W3C headers) across HTTP; export spans to Jaeger
  (OTLP) → one order request becomes one waterfall across services.
- Sampling: 10% is often enough for diagnosis; keep exporters OFF unless enabled
  (they add overhead/noise in tests).

## Health & probes
- `/actuator/health/liveness` (am I alive? restart if not),
  `/actuator/health/readiness` (can I take traffic?), `startup` (slow cold start).
- Liveness must not depend on downstreams; readiness may.

## SLI / SLO / SLA - the interview triangle
- **SLI**: the measurement (p95 latency, success rate, availability).
- **SLO**: the target over a window (p95 < 500 ms; ≥99.5% success over 30 days).
- **SLA**: contractual promise (breach = penalty), built on SLOs.
- **Alert on error-budget burn**, not on a single bad minute - e.g. "p95 > 1 s for
  5 minutes" paging, not one spike.

## Tell me about...
**"How do you know your API works in prod?"** → "Structured logs with a correlation
id per request, Micrometer timers for order placement and product reads exported to
Prometheus, OTel traces into Jaeger, readiness/liveness probes for K8s, and SLOs with
AlertManager burn-rate rules so we page on budget, not noise. The same metrics tell
us whether the Redis cache is actually paying for itself."

## Rapid Q&A
- Logs vs traces vs metrics overlap? → all share the correlation/trace id; that id
  is the join key.
- How much sampling? → traces: 5-10%; metrics: always; logs: level per environment.
- First SLO to define? → availability + latency of the core write path.
- Error budget 99.9% over 30d? → ~43 minutes of downtime budget.
