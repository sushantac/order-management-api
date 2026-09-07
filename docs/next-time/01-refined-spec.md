# 01 — Refined 35-PR Specification (Order Management API)

A corrected, executable version of the original `spec.md`: same learning
sequence, same domain, but with **definitions of done**, **up-front
architecture contracts**, and **first-run corrections baked in**. Designed to
be run by agents one PR at a time (see `02`/`03`).

## 1. Purpose & stack

Learning journey through a production-grade Java 21 / Spring Boot 3.2 API:
PostgreSQL + Liquibase, JPA, Redis, Kafka, Spring Security OAuth2, Resilience4j,
Testcontainers, Docker/Kubernetes, GitHub Actions, GitOps. Keep the stack from
the original spec (Java 21, Spring Boot 3.2.x, Postgres 16, Redis 7, Kafka,
Resilience4j 2.x). **Add**: ArchUnit for dependency rules, Awaitility for
timed assertions, and CI steps that execute the Docker/K8s/k6 artifacts.

## 2. Domain model (unchanged)

Customer → Address (1:N), Customer → Order (1:N), Order → OrderItem (1:N),
OrderItem → Product (N:1), Product ↔ Category (N:N), Order → Payment (1:1).
Events: OrderPlaced/Confirmed/Shipped/Delivered/Cancelled, PaymentProcessed/
Failed, StockReserved/Released. Keep the same DB FK rules: child CASCADE,
history RESTRICT, optional SET NULL, every FK indexed, money = NUMERIC/BigDecimal.

## 3. Cross-cutting standards applied from PR #1 (house rules)

1. **Layering & packaging** (see `04`): `domain` stays pure (no `api.dto` or
   Spring imports in entities); `application` holds use-cases/`@Transactional`;
   `infrastructure` owns persistence/messaging/config; `api` maps DTOs at the
   edge. An **ArchUnit test enforces the rules** from PR #1.
2. **DTOs over entities on the wire**, mapped in one explicit mapper per
   aggregate.
3. **Error contract** (see `04`): RFC 7807 with stable `code`s. The global
   handler is **last-resort only**; framework semantics preserved (404 unknown
   route, 405, 415, explicit 401/403). Regression test: unknown URL → 404.
4. **Security matrix** (see `04`): every endpoint has a row BEFORE it is built.
   All writes need a write scope or API key; reads need read scope unless
   public; `pii_*` scopes gate raw PII and GDPR actions.
5. **Idempotency policy**: every unsafe write accepts `Idempotency-Key` via ONE
   shared service; stored keys get TTL cleanup.
6. **State transitions** are declared maps with tests (no free-form sets).
7. **Cache discipline**: cache DTOs only; every writer that can change a cached
   read evicts the right key; TTL as safety net; hit/miss gauges exist.
8. **Messaging**: payloads carry no PII; publishing goes through the outbox;
   consumers are idempotent, ack manually, and route poison to a DLT.
9. **Migrations**: Liquibase owns the schema; Hibernate `validate` in tests/CI;
   never edit a shipped changeset.
10. **Testing**: unit for pure logic; integration against real Postgres/Redis/
    Kafka; every test class isolated with its own DB tag; timing assertions use
    Awaitility, not bare sleeps; schema-truth tests on schema PRs.
11. **Config**: behaviour in commented `application*.yml`; typed
    `@ConfigurationProperties`; profiles default (tests) / dev / prod.
12. **Per-PR docs**: update the README PR section + any ADR in the same PR.

## 4. Universal Definition of Done (apply to every PR)

- [ ] Implements only this PR's aspect.
- [ ] Files match the package map (`04`); no layering violations (ArchUnit green).
- [ ] New/modified endpoints have scope-matrix rows and correct guards.
- [ ] Errors are Problem Details with catalog codes; framework 4xx preserved.
- [ ] Targeted tests AND the full suite are green.
- [ ] House rules 5-8 respected where applicable (or an ADR explains why not).
- [ ] Branch created from `develop`; never pushed to `develop` directly.
- [ ] README PR section updated in the same PR; `feat(pr-XX): ...` commit.
- [ ] Nothing claimed in the PR body that was not actually executed/tested.

