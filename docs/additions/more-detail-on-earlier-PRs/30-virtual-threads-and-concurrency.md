# 30. Virtual Threads and Concurrency (PR #30)

> PR #30 — Java 21 virtual threads, `spring.threads.virtual.enabled:true:24`, `Executors.newVirtualThreadPerTaskExecutor:57`, pinning, `Redisson` distributed locks, `DashboardService` fan-out. Stack: Java 21, Spring Boot 3.4.1, `application.yml:20-25`, `DashboardService.java:50`, `RagStreamingController.java:57`, `RedissonConfig.java:19`. See `README.md:1772` roadmap `| 30 | Virtual Threads and Concurrency |`.

---

## 1. Purpose — what shipped

PR #30 switches the runtime from platform threads to **Java 21 virtual threads** for request handling and explicit concurrency. `application.yml:20-25` `spring.threads.virtual.enabled: true` makes Tomcat use a virtual-thread `Executor` — each HTTP request now costs a small heap stack, not an OS thread, so `Hikari maximum-pool-size 10:34` can be held by many more concurrent requests. `DashboardService.java:50` fans three stats queries onto `Executors.newVirtualThreadPerTaskExecutor()` (try-with-resources auto-close) and `CompletableFuture`/join merging; streaming controllers `RagStreamingController.java:57` and `AgentStreamingController.java:47` use the same executor with `.blockLast():138` to bridge `Flux` to MVC virtual threads. `Redisson 3.27.2:194` (`RedissonConfig.java:19` isolated from `Lettuce:163`) supplies `DistributedLockService` for hot `productId stock--`, complementing `@Retryable` optimistic locking. `JCache provider Ehcache:75-77` pin prevents `Redisson`'s `JCache` clash with Hibernate.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Tomcat platform pool (`server.tomcat.threads.max ~200`) capped concurrency — `200` concurrent `GET /api/v1/orders` each blocked on `ProductRepository.findById` (`SELECT 55`) or `paymentGateway charge:54` `2s` slow call would exhaust Tomcat while `Hikari 10:34` and DB were still able to serve. `DashboardService` stats did three sequential `SELECT COUNT(*)`, `SUM`, aggregation → latency additive. No distributed lock: concurrent `placeOrder` on same `productId` relied solely on `Product version 53` optimistic-lock retry `OrderService:77` (`@Retryable 3×`) — contention thundered on `version` bump `Product.setStock 98 flush`.

**After:** `spring.threads.virtual.enabled true:24` — every servlet request runs on a virtual thread (carrier = `ForkJoinPool`). `DashboardService:50 try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())` runs `3` tasks in parallel on ephemeral virtual threads, join before returning `DashboardResponse`. Streaming controllers `57` use same executor so `Flux` from `ChatModel.stream` `blockLast:138` parks the virtual thread (cheap unmount) not the carrier. `RedissonConfig:19` builds `RedissonClient` via `Config.useSingleServer().setAddress("redis://"+host+":"+port)` without stealing `LettuceConnectionFactory:91-93`; `DistributedLockService` does `RLock.tryLock(wait, lease)` per `productId` for hot keys.

### Theory — Virtual threads from first principles (100+ lines)

#### 2.1 What a virtual thread is — M:N scheduling, carrier, and cost

`Thread t = Thread.startVirtualThread(runnable)` creates a heap-allocated continuation (~KBs) not a `pthread`. The JVM mounts it onto a carrier `ForkJoinPool` worker (platform thread, count = `Runtime.availableProcessors()`). When the virtual thread **parks** (blocks on `LockSupport.park`, `Socket.read`, `join():60`, `blockLast():138`), the carrier is freed to run another virtual thread. Cost ratio: platform thread = `1MB` stack + kernel scheduling; virtual thread = `few KB` heap + user scheduling. `spring.threads.virtual.enabled true:24` simply replaces `Tomcat`'s `ThreadPoolExecutor` with `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())` (Spring Boot 3.2+). No code change in `OrderController:22`.

#### 2.2 How virtual threads differ from platform threads — pinning, TL, and sync

Three subtleties:

