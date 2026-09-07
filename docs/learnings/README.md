# Order Management API — Learning Document

*Everything this 35-PR journey taught, distilled: concepts → real code in this repo → interview-ready explanations → daily-work application.*

Use it in three modes:
1. **Learn an area**: read the matching chapter, then open the referenced classes (their Javadoc explains *why*, not just *what*).
2. **Revise for interviews**: skim each chapter's "Interview highlights" box, then drill with the cheat sheets in `../interview-cheat-sheets/`.
3. **Apply at work**: each chapter ends with "Apply this at work" - concrete habits to carry into any Java/Spring codebase.

The single most instructive file is `src/main/java/com/company/orderapi/domain/service/OrderService.java`; it is the seam where almost every concept meets (locking, transactions, outbox, metrics, retries, resilience). Read it last, after the chapters.

---

## 0. The mental model of the whole project

This is an **Order Management API**: customers place orders for products; stock is deducted atomically; a payment is charged; events are published; data is protected (PII/GDPR), cached, resilient, observable, and deployed with GitOps.

It was built as **35 one-PR-per-concept steps** - each PR taught exactly one production concern and every PR merged with green tests (final suite: **120 tests against real Postgres/Redis/Kafka**). The sequence is the syllabus:

schema → JPA mappings → cascades → fetching → locking → auditing → queries → projections → events → DTOs → REST → validation → exceptions → OpenAPI → security → PII/GDPR → caching → resilience → virtual threads → Kafka → observability → Docker/K8s → CI/CD/GitOps → enterprise extras.

Three cross-cutting habits made it "production-grade" rather than a toy:
1. **Every claim has a test** - tests run against real infrastructure (Testcontainers), and several assert *database-level* truths (FK delete rules, "payments has no card columns", table count).
2. **Config lives in commented properties** (`application.yml`), so a behaviour change never needs a code deploy.
3. **The README records the learning** (key decisions + answered questions per PR), not just the feature list.

---

## 1. Database schema & migrations (PRs 2, 14)

### Concepts
- **Migrations own the schema.** Hibernate runs with `ddl-auto: validate` - it *checks* entities match the DB and never changes it. Liquibase (`db/changelog/v1.0/01..08`) is the single source of truth, reproducible from git on every environment. Golden rule learned the hard way: **never edit a changeset that already ran** (checksum mismatch ⇒ Liquibase refuses to start) - always add a new one.
- **Schema design details that matter:**
  - Every FK has the right delete rule (CASCADE for child rows that cannot outlive the parent; RESTRICT so order history can never lose a product; SET NULL for *optional* references like shipping address).
  - Every FK column is **indexed** (leading-column rule) - verified by a test that queries `pg_index`.
  - Money is `NUMERIC(19,2)`/`BigDecimal`, never floating point.
- **8 migrations** create: domain tables, then `version` columns (locking), audit columns, `order_number`, event store, idempotency keys, audit log, outbox.

### Real code to read
- `db/changelog/db.changelog-master.xml` + `v1.0/*.sql`
- `DatabaseSchemaIntegrationTest` (asserts table list, FK delete rules, index coverage, and that `payments` stores no cardholder data)
- `SchemaValidationIntegrationTest` (JPA model ↔ real schema agreement)

### Interview highlights
- "Who owns the schema?" → Liquibase/Flyway; Hibernate `validate` only proves agreement in tests/CI.
- Why not `ddl-auto: update`? It only adds what Hibernate knows, never removes, and hides drift until prod.
- Explain index-every-FK and why `RESTRICT` protects history (products referenced by order_items must never vanish).

### Apply this at work
Treat migrations like reviewed artifacts: name constraints, give every FK its index, and add a schema test so the team cannot silently add card data, drop an index, or change a delete rule.

## 2. JPA mappings, fetching & the N+1 problem (PRs 3-7, 17)

