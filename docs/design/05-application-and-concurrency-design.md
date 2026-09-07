# 05 — Application & Concurrency Design

## 5.1 Layered/module design
```
api → application → domain/ports ← infrastructure (repos, messaging, config)
             └────────── api DTO mapping happens at the edge
```
Key components:
- `OrderService.placeOrder` - the flagship use-case (transaction boundary).
- `ProductCatalogueService` - cache-aside read path + eviction API for writers.
- `GdprService` - erasure/portability + audit writes.
- `DashboardService` - structured fan-out over virtual threads.
- `DistributedLockService` (Redisson) - cross-instance mutual exclusion.
- `ProductStockService`/`ProductInventoryService` - optimistic/pessimistic
  stock paths (also cache evictors).
- IdempotencyService, EventStoreService, OutboxPublisher, OrderEventProducer/
  Consumer - integration & replay semantics.

## 5.2 Transactional flows (normative)
**Place order** (single transaction): load customer → for each line lock/read
product (optimistic version; business check stock) → decrement stock → create
order + snapshot lines → charge payment (resilient gateway) → record PROCESSED
payment → write outbox row. Any failure ⇒ full rollback; idempotency replay
skips the whole flow.

**Read product**: cache hit ⇒ return DTO; miss ⇒ load + map + populate cache
(TTL). Writers (catalogue CRUD, stock decrement from orders/locking paths)
evict affected keys in the committing unit of work.

## 5.3 Concurrency strategy
| Concern | Mechanism |
|---|---|
| Hot row writes (stock) | optimistic `@Version` + bounded retry; pessimistic `FOR UPDATE` option; deadlock retry |
| Last-unit race | version check ⇒ one winner (proven by 100-caller test) |
| Cross-instance mutex | Redisson RLock with lease (crash-safe) |
| HTTP concurrency | virtual threads (`spring.threads.virtual.enabled`) |
| Fan-out reads | virtual-thread executor, join-all in scope |
| Shared mutable state | `ReentrantLock` (fair) demo; no `synchronized` in hot paths |
| Caching | Redis shared cache (instance-consistent), L2 per-JVM |

Design notes: virtual threads suit the I/O-bound gateway/DB waits; locks are
chosen by contention profile (optimistic for catalogue reads, pessimistic for
contended counters), and every cache consumer tolerates eventual freshness
within TTL except stock, which is evicted synchronously by writers.

## 5.4 Cache design
- Read-model DTO cache in Redis: keys `products::{id}`, TTL 10 min (dev/prod),
  prefix `orderapi:`; default profile uses in-memory cache (tests).
- Eviction correctness is the design centre: any writer changing what a read
  returns evicts (catalogue writes: all entries; stock writers: single key).
- Cache metrics: Redis keyspace hit/miss gauges; cache-aside populates on
  miss only. Entities are never cached directly (lazy/session state).

## 5.5 Configuration & profiles
- Behavior knobs in commented `application.yml`; typed property classes
  (`SecurityProperties`, `FeatureFlags`); master switches for tests
  (`app.security.enabled`), brokers (`app.kafka.enabled`) and cache type.
- Profiles: `default` (tests/zero-infra), `dev` (Redis/Kafka against compose,
  `ddl-auto: update` documented as dev-only hazard), `prod` (Redis/Kafka/JSON
  logs/tracing, `validate`).