- **Pinning:** `synchronized` block that parks **pins** the carrier (blocks it) on Java 21. Example: `synchronized(lock){ socket.read() }` — the virtual thread cannot unmount while holding the monitor. Fix: `ReentrantLock` (`java.util.concurrent.locks`) instead — `lock.lock(); try{ socket.read(); } finally{ lock.unlock(); }` parks without pinning. Hibernate's `synchronized` hotspots were audited in JDK 21; `Product.version 53` bump path does not pin after upgrades, but `Redisson`'s `Netty` path uses `ReentrantLock` correctly.
- **ThreadLocal:** `ThreadLocal` still works but `InheritableThreadLocal` and `ThreadLocal.withInitial(200*1MB)` on `10k` virtual threads = `10k × value` heap; use `ScopedValue` (preview JDK 21) or request-scoped beans instead. `SecurityContextHolder` uses `ThreadLocal` — cleared per request by `SecurityFilterChain` before virtual thread termination.
- **Thread identity:** `Thread.currentThread()` still returns the virtual thread; `Thread.sleep` parks and unmounts (cheap), not carrier sleep.

```
Platform:  Request ─► OS thread (1MB) blocks on SELECT → OS thread blocked → pool exhausted at ~200
Virtual:   Request ─► virtual thread (heap) parks on SELECT → carrier free → 10k concurrent cheap
```

#### 2.3 `Executors.newVirtualThreadPerTaskExecutor()` — ephemeral tasks

```java
// DashboardService.java:50-... and RagStreamingController.java:57
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) { // AutoCloseable
  Future<Long> orders = executor.submit(() -> orderRepo.count());
  Future<BigDecimal> revenue = executor.submit(() -> orderRepo.sumTotal());
  Future<Map<...>> top = executor.submit(() -> productRepo.topSelling(5));
  return new DashboardResponse(orders.get(), revenue.get(), top.get());
} // close() awaits termination
```

`newVirtualThreadPerTaskExecutor()` creates a **new virtual thread per task** (no pool) — ideal for IO-bound fan-out where `Hikari 10:34` and `Redis Lettuce 91-93` are the bottlenecks, not CPU. Alternatives: `Executors.newFixedThreadPool` (platform threads) would cap at pool size; `ForkJoinPool.commonPool` would share CPU threads; `VirtualThreadPerTaskExecutor` is the IO-optimal. `DashboardService:12 import Executors` fan-out reduces latency from `a+b+c` sequential to `max(a,b,c)` parallel — visible in `product.get 52 @Timed` histogram when three cached-product reads fan out.

#### 2.4 Streaming bridge — `Flux.blockLast()` on a virtual thread

`RagStreamingController:33` comment: `Spring MVC (non-WebFlux)` but `ChatModel.stream: returns Flux<String>` must be bridged. Pattern:

```java
// RagStreamingController.java:57,138
ExecutorService vExec = Executors.newVirtualThreadPerTaskExecutor();
vExec.submit(() -> {
  chatModel.stream(prompt).doOnNext(chunk -> sse.send(chunk)).blockLast(); // 138 parks virtual thread cheaply
});
```

`blockLast()` on a **platform** thread would block Tomcat (`200` limit); on a virtual thread it parks/unmounts — carrier serves other requests while tokens stream. `AgentStreamingController:47,105` same. Alternative WebFlux `Mono/Flux` non-blocking pipeline would avoid `blockLast` entirely but requires reactive `R2DBC` and `ReactiveRepository` — not adopted here; virtual-thread blocking is the simpler carrier-efficient bridge.

#### 2.5 Distributed locks — when virtual threads are not enough

Optimistic `Product @Version 53` + `@Retryable:77 ProductInventoryService 91 ProductStockService 39` handles **low contention**: `0 rows matched → retry`. Hot `productId=1` flash sale (`100 concurrent placeOrder` same SKU) → `100` tx compete on `version` → retries spike. `Redisson 3.27.2:194` provides `RLock` on `Redis 91 host:92`: `lock.tryLock(wait 3s, lease 10s)` per `productId` key `lock:product:1` before `stock--:98`. `RedissonConfig:19` uses plain `redisson:193` (not `spring-boot-starter`) to avoid `LettuceConnectionFactory` hijack (`RedisConfig:18-21` note). `try-with` pattern: `if(lock.tryLock()) try{ stock-- ; catalogue.evict97 } finally{ lock.unlock(); }`. Lease `10s` prevents dead lock if virtual thread crashes.