### Concepts
- **Mapping vocabulary you must own:** owning vs inverse side (`mappedBy` says "the other side owns the FK"), `@ManyToOne`/`@OneToMany`/`@OneToOne`/`@ManyToMany` (+`@JoinTable`), and that the FK column is written exactly once - on the owning (`@ManyToOne`) side. `Customer.addresses` is inverse (`mappedBy`); `Address.customer` holds `customer_id`.
- **Cascade is a JPA-side choice, independent of the DB FK.** Empirical finding burned into memory: `orphanRemoval=true` only actually DELETEs the removed child when the collection also has a cascade (`ALL`/`PERSIST...`) - an `orphanRemoval` without cascade silently leaves rows. Use `cascade = ALL, orphanRemoval = true` on owning collections.
- **Fetch types**: `@ManyToOne`/`@OneToOne` default EAGER, collections LAZY. EAGER = joins/extra selects you did not ask for. A nullable inverse `@OneToOne` cannot be lazy-proxied (Hibernate must load it to know whether it exists) - observed per-order payment SELECTs until the mapping was made non-nullable/owning.
- **N+1 problem**: loading N orders and touching each customer fires N+1 queries. Fixes taught and implemented:
  1. `JOIN FETCH` in JPQL (one query, `distinct` for collection joins);
  2. `@EntityGraph` (declarative fetch recipe, `attributePaths` or `@NamedEntityGraph`);
  3. batch fetching (`@BatchSize` or `default_batch_fetch_size: 20`): lazy loads become one `IN (...)` per owner - "cheap insurance" that kills most N+1 with zero query changes.
- **Projections**: interface projections (Spring generates the proxy; **aliases must match getters**) and class projections via JPQL constructor expressions (`new com...CustomerOrderTotal(...)`). Projections prove you can select columns instead of whole entities.
- **Auditing (PR 10)**: `@CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy` on a `BaseEntity` + `AuditorAware<String>` bean = who/when on every row, no service code. The "current user" should come from the security context (improved in the security PR).
- **Entity callbacks & listeners (PRs 13, 18)**: `@PrePersist` (generate `order_number`), `@PreUpdate` (enforce business invariant: SHIPPED requires an address), `@PreRemove` (refuse deleting a customer with orders). Callbacks put persistence cross-cutting concerns *in the entity layer*, not scattered in services.

### Real code to read
- `domain/Customer.java`, `Order.java`, `OrderItem.java`, `Product.java` (owning/inverse + cascade comments)
- `domain/repository/CustomerRepository.java` (all N+1 fixes side by side), `OrderRepository.java` (JPQL/native/constructor/SpEL variants), `domain/repository/CustomerSpecifications.java`
- `BaseEntity.java`, `config/JpaAuditingConfig.java`, `domain/listener/OrderBusinessListener.java`
- Tests: `FetchTypeIntegrationTest`, `BatchFetchIntegrationTest`, `NPlusOneDemoTest`, `JpaCascadingIntegrationTest`, `ProjectionIntegrationTest`

### Interview highlights
- Explain N+1 and give three fixes, and say when each fits (graph = known access path; batch = generic lazy access).
- "Where does the FK live?" → owning many-to-one side; `mappedBy` is documentation for the inverse.
- Explain the `orphanRemoval`-needs-`cascade` gotcha - it signals real debugging experience.
- When to prefer `@EntityGraph` over `JOIN FETCH` (reusable recipe vs imperative query).
- `@PreRemove` as a *business* guard layered on top of DB RESTRICT.

### Apply this at work
Default to LAZY everywhere and fetch explicitly. Before adding a custom query, ask "can batch fetching fix it?" (no JPQL to maintain). When you cache serialized entities, remember lazy/session state - cache DTOs, not entities (see chapter 5, caching).

## 3. Transactions, locking, virtual threads & caching (PRs 8-9, 16, 28, 30)

