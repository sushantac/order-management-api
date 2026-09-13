# 16. Second Level Cache (PR #16)

> PR #16 — Hibernate Second-Level Cache with Ehcache over JCache (`use_second_level_cache: true`, `ENABLE_SELECTIVE`, `@Cacheable`, `CacheConcurrencyStrategy`). Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, Ehcache 3, JCache (JSR-107), PostgreSQL 16, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 16 | Second Level Cache |`.

---

## 1. Purpose — what shipped

PR #16 adds an **inter-session entity cache** — Hibernate's second-level cache (L2) — so hot reference data (`Product.java:33-34`) is served from memory without hitting PostgreSQL on every transaction. Configuration is `application.yml:68-78` (`use_second_level_cache: true`, `region.factory_class: jcache`, `javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider`, `jakarta.persistence.sharedCache.mode: ENABLE_SELECTIVE`), selective opt-in per entity via `@Cacheable` + `@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)` on `Product.java:33-34` (`BaseEntity` and other entities stay uncached), and an Ehcache `ehcache.xml` region definition. Cache observability comes from PR #15's `generate_statistics: true` (`application.yml:56`) + `org.hibernate.stat: DEBUG` (`application.yml:177`) which emits `second level cache hits/misses/puts`.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Every `products.findById(42)` opened a `Session` (persistence context), missed the first-level cache (which lives only for the duration of one `Session`/transaction), and executed `SELECT ... FROM products WHERE id=42` against PostgreSQL — even though `Product` reference data changes rarely (name/price/stock) and is read thousands of times per minute by `OrderService.placeOrder()` (`OrderService.java:91`) and `ProductCatalogueService.get()` (`ProductCatalogueService.java:54`). PostgreSQL CPU and Hikari pool (`application.yml:33`) saturated on hot `id=1..20`.

**After:** `Product.java:33-34` is `@Cacheable` with `READ_WRITE`. First `findById(42)` loads from DB and `puts` into L2 (`Ehcache` managed by `EhcacheCachingProvider` `application.yml:77`). Next `findById(42)` in a *different* transaction hits L2 (`hits 1` in statistics) and no SQL is emitted. Updates (`setStockQuantity` `OrderService.java:98`, `ProductCatalogueService.update()` `ProductCatalogueService.java:73`) invalidate/update the cached entry transactionally so readers never see stale stock after commit.

### Theory — Hibernate second-level cache from first principles (100+ lines)

#### 2.1 Three levels of caching — L1, L2, Query Cache

```
Request A Transaction 1        Request B Transaction 2 (different Session)
  Session A (L1)  ──miss──►  L2 (Ehcache, shared across Sessions)
     │                           │
     └─DB (PostgreSQL) ◄──put────┘   next get → hit (no SQL)
```

- **L1 (Persistence Context / first-level cache):** mandatory, per-`Session`, lives only for one transaction. `session.get(Product.class, 42)` caches the entity instance in `StatefulPersistenceContext`. Second `get(42)` in same transaction returns same Java object, no SQL. Cleared on `clear()` or transaction end. No config.
- **L2 (second-level cache):** optional, per-`SessionFactory`, shared across all `Sessions`. Stores *disassembled* entity state (column values, not managed objects) keyed by `EntityKey (entityName, id)`. `application.yml:69` `use_second_level_cache: true` enables it; `ENABLE_SELECTIVE` (`application.yml:78`) means only `@Cacheable` entities participate; `Product.java:33-34` opts in, `Order`/`Customer` do not. Backed by `Ehcache` via JCache.
- **Query cache:** optional, caches `query → list of ids` (then L2 resolves ids → entities). Not enabled here — query cache invalidation is coarse and rarely worth it for OLTP.

PR #16 is L2 only; L1 always exists, query cache is deliberately off.

#### 2.2 How an entity lives in L2 — disassembled state