## 5. The 35 PRs at a glance

| # | Aspect | Notable corrections vs first run |
|---|---|---|
| 1 | Project setup | + package map, ArchUnit dep, pre-push guard, ADR skeleton |
| 2 | Schema & FKs (Liquibase) | schema-truth test from day one |
| 3 | JPA entity mappings | owning/inverse tests; orphan-removal gotcha documented |
| 4 | Cascading | test that orphan removal truly DELETEs |
| 5 | Fetch types | lazy by default; nullable inverse 1:1 caveat test |
| 6 | Fetch joins / entity graphs | statement-count test (N+1 guard) |
| 7 | Batch fetching | `default_batch_fetch_size` + N+1 demo test |
| 8 | Optimistic locking | 100-buyer test; retry-policy test |
| 9 | Pessimistic locking | + deadlock retry; lock performance comparison |
| 10 | Auditing | `AuditorAware` reads the SecurityContext principal |
| 11 | Custom queries | interface/constructor projections + alias test |
| 12 | Specifications | dynamic filters + paging test |
| 13 | Entity callbacks | seed the OrderStatus transition map |
| 14 | Schema validation | `ddl-auto: validate` test; dev-profile warning note |
| 15 | SQL logging | config + "noise off in prod" assertion |
| 16 | Second-level cache | per-class DB isolation rule documented |
| 17 | Projections | alias-must-match-getter regression test |
| 18 | Entity listeners | outbox-ready event-publishing seam |
| 19 | Event store | append-only integrity test |
| 20 | Service layer | all unsafe writes behind services; idempotency scaffold |
| 21 | DTOs (records) | mapper-only boundary; DTOs masking-ready |
| 22 | REST controllers | scope matrix written first; guards on ALL writes |
| 23 | Validation | groups + cross-field, reused for every request DTO |
| 24 | Exceptions + idempotency | last-resort handler + 404/405/415 tests; idempotency everywhere |
| 25 | OpenAPI | security schemes + generated-spec snapshot |
| 26 | OAuth2/JWT | RS256/JWKS behind a profile; matrix enforced |
| 27 | PII & GDPR | serializer + log masking; erasure/anonymize; no-PII audit |
| 28 | Redis caching | eviction-audit test for every writer |
| 29 | Resilience | retry-inside-breaker test; Redis-backed shared rate limit |
| 30 | Virtual threads | pinning-free code; fan-out join-all; lock lease test |
| 31 | Kafka/outbox | consumer dedupe demo; outbox dead-row handling + alerting |
| 32 | Observability | correlation, JSON logs, @Timed, traces, SLO files |
| 33 | Docker & K8s | **build image + run + kustomize build/apply in the PR** |
| 34 | CI/CD & GitOps | **run CI on a real branch**; promotion script exercised |
| 35 | Enterprise slice | i18n/CSV behind flags + ADRs (tenancy, backups, portal) |

## 6. Per-PR detail

Format per PR: **Goal** / **Deliver** / **DoD extras**. "DoD extras" are in
addition to the universal DoD.

### PR #1 Project setup
Deliver: Spring Boot 3.2 skeleton, Java 21, Maven wrapper, profiles (default/dev/
prod), Actuator, commented `application.yml`, README + `docs/adr/0001-*.md`.
DoD extras: package map per `04` created; ArchUnit dependency + test added;
pre-push guard installed (`03`); `spring-boot-starter-test` with Testcontainers.

### PR #2 Schema & foreign keys (Liquibase)
Deliver: Liquibase master + `01` changelog: 8 domain tables, FKs with explicit
delete rules, indexed FK columns, CHECK constraints, `NUMERIC` money.
DoD extras: `DatabaseSchemaIntegrationTest` (table list, FK rules, index
coverage) + "no card-data columns" rule decided (checked here so it stays).

### PR #3 JPA entity mappings
Deliver: entities matching the schema; owning/inverse sides documented.
DoD extras: mapping tests for FK ownership; note orphanRemoval caveat in the
PR's README (fixed properly in PR #4).