### Concepts
- **Why one transaction?** `OrderService.placeOrder` must be atomic: deduct stock, insert order+items, charge payment, record payment. Any failure rolls back ALL of it (verified: `paymentFailureRollsBackTheWholeOrder`). `@Transactional` on the service (not the controller) is the unit of work; callbacks + dirty checking flush at commit.
- **Optimistic locking (PR 8)**: `@Version` on the entity ⇒ `UPDATE ... WHERE id=? AND version=?`; 0 rows ⇒ `OptimisticLockingFailureException`. Conflict is *expected* on hot rows, so `@Retryable` re-runs the whole method in a fresh transaction (max attempts + tiny backoff). 100 concurrent "decrement stock" calls: retries make every legitimate caller eventually win without locks.
- **Pessimistic locking (PR 9)**: `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`) serializes writers *up front*; `FOR SHARE` (`PESSIMISTIC_READ`) blocks writers but not readers. Deadlocks are detected by Postgres and converted to `DeadlockLoserDataAccessException`, then retried. Choose: optimistic for low contention, pessimistic when the row is a hot counter.
- **Why both exist**: optimistic lets failures happen and heals by retry; pessimistic prevents them by queuing. Performance comparison lives in tests.
- **Second-level cache (PR 16)**: `@Cacheable` + `@Cache(READ_WRITE)` with Ehcache JCache. It is JVM-wide (`CacheManager` is a singleton per JVM) - the source of a painful test-isolation lesson: **each integration test class must get its own Spring context + fresh Postgres** (unique `integration.database.tag`), otherwise caches and DB state leak between classes. L2 is deliberately OFF except the dedicated test.
- **Application cache / Redis (PR 28)**: cache-aside with Spring Cache: `@Cacheable` (method body runs only on miss), `@CacheEvict` on every write, TTL as the safety net. Cache the **DTO read model**, never entities (lazy/session state must not be serialized). Invalidation discipline: catalogue writes evict all entries; stock writers that live outside the catalogue (order placement, locking services) evict the single product key - otherwise a cached stock level oversells the next buyer. `spring.cache.type: simple` in the default profile keeps tests broker-free; dev/prod switch to Redis with `spring.cache.redis.time-to-live`.
- **Java 21 virtual threads (PR 30)**: virtual threads park on blocking calls (cost ~KB, no OS-thread stack) so "thread per request" scales. `spring.threads.virtual.enabled=true`. Beware *pinning* (synchronized blocks pin the carrier) and shared mutable state. Concurrency test: 100 virtual buyers on the last unit ⇒ exactly one winner.
- **Locks recap**: `ReentrantLock` (fair, explicit, `finally` release) protects one JVM; **Redisson `RLock`** protects across instances (Redis SET NX + lease = a crashed holder's lock is reclaimed - proven by the crash-recovery test). `@Lazy` on the injection point means the Redisson client never connects unless a lock is used.

### Real code to read
- `OrderService.java`, `ProductStockService` (optimistic+retry), `ProductInventoryService` (pessimistic, deadlock demo), `ProductRepository` (lock queries), `BaseEntity` (`@Version`)
- `config/RedisConfig`, `domain/service/ProductCatalogueService`, `config/RedisCacheMetrics`
- `concurrency/FairSequenceAllocator`, `domain/service/DistributedLockService`, `config/RedissonConfig`
- Tests: `OptimisticLockingTest`, `PessimisticLockingIntegrationTest`, `LockingPerformanceComparisonTest`, `SecondLevelCacheIntegrationTest`, `CachingRedisIntegrationTest`, `VirtualThreadsIntegrationTest`, `DistributedLockIntegrationTest`

### Interview highlights
- Contrast optimistic vs pessimistic, and when to choose each; mention retry with exponential backoff.
- The classic **overselling / last-unit race** and how `@Version` + retry resolves it.
- Explain cache-aside + the invalidation "spiderweb": every write path that can change what a read returns must evict.
- L2 (Ehcache, JVM-wide) vs app cache (Redis, shared) - know the difference and the serialization pitfalls.
- Virtual threads: benefits, pinning, and why you still need locks for shared state.
- Distributed lock: why JVM locks are not enough; lease/watchdog semantics.

### Apply this at work
Write a *concurrency* test before trusting your locking story. Never cache an entity that has lazy associations; cache an immutable projection. When introducing a shared cache, audit every writer and add an eviction or risk a silent inconsistency that only load tests find.

## 4. REST API design: DTOs, validation, errors & docs (PRs 20-25)

### Concepts
- **Layers talk DTOs, never entities.** Controllers only ever see records (`OrderRequest`, `OrderResponse`, ...). `OrderMapper` is the single explicit place that maps entity → view model, so the API contract is reviewable in one file. "Entity internals never leak" is a rule with teeth: no lazy proxies, no `version`, no audit-user objects.
- **DTOs as Java records (PR 21)**: immutable, concise, perfect for validation annotations on components. A request record + validation groups (see below) replaces a bag of setters.
- **REST shape**: `POST` = create → 201 + `Location`; `GET` = read; `PUT` = full replace; `DELETE` = 204. Idempotent semantics on PUT/DELETE. Bulk operations have their own endpoint (`POST /orders/bulk`). Versioning strategy: `/api/v1/...` + deprecated aliases advertise RFC 8594 `Deprecation`/`Sunset` headers instead of breaking clients (PR 25).
- **Validation groups (PR 23)**: `@Validated(Create.class)` vs `@Validated(Update.class)` let the *same* DTO carry different rules per operation (email required both; name length only on create). Class-level custom constraints (`@ValidStock`, `@ValidOrderRequest` + `ConstraintValidator`) validate cross-field invariants the annotations cannot express.
- **Error handling (PR 24)**: one `@RestControllerAdvice` maps every exception to **RFC 7807 Problem Details** with a stable error *code* + *hint* (e.g. 409 `INSUFFICIENT_STOCK`, 409 `CONCURRENT_MODIFICATION`, 502 `PAYMENT_FAILED`, 404 `RESOURCE_NOT_FOUND`). Clients branch on `code`, never on message strings. A Java 21 pattern-matching `switch` classifies exception families - readable, exhaustive, single place.
- **Idempotency (PR 24)**: `Idempotency-Key` on risky writes: first request stores status+body keyed by the header; retries replay the stored response (no duplicate charge/order). Natural companion to the payment/order flow.
- **OpenAPI (PR 25)**: springdoc generates `/v3/api-docs` + Swagger UI from controllers/annotations (`@Operation`, `@ApiResponse`); security schemes are declared (bearer JWT + API key). Docs generated from code can't rot.

### Real code to read
- `api/dto/*` (+ `OrderMapper`), `api/dto/validation/*`
- `api/exception/GlobalExceptionHandler.java`
- `domain/idempotency/*`, `api/rest/controller/*` (controllers are thin)
- Tests: `ValidationIntegrationTest`, `ExceptionAndIdempotencyIntegrationTest`, `RestControllerIntegrationTest`, `OpenApiIntegrationTest`, `OrderMapperTest`

### Interview highlights
- Why DTO records and not entities on the wire (contract stability, no lazy proxy serialization, no audit leakage).
- Explain validation groups with a real asymmetry (create vs update).
- "How do you return errors?" → RFC 7807 + machine codes + hints; contrast with ad-hoc message maps.
- Idempotency-key design: what you store, what a retry returns, why it must be in the same transaction/table.
- Deprecation without breaking: `Sunset`/`Deprecation` headers vs hard deletes.

### Apply this at work
Define your error contract once (code catalog) before building endpoints. Never let a 500 leak for a 4xx (a catch-all `Exception` handler that returns 500 hid *access-denied* until we added explicit 401/403 handlers - a real bug we hit). Version URLs from day one.

## 5. Security & privacy: OAuth2/JWT, PII masking, GDPR (PRs 26-27)

### Concepts
- **Resource server, not identity provider**: the API *validates* bearer JWTs but never issues them - decoupled from the IdP. `spring-boot-starter-oauth2-resource-server` wires `JwtDecoder`; HS256 with a shared secret is fine for learning, production validates RS256 against the issuer's **JWKS** and pins `iss`/`aud`.
- **Scopes → authorities**: the JWT `scope` claim becomes `SCOPE_*` authorities via a custom `JwtAuthenticationConverter`, so `@PreAuthorize("hasAuthority('SCOPE_order_write')")` reads naturally. Everyday scope `order_read` cannot write; GDPR actions need the stronger `pii_*` scopes.
- **API keys for machine clients** (`ApiKeyAuthenticationFilter`): a trusted `X-API-Key` yields a `ROLE_API_KEY` principal that satisfies the same guards. (A machine can't hold a user session.)
- **Security config**: stateless (no sessions), CSRF off for a token API, CORS for the SPA origin, HSTS/CSP/X-Content-Type-Options headers, a permit-list (health, OpenAPI) and `anyRequest().authenticated()`. A master switch `app.security.enabled` lets non-security integration tests run unchanged - a deliberate, documented testing knob.
- **PII is a cross-cutting concern** (PR 27), not a field-level afterthought:
  1. **Responses**: DTO fields tagged `@MaskedPii(PiiType.EMAIL/PHONE/NAME)` are serialized through a `SensitiveDataSerializer`, wired by a Jackson `BeanSerializerModifier` (no per-endpoint code). Masking decision: authenticated callers WITHOUT `pii_read`/API key get masked values; anonymous/test contexts keep the historical full view (so legacy suites stay green).
  2. **Logs**: `PiiRedactionFilter` buffers `/api/**` bodies and logs them scrubbed (`PiiMasker.redactJson`) - log redaction is a property of the *sink*, independent of the caller's rights.
  3. **GDPR endpoints**: `DELETE /api/v1/customers/{id}/data` (right to erasure) and `GET .../portability` (data portability). Erasure is NOT always a DELETE: if order history must be kept (Art. 17(3)) the row is **anonymized** (`erased-<id>@erased.invalid`) instead. Both actions are audit-logged (audit rows survive even physical erasure because they carry no FK and no raw PII).
- **PCI-DSS stance**: the payments table stores *no cardholder data* - the gateway returns a transaction reference. Proven by a schema test ("no pan/cvv/cardholder columns"), because scope reduction is the strongest control.
- **Security error mapping**: method-security denials must map to 403 `ACCESS_DENIED` and auth failures to 401 - a catch-all `Exception` handler (from PR 24) was swallowing them as 500s until specific handlers were added.

### Real code to read
- `security/SecurityConfig`, `security/JwtAuthenticationConverter`, `ApiKeyAuthenticationFilter`, `security/ApiKeyRateLimiterFilter` (rate limiting also lives here), `security/SecurityProperties`
- `security/pii/*` (`MaskedPii`, `SensitiveDataSerializer`, `PiiMaskingModule`, `PiiAccessDecider`, `PiiMasker`, `PiiRedactionFilter`)
- `domain/service/GdprService`, `domain/audit/AuditLog`
- Tests: `SecurityIntegrationTest`, `GdprPiiIntegrationTest`, `SensitiveDataSerializerTest`, `PiiMaskerTest`

### Interview highlights
- OAuth2 roles: resource server vs authorization server; what "validate, never issue" means.
- Scope model end-to-end: token `scope` → `SCOPE_*` authority → `@PreAuthorize`; why `pii_read` is separate from `order_read`.
- Masking strategy: why mask in the *serializer* (one place), when to show raw (privileged caller), and why logs are scrubbed regardless.
- GDPR erasure vs anonymization - show you know Art. 17(3) (legal retention) and that audit trails must not contain raw PII.
- API-key auth + per-key rate limiting as a machine-client story.

### Apply this at work
Treat PII as data-flow: annotate, mask at the boundary, redact in logs, audit subject-rights actions, and *prove* PCI scope with tests. When you add a `@RestControllerAdvice`, immediately add explicit 401/403 handlers or method-security denials will silently become 500s.

## 6. Events, resilience & observability (PRs 18-19, 29, 31-32)

### Concepts
- **Event store (PR 19)**: append-only log of *facts* (`aggregate_id`, `version`, `payload`); a UNIQUE `(aggregate_id, version)` makes duplicate appends impossible - that uniqueness *is* event-sourcing integrity. Deliberately no optimistic-lock `version`/audit on the log rows (it is not a mutable aggregate).
- **Resilience with Resilience4j (PR 29)**, composed *programmatically* (visible order) on the payment gateway:
  `ThreadPoolBulkhead` (own small pool: gateway slowness cannot starve DB threads) → `CircuitBreaker` (CLOSED → OPEN after failures/slow calls → HALF_OPEN probe → CLOSED) → `Retry` with exponential backoff.
  Key empirical finding: **Retry sits INSIDE the CircuitBreaker** - each logical request is one breaker record, and inner attempts retry inside it (we verified the count in tests). Slow calls also trip the breaker (`slow-call-duration-threshold`) - latency is a failure mode. Rate limiting per API key: a separate bucket per key (`ApiKeyRateLimiterFilter`), 429 + `X-RateLimit-*` + `Retry-After`.
- **Kafka with the outbox pattern (PR 31)**: Kafka cannot join the DB transaction, so the event is written to an `outbox` table *in the same transaction* as the order (atomic: event exists iff order exists). A polling publisher claims PENDING rows (`FOR UPDATE SKIP LOCKED` - safe across instances), publishes, and marks PUBLISHED only after the broker acked; failures retry with attempt counters. Delivery is **at-least-once**, so consumers must be idempotent, commit offsets **manually** after processing, and route poison messages to a **dead-letter topic** (never block the group).
- **Domain events in this repo** are plain records (`OrderPlacedEvent`, `OrderConfirmedEvent`) implementing `DomainEvent`; an entity listener hook fires after persist. Message payloads for Kafka carry **no PII** by design (downstream that needs more asks the API).
- **Observability (PR 32)** - three pillars:
  1. **Structured logs**: JSON per event in prod (`logback-spring.xml` + Logstash encoder), human-readable in dev. MDC `correlationId` (from `CorrelationIdFilter`, honouring/generating `X-Correlation-Id`) rides every log line and response.
  2. **Metrics**: Micrometer `@Timed` (via a `TimedAspect` bean) on `order.place`/`product.get`; Prometheus registry exposed at `/actuator/prometheus`; Redis keyspace hit/miss gauges for the cache.
  3. **Traces**: OpenTelemetry bridge + OTLP exporter to Jaeger (compose service). Tracing disabled by default so ordinary contexts never start an exporter; enabled in prod.
  Plus custom **health indicators** (`/actuator/health/liveness|readiness` for K8s probes), **SLO definitions** and **AlertManager rules** (`docs/slo`, `docs/monitoring`).
- **SLI vs SLO vs SLA**: measure (SLI: p95, success rate) → target over time (SLO: p95 < 500 ms, ≥99.5% success) → contractual promise (SLA). Alerts should fire on error-budget burn, not single flaky minutes.

### Real code to read
- `domain/event/*`, `domain/eventstore/*`, `domain/listener/OrderBusinessListener.java`
- `domain/service/SimulatedPaymentGateway.java` (the composed resilience stack), `security/ApiKeyRateLimiterFilter`, `resilience4j.*` in `application.yml`
- `domain/outbox/*`, `messaging/*` (producer, consumer, publisher, KafkaConfig), Liquibase `08`
- `observability/*` (CorrelationIdFilter, AppInfoHealthIndicator), `config/ObservabilityConfig`, `logback-spring.xml`
- Tests: `EventStoreIntegrationTest`, `ResilienceChaosIntegrationTest`, `RateLimitIntegrationTest`, `KafkaOutboxIntegrationTest`, `ObservabilityIntegrationTest`

### Interview highlights
- Outbox pattern: why (no 2PC), how (same-tx insert + poller + SKIP LOCKED), and delivery semantics (at-least-once ⇒ idempotent consumers + DLT).
- Circuit breaker + retry composition and the per-request-vs-per-attempt subtlety; slow calls as failures.
- At-least-once vs exactly-once and why "exactly-once" needs idempotency anyway.
- The three pillars and one concrete example of each from your work; correlation ids across services.
- Event store integrity = uniqueness of (aggregateId, version).

### Apply this at work
Before publishing events directly from a transaction, ask "what happens if the broker is down at commit?" - that is exactly the case the outbox solves. Wrap every downstream call in a breaker and decide retry placement deliberately. Ship correlation ids and a sample trace early; retrofitting observability is miserable.

## 7. Platform: Docker, Kubernetes & GitOps (PRs 33-35)

### Concepts
- **Containerization**: multi-stage `Dockerfile` (Maven build stage → slim JRE runtime, non-root user) keeps images small and toolchain-free. `docker-compose` runs the WHOLE stack (postgres, redis, kafka, jaeger, api) with health-gated `depends_on`.
- **Kubernetes essentials**: Deployment (declarative desired state), Service (stable network endpoint), ConfigMap (non-secret config), HPA (CPU-based autoscale), Ingress + cert-manager TLS. **Probes**: `startup` (don't kill a slow cold start), `readiness` (gate traffic), `liveness` (restart a wedged pod) - each uses its OWN endpoint because a busy-but-healthy pod must not be restarted. Graceful shutdown: `server.shutdown: graceful` + a drain timeout so in-flight requests finish.
- **Kustomize overlays** (dev/test/uat/staging/prod): the same base + per-env patches (replicas, image tags, active Spring profile). This is the promotion artifact: environment = git branch/overlay.
- **Secrets**: plaintext never in git. **Sealed Secrets** encrypt a Secret so the manifest is commit-safe; only the cluster controller can decrypt it (kubeseal flow in `docs/k8s`).
- **GitOps & CI/CD**: GitHub Actions CI runs the full Testcontainers suite on every PR; promotion is a commit that bumps the overlay image tag; **ArgoCD** watches env branches and converges the cluster. Rollback = revert the commit. Progressive delivery = promote one stage at a time watching SLOs.
- **Feature flags** (`app.features.*`) let a capability ship dark and switch by config per environment - the bridge between code and progressive delivery.
- **Enterprise slice**: i18n (message bundles resolved by `Accept-Language`), CSV export behind a feature flag; design notes for multi-tenancy (discriminator vs schema-per-tenant), developer portal/API-key self-service, scheduled/background processing, DB backup strategy (WAL + PITR + restore drills).

### Real code to read
- `Dockerfile`, `docker-compose.yml`, `.github/workflows/*`, `k8s/base/*`, `k8s/overlays/*`, `k8s/argocd/order-api.yaml`
- `config/FeatureFlags`, `api/rest/controller/{FeatureFlagsController,I18nController,ExportController}`

### Interview highlights
- Container vs image vs orchestration; why multi-stage.
- Probes: difference between liveness/readiness/startup and why separate endpoints.
- GitOps: desired state in git, operator converges, deploy=merge, rollback=revert.
- Kustomize overlays as the per-env mechanism; Sealed Secrets for secrets-in-git.
- Progressive delivery + feature flags.
- Enterprise breadth answers: multi-tenancy options and trade-offs.

## 8. Engineering process: how the project stayed honest

- **One PR = one concept**, reviewable diff, green tests, then merge. Small steps keep every change understandable.
- **GitHub flow with env branches** (develop → test → uat → staging → main → prod); promotions owned by humans (in this journey, an explicit approval gate except the final stretch).
- **Test strategy pyramid realized with real infra**: unit tests for pure logic (masking, sequence allocator, DTO mapping), `@SpringBootTest` + Testcontainers for everything that touches a DB/Redis/Kafka, plus *schema truth* tests. Every integration test class uses its own Postgres (unique `integration.database.tag`) because global state (Ehcache CacheManager, Hibernate stats, JVM-wide listeners) leaks otherwise - **the single most valuable testing lesson in this repo**.
- **Real bugs we hit (gems for interviews):**
  - `orphanRemoval` without cascade doesn't delete.
  - Inverse nullable `@OneToOne` can't be lazy → per-row SELECTs.
  - Projection aliases must match getter names.
  - A catch-all `Exception` handler turned 403s into 500s.
  - Redisson brings a second JSR-107 provider → Hibernate L2 startup ambiguity → pin `hibernate.javax.cache.provider`.
  - `@Lazy` must annotate the *injection point* (proxy), not only the `@Bean`, or eager singletons still connect to Redis at startup.
  - `@Timed.percentiles` is `double[]`; `ExecutorService.submit(methodRef)` is ambiguous for primitive-returning refs; `NewTopic` is a Kafka-clients class.
  - Retry inside the breaker ⇒ CB counts requests, not attempts.
  - MockMvc needs `.secure(true)` for HSTS assertions.

### Final exercise (30 minutes, do it!)
1. Open `OrderService.placeOrder` and list every cross-cutting system that method touches (transactions, optimistic locking, cache eviction, outbox, metrics, resilience via the gateway bean).
2. Explain out loud, to a rubber duck: what happens if the payment gateway is down for 5 minutes *exactly at commit time*? (breaker opens → fast-fail → no order → retry budget → DLT none needed; outbox row stays PENDING and publishes later).
3. Write one sentence per chapter of this doc without looking. The chapters you cannot summarise are your revision list.

*End of learning document. Continue with the interview cheat sheets in `docs/interview-cheat-sheets/`.*