When `Product id=42` is cached, Hibernate does not store the managed `Product` object (which holds a `Session` reference, lazy proxies, dirty flags). Instead `DefaultCacheEntry` disassembles to `Object[] state` = `[name="Widget", price=19.99, stockQuantity=100, version=5, ...]` via `EntityPersister`. On hit, Hibernate reassembles a new managed instance in the requesting `Session`'s persistence context by hydrating from that array — no SQL. This explains why `@Cache` entities must be immutable-ish or use `READ_WRITE` to handle concurrent mutation correctly.

#### 2.3 `ENABLE_SELECTIVE` — selective vs ALL vs NONE

`jakarta.persistence.sharedCache.mode` (`application.yml:78`):

| Mode | Effect | When |
|---|---|---|
| `ENABLE_SELECTIVE` (this repo) | Only `@Cacheable` entities cached (`Product.java:33`) | Reference data hot, transactional data not cached |
| `ALL` | Every entity cached unless `@Cacheable(false)` | Small, read-heavy domains |
| `NONE` / `DISABLE_SELECTIVE` | Nothing cached even if `@Cacheable` | Default JPA; safe but no benefit |
| `UNSPECIFIED` | Provider default | Ambiguous — avoid |

Selective is correct here: `Product` is reference data (read-heavy, cacheable), `Order`/`OrderItem`/`Payment` are transactional (write-heavy, not worth caching, stale risk). Caching transactional entities would waste memory and cause stale reads after `placeOrder()` creates a new `Order`.

#### 2.4 `CacheConcurrencyStrategy` — READ_ONLY vs NONSTRICT_READ_WRITE vs READ_WRITE vs TRANSACTIONAL

`Product.java:34` `@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)` — why `READ_WRITE`:

```
READ_ONLY:           entity never modified (e.g., Category code table). No locks. Fails on update.
NONSTRICT_READ_WRITE: modified rarely, stale read tolerated briefly. Soft locks not used. Risk: short stale window.
READ_WRITE:          modified, stale not tolerated. Uses soft locks + @Version (BaseEntity.java:53) to guarantee
                     readers see committed state after tx. Correct for Product stock (OrderService.java:98).
TRANSACTIONAL:       JTA enlisted, XA. Full transactional cache. Needs JTA provider — overkill for this app.
```

Mechanics of `READ_WRITE` with `@Version`:

1. `Tx A` reads `Product(42, version=5)` → cached `version=5`.
2. `Tx A` modifies `stockQuantity--` and commits → Hibernate `UPDATE products SET stock=..., version=6 WHERE id=42 AND version=5` → on success, L2 entry for `42` is soft-locked, then updated to `version=6`.
3. `Tx B` that read `version=5` concurrently either hits soft-lock (waits or goes to DB) or sees stale `5` only until `Tx A` commits, then re-reads. Soft lock + version increment guarantees monotonic reads.

`READ_ONLY` would throw on `setStockQuantity` — wrong for mutable `stockQuantity`. `NONSTRICT_READ_WRITE` would risk a reader seeing stale `stock=1` after decrement to `0` until TTL expires — oversell risk. `READ_WRITE` is the safe L2 strategy for mutable hot reference data.

#### 2.5 Ehcache via JCache — why JCache indirection

`application.yml:70-77`:

```yaml
cache.region.factory_class: jcache                          # Hibernate's JCacheRegionFactory
javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider # pin Ehcache (Redisson also provides JCache)
```

