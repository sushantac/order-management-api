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


---

## Deep Dives for PRs #1–#35 — Job-Ready “Purpose → How to Use”

The 35-PR base journey is now documented as **one file per PR** in [`docs/additions/more-detail-on-earlier-PRs/README.md`](../additions/more-detail-on-earlier-PRs/README.md) — each 300–400 lines, 10 sections, with `file:line`, `curl`/`psql`/`actuator`, and interview Q&A. This section is now a **thin index** (the detailed prose lives in those deep dives; git history retains the original chapters).

| PR | Deep Dive | One-Line Purpose | Try It |
|---|---|---|---|
| #1 | [01-project-setup.md](../additions/more-detail-on-earlier-PRs/01-project-setup.md) | Maven wrapper, Java 21, Spring Boot skeleton | `./mvnw verify` |
| #2 | [02-database-schema-with-foreign-keys.md](../additions/more-detail-on-earlier-PRs/02-database-schema-with-foreign-keys.md) | Liquibase schema with FKs | `psql \d orders` |
| #3 | [03-jpa-entities-with-foreign-key-mappings.md](../additions/more-detail-on-earlier-PRs/03-jpa-entities-with-foreign-key-mappings.md) | JPA FK mappings | `GET /api/customers` |
| #4 | [04-cascading-strategies.md](../additions/more-detail-on-earlier-PRs/04-cascading-strategies.md) | Cascade ALL + orphanRemoval | `POST /api/orders` |
| #5 | [05-fetch-types.md](../additions/more-detail-on-earlier-PRs/05-fetch-types.md) | LAZY vs EAGER | `enable_lazy_load_no_trans` demo |
| #6 | [06-fetch-joins-and-entity-graphs.md](../additions/more-detail-on-earlier-PRs/06-fetch-joins-and-entity-graphs.md) | JOIN FETCH / @EntityGraph | `EXPLAIN ANALYZE SELECT ... JOIN FETCH` |
| #7 | [07-batch-fetching.md](../additions/more-detail-on-earlier-PRs/07-batch-fetching.md) | @BatchSize | `default_batch_fetch_size=20` |
| #8 | [08-optimistic-locking.md](../additions/more-detail-on-earlier-PRs/08-optimistic-locking.md) | @Version stock | `ab -n 10 -c 10 POST /api/orders` |
| #9 | [09-pessimistic-locking.md](../additions/more-detail-on-earlier-PRs/09-pessimistic-locking.md) | SELECT FOR UPDATE | concurrent cancel |
| #10 | [10-auditing.md](../additions/more-detail-on-earlier-PRs/10-auditing.md) | @CreatedDate auditing | `SELECT created_date FROM orders` |
| #11 | [11-custom-queries.md](../additions/more-detail-on-earlier-PRs/11-custom-queries.md) | @Query JPQL/native | `GET /api/orders?status=PLACED` |
| #12 | [12-specifications-and-querydsl.md](../additions/more-detail-on-earlier-PRs/12-specifications-and-querydsl.md) | Specifications/QueryDSL | `?filter=customer:1` |
| #13 | [13-entity-lifecycle-callbacks.md](../additions/more-detail-on-earlier-PRs/13-entity-lifecycle-callbacks.md) | @PrePersist orderNumber | `SELECT order_number FROM orders` |
| #14 | [14-schema-generation-and-validation.md](../additions/more-detail-on-earlier-PRs/14-schema-generation-and-validation.md) | ddl-auto validate | `./mvnw -Pprod` |
| #15 | [15-sql-logging-and-debugging.md](../additions/more-detail-on-earlier-PRs/15-sql-logging-and-debugging.md) | SQL logging | `logging.level.org.hibernate.SQL=DEBUG` |
| #16 | [16-second-level-cache.md](../additions/more-detail-on-earlier-PRs/16-second-level-cache.md) | L2 cache | `GET /api/products/1` ×2 |
| #17 | [17-dto-projections.md](../additions/more-detail-on-earlier-PRs/17-dto-projections.md) | Record DTOs | `GET /api/orders → OrderResponse` |
| #18 | [18-jpa-events-and-listeners.md](../additions/more-detail-on-earlier-PRs/18-jpa-events-and-listeners.md) | @EntityListeners | `POST /api/orders` → event |
| #19 | [19-event-sourcing-and-event-store.md](../additions/more-detail-on-earlier-PRs/19-event-sourcing-and-event-store.md) | event_store append-only | `SELECT * FROM event_store` |
| #20 | [20-service-layer.md](../additions/more-detail-on-earlier-PRs/20-service-layer.md) | @Service @Transactional | `POST /api/orders` atomic |
| #21 | [21-dtos-with-java-records.md](../additions/more-detail-on-earlier-PRs/21-dtos-with-java-records.md) | Java Records DTOs | `{"productId":1,"quantity":2}` |
| #22 | [22-rest-controllers.md](../additions/more-detail-on-earlier-PRs/22-rest-controllers.md) | @RestController | `201 + Location` |
| #23 | [23-validation.md](../additions/more-detail-on-earlier-PRs/23-validation.md) | Bean Validation | `{"quantity":0} → 400` |
| #24 | [24-exception-handling-and-idempotency.md](../additions/more-detail-on-earlier-PRs/24-exception-handling-and-idempotency.md) | Advice + Idempotency-Key | `Idempotency-Key: abc` ×2 |
| #25 | [25-openapi-documentation.md](../additions/more-detail-on-earlier-PRs/25-openapi-documentation.md) | springdoc Swagger | `/swagger-ui.html` |
| #26 | [26-security-with-oauth2-and-jwt.md](../additions/more-detail-on-earlier-PRs/26-security-with-oauth2-and-jwt.md) | JWT/API-key dual chain | `X-API-KEY` vs `Bearer` |
| #27 | [27-pii-and-gdpr.md](../additions/more-detail-on-earlier-PRs/27-pii-and-gdpr.md) | PII + GDPR | `POST /api/gdpr/erase` |
| #28 | [28-caching-with-redis.md](../additions/more-detail-on-earlier-PRs/28-caching-with-redis.md) | Redis products | `redis-cli GET` |
| #29 | [29-resilience-patterns.md](../additions/more-detail-on-earlier-PRs/29-resilience-patterns.md) | Resilience4j | `ab → 429` |
| #30 | [30-virtual-threads-and-concurrency.md](../additions/more-detail-on-earlier-PRs/30-virtual-threads-and-concurrency.md) | Virtual threads | `spring.threads.virtual.enabled` |
| #31 | [31-kafka-event-driven.md](../additions/more-detail-on-earlier-PRs/31-kafka-event-driven.md) | Outbox + Kafka | `SELECT * FROM outbox` |
| #32 | [32-observability.md](../additions/more-detail-on-earlier-PRs/32-observability.md) | Actuator/Prometheus | `/actuator/prometheus` |
| #33 | [33-docker-and-kubernetes.md](../additions/more-detail-on-earlier-PRs/33-docker-and-kubernetes.md) | Dockerfile + K8s | `kubectl apply -k k8s/base` |
| #34 | [34-ci-cd-and-gitops.md](../additions/more-detail-on-earlier-PRs/34-ci-cd-and-gitops.md) | Actions + Kustomize | `git push` → Actions |
| #35 | [35-enterprise-features.md](../additions/more-detail-on-earlier-PRs/35-enterprise-features.md) | Rate limit/bulk/reporting | `POST /api/admin/import` |
|  | Bonus #38–#52 | See [`more-detail-on-additions/README.md`](../additions/more-detail-on-additions/README.md) | AI arc deep dives |

> Each row links to a 300–400-line deep dive with Purpose → Problem → Solution (ASCII) → How Implemented (`file:line`) → **How to Use** (`curl`/`psql`) → Key Decisions → How to Verify → Job Lens → Interview Q&A → Limits. The original chapters are retained in `git log` (pre-replacement).

---


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