```
Optimistic (default):  stock-- 98, 0 matched → @Retryable 91 re-runs whole tx
Pessimistic (hot key): DistributedLockService.lock(productId) → stock-- → unlock → evict97  (single owner, no retry)
```

Choice table: `version` retry good when `contention < 5%`; `Redisson lock` good when `hot key` burst. Tests keep `spring.cache.type simple 84` — Redisson still connects to `docker redis 6379` only when `DistributedLockService` invoked.

#### 2.6 `JCache` provider pin — `Ehcache` vs `Redisson` clash

`application.yml:66-77` `hibernate.cache.use_second_level_cache true 69` + `region.factory_class jcache 71` + `javax.cache.provider EhcacheCachingProvider 77` pins `Ehcache` as `javax.cache.spi.CachingProvider`. Without `77`, `Redisson` (which ships `RedissonCachingProvider`) on classpath would make `Caching.getCachingProvider()` ambiguous and `SessionFactory` startup fails. Comment at `application.yml:72-74` explains. `RedisConfig:20 @EnableCaching` simple vs redis distinction (§2.4 PR28) unaffected — this is Hibernate L2 `Product.java:33 @Cacheable` vs application `products::42` split.

#### 2.7 Carrier sizing and `Hikari` interplay

Virtual threads increase concurrency but `Hikari 10:34` is still `10` DB connections. `10k` virtual threads contending for `10` connections queue on `Hikari.getConnection()` — park cheap, but latency shows pool wait. Dashboard fan-out `3` concurrent counts need `3` connections simultaneously — with `Hikari 10` ample, but `placeOrder` burst `100` stock-- needs pooling; `default_batch_fetch_size 20:61` and `fetch_size 100:65` still cap DB round-trips. Alternative `Hikari 50` would raise DB load; keep `10` and let `bulkhead queue5:251` backpressure.

> Interview anchor: "`application.yml:24 spring.threads.virtual.enabled true` — Tomcat virtual threads; `DashboardService.java:50 Executors.newVirtualThreadPerTaskExecutor()` fan-out `3` stats `max(a,b,c)`, `RagStreaming 57 blockLast138` bridges `Flux` without WebFlux; `RedissonConfig:19` plain redisson 3.27.2 isolated from `Lettuce 91` for `DistributedLockService lock(productId)` hot key vs `@Version53 @Retryable91` low contention; `application.yml:77 provider Ehcache` pin fixes Redisson JCache clash; `Hikari10:34` still the pool bottleneck."

#### 2.8 Pinning hazards — `synchronized` vs `ReentrantLock` in IO paths

Java 21 virtual thread parks **pinned** if inside `synchronized`: carrier blocked equals platform-thread behavior, defeating scalability. Audit: `HashMap` traversal `synchronized`, `PrintStream`, `FileOutputStream`. `ProductCatalogueService get:50` has no `synchronized` (Spring `@Cacheable` proxy uses `ReentrantLock` internally). Use `ReentrantLock` in any explicit IO wrapper; `Hikari` pool internals use `ReentrantLock` (safe). Detect pinning with `JDK Flight Recorder (JFR) jdk.VirtualThreadPinned` event.

#### 2.9 Virtual threads vs reactive — choosing the bridge

Reactive `WebFlux + R2DBC` yields non-blocking end-to-end but rewrites `OrderRepository extends JpaRepository` to `ReactiveCrudRepository` and needs `DatabaseClient` — invasive. Virtual threads achieve similar **throughput** with **imperative** code (`OrderService.java:81` stays `imperative`), blocking calls park cheaply without rewriting. Throughput `virtual threads: ~10k rps` ~ `WebFlux` on IO-bound; CPU-bound (long `BigDecimal` loops) still needs carriers = processors. Hence `DashboardService 50 virtual executor` for IO fan-out, not CPU compute.

#### 2.10 Lifecycle — graceful shutdown and carrier drain