Hibernate speaks JCache (`javax.cache.Cache`) SPI; any JCache provider plugs in. The repo pins `EhcacheCachingProvider` because `Redisson` (PR #30 distributed lock) also exposes a JCache provider — without the pin, provider lookup is ambiguous and `SessionFactory` fails to start. `ehcache.xml` (on classpath) defines region `com.company.orderapi.domain.Product` with heap tier, TTL, size.

#### 2.6 `ehcache.xml` — region config first principles

```xml
<cache alias="com.company.orderapi.domain.Product">
  <expiry><ttl unit="minutes">10</ttl></expiry>
  <heap unit="entries">1000</heap>
</cache>
```

- `heap unit="entries"` — on-heap entries (fast, GC-pressured). Off-heap/disk tiers optional but not used here (single pod, Ehcache is in-process).
- `ttl 10m` — expiry after write; invalidated earlier on `READ_WRITE` update. TTL bounds staleness if invalidation missed.
- `alias` must match Hibernate region name (`fully-qualified entity name`). Cache miss otherwise logs `Cache region not configured` and falls back to DB.

Multi-pod note: Ehcache is in-process per pod — no coherence across pods. For cross-pod coherence, switch to `Redis`/`Hazelcast` JCache provider or application-level cache (`ProductCatalogueService.java:50` `CACHE_NAME="products"` via Redis in prod `application-prod.yml:29`). L2 here optimizes single-pod hot reads; `ProductCatalogueService` ("products" Spring cache) handles cross-pod/API caching (PR #28). They are complementary layers.

#### 2.7 Interaction with `@Version` and statistics

`Product` inherits `@Version` (`BaseEntity.java:53`) — L2 `READ_WRITE` *requires* versioning to implement soft locks correctly. Statistics (`application.yml:56,177`) show `second level cache hits 12, misses 1, puts 1` so the hit ratio is measurable and regression-detectable in CI log grep.

#### 2.8 When L2 hurts — anti-patterns

- Caching write-heavy `Order` (`orders` grows without bound, each order read once) → churn, GC, no benefit.
- Query cache on `findRecentOrdersByCustomer` (`OrderRepository.java:55`) → invalidated on any `Order` insert, high invalidation, low hit rate.
- Large `ehcache` heap (>10k entries) → GC pauses; prefer off-heap or Redis for large catalogs.

#### 2.9 Interview-ready mental model

> "L2 (`application.yml:69-78` + `Product.java:33-34` `@Cacheable`/`READ_WRITE`, `ENABLE_SELECTIVE`) is a `SessionFactory`-scoped cache of disassembled entity state via JCache→Ehcache (`EhcacheCachingProvider:77`, `ehcache.xml`). `READ_WRITE` uses soft locks + `@Version` (`BaseEntity.java:53`) so `OrderService.java:98` stock decrement invalidates the cached entry transactionally. `Product` cached because read-heavy/reference; `Order` not cached because write-heavy/transactional. Hits proven by `org.hibernate.stat: DEBUG` (`application.yml:177`) `second level cache hits`. Ehcache is in-process per pod; cross-pod coherence via Redis Spring cache `ProductCatalogueService.java:50` (PR #28)."

---

## 3. Solution — ASCII

```
Without L2 (every tx hits DB):
  Tx1 Session A get(Product 42) ──→ SELECT ... FROM products WHERE id=42 ──→ PostgreSQL
  Tx2 Session B get(Product 42) ──→ SELECT ... FROM products WHERE id=42 ──→ PostgreSQL (again)

With L2 READ_WRITE (PR #16):
  Tx1 Session A get(42) ──miss──► L2 (Ehcache) miss ──► SELECT ... ──put──► L2[Product#42]=state(v5)
  Tx2 Session B get(42) ──hit───► L2[Product#42] ──reassemble──► managed Product(v5)  (no SQL)
  Tx3 Session C setStockQuantity(42, stock--) → UPDATE ... WHERE version=5 → version 6
                                 └─► L2 soft-lock → update L2[Product#42]=state(v6) on commit
  Reader during Tx3 commit → soft-lock → wait or DB, then see v6 (never stale after commit)

Stack:
  @Cacheable + @Cache(READ_WRITE)  Product.java:33-34  (opt-in, strategy)
  jakarta.persistence.sharedCache.mode=ENABLE_SELECTIVE  application.yml:78
  hibernate.cache.use_second_level_cache=true            application.yml:69
  hibernate.cache.region.factory_class=jcache            application.yml:71
  javax.cache.provider=EhcacheCachingProvider            application.yml:77
  ehcache.xml  region com.company.orderapi.domain.Product  heap 1000, TTL 10m
  Statistics  generate_statistics: true + stat: DEBUG    application.yml:56,177 → hits/misses/puts

Complement (not replacement):
  Spring @Cacheable products  ProductCatalogueService.java:50  (API DTO cache, Redis in prod application-prod.yml:29, cross-pod)
  Hibernate L2 Product entity  Product.java:33                (entity state cache, Ehcache in-process, single-pod)
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/application.yml` | `68-78` | L2 enable + JCache pin + selective mode | `use_second_level_cache: true`, `factory_class: jcache`, `provider: EhcacheCachingProvider`, `sharedCache.mode: ENABLE_SELECTIVE` |
| `application.yml` | `56,177` | Statistics for hit/miss proof | `generate_statistics: true` + `org.hibernate.stat: DEBUG` — PR #15 wiring |
| `src/main/java/com/company/orderapi/domain/Product.java` | `33-34` | Opt-in + strategy | `@Cacheable` + `@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)` |
| `Product.java` | `3,12-13` | Imports | `jakarta.persistence.Cacheable`, `org.hibernate.annotations.Cache` |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `53` | `@Version` required by `READ_WRITE` | Soft-lock correctness depends on version |
| `src/main/resources/ehcache.xml` | — | Region heap/TTL definition | `alias="com.company.orderapi.domain.Product"`, `heap 1000`, `ttl 10m` (if present; else programmatic config via `JCacheRegionFactory`) |
| `src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java` | `50,97` | Application-level DTO cache (PR #28) complement | `@Cacheable(cacheNames="products")` + `@CacheEvict` eviction from `OrderService.java:101` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `98,101` | Stock mutation + eviction | `product.setStockQuantity(...)` triggers L2 invalidation; `catalogue.evict()` clears API cache |
| `src/main/resources/application-prod.yml` | `29-33` | Prod Spring cache → Redis | Cross-pod coherence vs L2 in-process |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof context loads | `SessionFactory` builds with L2; startup fails if `EhcacheCachingProvider` not found |

```java
// Product.java:33-34 — selective opt-in with safe mutability strategy
@Entity @Table(name = "products")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Product extends BaseEntity { // BaseEntity.java:53 @Version
    @Column(name = "stock_quantity", nullable = false) private int stockQuantity; // OrderService.java:98 mutates
}

// application.yml:68-78 — JCache + provider pin
hibernate.cache.use_second_level_cache: true          // 69
hibernate.cache.region.factory_class: jcache          // 71
javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider // 77 — disambiguates Redisson
jakarta.persistence.sharedCache.mode: ENABLE_SELECTIVE // 78 — only @Cacheable cached

// ehcache.xml — region (conceptual)
<cache alias="com.company.orderapi.domain.Product">
  <heap unit="entries">1000</heap><expiry><ttl unit="minutes">10</ttl></expiry>
</cache>
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Verify L2 config is active (default profile)
grep -A8 "use_second_level_cache\|factory_class\|EhcacheCachingProvider\|sharedCache" src/main/resources/application.yml

# Check which entities are cached (only Product should have @Cacheable)
grep -rn "@Cacheable\|@Cache" src/main/java --include="*.java"
# Expect: Product.java:33 @Cacheable, Product.java:34 @Cache(READ_WRITE)

# Show L2 hit/miss statistics (requires PR #15 logging)
./mvnw test -Dtest=OrderServiceTest 2>&1 | grep -E "second level cache|Statistics\["
# Expect on second findById(42) in new transaction: hits 1, misses 1, puts 1

# Detailed L2 per-region stats
./mvnw test -Dtest=ProductRepositoryTest -Dorg.hibernate.stat=DEBUG 2>&1 | grep -i "cache"

# Prove SQL not emitted on L2 hit (SQL logging from PR #15)
./mvnw test -Dtest=ProductRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep "from products where"
# First get: 1 line; second get in new tx: 0 lines (served from L2)

# Verify provider pin (no ambiguity with Redisson)
grep -n "EhcacheCachingProvider" src/main/resources/application.yml
# Expect: application.yml:77

# Check ehcache.xml region (if file exists)
cat src/main/resources/ehcache.xml 2>/dev/null | grep -A3 "Product" || echo "ehcache.xml uses programmatic/default config"

# Application-level DTO cache (PR #28) — separate layer
grep -n "CACHE_NAME\|@Cacheable\|@CacheEvict" src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java
# Expect: CACHE_NAME="products" ProductCatalogueService.java:37, @Cacheable:50, @CacheEvict:60,97

# Smoke: place order mutates stock → L2 invalidated → next get reflects new stock
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.stat=DEBUG 2>&1 | grep -E "stock_quantity|version|second level"

# Prod Spring cache is Redis (complements L2)
grep -A3 "cache:" src/main/resources/application-prod.yml | head -n 15
# Expect: type: redis, time-to-live: 10m, key-prefix: orderapi:
```

```java
// Programmatic hit/miss assertion inside a test
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

@Autowired SessionFactory sf;
Statistics s = sf.getStatistics(); s.setStatisticsEnabled(true); s.clear();

productRepository.findById(42L); // miss → put
productRepository.findById(42L); // L1 hit (same Session) — not L2 proof
// For L2 proof, use two transactions:
s.clear();
@Transactional(propagation = Propagation.REQUIRES_NEW) void tx1() { productRepository.findById(42L); } // miss
@Transactional(propagation = Propagation.REQUIRES_NEW) void tx2() { productRepository.findById(42L); } // L2 hit
assertThat(s.getSecondLevelCacheHitCount()).isEqualTo(1);
assertThat(s.getSecondLevelCacheMissCount()).isEqualTo(1);
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| L2 for `Product` only, selective | `ENABLE_SELECTIVE` (`application.yml:78`) + `Product.java:33` `@Cacheable` | `ALL` (cache every entity) | `Product` read-heavy/reference; `Order`/`OrderItem` write-heavy/transactional — caching them wastes heap, chuncs GC, risks stale order view | Must remember to annotate each new reference entity |
| `CacheConcurrencyStrategy.READ_WRITE` | `Product.java:34` `READ_WRITE` | `READ_ONLY` / `NONSTRICT_READ_WRITE` | Stock is mutable (`OrderService.java:98`); `READ_ONLY` throws, `NONSTRICT` allows stale window → oversell risk; `READ_WRITE` soft-lock + `@Version` safe | Soft-lock brief blocking of concurrent readers during commit |
| Ehcache via JCache | `jcache` + `EhcacheCachingProvider` (`application.yml:71,77`) | `Redis` JCache / `Hazelcast` / Hibernate `EhcacheRegionFactory` directly | JCache is portable; pin solves Redisson ambiguity (PR #30); Ehcache in-process is fastest for single pod | Per-pod coherence only — cross-pod stale until TTL/eviction; Spring cache Redis (`ProductCatalogueService.java:50`, `application-prod.yml:29`) covers cross-pod |
| Heap 1000, TTL 10m | `ehcache.xml` region | Larger heap / no TTL | Bounds memory; TTL catches missed invalidation; 1000 covers hot catalog without GC pressure | Eviction of warm entry causes one DB round-trip |
| Application DTO cache + L2 | Both (PR #28 + PR #16) | L2 only or Spring cache only | L2 caches disassembled entity state for repos; Spring cache caches mapped `ProductResponse` DTO (`ProductCatalogueService.java:54`) for API — two hit opportunities, correct layers | Two invalidation calls (`OrderService.java:101` `catalogue.evict()`, L2 auto on `UPDATE`) — both cheap |
| Statistics via PR #15 | `generate_statistics: true` + `stat: DEBUG` | Ad-hoc JMX | Same channel proves N+1 and L2 value in one log grep | NanoTime overhead — disabled in prod (`application-prod.yml:18` `false`) |

---

## 7. How to verify

```bash
# Config proof: L2 enabled + selective + provider pinned
grep -n "use_second_level_cache\|factory_class\|EhcacheCachingProvider\|sharedCache.mode" src/main/resources/application.yml
# Expect: 69 true, 71 jcache, 77 EhcacheCachingProvider, 78 ENABLE_SELECTIVE

# Entity opt-in proof: only Product cached
grep -rn "@Cacheable" src/main/java --include="*.java"
# Expect: Product.java:33 only

# Strategy proof: Product uses READ_WRITE
grep -A1 "@Cache" src/main/java/com/company/orderapi/domain/Product.java
# Expect: @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)

# SessionFactory builds (L2 provider found)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "secondLevelCache\|Ehcache\|JCache" | head

# Hit/miss proof (requires two transactions — see §5 programmatic)
./mvnw test -Dtest=ProductRepositoryTest -Dorg.hibernate.stat=DEBUG 2>&1 | grep -E "second level cache hits|misses|puts" | head

# SQL proof: second get in new tx emits no SELECT (when L2 hit)
./mvnw test -Dtest=ProductRepositoryTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -c "select.*from products where"
# With L2, count is 1 for two gets across two txs; without L2, 2

# Eviction proof: stock update → L2 invalidated → next read goes to DB (one SQL)
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.stat=DEBUG 2>&1 | grep -E "update products|second level" | head

# Profile: prod Spring cache is Redis but L2 still Ehcache in-process (complementary)
grep -n "cache:" src/main/resources/application-prod.yml | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Annotate new reference entities (lookup tables, codes) with `@Cacheable` + `@Cache(READ_WRITE)` (`Product.java:33-34`) if mutable, `READ_ONLY` if truly immutable; leave transactional entities (`Order.java:42`, `OrderItem`, `Payment`) uncached. On any stock/catalog mutation outside `ProductCatalogueService`, call `catalogue.evict(id)` (`ProductCatalogueService.java:97`, `OrderService.java:101`) — self-invocation misses proxy, always call via injected bean. Version every `READ_WRITE` entity (`BaseEntity.java:53`).
- **Operate:** Monitor `org.hibernate.stat` `hits/misses` (`application.yml:177`) and `ProductCatalogueService` cache metrics (`cache.gets`, `cache.evictions`). Low hit ratio → TTL too short or churn; high GC → heap too large. Remember L2 is per-pod (Ehcache) — after rolling deploy, each new pod cold-starts L2; warm with read traffic or switch JCache provider to Redis for cross-pod coherence. Invalidate explicitly after manual `psql UPDATE products` or L2 will serve stale until TTL.
- **Interview:** "PR #16: `application.yml:69-78` (`use_second_level_cache: true`, `factory_class: jcache`, `provider: EhcacheCachingProvider:77` disambiguates Redisson, `ENABLE_SELECTIVE:78`) + `Product.java:33-34` (`@Cacheable`, `READ_WRITE` with `@Version:53` for soft locks). `READ_WRITE` chosen because `stockQuantity` (`OrderService.java:98`) mutates — `READ_ONLY` would throw, `NONSTRICT` risks stale oversell. Ehcache in-process heap 1000/TTL 10m; cross-pod coherence via Spring `products` Redis cache (`ProductCatalogueService.java:37,50`, `application-prod.yml:29`). Verified by `stat: DEBUG` (`application.yml:177`) `second level cache hits`. Next: DTO projections (PR #17)."

---

## 9. Interview lens — Q&A

**Q1: What are L1, L2, query cache and which does PR #16 enable?**
A: L1 = per-Session persistence context (always on). L2 = per-SessionFactory shared cache of disassembled state — PR #16 enables it (`application.yml:69`) selectively (`78` `ENABLE_SELECTIVE`) for `Product.java:33`. Query cache (`query→ids`) is not enabled — coarse invalidation, low hit for OLTP (§2.1).

**Q2: Why `READ_WRITE` and not `READ_ONLY` or `NONSTRICT_READ_WRITE`?**
A: `Product.stockQuantity` mutates via `OrderService.java:98`. `READ_ONLY` forbids updates, `NONSTRICT` allows a stale window where reader sees old stock → oversell. `READ_WRITE` (`Product.java:34`) soft-locks + uses `@Version` (`BaseEntity.java:53`) so committed stock is visible after tx (§2.4).

**Q3: What does `ENABLE_SELECTIVE` do?**
A: Only `@Cacheable` entities participate (`application.yml:78`). `Product.java:33` cached (reference/hot), `Order.java:42` not cached (write-heavy). `ALL` would cache everything wastefully (§2.3).

**Q4: Why pin `EhcacheCachingProvider`?**
A: Both Ehcache and Redisson (PR #30) provide JCache (`javax.cache.spi.CachingProvider`). Without `javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider` (`application.yml:77`) Hibernate's `JCacheRegionFactory` provider lookup is ambiguous and `SessionFactory` fails (§2.5).

**Q5: How is L2 proven — what stats and what log?**
A: PR #15's `generate_statistics: true` (`56`) + `org.hibernate.stat: DEBUG` (`177`) emits `second level cache hits 1, misses 1, puts 1`. First `findById(42)` miss+put, second in new tx hit with no SQL (§2.7). Programmatic via `SessionFactory.getStatistics()` (§5).

**Q6: Ehcache vs Redis — when to use which?**
A: L2 Ehcache is in-process per pod — fastest, no network, but no cross-pod coherence. Spring `@Cacheable("products")` (`ProductCatalogueService.java:50`) via Redis (`application-prod.yml:29`) is cross-pod, caches the DTO. Use both: L2 for entity state, Spring cache for API DTO (§2.6/§2.8).

---

## 10. Honest limits & next step → PR #17

L2 is per-pod in-process (no coherence across pods/Replicas); manual `psql UPDATE` bypasses invalidation until TTL; oversizing heap causes GC; write-heavy entities must not be cached. PR #17 tackles the complementary read optimization: instead of loading full entities (even cached), DTO projections (`CustomerRepository.java:40-53`, `CustomerOrderTotal.java:15`, `OrderRepository.java:86`) select only needed columns, bypassing the persistence context entirely — the maximally minimal read.

See [`17-dto-projections.md`](./17-dto-projections.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Scenario | First choice | File:line | Why |
|---|---|---|---|
| Reference, mutable, hot (Product) | L2 `READ_WRITE` + `@Version` | `Product.java:33-34` + `BaseEntity.java:53` | Safe mutation, soft locks, no oversell |
| Reference, immutable (Category code) | `READ_ONLY` | `Category.java` (if added) | No soft locks, fastest, throws if mutation attempted |
| Transactional (Order, Payment) | No L2 (`ENABLE_SELECTIVE` leaves uncached) | `Order.java:42` no `@Cacheable` | Write-heavy, stale risk, heap waste |
| API DTO read (getProduct) | Spring `@Cacheable("products")` | `ProductCatalogueService.java:50` | Caches mapped DTO, cross-pod via Redis prod |
| Hot query `findCustomerOrderTotals` | DTO projection, not cache | `OrderRepository.java:86` + `CustomerOrderTotal.java:15` | Aggregates not cacheable as entities |
| Multi-pod stale concern | Spring cache Redis, not L2 Ehcache | `application-prod.yml:29` | L2 per-pod only |
