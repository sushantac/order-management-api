# Interview Cheat Sheets — Order Management API

Rapid-revision sheets for interviews, each built from a real production-grade
Spring Boot project (35 PRs, 120 tests). Read the companion learning document in
`docs/learnings/` for depth; use these for recall.

## How to use
- **24h before**: skim every sheet once; highlight 3 facts per topic you do not know cold.
- **2h before**: drill the "Tell me about" boxes out loud (rubber-duck style).
- **On the day**: only the one-page mental models + the project story.

## Your project story (memorize this)

> "I built a production-grade Order Management API in Java 21 / Spring Boot as a
> 35-PR learning journey - one concept per PR, 120 integration tests against real
> Postgres, Redis and Kafka. It handles atomic order placement with optimistic
> locking, payment resilience (circuit breaker + retry + bulkhead), event publishing
> via a transactional outbox to Kafka, PII masking and GDPR endpoints, Redis
> cache-aside on the catalogue, virtual-thread concurrency, and full observability.
> It is deployed via GitOps with Docker/Kubernetes and Kustomize overlays per
> environment."

Extension sentences (pick by interviewer direction):
- Data: "Schema is Liquibase-owned; every FK is indexed and tests assert DB-level rules."
- Concurrency: "100 concurrent buyers for one last unit end with exactly one winner thanks to @Version + retry."
- Reliability: "A poisoned Kafka message lands on a dead-letter topic instead of stalling the group."
- Privacy: "Emails/phones are masked at the serializer boundary and redacted in logs regardless of caller rights."

## Sheet index

| File | Topic | Covers |
|------|-------|--------|
| 01 | Core Java 21 & Spring Boot | records, virtual threads, DI, profiles, Boot starters |
| 02 | JPA, Hibernate & PostgreSQL | mappings, N+1, projections, migrations, auditing |
| 03 | Transactions, locking, caching | ACID, optimistic/pessimistic, Redis cache-aside, L2 |
| 04 | Security & privacy | OAuth2/JWT resource server, scopes, PII/GDPR, API keys |
| 05 | REST API design | DTOs, validation groups, RFC 7807 errors, idempotency |
| 06 | Event-driven & Kafka | domain events, outbox, delivery semantics, DLT |
| 07 | Resilience & performance | Resilience4j, rate limiting, virtual threads |
| 08 | Observability & SLOs | logs, metrics, traces, health, SLO/alerting |
| 09 | DevOps: Docker/K8s/GitOps/CI | images, probes, Kustomize, ArgoCD, feature flags |
| 10 | System design & behavioral | order-flow design, trade-off answers, STAR stories |

## One-line definitions (recite until instant)

- **ACID**: each unit of work is atomic, consistent, isolated, durable.
- **N+1**: one query for parents + one per child; fix with fetch join/entity graph/batching.
- **Optimistic lock**: version column; retry the loser.
- **Pessimistic lock**: `SELECT ... FOR UPDATE`; prevent, don't heal.
- **Cache-aside**: check cache → miss → load DB → populate with TTL.
- **Outbox**: write event in the DB transaction; a poller publishes to the broker.
- **At-least-once**: no loss, possible duplicates ⇒ idempotent consumers + DLT.
- **Resource server**: validates tokens, never issues them.
- **SCOPE_***: JWT scope → Spring authority, used in `@PreAuthorize`.
- **Bulkhead**: isolate a dependency on its own pool so it can't sink the ship.
- **Circuit breaker**: CLOSED→OPEN→HALF_OPEN; fail fast, probe recovery.
- **SLO vs SLA**: measured target vs contractual promise; alert on budget burn.
- **GitOps**: desired state in git; operator converges the cluster.
