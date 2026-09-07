# 10 — System Design & Behavioral

## Design an order/checkout flow (30-40 second skeleton)
1. **Write path**: POST /orders with Idempotency-Key.
   Validate (DTO groups) → one `@Transactional` service: lock/version stock,
   insert order+items, charge payment (resilient gateway), record payment, write
   outbox event. Failure anywhere → full rollback.
2. **Consistency**: optimistic locking on stock (`@Version` + retry); DB-level
   checks; idempotency keys against duplicate charges.
3. **Read path**: cache the catalogue DTOs (cache-aside, TTL), evict on writes;
   projections for dashboards; batch/fetch joins to kill N+1.
4. **Async**: outbox → Kafka; consumers idempotent, manual acks, DLT.
5. **Scale**: stateless API (JWT), virtual threads, HPA; Redis locks if multiple
   instances mutate shared counters outside the DB.
6. **Non-functional**: rate limits per key, circuit breakers on downstreams,
   structured logs + metrics + traces, SLOs; GDPR erasure/portability; secrets
   sealed; GitOps promotion with ArgoCD.

## Trade-off vocabulary to use
- **Consistency vs availability**: DB transactions vs outbox/eventual.
- **Optimistic vs pessimistic**; **cache correctness vs cost** (TTL/eviction).
- **At-least-once vs exactly-once** (idempotency).
- **Monolith vs services**: this repo = modular monolith (right default);
  extract bounded contexts (events, orders) when they need to scale/own data.

## Behavioral / situational scripts (STAR skeletons)
1. **"Real bug you found."**
   S: method-security denials returned 500. T: add explicit 401/403 handlers.
   A: mapped `AccessDeniedException`/`AuthenticationException` to RFC 7807.
   R: security tests assert 403/401 bodies; 500s stop hiding auth bugs.
2. **"Hard technical lesson."**
   S: integration tests polluted each other (Ehcache CacheManager is JVM-wide).
   A: each test class gets its own Spring context + fresh Postgres
   (`integration.database.tag`); L2 off except its dedicated test.
   R: 120 green tests that never flake from cache bleed.
3. **"Conflict / disagreement."** (use a real-ish one)
   S: wanted Redis caching on a read path; colleague feared stale stock.
   A: audited every stock writer, evict on write, TTL safety net, cache metrics.
   R: cache proven correct by tests (order changes visible immediately) + hit/miss
   gauges. Frame as: data > opinion; prove correctness with tests.
4. **"Tight deadline / scope."**
   A: ship a feature flag (dark launch), verify with metrics, flip gradually.
   R: no midnight rollouts; instant rollback.

## Questions to ask the interviewer (choose 2-3)
- "What does the team consider the hardest reliability problem right now?"
- "How do you run database migrations and who reviews them?"
- "What is your on-call/error-budget culture?"
- "Where do you see the product's biggest scaling constraint next year?"

## Final polish tips
- Say "it depends" then immediately give the deciding factors (contention, scale,
  team, cost).
- Every technical claim from this project has a test behind it - say that once;
  it separates you from tutorial-followers.
- End answers with the trade-off you accepted and how you validated it.

---

*Revision loop: pick any sheet → cover the answers → re-tell the project story in
60 seconds. Repeat until effortless.*