### PR #4 Cascading strategies
Deliver: correct cascade per relation (`ALL, orphanRemoval` on order items).
DoD extras: **test proves removing an orphaned child issues a DELETE** (the
first-run gotcha, encoded as a regression test).

### PR #5 Fetch types
Deliver: LAZY defaults chosen; demonstrate EAGER-vs-LAZY.
DoD extras: test that a *nullable inverse @OneToOne* cannot be lazy and that
the mapping is set to owning/non-nullable to avoid per-row SELECTs.

### PR #6 Fetch joins & entity graphs (N+1)
Deliver: `JOIN FETCH` + `@EntityGraph` variants.
DoD extras: **statement-count test** asserts the fetch fix issues one query.

### PR #7 Batch fetching
Deliver: `@BatchSize` / `default_batch_fetch_size` + naive demo.
DoD extras: test verifies lazy access collapses to an `IN (...)` batch.

### PR #8 Optimistic locking
Deliver: `@Version`, `OptimisticLockingFailureException` mapping, `@Retryable`.
DoD extras: 100-concurrent "decrement last stock" test → exactly one winner;
retry-not-on-business-rejection test.

### PR #9 Pessimistic locking
Deliver: `PESSIMISTIC_WRITE/READ`, deadlock demo + retry.
DoD extras: `LockingPerformanceComparisonTest`; README decision on when to
pick each strategy.

### PR #10 Auditing
Deliver: `BaseEntity` audit columns + `AuditorAware`.
DoD extras: `AuditorAware` reads the Spring Security principal when present
(fallback "system" only when there is none) - first run used a constant.

### PR #11 Custom queries
Deliver: JPQL, native SQL, constructor projections on repositories.
DoD extras: interface projection with **alias-must-match-getter** test.

### PR #12 Specifications
Deliver: `JpaSpecificationExecutor` + `CustomerSpecifications`.
DoD extras: dynamic filter + paging test.

### PR #13 Entity callbacks
Deliver: `@PrePersist` (order number), `@PreUpdate` invariants, `@PreRemove`
guard; register the OrderStatus transition map (`04`) as the single decision
point.
DoD extras: transition-map unit tests.

### PR #14 Schema generation & validation
Deliver: `ddl-auto: validate` profile semantics + `SchemaValidationIntegrationTest`.
DoD extras: assert `update` is dev-only and documented as dangerous.

