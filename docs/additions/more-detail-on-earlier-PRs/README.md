# more-detail-on-earlier-PRs — Job-ready deep dives for base PRs #1–#35

This folder is the companion to [`more-detail-on-additions`](../more-detail-on-additions/README.md). While that folder covers the bonus AI arc (#38–#52), this one covers the **35-PR base journey** — from project setup to Kubernetes — with the same 10-section template: **Purpose, Problem Solved, Implementation (how it works + key files/code), How to Use (copy/run), and Job Relevance** — so you can explain, demo, and interview on any of #1–#35 without digging through diffs.

> Back to additions: [`docs/additions/README.md`](../README.md) · Start here: [`00-overview-early-journey.md`](./00-overview-early-journey.md) · Bonus deep dives: [`../more-detail-on-additions/README.md`](../more-detail-on-additions/README.md)

| Document | PR | Purpose | How to Use | Job Lens |
|---|---|---|---|---|
| [`00-overview-early-journey.md`](./00-overview-early-journey.md) | — | Map of #1–#35: data → persistence → API → security → events → platform | Read first to orient, then jump to any row | Frame the whole base journey in 90s |
| [`01-project-setup.md`](./01-project-setup.md) | #1 | Project skeleton: Maven wrapper, Java 21, Spring Boot, .gitignore, CI-ready layout | `./mvnw verify` — build without DB | Explain why not `spring init` |
| [`02-database-schema.md`](./02-database-schema.md) | #2 | Liquibase schema with FKs, orders DB | `docker compose up postgres; ./mvnw liquibase:update; psql \d orders` | Schema as code — GitOps for DB |
| [`03-jpa-entities.md`](./03-jpa-entities.md) | #3 | JPA entities with FK mappings (Customer→Order→OrderItem) | `curl /api/customers` after `POST /api/orders` | ORM mapping — own/ inverse side |
| [`04-cascading.md`](./04-cascading.md) | #4 | Cascading ALL + orphanRemoval for Order→Items→Payment | `POST /api/orders` then `DELETE /api/orders/{id}/items/{itemId}` | Cascade vs manual save |
| [`05-fetch-types.md`](./05-fetch-types.md) | #5 | LAZY vs EAGER, when to use each | `spring.jpa.properties.hibernate.enable_lazy_load_no_trans=false` demo | N+1 root cause |
| [`06-fetch-joins-entity-graphs.md`](./06-fetch-joins-entity-graphs.md) | #6 | Fix N+1 with fetch joins & @EntityGraph | `EXPLAIN ANALYZE SELECT ... JOIN FETCH` | Performance — N+1 → 1 query |
| [`07-batch-fetching.md`](./07-batch-fetching.md) | #7 | @BatchSize for collections | `hibernate.default_batch_fetch_size=20` demo | Batch N+1 without join |
| [`08-optimistic-locking.md`](./08-optimistic-locking.md) | #8 | @Version for stock concurrency | `ab -n 10 -c 10 POST /api/orders` → 409 → @Retryable | Optimistic vs pessimistic |
| [`09-pessimistic-locking.md`](./09-pessimistic-locking.md) | #9 | SELECT … FOR UPDATE for payments | `curl` concurrent cancel → one wins | When pessimistic wins |
| [`10-auditing.md`](./10-auditing.md) | #10 | @CreatedDate/@LastModifiedDate + AuditorAware | `psql SELECT created_date FROM orders` | Audit columns for free |
| [`11-custom-queries.md`](./11-custom-queries.md) | #11 | @Query JPQL / native + projections | `GET /api/orders?status=PLACED` | Beyond findById |
| [`12-specifications-querydsl.md`](./12-specifications-querydsl.md) | #12 | Specifications & QueryDSL for dynamic filters | `GET /api/orders?filter=customer:1;status:PLACED` | Dynamic queries without string concat |
| [`13-entity-lifecycle-callbacks.md`](./13-entity-lifecycle-callbacks.md) | #13 | @PrePersist orderNumber generation | `psql SELECT order_number FROM orders` | Business keys in callbacks |
| [`14-schema-generation-validation.md`](./14-schema-generation-validation.md) | #14 | ddl-auto validate vs update, in prod | `./mvnw -Pprod` → validate fails if drift | Never `update` in prod |
| [`15-sql-logging-debugging.md`](./15-sql-logging-debugging.md) | #15 | SQL logging + format_sql + bind params + statistics | `logging.level.org.hibernate.SQL=DEBUG` + `tail -f` | Debug without debugger |
| [`16-second-level-cache.md`](./16-second-level-cache.md) | #16 | Hibernate L2 + query cache | `GET /api/products/{id}` twice → second from L2 (`hitCount`) | Cache vs DB round-trip |
| [`17-dto-projections.md`](./17-dto-projections.md) | #17 | Record DTOs + MapStruct/manual mapping | `GET /api/orders → OrderResponse record` | Never expose entities |
| [`18-jpa-events-listeners.md`](./18-jpa-events-listeners.md) | #18 | @EntityListeners for domain events | `POST /api/orders` → listener publishes `OrderCreatedEvent` | Decoupling via events |
| [`19-event-sourcing-store.md`](./19-event-sourcing-store.md) | #19 | Append-only event_store table | `psql SELECT * FROM event_store WHERE aggregate_id=...` | Event sourcing vs CRUD |
| [`20-service-layer.md`](./20-service-layer.md) | #20 | @Service + @Transactional + placeOrder atomic 4 steps | `POST /api/orders` with stock → success or full rollback | ACID in service |
| [`21-dtos-records.md`](./21-dtos-records.md) | #21 | Java Records for request DTOs (OrderLine) | `curl -d '{"customerId":1,"lines":[{"productId":1,"quantity":2}]}'` | Records for DTOs |
| [`22-rest-controllers.md`](./22-rest-controllers.md) | #22 | @RestController, ResponseEntity, location header | `curl -i POST /api/orders` → 201 + Location | REST idioms |
| [`23-validation.md`](./23-validation.md) | #23 | Bean Validation @Valid, @NotNull, global handler | `curl -d '{"quantity":0}'` → 400 with errors array | Fail fast with 400 |
| [`24-exception-handling-idempotency.md`](./24-exception-handling-idempotency.md) | #24 | @ControllerAdvice + Idempotency-Key header | `curl -H "Idempotency-Key: abc"` twice → 200 then 200 no duplicate | Idempotency for retries |
| [`25-openapi-documentation.md`](./25-openapi-documentation.md) | #25 | springdoc OpenAPI + Swagger UI | `open http://localhost:8080/swagger-ui.html` | Docs from code |
| [`26-security-oauth-jwt.md`](./26-security-oauth-jwt.md) | #26 | JWT + API-key dual chain, SecurityFilterChain | `curl -H "X-API-KEY: dev-api-key"` vs `Authorization: Bearer <JWT>` | AuthN/Z |
| [`27-pii-gdpr.md`](./27-pii-gdpr.md) | #27 | PII redaction + GDPR erase/portability | `POST /api/gdpr/erase/{customerId}` → `audit_log` | GDPR as feature |
| [`28-caching-redis.md`](./28-caching-redis.md) | #28 | Redis cache for products | `GET /api/products/1` → Redis `GET orderapi:products:1` | Cache stampede |
| [`29-resilience-patterns.md`](./29-resilience-patterns.md) | #29 | Resilience4j CB/Retry/Bulkhead/RateLimiter | `ab` → 429, payment failure → CB open | Resilience without code |
| [`30-virtual-threads.md`](./30-virtual-threads.md) | #30 | `spring.threads.virtual.enabled=true` for Tomcat | `ab -n 1000 -c 100` → all virtual threads | Virtual threads vs platform |
| [`31-kafka-event-driven.md`](./31-kafka-event-driven.md) | #31 | Outbox + Kafka via docker-compose | `psql SELECT * FROM outbox`; `kcat -L` | Outbox for exactly-once |
| [`32-observability.md`](./32-observability.md) | #32 | Actuator, Prometheus, OTel, logstash JSON | `curl /actuator/prometheus \| grep jvm_` | What to watch |
| [`33-docker-kubernetes.md`](./33-docker-kubernetes.md) | #33 | Multi-stage Dockerfile + K8s manifests | `docker build -t api .; kubectl apply -k k8s/base` | Ship it |
| [`34-cicd-gitops.md`](./34-cicd-gitops.md) | #34 | GitHub Actions + GitOps + Kustomize overlays | `git push` → Actions → `kubectl` | CI/CD as code |
| [`35-enterprise-features.md`](./35-enterprise-features.md) | #35 | Optional: rate limit, bulk import, reporting | `POST /api/admin/import` | Enterprise checklist |

> Tip: For interviews, pick one row, run its **How to Use** one-liner, then narrate **Purpose → Problem Solved → Implementation → Job Lens** from that doc — 90 seconds, job-ready.