`server.shutdown graceful:137` + `spring.lifecycle.timeout-per-shutdown-phase 20s:18` + `Deployment terminationGracePeriodSeconds 25: k8s/deployment.yaml` coordinate: on `SIGTERM`, Tomcat stops accepting, virtual threads in `placeOrder` tx finish up to `20s`, `OutboxPublisher` scheduled ceases. `Executors.newVirtualThreadPerTaskExecutor()` is `AutoCloseable`: `close()` waits for submitted tasks. `blockLast:138` streaming suspends until `Flux` completes; shutdown interrupt propagates via virtual thread interruption — handle `InterruptedException`.

#### 2.11 Observability of virtual threads

`Micrometer:291` `jvm.threads.virtual` + `jvm.threads.live` gauges; `actuator/metrics/jvm.threads.live` shows carrier vs virtual split. `logging.pattern console` includes `%t` virtual thread name `VirtualThread[#123]/runnable`. `prometheus:144` `process_cpu_usage` may spike on many virtual threads creation but heap `MaxRAMPercentage 75: Dockerfile JAVA_TOOL_OPTIONS` handled.

---


#### 2.12 Structured concurrency preview — why not yet

JDK 21 `StructuredTaskScope` (preview) would let `DashboardService` write `try(var scope=new StructuredTaskScope.ShutdownOnFailure()){ scope.fork(count); scope.fork(sum); scope.join(); }` with automatic cancel-on-failure and scoped error propagation vs manual `Future.get`. Not adopted because preview flag `--enable-preview` would ripple to `maven-compiler-release 21` and CI; keep `ExecutorService` auto-closeable pattern `50` until GA.

#### 2.13 Fairness and starvation — ForkJoinPool quirks

Carrier `ForkJoinPool` is FIFO for virtual threads but work-stealing can reorder `Dashboard` subtasks; `DashboardService` `topSelling` may starve if `count` holds DB connection `Hikari 10`. Mitigate by ordering subtasks by expected latency or using `Semaphore` for `Hikari` admission. `FairSequenceAllocator.java:12` shows similar fairness concerns for order-number allocation.


## 3. Solution — ASCII

```
Before (platform pool):
[Client 200×] ─► Tomcat ThreadPool 200 platform threads (1MB each)
                  ├─ SELECT * FROM products WHERE id=42 (blocks OS thread) ─► DB Hikari10 34
                  └─ paymentGateway.charge 2s slow ─► PSP (2s OS block)  → pool exhausted

After (virtual threads):
[Client 10k×] ─► Tomcat VirtualThreadPerTaskExecutor (spring.threads.virtual.enabled true 24)
                 virtual thread per request (heap, parks → carrier free ForkJoinPool)
                  ├─ OrderService.placeOrder:77 (@Transactional tx + stack)
                  │    ├─ stock-- 98 (version53 bump) contend? → @Retryable 3× OR Redisson lock per productId
                  │    ├─ charge 54 resilient bulkhead57→breaker58→retry59→attempt74 (park bulkhead thread cheap)
                  │    └─ OutboxEntry.pending 125 save (join tx)
                  └─ DashboardService:50 fan-out
                       try(ExecutorService vExec=newVirtualThreadPerTaskExecutor()){  //50
                         Future count = vExec.submit(()->orderRepo.count());            // virtual thread per stat
                         Future sum   = vExec.submit(()->orderRepo.sumTotal());
                         Future top   = vExec.submit(()->productRepo.topSelling(5));
                         DashboardResponse(count.get(), sum.get(), top.get())          // max latency
                       } //close() awaits
                  └─ RagStreaming 57 / AgentStreaming 47
                       vExec.submit(()-> chatModel.stream(prompt).doOnNext(sse::send).blockLast() 138)
                       // blockLast parks virtual thread → carrier free while LLM tokens stream
                  └─ Hibernate: Hikari10 34 still 10 connections (queue cheap)
                  └─ RedissonConfig 19 (host 91 redis 6379) → DistributedLockService.lock(productId)
                       vs Ehcache L2 provider pin 77 (jcache EhcacheCachingProvider) keeps SessionFactory safe

ConfigMap  k8s/base/configmap.yaml SPRING_PROFILES_ACTIVE staging
Deployment k8s/base/deployment.yaml replicas2 terminationGracePeriod 25 (>20s phase 18) probes liveness/readiness
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/application.yml` | `20-25,32-35,66-77,80-94,136-137` | Virtual + pool wiring | `threads.virtual.enabled true24 timeout20s lifecycle18 shutdown graceful137 Hikari10-34 L2 Ehcache77 cache simple84` |
| `src/main/java/com/company/orderapi/domain/service/DashboardService.java` | `12,20,50` | Fan-out | `import Executors12 comment20 try newVirtualThreadPerTaskExecutor50 3 tasks max latency` |
| `src/main/java/com/company/orderapi/api/rest/controller/RagStreamingController.java` | `25,33,57,138` | Streaming bridge | `Executors25 comment Spring MVC not WebFlux33 newVirtualThread57 blockLast138 parks` |
| `src/main/java/com/company/orderapi/api/rest/controller/AgentStreamingController.java` | `21,47,105` | Same bridge | `Executors21 newVirtualThread47 blockLast105` |
| `src/main/java/com/company/orderapi/config/RedissonConfig.java` | `19` | Distributed lock client | `Redisson.create(Config.useSingleServer redis://host91:port92) plain redisson193 no Lettuce hijack 18-21` |
| `src/main/java/com/company/orderapi/domain/service/DistributedLockService.java` | — | Lock API | `RLock tryLock(wait, lease) per productId` vs optimistic `version53` |
| `src/main/java/com/company/orderapi/domain/service/ProductStockService.java` | `39` | Contention retry | `@Retryable39 optimistic 0 rows matched → retry whole stock--` hot key complement to Redisson |
| `pom.xml` | `163-166,185-196` | Runtime deps | `spring-boot-starter-data-redis 163 Lettuce, redisson 3.27.2 192-196 plain` |
| `Dockerfile` | `8,11,14` | Carrier sizing | `eclipse-temurin21-jre-alpine11 JAVA_TOOL_OPTIONS MaxRAMPercentage75` |
| `src/main/resources/application-*.yml` | `77` | JCache pin | `javax.cache.provider EhcacheCachingProvider77 fixes Redisson clash 72-74` |