### PR #15 SQL logging & debugging
Deliver: logging config (SQL DEBUG/bind TRACE in dev, off in prod) + test.
DoD extras: document that bind logs may contain values → never enable in prod;
correlate with the PII redaction rule (PR #27).

### PR #16 Second-level cache
Deliver: Ehcache L2 with READ_WRITE on `@Cacheable` entities.
DoD extras: **per-class DB isolation** (`integration.database.tag`) documented
and used from here on - CacheManager is JVM-wide.

### PR #17 DTO projections
Deliver: interface + class projections; alias test.
DoD extras: assert projection result columns equal entity columns for sample data.

### PR #18 Entity listeners
Deliver: custom `@EntityListeners` (count + publish seam).
DoD extras: keep the listener Spring-free; hook the event through a holder that
later becomes the outbox (no service-layer dependency from entities).

### PR #19 Event store
Deliver: `event_store` table (append-only, unique aggregate/version), service.
DoD extras: duplicate-append rejected test; no optimistic-lock/audit columns on
the log.

### PR #20 Service layer
Deliver: `OrderService.placeOrder` atomic use-case; all unsafe operations
behind services (products/customers get services here, not controllers).
DoD extras: idempotency-key scaffold (service + table + TTL plan); payment
gateway interface + deterministic fake for tests; full rollback test.

### PR #21 DTOs (records)
Deliver: request/response records + one mapper per aggregate.
DoD extras: DTOs marked masking-ready (PR #27 hooks) but not yet annotated.

### PR #22 REST controllers
Deliver: full CRUD controllers (orders, customers, products, categories).
DoD extras: **scope matrix filled BEFORE coding**; every mutation guarded with
the write scope pattern; list/legacy/PATCH/DELETE all consistent; ETag on
resources that need optimistic concurrency.

### PR #23 Validation
Deliver: validation groups + cross-field validators reused everywhere.
DoD extras: same DTO validated per-operation; error `code=VALIDATION_ERROR`.

### PR #24 Exceptions & idempotency
Deliver: RFC 7807 global handler + idempotency for order create.
DoD extras: **last-resort semantics**: 404/405/415 preserved + regression test
(unknown URL → 404); 401/403 explicit; Idempotency-Key now also on update/
delete/bulk where side-effectful; key TTL cleanup job.

### PR #25 OpenAPI documentation
Deliver: springdoc, security schemes, generated snapshot, Postman export.
DoD extras: snapshot committed and diffed in CI (docs drift guard).

### PR #26 Security OAuth2 & JWT
Deliver: resource server, scopes→authorities, API keys, headers, CORS.
DoD extras: matrix verified by an end-to-end test that walks EVERY guarded
endpoint (positive + negative); RS256/JWKS decoder behind the prod profile
(HS256 only in dev/default for tests).

### PR #27 PII & GDPR
Deliver: `@MaskedPii` serializer masking, log redaction, erasure/portability,
no-PII audit log.
DoD extras: mask-or-raw decision covered for anonymous vs `order_read` vs
`pii_read`; audit survives deletion (no FK); erasure idempotence test.

### PR #28 Caching (Redis)
Deliver: cache-aside catalogue, TTL, eviction on writes + stock writers.
DoD extras: **eviction audit test** for every writer that can change a cached
read; hit/miss gauges asserted.

### PR #29 Resilience patterns
Deliver: Resilience4j breaker/retry/bulkhead + rate limiting.
DoD extras: retry-inside-breaker count test (requests vs attempts); shared
(Redis-backed) rate limiting so N instances enforce one quota; 429 header tests.

### PR #30 Virtual threads & concurrency
Deliver: virtual threads, fan-out, Redisson distributed lock, ReentrantLock demo.
DoD extras: 100-buyer virtual-thread test; lock lease crash-recovery test;
no `synchronized` in hot paths (pinning).

### PR #31 Kafka & outbox
Deliver: outbox table/poller, producer/consumer, manual ack, DLT.
DoD extras: consumer **dedupe** implementation + test; outbox dead-row
handling (max attempts → alert/DLT table) - closes the first-run gap.

### PR #32 Observability
Deliver: correlation filter, JSON logs, @Timed metrics, OTel/Jaeger, health
probes, SLO + alert files.
DoD extras: /actuator/prometheus exposure test; prod profile JSON log smoke;
SLO/alert YAML linted with promtool in CI.

### PR #33 Docker & Kubernetes
Deliver: multi-stage Dockerfile, full compose, k8s base + overlays, sealed
secrets, probes, graceful shutdown.
DoD extras: **PR includes evidence**: `docker build` + image run against
compose services, `kustomize build` for every overlay; compose api healthcheck
verified on the real base image.

### PR #34 CI/CD & GitOps
Deliver: GitHub Actions CI + promotion workflow, ArgoCD example, GitOps doc,
feature flags, Pact docs.
DoD extras: **run the CI workflow on a branch** and record the run link;
exercise the promotion script end-to-end on a lower env.

### PR #35 Enterprise slice
Deliver: i18n, flag-gated CSV export, enterprise ADRs (multi-tenancy, backups,
developer portal, scheduled jobs).
DoD extras: i18n + CSV tests; ADRs updated; final closure checklist (`06`)
executed and the retrospective written from recorded data.

## 7. What to import from the previous attempt

Reuse the good parts; do not rebuild them from scratch:
- The domain entity field design, DB FK rules and Liquibase pattern
  (`01-08` migrations are a reference, not copy-paste - they teach).
- The empirical gotcha list (`docs/learnings/README.md` §8) as the seed for
  regression tests.
- Package/scope/error/cache/outbox contracts in `04` - treat them as
  approved ADRs unless a new PR explicitly overrides one (and says why).

Do NOT import: unguarded endpoints, catch-all error semantics, single-use
idempotency, in-memory rate limit stand-ins presented as production, or
"created but never executed" infra artifacts.

*End of refined spec. Continue with `02-refined-instructions.md`.*




