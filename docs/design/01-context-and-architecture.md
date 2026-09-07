# 01 — Context & Architecture

## 1.1 Business context & goals
A retailer needs to manage customers, a product catalogue and orders, with
strict correctness on money and stock. Success criteria:

1. **Correctness**: placing an order must be atomic (stock, order, payment),
   idempotent for retries, and safe under high concurrency.
2. **Privacy**: personal data protected at every boundary; GDPR subject rights
   available.
3. **Reliability**: degrade gracefully when the payment provider or brokers
   fail; protect each client from others (rate limiting).
4. **Operability**: observable (logs/metrics/traces/health), runnable locally
   with one command, deployable via GitOps.
5. **Learnability**: implemented as 35 one-concept PRs with documentation -
   this is an engineering-education platform as much as an API.

## 1.2 Scope
**In:** customer/product/category/order management; payments (simulated);
PII masking; GDPR erasure/portability; audit; events to Kafka; caching;
resilience; observability; container/K8s/GitOps artefacts; enterprise extras
(i18n, CSV export, feature flags).

**Out (recorded boundaries):** no identity provider or UI; no real PSP
integration; no refunds/cancellation flows; single-tenant; rate limiting is
per-process in the current build (see §9); several ops artefacts are designed
and versioned but require the §9 execution plan to be considered proven.

## 1.3 Context view (C4 Level 1)
```
        ┌────────────┐        HTTPS /api/v1      ┌───────────────────────────┐
        │ Auth server│◄─────── (issues JWTs) ───►│   Order Management API    │
        └────────────┘                           │ (Spring Boot, stateless)  │
┌──────────────┐   HTTPS (Bearer JWT / X-API-Key)│                           │
│ Web front-end│────────────────────────────────►│   PostgreSQL 16 (source   │
└──────────────┘                                 │   of truth + event store  │
┌──────────────┐                                 │   + outbox + audit)       │
│ Integrators  │────────────────────────────────►│   Redis 7 (cache + locks) │
│ (API key)    │        (order events consumed)  │   Kafka (event bus)       │
└──────────────┘                                 │   Jaeger/OTLP (traces)    │
        ▲                                        └────────────┬──────────────┘
        │                 Prometheus scrape (metrics)          │ outbox poller
        └──────────────────────────────────────────────┐       ▼
                                                        │   Kafka topics
```

## 1.4 Container/module view (C4 Level 2 → package map)
```
api        — controllers + DTO records + mappers (boundary; DTOs never entities)
application— use-cases: OrderService.placeOrder, GdprService, DashboardService,
             ProductCatalogueService (cache-aside), DistributedLockService
domain     — entities, domain events, value/enum types, outbox/audit/event-store
             models, repository interfaces
infrastructure — Spring Data repositories, Liquibase changelogs, messaging
             (Kafka), config (typed properties), observability glue, security
observability — CorrelationIdFilter, metrics config, health indicators
```
Rule enforced by design & (planned) ArchUnit: `domain` never imports `api`/
`messaging` DTOs. (Known drift exists in the as-built code - `ProductCatalogueService`
returns an API DTO and `OrderService` references the message type; recorded as
a deviation to remediate in the forward plan, §9.)

## 1.5 Architecture principles
| # | Principle | Meaning here |
|---|---|---|
| P1 | Contracts over code | Error catalog, scope matrix, state maps defined before/with endpoints |
| P2 | Correctness at the write path | One transaction; version checks; idempotency keys |
| P3 | Least privilege | Read vs write vs pii_* scopes; masked by default |
| P4 | Reliable integration | Outbox publishing; at-least-once; idempotent consumers |
| P5 | Decouple policy from code | Config-driven behaviour (profiles, feature flags, resilience4j) |
| P6 | Fail fast, degrade gracefully | Circuit breakers/bulkheads/rate limits |
| P7 | Everything observable | correlation ids, timers, probes, SLOs |
| P8 | Schema owned by migrations | Liquibase; Hibernate validates |
| P9 | Single source of docs truth | OpenAPI generated; README per PR; ADR discipline |

## 1.6 Key decisions at a glance
Full rationale and options in §09. Highlights: JSON over GraphQL for v1
(dual-API optionality retained); PostgreSQL 16 as single source of truth
(plus Redis for read cache only); outbox over in-process publish; scope-based
resource-server security with separate `pii_*` scopes; composite resilience
stack on the gateway; GitOps (Kustomize + ArgoCD model) for delivery.