```java
// DashboardService.java:50 — IO fan-out on virtual threads
try(ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor()){
  var cnt=executor.submit(()-> orders.count());
  var sum=executor.submit(()-> orders.sumRevenue());
  var top=executor.submit(()-> products.findTop5());
  return new DashboardResponse(cnt.get(), sum.get(), top.get());
}
// RagStreamingController.java:57,138 — Flux bridge
Executors.newVirtualThreadPerTaskExecutor().submit(
  ()-> chatModel.stream(prompt).doOnNext(sse::send).blockLast());
// RedissonConfig.java:19
Config cfg=new Config(); cfg.useSingleServer().setAddress("redis://"+redisHost+":"+redisPort);
RedissonClient client=Redisson.create(cfg);
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Verify virtual threads enabled
grep -n "spring.threads.virtual\|timeout-per-shutdown-phase\|Hikari.*maximum" src/main/resources/application.yml  # 18 24 34
grep -rn "newVirtualThreadPerTaskExecutor" src/main/java --include="*.java"  # DashboardService 50, Rag 57, Agent 47
grep -n "redisson\|RedissonConfig\|DistributedLockService" src/main/java/com/company/orderapi --include="*.java" -R | head

# Run tests (virtual threads transparent)
./mvnw test -Dtest=OrderServiceTest,DashboardServiceTest 2>&1 | grep -E "Tests run|Virtual"

# Run with virtual threads (default)
./mvnw spring-boot:run & sleep 12
curl -s http://localhost:8080/actuator/health | jq '.components.db'
ps -o pid,cmd | grep java; jcmd $(pgrep -f order-management-api) Thread.print 2>/dev/null | grep -i "VirtualThread" | head
kill %1

# Dashboard fan-out latency (three stats parallel)
time curl -s http://localhost:8080/api/v1/dashboard -H "X-API-KEY: dev-api-key-orderapi" | jq .

# Streaming on virtual thread (SSE)
curl -N http://localhost:8080/api/v1/rag/stream?query=what+is+an+order -H "X-API-KEY: dev-api-key-orderapi" | head -n 20
curl -N http://localhost:8080/api/agent/stream -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" -d '{"message":"hello"}' | head -n 20

# Distributed lock vs optimistic retry (hot key smoke)
# concurrent placeOrder same productId — with DistributedLockService path lock(productId) serializes stock-- without version retries
for i in {1..5}; do curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq .orderNumber & done; wait | grep order
```

