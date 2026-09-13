# 28. Caching with Redis (PR #28)

> PR #28 — `ProductCatalogueService.java:35` cache-aside via Spring `Cache` abstraction; `RedisConfig.java:20 @EnableCaching`, Lettuce (`spring-boot-starter-data-redis`) + `spring.cache.type` switch: default `simple` (ConcurrentHashMap) for tests vs `redis` with `10m TTL` in dev/prod, key `products::<id>`, DTO `ProductResponse` not entity `Product`. Stack: Java 21, Spring Boot 3.4.1, Spring Cache (`@Cacheable/@CacheEvict`), Lettuce, Redis, `ProductCatalogueService.java:35` + `RedisConfig:20` + `RedisConfig/RedisCacheMetrics` + `application.yml:80-94`. See `README.md:1772` roadmap `| 28 | Caching with Redis |`.

---

## 1. Purpose — what shipped

PR #28 accelerates the hot read `ProductController.get:48` (`GET /api/v1/products/{id}`) without weakening stock consistency (`OrderService.placeOrder:91` stock-- still authoritative). The catalogue read/write path is cached as `ProductResponse` view models (immutable, session-free). Shipped: `RedisConfig.java:20` (`@Configuration @EnableCaching`) toggles the `CacheManager` by `spring.cache.type:84-85 simple` (tests, zero-infra) vs `redis:16 real store dev/prod` (`spring.data.redis.host 91 localhost/port 93` defaults `docker-compose redis`), `ProductCatalogueService.java:35` `CACHE_NAME "products":37` with `@Cacheable(cacheNames=CACHE_NAME, key="#id"):50` `get:54` (`findById 55→toProductResponse 56`), write evictions `@CacheEvict(allEntries=true):60,71,85` on `create:60`/`update:71`/`delete:85` flushing the whole `"products"` cache, and `@CacheEvict(key="#productId"):97` `evict:97` empty-body single-entry eviction for `OrderService:101 catalogue.evict(line.productId())` stock-mutation callers (locking + resilience services outside the catalogue also call `evict`). Redis is activated per profile — `DEFAULT simple` no Redis needed by integration contexts, `dev/prod redis host 91 TTL 10m`. Complementaries: `@Timed(product.get):52` for hit vs miss latency, `RedisCacheMetrics` binding to Micrometer, `application.yml:84-94` cache ports.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Every `GET /api/v1/products/{id}:48` hit `ProductRepository.findById:55` → `SELECT * FROM products WHERE id=?` + `OrderMapper.toProductResponse` `56` per request. Under `ProductController.list:42` + burst traffic, product pages thrash the DB for the same popular `id=42` and `Hikari maximum-pool-size:10` (`application.yml:34`) queues. `ProductRepository.findAll:44` `Pageable` lists are still DB-direct (intentionally uncached, high cardinality). Stock mutation (`OrderService.placeOrder:98 stock--`) has no cache invalidation — a stale cache would serve old `stockQuantity` indefinitely.

**After:** First `GET 42 → miss → findById 55 + toProductResponse 56 → stored products::42 TTL 10m`; second `GET 42 → hit → DTO from Redis (no SQL)` verified by `org.hibernate.SQL` (`application.yml:175 DEBUG`) absence and `cache hit` metric. Any catalogue write `create:60`/`update:71`/`delete:85` → `allEntries=true` evict-all flushes stale `"products"` (simple correct); any stock-write `OrderService.evict:101` → `evict:97 key="#productId"` drops just that entry so next `get:54` re-reads fresh `stockQuantity`. Tests keep `spring.cache.type=simple:84` — no Redis daemon needed.

### Theory — Spring Cache abstraction, Redis cache-aside, and TTL from first principles (100+ lines)

#### 2.1 Spring Cache abstraction — `CacheManager` as the pluggable map

Spring's cache abstraction (`spring-context Cache, CacheManager`) exposes two annotations and one key concept:

- `CacheManager` implemented either by `SimpleCacheManager` (`ConcurrentHashMap` per cache name, created by `RedisConfig 20 @EnableCaching` fallback when `spring.cache.type:84 simple`) or `RedisCacheManager` (Lettuce `RedisConnectionFactory` + `RedisCacheConfiguration` `serializeValuesWith(GenericJackson2JsonRedisSerializer)` + default TTL).
- `@Cacheable(cacheNames="products", key="#id"):50` on `get:54` is a proxy advice: before executing the body, check `CacheManager.getCache("products").get(id)` → hit? return cached `ProductResponse`; miss? execute body `findById 55` → put result `cache.put(id, response)` with the manager's TTL.
- `@CacheEvict(cacheNames="products", allEntries=true):60` / `(key="#productId"):97` removes entries via `Cache.evict` / `Cache.clear`.

