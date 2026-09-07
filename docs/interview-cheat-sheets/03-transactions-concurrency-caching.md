# 03 — Transactions, Locking & Caching

## One-page mental model
- `@Transactional` = one ACID unit. Put it on **services**, rollback on any
  `RuntimeException` by default. Locks are held until commit, not per statement.
- Concurrency conflicts on a ROW: **optimistic** (version, retry loser) or
  **pessimistic** (lock up front). Distributed coordination (multi-instance):
  Redis lock (Redisson) with a lease. Speed for reads: caches.

## Optimistic vs pessimistic (compare like this)
| | Optimistic (`@Version`) | Pessimistic (`SELECT ... FOR UPDATE`) |
|---|---|---|
| When | low/medium contention, reads >> writes | hot counters, must-not-fail writes |
| Cost | failed commit + retry | blocked transactions/queueing |
| Failure | `OptimisticLockingFailureException` | lock timeouts / deadlock abort |
| Bonus | retry with backoff makes it eventually consistent | `FOR SHARE` lets readers share |
Postgres maps `PESSIMISTIC_READ`→`FOR SHARE` (writers wait, readers don't); deadlock victims surface as retryable `DeadlockLoserDataAccessException`.

## The classic interview scenario: overselling the last unit
100 concurrent requests buy the last item.
- Without locking: two threads read stock=1, both write 0 → oversold.
- `@Version`: one UPDATE matches; the other matches 0 rows → exception → `@Retryable`
  re-reads (0 now) → clean "insufficient stock". Exactly one winner.
- Pessimistic alternative: queue on `FOR UPDATE`.

## Application caching (Redis, cache-aside)
Flow: read cache → hit? return. miss? load DB → write cache **with TTL**.
- `@Cacheable` (method body = miss path), `@CacheEvict` on writes.
- **Cache DTOs, not entities** (lazy/session state cannot be serialized).
- Invalidation spiderweb: every writer that changes what a read returns must evict.
  In this repo: catalogue writes evict all; order/locking services evict the product
  key after stock changes - or a cached stock level silently oversells.
- `spring.cache.type=simple` for tests, `redis` in dev/prod (`time-to-live`, prefix).

## L2 cache (Hibernate/Ehcache)
- Second-level cache = entity data across sessions, JVM-wide, `@Cacheable` +
  `READ_WRITE`. JCache `CacheManager` is a **JVM-wide singleton** - the reason each
  integration test class needs an isolated Spring context/DB (state leaks otherwise).
- App cache (Redis) is shared across instances; L2 is per-JVM.

## Virtual threads & Java 21 concurrency
- Virtual threads park on blocking calls (~KB) vs platform threads (~MB): "thread per
  request" finally scales. `spring.threads.virtual.enabled=true`.
- Structured fan-out: run independent reads concurrently and join ALL before
  returning (try-with-resources executor scope = structured concurrency).
- Beware **pinning** (synchronized/native blocks pin a carrier) and shared state.

## Distributed locks (Redisson)
- JVM locks protect one process only; multiple instances need a shared lock.
- Redisson `RLock.tryLock(wait, lease)`: SET NX + token release + **lease** so a
  crashed holder cannot deadlock everyone (tested: lease expiry reclaims the lock).
- `@Lazy` on the injection point ⇒ client never connects until a lock is used.

## Tell me about...
**Cache consistency.** → "I cache the catalogue DTO. The dangerous case was stock:
orders change stock outside the catalogue, so every stock writer evicts that product's
key. I cache read models, not entities, and TTL bounds staleness even if an eviction is
missed. A Redis metrics gauge shows hit/miss so we notice a dead cache."

## Rapid Q&A
- Retry optimistic conflicts? → yes, bounded attempts + backoff, only for
  optimistic/deadlock exceptions - never retry business rejections.
- Cache invalidation vs TTL? → both: explicit eviction for correctness, TTL as
  safety net.
- Sleep in a transaction? → never (holds locks); demo-only in this repo.
- Virtual threads & locks? → still need locks for shared mutable state.