```java
// Fan-out template for any IO-bound aggregation on virtual threads
try(ExecutorService exec=Executors.newVirtualThreadPerTaskExecutor()){
  Future<A> fa=exec.submit(()-> repoA.findAll());
  Future<B> fb=exec.submit(()-> repoB.count());
  return new Aggregate(fa.get(), fb.get());
} // no pool exhaustion, virtual threads park cheaply
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why | Cost |
|---|---|---|---|---|
| `spring.threads.virtual.enabled true:24` Tomcat | Virtual per request | Platform pool `200` + tuning | `10k` concurrent cheap; sequential `SELECT` park not block carrier | `Hikari10:34` still pool bottleneck; pinning risk `synchronized` |
| `newVirtualThreadPerTaskExecutor:50` fan-out | Ephemeral virtual per task | `FixedThreadPool platform` + `CompletableFuture` | Optimal IO fan-out `max(a,b,c)` latency; `AutoCloseable` | `3` virtual threads each needs `DB conn` contended |
| `Flux.blockLast:138` on virtual thread | Parks cheap, MVC stays | Full WebFlux + R2DBC | Incremental non-reactive, no repo rewrite | `blockLast` hidden blocking; must ensure virtual context |
| `Redisson plain 193` `DistributedLockService` | Per hot `productId` lock vs `version53` | Only optimistic `@Retryable 91` | Hot SKU burst no retry thunder; `tryLock+lease` safe | Redis `host91` failure falls back to optimistic (must handle) |
| `JCache provider Ehcache:77` pin | `EhcacheCachingProvider` | Default lookup | `Redisson` `JCache` clash would break `SessionFactory` boot `72-74` | Hard-coded provider |
| `ReentrantLock` over `synchronized` | `ReentrantLock` in locks | `synchronized` | Avoid pinning carrier (JDK21) | Slight verbosity |
| `Hikari 10:34` keep | `10` | Raise to `50` | Matches `DB 16 PostgreSQL 16` CPU; let virtual park cheaply | Tail latency on burst queues |

---

## 7. How to verify

```bash
grep -n "spring.threads.virtual.enabled\|shutdown: graceful\|timeout-per-shutdown-phase" src/main/resources/application.yml  # 18 24 136-137
grep -rn "newVirtualThreadPerTaskExecutor\|blockLast" src/main/java --include="*.java" | head -n 10  # Dashboard 50, Rag 57+138
grep -n "Redisson\|DistributedLock\|useSingleServer\|REDIS_HOST\|REDIS_PORT" src/main/java/com/company/orderapi/config/RedissonConfig.java src/main/resources/application.yml | head
grep -n "javax.cache.provider.*Ehcache" src/main/resources/application.yml  # 77
grep -n "eclipse-temurin.*21.*jre-alpine\|MaxRAMPercentage" Dockerfile  # 8,11,14 Build + Runtime
# Live thread dump shows virtual threads
./mvnw spring-boot:run & sleep 12; jcmd $(pgrep -f order-management-api) Thread.print 2>/dev/null | grep -c "VirtualThread"; kill %1
# Dashboard parallel correctness
./mvnw test -Dtest=DashboardServiceTest 2>&1 | grep -E "Tests run|Dashboard"
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New IO fan-out → `try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) { Future a = exec.submit(()-> repoA); ... }` (`DashboardService:50`). New streaming SSE → submit `Flux.stream.blockLast():138` onto virtual executor (`RagStreaming:57`). Hot contended write `stock-- 98` → wrap with `DistributedLockService.lock(productId)` (`RedissonConfig:19 host91 port92`) then `try/catch + evict97`; keep `@Retryable 91` as fallback for non-hot. Never `synchronized` around IO — use `ReentrantLock`.
- **Operate:** Monitor `jvm.threads.live/virtual`, `hikaricp_connections_pending` (`prometheus:144`), `resilience bulkhead pending` vs `dashboard @Timed`. Pinning alert via `JFR jdk.VirtualThreadPinned`. On restart, `terminationGracePeriod 25` (`deployment.yaml`) > `20s` (`application.yml:18`) drains virtual threads. `REDIS_HOST:91` loss degrades to optimistic retry — alert `redisson connection`.
- **Interview:** "PR #30: `spring.threads.virtual.enabled true:24` — Tomcat virtual threads, carrier `ForkJoinPool`. `DashboardService:50 newVirtualThreadPerTaskExecutor` fan-out `max`, `Rag/Agent 57 blockLast138` bridges `Flux` cheaply on MVC, `RedissonConfig:19` plain redisson isolated from `Lettuce91` per-hot `productId` `RLock` vs `@Version53 @Retryable91`, `application.yml:77 Ehcache provider pin` fixes JCache clash, `Hikari10:34` still pool bottleneck, no `synchronized` pinning."