`@EnableCaching:20` imports `ProxyCachingConfiguration` which registers a `BeanPostProcessor` that wraps every `@Cacheable/@CacheEvict` bean (`ProductCatalogueService:35`) with an `CacheInterceptor` proxy — same layering as `@Transactional` (`OrderService:76`), but lower `Ordered` precedence so transaction inside the cache advice (read-through before tx is safe because `get:54` is `readOnly:51` and idempotent).

#### 2.2 Cache-aside (lazy loading) — the read path economy

```java
// ProductCatalogueService.java:50-58
@Cacheable(cacheNames=CACHE_NAME, key="#id") @Transactional(readOnly=true) @Timed("product.get")
public ProductResponse get(Long id){ return products.findById(id).map(OrderMapper::toProductResponse).orElseThrow(...); }
```

Cache-aside defers population to the first read: `miss → load from source of truth (Postgres via ProductRepository 55) → serialize + PUT into distributed cache → return`. Subsequent `GET` with the same `id` returns from cache `O(RTT Redis)` instead of `O(DB index lookup + mapping 56)`; under load many clients share the single Redis value. Alternatives: cache-through (write-through would populate cache on every `saveAndFlush` automatically) but the cache key `products::<id>` is only read-heavy — cache-aside avoids warming rarely-read products; write-behind batches but risks loss on crash — wrong for catalog stock. `get` caches the immutable `ProductResponse` (`OrderResponse 16` pattern `OrderMapper.toProductResponse`) not the `Product` entity `Product.java` — entity caching would leak Hibernate `Session` identity, lazy collections `Product.category`, and `version 53` staleness into Redis (serializing a detached proxy is wrong; cache the view the API returns).

#### 2.3 Key design — `products::<id>` and `allEntries` vs single `key`

- `key="#id":50` → SpEL using the first parameter `Long id`. Redis entry `products::42 → {"id":42, "name":"Widget", "price":9.99, "stockQuantity":...} (JSON)`. `::` is `RedisCacheManager` convention `<cacheName>::<key>`. Composite keys (`key="#id + ':' + #locale"`) would be needed for locale-scoped products (not present here).
- `allEntries=true:60,71,85` on catalogue writes `create/update/delete` evicts the *whole* `"products"` cache (many product keys share the name `"products"`). Correct but coarse: writes are rare vs reads (`product.get` 52), clearing 1000 entries is cheaper than tracking which were dirty. The alternative per-key `@CacheEvict(key="#id")` on `update:71` would leave stale neighbors (e.g. a category list cache not covered here) — `allEntries` aligns with the `findAll:44 Pageable` bypass pattern where `list` is uncached (no invalidation burden).
- `evict(Long productId):97-100` empty-body `@CacheEvict(key="#productId")` is a targeted single-entry drop for *stock mutations outside the catalogue* (`OrderService:101`, `DistributedLockService`, resilience stock services). Body is empty — annotation is the effect. Must be invoked *through the bean proxy* `catalogue.evict(id)` not `this.evict` (self-invocation bypasses proxy — classic `Spring AOP` pitfall).

#### 2.4 TTL and Redis connection — what `10m` and `Lettuce` buy

`spring.cache.type:84 simple` in `application.yml:84` means tests use `ConcurrentHashMap` (`SimpleCacheManager`) — isolated, no daemon, deterministic. `dev`/`prod` profile (`application-redis.yml` / `application-dev.yml` not shown but per `RedisConfig:11-17` comment) sets `spring.cache.type: redis` + `spring.data.redis.host 91/port 93` (`docker-compose redis:6379`) + `RedisCacheConfiguration entryTtl(Duration.ofMinutes(10))` + `keyPrefix` + `GenericJackson2JsonRedisSerializer` for values (`ProductResponse` → `{"id":42,...}`). `TTL 10m` bounds staleness: if an out-of-band `UPDATE products SET stockQuantity=... WHERE id=...` bypasses `evict:97`, the entry self-expires in `10m` rather than forever. Shorter `TTL 1m` → more misses; longer `TTL 30m` → stock spike visible later without `evict`. `evict:97` still explicitly removes on `order placement:101` so stock is immediate; TTL is defense-in-depth.