---

## 9. Interview lens — Q&A

**Q1: What makes a virtual thread cheaper than a platform thread?**
A: Heap allocation (`KB`) mounted on `ForkJoinPool` carrier; park (LockSupport, Socket read, `join:60`, `blockLast:138`) unmounts carrier free → `10k` vs `200` (§2.1).

**Q2: When does a virtual thread pin the carrier and how fix?**
A: `synchronized` block that parks pins carrier (JDK21); use `ReentrantLock` instead; detect `JFR jdk.VirtualThreadPinned` (§2.2,2.8).

**Q3: Why `newVirtualThreadPerTaskExecutor:50` for `DashboardService`?**
A: New virtual per IO task, no pool — `3` counts parallel `max(a,b,c)` vs sequential, optimal when `Hikari10:34` is bottleneck not CPU (§2.3).

**Q4: How does `Flux.blockLast:138` not block Tomcat?**
A: Run on virtual thread executor (`RagStreaming:57`); `blockLast` parks virtual (unmount cheap) vs platform `200` block (§2.4).

**Q5: When would you use `Redisson` lock vs `version` optimistic?**
A: Hot `productId` flash-sale burst → `DistributedLockService` per-key `tryLock` serializes `stock--:98`; low contention → `@Retryable91` on `0 rows` cheaper (§2.5).

**Q6: Why pin `javax.cache.provider:77` to `Ehcache`?**
A: `Redisson` ships `JCache` provider; ambiguous `CachingProvider` lookup would fail `SessionFactory` boot `72-74` (§2.6).

**Q7: What still limits scalability with virtual threads?**
A: `Hikari maximum10:34` DB connections — `10k` virtual threads queue on `DataSource.getConnection()` park cheap but latency reveals pool wait; tune pool and `bulkhead queue5:251` (§2.7).

---

## 10. Honest limits & next step → PR #31

Virtual threads do not fix `synchronized` pinning in third-party libs — audit with `JFR`. `Hikari 10:34` and `bulkhead queue5:251` still bound DB burst; hot `productId` lock lease `10s` can deadlock if `evict97` missed. No `StructuredTaskScope` (JDK21 preview) yet — `DashboardService:50` manual `Future.get` lacks cancel-on-first-failure. Next PR makes the committed tx durable across processes: atomic `OutboxEntry.pending:125` + `FOR UPDATE SKIP LOCKED:24` relay to Kafka, at-least-once + DLT, so `OrderPlaced` survives consumer crash.

See [`31-kafka-event-driven.md`](./31-kafka-event-driven.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| `10k` concurrent HTTP cheap | `virtual threads enabled:24` | `application.yml:24` | Tomcat virtual per request, carrier reusable |
| IO fan-out `3` stats | `newVirtualThreadPerTaskExecutor` | `DashboardService.java:50` | `max` not sum latency |
| Bridge `Flux` to MVC | `blockLast:138` on virtual exec | `RagStreaming:57 Agent:47` | Parks cheap, no WebFlux rewrite |
| Hot key contention | `Redisson RLock per productId` | `RedissonConfig.java:19` | `tryLock+lease` vs `version53` |
| Keep `SessionFactory` safe | `Ehcache provider pin` | `application.yml:77` | Redisson JCache clash `72-74` |
| Observe virtual | `Micrometer jvm.threads` | `management:139 prometheus:144` | `jvm.threads.virtual` gauge |