`spring-boot-starter-data-redis:163-166` uses `Lettuce` (non-blocking `RedisClient` NIO) not `Jedis` — single `LettuceConnectionFactory` multiplexes the `Hikari 34 HikariPoolSize 10` workload to Redis on many product hits. Alternative `Redisson` (`RedissonConfig.java` PR #30) is a *separate* client for distributed locks `DistributedLockService` on the same Redis `host 91` — plain `RedissonConfig` is kept not `spring-boot-starter` to avoid connection-factory clash (`RedisConfig:18-21` comment).

#### 2.5 Why `ProductResponse` not `Product` in the cache — DTO vs entity

`Product` entity `Product.java` has `@Version 53` bump per `setStockQuantity:98` flush, lazy `ManyToOne Category`, `BaseEntity` audit `createdAt/updatedAt`, and a `Hibernate` `Session` identity — all unsafe to serialize into `products::42` (`LazyInitializationException` or `PersistentSet not serializable`). `OrderMapper.toProductResponse(Product):` `snapshot = new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStockQuantity(), product.getDescription())` is the immutable tuple the API and Swagger (`OpenApiConfig.java`) document — exactly what `ProductController.get:48` returns. Caching this DTO keeps Redis off JPA.

#### 2.6 `get` inside the HTTP layer — `@Transactional(readOnly=true) 51` nuance

`get:54` is both cached and transactional `readOnly 51`: on `cache hit` the method body never runs, so no `Session` or `SELECT 55` is needed — the Proxy short-circuits before tx. On `miss`, the `findById 55` participates in the read-only tx (Hibernate `FlushMode.MANUAL` skip flush, no `version` bump). `ProductController.get:48` calls `catalogue.get:49` via bean injection (proxy) — transactional. Alternative `products.findById` over non-cache `ProductRepository 44 findAll:44 Pageable` (list) is intentional uncached: paging over `product` offsets (`LIMIT 20 OFFSET ...`) is not a stable key and cache eviction on list updates is combinatorial.

#### 2.7 Invalidation correctness — catalogue writes vs stock mutations

```
write path                annotation                     effect
────────────────────────  ─────────────────────────────  ───────────────────────────
catalogue.create:60       @CacheEvict(allEntries=true)   clear products::1..products::N (safe; creates new id)
catalogue.update:71       @CacheEvict(allEntries=true)   clear all (update may change stockQuantity alongside name/price)
catalogue.delete:85       @CacheEvict(allEntries=true)   clear all (deletion must not be served from cache)
orderService.placeOrder:101 catalogue.evict(id):97  @CacheEvict(key=id) single entry drop (stock-- 98)
locking stock decrement          same evict:97          caller evict after external stock update
```

Stock mutations are frequent and touch only `id` — per-key eviction (`97`) minimizes cache churn. Bulk-clear on catalogue writes is acceptable because catalogue writes are administrative. Without `evict:97`, `placeOrder:91` stock change would not be visible for up to `TTL 10m`.

#### 2.8 RedisCacheMetrics and observability — confirming the win

`RedisCacheMetrics` (if wired in `config/RedisCacheMetrics.java`) binds `cache.hit/cache.miss/cache.put/cache.evict` counters to `Micrometer` (`/actuator/prometheus` PR #32 tags `cache=products`). ` @Timed(product.get):52` histogram splits into hit (< 5ms Redis) vs miss (~20ms `SELECT`+map) — in prod `product.get` p50 should short-circuit on cache vs p95 still flat. Validate by `curl /actuator/metrics/cache.gets?tag=cache:products` vs `org.hibernate.SQL 175 DEBUG`.

#### 2.9 Failure mode — when Redis is down or cache poisoned

`RedisCacheManager` when `Redis` is unreachable throws `RedisConnectionFailureException` during `cache.get` inside the proxy — by default propagates outside `get:54` as a failure (strict). Alternative is `CacheErrorHandler` / `spring.cache.redis.cache-null-values=false` + `RedisCacheConfiguration disableCachingNullValues()` so null unrecognized product `Unknown product 57` is not cached forever (negative caching). Null-value avoidance is correct here: a missing `id` (`ProductResponse` absent) is not memoized so a concurrent `create:60` becomes visible as soon as the key appears.

#### 2.10 Alternatives vs lattice — global `spring.cache` vs levels

- Default in-memory `spring.cache.type:84 simple` vs `redis 16`: simple local cache lives per pod (no sharing, vanishes on restart, tiny `TTL` weirdness due to JVM heap), `redis` shareable across pods via `docker-compose redis 91:93` needed for multi-instance. Tests use `simple` to stay daemon-less.
- `Hibernate second-level cache` (`application.yml:66-78 JCache Ehcache`) `Product.java:33 @Cacheable` covers entity-level `SessionFactory` cache (PR #16) distinct from this `RedisConfig:20` `Spring Cache` app-level view cache — they stack: L1 `Session` + L2 `Ehcache` + application Redis.
- `CDN/edge` `Cache-Control: max-age=60` header on `GET /api/v1/products/{id}` could complement — but cache ownership would be edge CDN not application, not covered here; application `Redis` is still authoritative invalidation via `evict:97`.

> Interview anchor: "PR #28: `RedisConfig.java:20 @EnableCaching` (`spring-boot-starter-data-redis 163`, `Lettuce`, `spring.cache.type 84 simple` tests vs `redis 16` `host 91/port 93` `10m TTL` per profile) + `ProductCatalogueService.java:35 CACHE_NAME products 37`. Read `@Cacheable(cacheNames=CACHE_NAME,key=\"#id\") 50 @Transactional(readOnly=true) 51 @Timed 52 get:54 findById 55 toProductResponse 56` cache-aside → `Dto ProductResponse` in `products::id`. Writes `@CacheEvict allEntries=true 60/71/85 create/update/delete` clear all; `evict:97 @CacheEvict key=\"#productId\"` empty-body single-entry targeted by `OrderService 101 stock--`. Must call via bean proxy (not this). Null not cached, not RS L2 `Product:33`. Prod `RedisCacheMetrics` + `org.hibernate.SQL` prove hit."

#### 2.11 Redis RESP and Lettuce event-loop — how a GET hits Redis

`Lettuce` uses Netty `EventLoop` non-blocking: `RedisCacheManager.getCache("products").get("42")` encodes `GET products::42` as RESP bulk string over TCP `host 91:93`. Command queued on the shared channel multiplexed from `Hikari 34 HikariPoolSize 10` callers so one TCP connection serves many concurrent `ProductController.get:48` threads (distinct from `RedissonConfig` PR #30 pool). Latency is `RTT ~1ms` vs `DB index lookup ~5-15ms`. Failure propagates as `RedisConnectionFailureException` - by default evicts not suppresses, forcing fallback to DB miss rather than stale hit.

#### 2.12 Serialization — GenericJackson2JsonRedisSerializer and ProductResponse shape

Value serializer `GenericJackson2JsonRedisSerializer` configured in `RedisCacheConfiguration` writes `ProductResponse` JSON `{"id":42,"name":"Widget","price":9.99,"stockQuantity":87}` with type metadata disabled (pure DTO). Entity `Product.java` with `HibernateProxy` and `PersistentCollection` would require `Hibernate5Module` and still leak `Session` - `OrderMapper:56` snapshot avoids this. Changing DTO component name requires cache flush or versioned key (`products::v2::42`) to avoid stale schema.

#### 2.13 Eviction and expiry interplay — LRU vs TTL vs explicit drop

Redis `maxmemory-policy` (default `noeviction` per `docker-compose redis`) + `TTL 10m` gives boundedness: key `products::42` expires after `10m` regardless. Explicit `@CacheEvict 60/71/85/97` clears before `TTL` on write. When `Redis` restarts `flushall` occurs, cache cold - spike of `SELECT 55` misses auto-repopulates. Alternative `Caffeine` local `expireAfterWrite 10m` would diverge per pod; `Redis` share fixes divergence.


#### 2.14 Cold-start and stampede — sync=true and cache warming

Concurrent `GET /products/42` cold burst all miss → 100 `SELECT 55` herd. `@Cacheable(sync=true):50` would serialize misses per key via `Cache.get(key, Callable)` lock, letting one `SELECT` populate while others block on the cache promise. Pre-warming popular keys (`products:1..100`) on startup via `CommandLineRunner` querying `ProductRepository.findAll(PageRequest.of(0,100))` and priming `cache.put` mitigates first-hour miss storm after deploy. Invalid.
+ TTL per-cache override via `RedisCacheManagerBuilderCustomizer` (e.g. `products 10m` vs `widgets 1m`).
+ Key hashing with `StringRedisSerializer` preserves `products::42` readability for `redis-cli KEYS`.
+ `spring.cache.redis.cache-null-values=false` prevents absent `Unknown product 57` negative cache blocking later `create:60` visibility.
+ Compression via `Gzip` wrapper not needed here (ProductResponse < 1KB) but would reduce Redis memory for large catalog pages.

---

## 3. Solution — ASCII

```
Request  GET /api/v1/products/42   (anonymous API-key Jwt handled by SecurityConfig:55)
      │
      ▼  DispatcherServlet → ProductController.get:48  return catalogue.get(42):49
                                 via Spring bean proxy → CacheInterceptor (RedisConfig:20 @EnableCaching)
                                        │
                                        ├─ cache = RedisCacheManager.getCache("products") (SimpleCacheManager when spring.cache.type 84 simple)
                                        │   RedisCacheConfig TTL 10m + keyPrefix + GenericJackson2JsonRedisSerializer(ProductResponse)
                                        │   Lettuce RedisConnectionFactory host 91 localhost port 93 6379
                                        │
                                        ├─ cache.get("42") ?
                                        │    ├─ HIT → deserialize JSON ProductResponse → return (no repo call, no @Transactional:51 tx needed)
                                        │    └─ MISS → enter target ProductCatalogueService.get:54
                                        │              @Transactional(readOnly=true):51 (Hibernate FlushMode MANUAL, no flush)
                                        │              @Timed("product.get"):52 → Micrometer product.get histogram Hit: <5ms vs Miss ~20ms
                                        │              products.findById(42):55 → SELECT ... FROM products WHERE id=42 (org.hibernate.SQL 175)
                                        │              OrderMapper.toProductResponse:56 snapshots Product(name,price,stockQuantity,description) → immutable ProductResponse record
                                        │              cache.put("42", response, TTL 10m) → Redis products::42 value
                                        │              return response
                                        └─ ProductController:48 wraps response (200)
      │
      ▼  HTTP 200 JSON ProductResponse  {id:42, name:"Widget", price:9.99, stockQuantity:87, ...}  (origin: cache hit or DB miss)

Write  POST /api/v1/products {name,price,stock,description} → ProductController.create:53 → catalogue.create:60 @CacheEvict(allEntries=true):60 → saveAndFlush:64/67 → flush cache products::* → response 201 Location /api/v1/products/{id}:57
       PUT /api/v1/products/{id} → catalogue.update:71 @CacheEvict(allEntries=true) → findById:75 flush 81 → put fresh on next GET 49
       DELETE /{id} → catalogue.delete:85 @CacheEvict(allEntries=true) → deleteById:88 → next GET 42 would be miss

Stock outside catalogue  orderService.placeOrder:91 (stock-- 98, OrderItem snapshot 103)
         → catalogue.evict(productId):101 → @CacheEvict(cacheNames=products, key="#productId"):97  // empty body 98-100 - annotation is the effect
           cache.evict(42) single entry drop → next GET 42 miss fresh stockQuantity (defense-in-depth: TTL 10m expiry even if evict missed)

Separation  Hibernate L2 Cache (application.yml:66-78 JCache Ehcache, Product.java:33 @Cacheable) sits below the SessionFactory
            Spring Cache ProductResponse Redis (this PR) sits above it — entity L2 vs view Redis, independent

Profiles    DEFAULT (tests): spring.cache.type:84 simple → SimpleCacheManager ConcurrentHashMap per cache, no Redis daemon, deterministic
            dev/prod: spring.cache.type: redis → RedisCacheManager Lettuce host 91 port 93 + TTL 10m + keyPrefix + serializer (docker-compose redis)
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/config/RedisConfig.java` | `19-22` | Enable abstraction | `@Configuration @EnableCaching:20` — imports `ProxyCachingConfiguration` so `@Cacheable/@CacheEvict` on `ProductCatalogueService:35` are advised |
| `src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java` | `35-37` | Catalogue service | `@Service 35 CACHE_NAME "products" 37` comments DTO vs entity `16-33` |
| `ProductCatalogueService.java` | `50-58` | Read cache-aside | `@Cacheable(key="#id") 50 @Transactional(readOnly=true) 51 @Timed 52 get:54 findById 55 toProductResponse 56 orElseThrow 57` — cached `Dto` not `Entity` |
| `ProductCatalogueService.java` | `60-69` | `create` invalidate | `@CacheEvict(allEntries=true) 60 @Transactional 61 saveAndFlush 64 setDescription 66 flush 67 toProductResponse 68` |
| `ProductCatalogueService.java` | `71-84` | `update` invalidate | `@CacheEvict(allEntries=true) 71 update 73-83 findById 75 sets 77-80 flush 81` |
| `ProductCatalogueService.java` | `85-93` | `delete` invalidate | `@CacheEvict(allEntries=true) 85 deleteById 88` |
| `ProductCatalogueService.java` | `97-100` | `evict(id)` single-drop | `@CacheEvict(key="#productId") 97` empty body `99` — annotation-driven, must invoke via proxy `catalogue.evict:101` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `101` | Stock-side invalidation | `catalogue.evict(line.productId()):101` after `setStockQuantity 98` stock change visible next `get:50` |
| `src/main/java/com/company/orderapi/api/rest/controller/ProductController.java` | `30,48-72` | HTTP uses cache | `GET 48 catalogue.get 49` (cached), `list:42 findAll 44 uncached Pageable`, `POST 53-58 201 Location 57`, `PUT 62`, `DELETE 69` |
| `src/main/java/com/company/orderapi/config/RedisCacheMetrics.java` | — | Metrics | `cache.hit/miss/put/evict` + `product.get 52` p50/p95 for hit vs miss delta |
| `src/main/resources/application.yml` | `80-94` | Cache wiring | `cache.type:84 simple` tests zero-infra vs comment `Redis` dev/prod 86-89, `data.redis.host 91 port 93` Lettuce |
| `pom.xml` | `163-166` | Redis dep | `spring-boot-starter-data-redis 165` Lettuce + `Redisson 191` isolated via `RedissonConfig:20` |
| `docker-compose.yml` | — | Infra | `redis:6379` backing `spring.data.redis 91-93`; PR #30 `Redisson` shares same host/port |

```java
// ProductCatalogueService.java:50-58,97-100 — cache-aside + targeted evict
@Cacheable(cacheNames=CACHE_NAME, key="#id") @Transactional(readOnly=true) @Timed(value="product.get", percentiles=0.95)
public ProductResponse get(Long id){ return products.findById(id).map(OrderMapper::toProductResponse).orElseThrow(()->new IllegalArgumentException("Unknown product "+id)); }
@CacheEvict(cacheNames=CACHE_NAME, allEntries=true) @Transactional public ProductResponse create(...){ Product p=products.saveAndFlush(new Product(name,price,stockQuantity)); products.flush(); return OrderMapper.toProductResponse(p);}
@CacheEvict(cacheNames=CACHE_NAME, key="#productId") public void evict(Long productId){ /* annotation is the effect */ }
// RedisConfig.java:20
@Configuration @EnableCaching public class RedisConfig {}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Confirm cache abstraction wired
grep -n "@EnableCaching\|spring.cache.type\|CACHE_NAME\|@Cacheable\|@CacheEvict" \
  src/main/java/com/company/orderapi/config/RedisConfig.java src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java src/main/resources/application.yml | head -n 20

# Tests use Simple in-memory cache (no Redis)
grep -n "spring.cache.type.*simple" src/main/resources/application.yml  # 84 simple
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.profiles.active=test 2>&1 | grep -i "cache\|Redis\|Cacheable" | head
# Redis absent in test profile — expected

# Spin local Redis (docker-compose redis matches application.yml:91-93 defaults)
docker compose up -d redis && redis-cli ping  # PONG
# Or dev profile brings redis cache type
SPRING_CACHE_TYPE=redis SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev &
# Verify connection
curl -s http://localhost:8080/actuator/health | jq '.components.redis // .components.db'

# Cache behaviour live
# Miss → SELECT
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" | jq '{id,stockQuantity}'
# Observe Hibernate SQL when miss (application.yml:175 DEBUG): one SELECT
# Hit → no SQL
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" | jq '{id,stockQuantity}'
# Second GET should show no SELECT in logs (cache hit)

# Evict by catalogue writes (allEntries)
curl -s -X PUT http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"name":"Widget","price":9.99,"stockQuantity":100,"description":"x"}' -i | grep 200
# Next GET miss again
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" | jq '{stockQuantity}'  # 100 (fresh)
# Check logs: re-loaded SELECT present

# Evict by stock mutation outside catalogue (order placement)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '.orderNumber'
# Next product GET should reflect stock-- (catalogue.evict 97 inside placeOrder:101)
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" | jq '{stockQuantity}'
redis-cli KEYS "products::*"  # keys present (or empty after allEntries)
redis-cli GET "products::1" | jq .  # value if using redis
redis-cli TTL "products::1"  # 10m default

# Metrics hit vs miss split
curl -s http://localhost:8080/actuator/metrics/cache.gets 2>/dev/null | jq .  # cache=products tags
curl -s http://localhost:8080/actuator/prometheus | grep -E "product_get|caches"
```

```java
// Reuse cache-aside for any heavy read DTO
@Cacheable(cacheNames="widgets", key="#id") @Transactional(readOnly=true)
public WidgetResponse getWidget(Long id){ return widgets.findById(id).map(WidgetMapper::toResponse).orElseThrow(...); }
@CacheEvict(cacheNames="widgets", key="#widgetId") public void evictWidget(Long widgetId){}
// Stock writer elsewhere
widgets.setStock(...); widgetCatalogue.evictWidget(widgetId); // must be bean call, not this.evict
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| DTO `ProductResponse` in `Redis` not `Product` entity | `Productos:56 snapshot via OrderMapper.toProductResponse` | `@Cacheable Product` entity (unsafe) | Detached `Product` carries `Session` proxy + lazy `Category` + `version:53`; serialization leaks Hibernate internals; DTO is the API contract and immutable | Snapshot must be remapped on update |
| `RedisConfig @EnableCaching:20` + `spring.cache.type:84 simple` tests vs `redis` prod | `ConcurrentHashMap` unit vs `Lettuce Redis 91-93 10m TTL` real | Always `redis` or always `simple` | Tests no daemon (fast, deterministic); prod shared, `TTL 10m` bounds stale — multi-pod hits share the cache | Two `CacheManager` types must match serializer (`GenericJackson2JsonRedisSerializer`) |
| `@Cacheable(key="#id") 50` per `id` | SpEL id | `key="T(java.util.Objects).hash(#id)"` etc. | Natural equality by resource `id` matches `GET /{id}:48` URI; key string stable `products::42` | Composite `locale: id+locale` would need broader key |
| `@CacheEvict allEntries 60/71/85` on catalogue writes | Clear all `"products"` | `key="#id"` per entry | Writes rare vs reads; `findAll:44` list uncached so no list invalidation cost; blast clear simpler than per-key tracking for `create` | Full clear on `create`/`update` resets warm neighbours (~1000 entries) briefly |
| `@CacheEvict key="#productId" 97` `evict` for stock callers | Single-entry drop from `OrderService:101` | `allEntries` on every `stock-- 98` | Hot path `stock--` touches one `id` only; per-key minimizes churn while `placeOrder:91` is contended | Requires caller to know `productId` (holds it) |
| Empty-body evict + proxy requirement | `evict:97` body empty, annotation is effect | Imperative `cacheManager.evict()` call | Declarative, tier-aware (works regardless of `simple` vs `redis`) but must call via bean `catalogue.evict` not `this.evict` | Silent no-op on `this.evict` (self-invocation bypass) |
| `readOnly 51` on cached `get` | `readOnly` hint | Default `readOnly=false` | Skip `dirty-check` + `flush` on hit; on miss `SELECT 55` is naturally read-only | None |
| `null-value` not cached | Missing `42` throws `IllegalArgumentException Unknown:57` | `@Cacheable unless="#result==null"` semantics | Unknown id repeatedly could be brute-forced — not hot; not manufacturing a negative cache that blocks later `create:60` returning zero; consistent with repo `orElseThrow` | Concurrent unknown hammer would thrash miss path |

---

## 7. How to verify

```bash
# Enable and catalogue contract present
grep -n "@EnableCaching\|CACHE_NAME\|@Cacheable\|@CacheEvict\|readOnly\|Timed" \
  src/main/java/com/company/orderapi/config/RedisConfig.java src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java

# Dual store types
grep -n "spring.cache.type\|spring.data.redis" src/main/resources/application.yml  # 84 simple vs 91-93 redis
grep -n "spring-boot-starter-data-redis\|Lettuce\|RedissonConfig" pom.xml src/main/java/com/company/orderapi/config/RedissonConfig.java | head
grep -n "docker-compose.*redis\|REDIS_HOST\|REDIS_PORT" src/main/resources/application*.yml docker-compose.yml | head

# Proxy invalidation call site OrderService
grep -n "catalogue.evict\|ProductCatalogueService\|@CacheEvict" \
  src/main/java/com/company/orderapi/domain/service/OrderService.java src/main/java/com/company/orderapi/domain/service/ProductCatalogueService.java | head
# Expect OrderService:101 catalogue.evict + Catalogue:97 key="#productId"

# Hits vs misses visible (after running with X-API-KEY)
# SQL absent on hit
LOG=/tmp/orderapi.log; ./mvnw spring-boot:run > $LOG 2>&1 & sleep 15
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" > /dev/null
grep -c "select.*products.*where.*id.*1" $LOG  # 1 miss
curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" > /dev/null
grep -c "select.*products.*where.*id.*1" $LOG  # still 1 — hit no new SELECT

# With redis type
docker compose up -d redis && redis-cli flushall && curl -s http://localhost:8080/api/v1/products/1 -H "X-API-KEY: dev-api-key-orderapi" > /dev/null
redis-cli keys "products::*"  # products::1
redis-cli get "products::1" | jq '{id,name,stockQuantity}'
# TTL
redis-cli TTL "products::1"  # approx 600

# Metrics (actuator prometheus if enabled)
curl -s http://localhost:8080/actuator/prometheus | grep product_get
curl -s http://localhost:8080/actuator/metrics/cache.gets 2>/dev/null | jq '.measurements'
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New heavy-read resource → `RedisConfig:20 @EnableCaching` already present; `CACHE_NAME widgets` + `@Cacheable widgets, key="#id" 50` on `get:54` pattern returning an immutable response `WidgetResponse` via `WidgetMapper.toResponse` snapshot (not the entity). Mutating writers (stock, admin `PUT/DELETE`) call `widgets.evictWidget(widgetId):97` via injected `WidgetService` bean (never `this.evict` bypass). When writes are rare/admin only `allEntries=true 60` full clear simpler; when hot per-row `stock-- 98` targeted `evict:97`. Keep `spring.cache.type:84 simple` in tests deterministically; set `redis 91 host 93` + `10m TTL` per env profile `application-dev.yml/prod.yml`.
- **Operate:** Monitor `product.get 52 p95` vs `cache hit` ratio in `prometheus`: degrade hit ratio → check `allEntries` storm clearing cache or missing `evict:101` caller (stale stock). Redis failure (`Lettuce RedisConnectionFailure`) propagates as `500` unless `CacheErrorHandler` retries fallback to DB — decide per SLO. `RedisCacheMetrics` + `org.hibernate.SQL 175` absence on hit validates net DB load cut. On rolling deploys, `FlushDB` or `TTL 10m` expiry calms blue/green stampede.
- **Interview:** "PR #28: `RedisConfig.java:20 @EnableCaching` `CacheManager` `simple` tests `spring.cache.type 84` vs `redis Lettuce host 91 port 93 TTL 10m` dev/prod (Redisson isolated `RedissonConfig` PR #30). `ProductCatalogueService:35 products 37` cache-aside `@Cacheable cacheNames=products key=\"#id\" 50 @Transactional(readOnly=true) 51 @Timed 52 get:54 findById 55 toProductResponse 56` stores `ProductResponse` Dto not `Product` entity. Writes `@CacheEvict allEntries=true 60/71/85 create/update/delete`; stock path `OrderService:101 catalogue.evict(id)` single `evict:97 key=\"#productId\"` empty-body proxy required. `ProductController.get:48 → catalogue.get:49` hit → no `SELECT 55` `org.hibernate.SQL 175`."

---

## 9. Interview lens — Q&A

**Q1: Which `CacheManager` runs in tests vs prod and why two?**
A: `RedisConfig:20 @EnableCaching` + `application.yml:84 simple → SimpleCacheManager ConcurrentHashMap` no daemon fast/deterministic tests; `dev/prod spring.cache.type: redis 16 → RedisCacheManager Lettuce:163 docker-compose host 91 port 93 TTL 10m` shareable multi-pod (§2.3-2.4).

**Q2: Why cache `ProductResponse` not `Product` entity?**
A: `OrderMapper.toProductResponse:56` snapshot `ProductResponse` is immutable session-free view; `Product` entity carries `version 53`, lazy `Category`, `Session` identity unsafe to serialize to `products::42` (§2.5).

**Q3: `@Cacheable(key="#id") 50` — what does the SpEL express?**
A: Uses the `get:54` param `id` as the Redis key suffix → `products::42`; miss triggers `findById 55 → toProductResponse 56 → cache put`, hit short-circuits the method body and needs no `@Transactional:51` tx (§2.1-2.3).

**Q4: Why `allEntries=true 60/71/85` on `create/update/delete` vs `key="#productId" 97` on `evict`?**
A: Catalogue writes `60/71/85` are admin-rare `→` clear all `"products"` warm entries simpler than per-key; hot `stock-- 98` via `OrderService:101` touches one `id` → per-key `97` minimizes churn, `TTL 10m` defense-in-depth even if `evict` missed (§2.7).

**Q5: How is `evict:97` kept correct when stock is mutated outside `ProductCatalogueService`?**
A: `OrderService.placeOrder:101` (+ any locking/resilience stock writer) calls `catalogue.evict(productId) 101` through the `ProductCatalogueService` bean proxy — the `@CacheEvict 97` then drops `products::id` so next `get:50` re-reads fresh `stockQuantity` (§2.6-2.7). `this.evict` self-call would bypass proxy and silently fail.

**Q6: What verifies that a cache hit actually avoids SQL?**
A: `application.yml:175 DEBUG org.hibernate.SQL` emits `SELECT ... WHERE id=? 55` only on miss; second `GET /1` produces no new `SELECT` line while `RedisCacheMetrics` `cache.hit++` bumps; `product.get 52 @Timed` p50 goes <5ms (§2.8).

**Q7: `ProjectCatalogue list:42 findAll Pageable` — why not cached?**
A: `findAll:44` paged `Pageable LIMIT/OFFSET` keys are combinatorial (`page*size*sort`) — caching every `PageRequest` combo is huge; invalidation on each `create:60` would be a regex blast — keep listed paginated reads DB-direct, cache only `get:50 id` point query.

---

## 10. Honest limits & next step → PR #29

`10m TTL` still serves stale `stockQuantity` for `10m` if `evict:97` is missed on a non-catalogue mutation path (e.g. `liquibase migration UPDATE products` or manual `psql UPDATE`) — audit missing paths and keep `TTL` short or add `CacheEvict` interceptor on `ProductsRepo.save`. `SimpleCacheManager` `84 simple` per-pod means two pods diverge without `redis`. First-hit stampede (`100 concurrent GET 42` on cold `products`) all miss → 100 `SELECT` thundering herd — mitigate with `Cache.putIfAbsent` lock (`sync=true` on `@Cacheable`) or per-key `DistributedLockService` PR #30. No client `Cache-Control` header; CDN rarely needed yet. Next PR makes the `paymentGateway.charge:112` the slot that calls `cache evict` survivable under fault: Resilience4j circuit breaker / retry / bulkhead (`OrderService` `paymentGateway`) with shared `ResilienceProperties` in `application.yml:229-252`.

See [`29-resilience-patterns.md`](./29-resilience-patterns.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Enable `Spring Cache` proxies | `@EnableCaching` `RedisConfig.java:20` | `RedisConfig.java:20`, `spring-boot-starter-data-redis 163` | Advises `@Cacheable/@CacheEvict` on `ProductCatalogueService:35` |
| Popular `GET /{id}` acceleration | `cache-aside` `get:50-56` | `ProductCatalogueService.java:50-56` | `Dto ProductResponse` in `products::id` `10m TTL` `hit <5ms` |
| Exhaust local in tests | `spring.cache.type 84 simple` | `application.yml:84` | `ConcurrentHashMap` `SimpleCacheManager` no daemon |
| Stock visibility after `placeOrder:98 stock--` | `catalogue.evict(id):101` single drop | `ProductCatalogueService.java:97-100` + `OrderService.java:101` | `@CacheEvict key="#productId" 97` next `get:50` fresh |
| Full catalogue staleness clear | `allEntries 60/71/85` | `ProductCatalogueService:60 create,71 update,85 delete` | Rare admin write → clear all correct |
| Multi-pod shared cache | `Lettuce Redis 91-93` redisson isolated | `RedisConfig:18-21` note `RedissonConfig PR #30` | Real `RedisCacheManager` share vs `simple` divergence |
| Prove hit vs miss | `org.hibernate.SQL DEBUG + product.get 52` | `application.yml:175` + `RedisCacheMetrics` | `SELECT` only on miss; `hit` `cache.++` |

