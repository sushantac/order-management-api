# 29. Resilience Patterns (PR #29)

> PR #29 — Resilience4j circuit breaker, retry, bulkhead, rate limiter, composition. Stack: Java 21, Spring Boot 3.4.1, Resilience4j 2.2.0 `resilience4j-spring-boot3:182`, `spring-boot-starter-aop:109`, `SimulatedPaymentGateway.java:32`, `ApiKeyRateLimiterFilter.java:35`, `OrderService.java:77`, `application.yml:212-253`. See `README.md:1772` roadmap `| 29 | Resilience Patterns |`.

---

## 1. Purpose — what shipped

PR #29 makes the payment edge survivable without changing order transaction semantics. `SimulatedPaymentGateway.java:32-93` (`PaymentGateway` `FAILURE_RATE 10%:39` via `ThreadLocalRandom:75`) now crosses an explicit Resilience4j stack **bulkhead → circuit breaker → retry → attempt** (`charge:54-67`). `OrderService.placeOrder:77` keeps `@Retryable` for DB contention; gateway resilience lives at `RESILIENCE_INSTANCE paymentGateway:36` configured purely from `application.yml:212-253`. Separately, `ApiKeyRateLimiterFilter.java:35` (`OncePerRequestFilter`) rate-limits `X-API-Key:38` callers per-key via `RateLimiterRegistry:40` + `RateLimiterConfig template:41` from `apiKey limit 1000/min:239-246`, emitting `429` with `Retry-After:83-84`, `X-RateLimit-Remaining:68`, `X-RateLimit-Reset:69`. Chaos-testable via `protected attempt:74` override; verified by `RateLimitIntegrationTest`.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `OrderService.placeOrder` `paymentGateway.charge:112` was a direct `attempt:74` — `10%` random `PaymentFailedException:77` and any `slowCall >2s:226` or burst `>queue 5:251` propagated as `500` on every order. No `retry` so legitimate transient declines became lost orders. No `breaker` so sustained PSP outage hammered the provider. No `bulkhead` so provider stalls tied up `Hikari maximum-pool-size 10:34` and `virtual threads:24`. No admission control: one `dev-api-key-orderapi:188` hammering `GET /api/v1/orders` could starve others.

**After:** `charge:54-67` composes `bulkhead.executeRunnable:57` → `circuitBreaker.executeRunnable:58` → `retry.executeRunnable:59` → `attempt:74` then `.toCompletableFuture().join():60` with `translate:84`. `retry` `max-attempts 3:232` `wait 200ms:233` `exponential x2:235` for `PaymentFailedException:238` (`200,400,800 ≈1.4s`). `breaker` `slidingWindow 10:223` `minimum 5:224` `failureRate 50:225` / `slowCall 50 + 2s:226-227` opens `5s:228` then `halfOpen 2 probes:229`. `bulkhead` `core1 max2 queue5 keepAlive1m:250-253` isolates; `BulkheadFullException` translated at `charge:64`. `RateLimiterFilter:62 limiter.acquirePermission()` per `bucketName(apiKey) apiKey-<hash>:74` distinct buckets; `429 {status:429 code:RATE_LIMITED hint 87-88}`.

### Theory — Resilience4j from first principles (100+ lines)

#### 2.1 Why resilience is first-class — fail fast, fail safe, bulkhead isolation

A distributed call `OrderService → PaymentGateway → PSP` fails three ways: transient decline (card blip), slow call (p99 spike >2s), overload (concurrent burst). Without guards, every failure is caller-visible, every retry unbounded, every slow call holds a thread. Resilience4j applies four orthogonal guards: `Retry` bounds attempts by count+delay, `CircuitBreaker` bounds failure fraction, `Bulkhead` bounds concurrency, `RateLimiter` bounds rate. Each is independent but composition determines correctness. `resilience4j-spring-boot3:182` auto-registers `CircuitBreakerRegistry`, `RetryRegistry`, `RateLimiterRegistry`, `ThreadPoolBulkheadRegistry` from `resilience4j.*` properties — no redeploy to tune.

#### 2.2 Composition order — bulkhead ⟶ breaker ⟶ retry ⟶ attempt

```java
// SimulatedPaymentGateway.java:54-67
public void charge(BigDecimal amount){
  try{
    bulkhead.executeRunnable(() ->                    // 57 outermost: isolate
      circuitBreaker.executeRunnable(() ->             // 58 middle: short-circuit
        retry.executeRunnable(() -> attempt(amount))   // 59 inner: absorb transient before counting
      )).toCompletableFuture().join();                 // 60 ThreadPoolBulkhead is async
  } catch(CompletionException e){ throw translate(e.getCause()); } //61
    catch(RuntimeException e){ throw translate(e);}    //63-65 BulkheadFullException path
}
```

Order matters critically. Bulkhead **outside** protects breaker state from queue buildup: if pool saturated, `BulkheadFullException` is rejected before breaker counts it as failure and before retry wastes permits. Breaker **outside** retry means `3` retries that all throw `PaymentFailedException:77` count as **one** breaker failure. Reverse (`retry` outside `breaker`) would inflate failure count `3x` and trip breaker prematurely. Programmatic composition (not annotation `@CircuitBreaker @Retry @Bulkhead`) makes order visible and unit-testable (inject registries, override `attempt:74`).

```
bulkhead queue5×2threads ─┐
breaker open 5s half 2 ────┼─► translate():84 → PaymentFailedException (domain)
retry 3× 200·2^n backoff ──┘   (≈1.4s worst before open)
```

#### 2.3 Circuit breaker — sliding window, states, half-open probe

`CircuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE:48)` uses `paymentGateway:220-229`:

```yaml
# application.yml:220-229
circuitbreaker.instances.paymentGateway:
  sliding-window-size: 10                # counted window last 10 calls
  minimum-number-of-calls: 5             # evaluate only when ≥5
  failure-rate-threshold: 50             # open when ≥50% failed
  slow-call-duration-threshold: 2s       # >2s is slow
  slow-call-rate-threshold: 50           # also open when ≥50% slow
  wait-duration-in-open-state: 5s        # snooze before half-open
  permitted-number-of-calls-in-half-open-state: 2
```

States: `CLOSED` (normal) → `failureRate = failures/window` once `calls ≥ minimumNumberOfCalls`. Exceed `50%` → `OPEN 5s`: `executeRunnable` throws `CallNotPermittedException` (via `translate:84`). After `5s`, `HALF_OPEN`: allow exactly `2` probes; if both succeed and rates <50% → `CLOSED`, else `OPEN` again. Counted window (`size 10`) reacts to last `10` calls at any QPS; time-based window alternative would need `minimumCalls` per second. `2 slow calls` of `10` at `2.1s` each already trips `slowRate 50%`.

#### 2.4 Retry — exponential backoff, exception predicate, tx interaction

`RetryRegistry.retry(RESILIENCE_INSTANCE:49)` config `application.yml:230-238`:

```yaml
retry.instances.paymentGateway:
  max-attempts: 3
  wait-duration: 200ms
  enable-exponential-backoff: true
  exponential-backoff-multiplier: 2        # 200,400,800ms
  retry-exceptions: [PaymentFailedException]
```

Only `PaymentFailedException:238` retried; `CallNotPermittedException` (breaker open) and `BulkheadFullException` not listed → fail fast, no wasted wait. The retried unit is `attempt:74` only, not the enclosing `OrderService @Transactional`. `placeOrder` writes `Order+OrderItem+Payment+OutboxEntry.pending:125` atomically; if `charge` succeeds on retry #2, tx commits once — no duplicate outbox. `OrderService:77 @Retryable` retries the **whole** tx for `ObjectOptimisticLockingFailureException` (stock contention), orthogonal to provider retry. `protected attempt:74` lets tests subclass deterministically without `ThreadLocalRandom`.

```
Timeline (worst successful): attempt1 fail 0ms → 200ms → attempt2 fail → 400ms → attempt3 success @600ms → commit
Timeline (all fail): 0 +200 +400 + attempt3 fail → breaker counts 1 failure → translate → 500 via GlobalExceptionHandler
```

#### 2.5 Thread-pool bulkhead — isolation vs semaphore

`ThreadPoolBulkheadRegistry.bulkhead(RESILIENCE_INSTANCE:50)` `core 1 max 2 queue 5 keepAlive 1m:249-253`. Every `charge` submits to this pool (`executeRunnable→CompletionStage:57-60`). `core 1` warm, scale to `2` on load, queue `5`. When saturated → `BulkheadFullException catch:64`. Why thread-pool not semaphore? Semaphore bulkhead (`Bulkhead` annotation) limits concurrency on **calling** thread (Tomcat/virtual thread) — slow `attempt 2s` still blocks caller. Thread-pool bulkhead truly isolates: provider latency ties up bulkhead threads, not `Hikari 34 HikariPool` nor `OrderService virtual threads:24`. Tradeoff: `.join():60` blocks caller until bulkhead thread completes; alternative `CompletableFuture` chaining non-blocking complicates `translate`.

#### 2.6 Rate limiter — per-API-key token bucket, 429 contract

`RateLimiterRegistry:40` + `RateLimiterConfig template:41-46` seeded from `application.yml:239-246` (`apiKey limit 1000 period 1m timeout 0s:244-246`). `ApiKeyRateLimiterFilter.doFilterInternal:50-71`:

```java
// ApiKeyRateLimiterFilter.java:62-69
RateLimiter limiter = rateLimiterRegistry.rateLimiter(bucketName(apiKey), template); // lazily per hash
if (!limiter.acquirePermission()) { writeRateLimited(response, limiter); return; } // 429 77-88
response.setHeader("X-RateLimit-Remaining", String.valueOf(limiter.getMetrics().getAvailablePermissions())); //68
response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds(limiter))); //69
filterChain.doFilter(request, response);
```

Token bucket: `1000` tokens refill every `1m`. `timeout 0s` means fail immediately when empty (no wait). `bucketName:73-75` `apiKey-<hash>` — each `X-API-Key:38` gets its own `RateLimiter` instance; registry is a concurrent map. `Bearer-JWT` (`apiKey null/blank:56`) **bypasses** — scope quotas belong to gateway. On `429`, `Retry-After:83` is `(refreshPeriod+999)/1000` (`60s` floor `1s`) and `X-RateLimit-Reset:93` epoch seconds `now+refresh`.

```
Client A (key=abc) ── bucket apiKey-123 ── 1000/min ─┐
Client B (key=xyz) ── bucket apiKey-789 ── 1000/min ─┼─ independent; A cannot exhaust B
JWT bearer  ──────── bypass 56 ──────────────────────┘
```

#### 2.7 Filter vs annotation ordering

`RateLimiter` as `OncePerRequestFilter:35` runs before `SecurityConfig` JWT/API-key auth for `/api/`. Anonymous hammer without key (`apiKey null:56`) bypasses limiter → security still rejects, but no bucket created. Alternative `@RateLimiter(name="apiKey")` on `OrderController:22` would limit **after** auth — filter protects auth cost. `SimulatedPaymentGateway` programmatic vs annotation (`@CircuitBreaker @Retry @Bulkhead`): programmatic makes ordering `bulkhead→breaker→retry` explicit and testable; aspect ordering (`Ordered` via `spring-aop:174`) harder to reason.

#### 2.8 Alternatives and lattice

- Resilience4j vs Sentinel/Hystrix/Istio: Resilience4j library-level, no sidecar, per-instance `Micrometer:291` metrics fit `prometheus:144`. Hystrix deprecated; Sentinel needs dashboard; Istio L7 mesh coexists but not method-level `paymentGateway`.
- Resilience4j retry vs Spring Retry `@Retryable`: Spring Retry (`RetryConfig.java:15 @EnableRetry`) handles `OptimisticLockException:77` inside same tx; gateway retry needs `Retry` instance tied to shared `CircuitBreaker` registry — Spring Retry cannot integrate.
- Admission: `RateLimiter` per-key vs global concurrency vs token quota — per-key chosen because `X-API-Key dev-api-key-orderapi:188` is machine/script path; Bearer `JwtDecoder` per-user; global quota mis-attributes.

> Interview anchor: "`SimulatedPaymentGateway.java:36 RESILIENCE_INSTANCE paymentGateway` composes `bulkhead 57→breaker58→retry59→attempt74` (bulkhead outer fails before breaker counts, breaker outer counts retry batch as one). `application.yml:212-253` `breaker sliding10 min5 rate50 slow2s wait5s half2 220-229`, `retry3×200ms×2 exp 232-238 PaymentFailedException`, `bulkhead core1 max2 queue5 250-253` isolates `Hikari10:34`, `RateLimiterFilter:62 per hash apiKey- bucket template apiKey 1000/min 239-246 429 X-RateLimit 67-69 Retry-After83`. Chaos via `protected attempt74` override."

#### 2.9 Failure taxonomy

| Failure | Guard | Behaviour |
|---|---|---|
| Transient decline `10%:39` | `Retry 3× exp` | `200,400,800ms` before breaker sees 1 failure |
| Slow `>2s:226` | `Breaker slowRate50%` | `≥5 calls` half slow → `OPEN 5s:228`, then `2 probes:229` |
| Sustained error | `Breaker rate50%:225` over `10:223` | `CallNotPermitted → translate84` |
| Burst `>queue5+2threads` | `Bulkhead` | `BulkheadFullException:64` immediate |
| Hammer client `>1000/min` | `RateLimiterFilter:63` | `429 RATE_LIMITED 87-88` |

#### 2.10 Bulkhead saturation as backpressure

`queue 5:251` buffers between `OrderService` virtual threads and provider threads. Many `placeOrder` virtual threads park on `join():60` while bulkhead threads call `attempt`. On full, caller gets `BulkheadFullException` immediately (`timeout 0s` equiv). `Micrometer` `resilience4j_bulkhead_available_concurrent_calls` shows saturation before latency. Alternative `queue 0` stricter but spike propagates; `queue 100` hides overload.

#### 2.11 Observability — breaker/rate metrics in Prometheus

`resilience4j-spring-boot3:182` exports `resilience4j_circuitbreaker_* (state, buffered_calls, failures, slow)`, `resilience4j_retry_calls`, `resilience4j_bulkhead_available`, `resilience4j_ratelimiter_available_permissions` to `Micrometer:291` `/actuator/prometheus:144`. `TimedAspect:29` tags `cache=paymentGateway`. Alert `state=OPEN >5m` or `rateLimitRemaining==0` bursts.

---

## 3. Solution — ASCII

```
Request  POST /api/v1/orders {customerId, items[{productId,qty}]}
       │  OrderController.create:22 → OrderService.placeOrder:77 (@Transactional + @Retryable optimisticLock)
       │     1. validate + lock Product stock-- 98 + Order+OrderItem+Payment persist
       │     2. OutboxEntry.pending Order 125 saveAndFlush (same tx)
       │     3. paymentGateway.charge(amount):54               ← Resilience4j enters
       │          bulkhead.executeRunnable:57 ── ThreadPool core1 max2 queue5:250 ─┐
       │            circuitBreaker.executeRunnable:58  state CLOSED/OPEN/HALF      │
       │              retry.executeRunnable:59  3× 200ms×2 for PaymentFailedEx     │
       │                attempt:74  ThreadLocalRandom 10% → PaymentFailedEx 77     │
       │              ← translate:84 maps BulkheadFull/CallNotPermitted → PaymentFailedEx
       │            ───────────────────────────────────────────────────────────────┘
       │          .toCompletableFuture().join():60  (virtual thread parks; bulkhead thread runs attempt)
       │     4. on success → commit tx (order visible); on PaymentFailedEx after retry/breaker → rollback (no trace)
       │
       └─ HTTP 201 + Location /api/v1/orders/{id}  or 502 Payment provider unavailable

Rate limiting (orthogonal, per-request filter):
[Client] ── X-API-Key: dev-api-key-orderapi:188 ──► ApiKeyRateLimiterFilter:35 doFilterInternal:50
             │  bucketName(apiKey) apiKey-<hash>:74 per-key RateLimiter:62 template apiKey 1000/min:244
             ├─ acquirePermission()? NO → 429 writeRateLimited:77 {status:429 RATE_LIMITED} + X-RateLimit-Remaining 0:80 + Retry-After 83 + Reset 81-93
             └─ YES → X-RateLimit-Remaining:68 + Reset:69 → chain → SecurityConfig Jwt/API-key auth → Controller
             JWT Bearer (apiKey null:56) → bypass limiter

States: CLOSED (count failures over sliding 10:223 until 5:224) ─50%→ OPEN 5s:228 ─► HALF_OPEN 2 probes:229 ─success→ CLOSED
        Bulkhead: threadPool core1 max2 queue5:251 full → BulkheadFullException:64 → 429-like domain failure
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/service/SimulatedPaymentGateway.java` | `32-36` | Gateway resilience | `@Component 32 RESILIENCE_INSTANCE paymentGateway 36` registries ctor `45-50` |
| `SimulatedPaymentGateway.java` | `54-67` | Composition | `bulkhead 57 → breaker58 → retry59 → attempt74 join60 translate84` |
| `SimulatedPaymentGateway.java` | `74-81` | Provider hook | `protected attempt:74 ThreadLocalRandom 10% 75 → PaymentFailedException77 log warn76` override for chaos |
| `SimulatedPaymentGateway.java` | `84-92` | Normalise | `translate:84 CallNotPermitted/BulkheadFull → PaymentFailedException` domain |
| `src/main/java/com/company/orderapi/security/ApiKeyRateLimiterFilter.java` | `35-47` | Rate limiter | `OncePerRequestFilter 35 RATE_LIMITER_NAME apiKey37 template46 per-key bucket62` |
| `ApiKeyRateLimiterFilter.java` | `50-75` | Filter gate | `doFilterInternal50 X-API-Key38 bucketName74 acquirePermission63 429 write77 filterChain70` |
| `ApiKeyRateLimiterFilter.java` | `77-95` | 429 contract | `writeRateLimited77 status429:79 Remaining0:80 Reset81 Retry-After83 JSON87-88 resetEpoch92-94` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `77,112,125` | Tx boundary | `@Retryable77 optimisticLock, placeOrder charge + Outbox pending125 atomic` |
| `src/main/resources/application.yml` | `212-253` | Config | `circuitbreaker 220-229 sliding10 min5 rate50 slow2s open5s half2; retry 230-238 3×200×2; ratelimiter 239-246 1000/min; bulkhead 247-253 core1 max2 queue5` |
| `pom.xml` | `174-183` | Deps | `spring-boot-starter-aop:174-178 resilience4j-spring-boot3 2.2.0:180-183` |
| `src/test/java/.../RateLimitIntegrationTest.java` | — | Proof | Shrinks window `limit-for-period` to trigger `429` deterministically |

```java
// SimulatedPaymentGateway.java:36,54-67,74 — instance + composition + chaos hook
public static final String RESILIENCE_INSTANCE="paymentGateway";
public void charge(BigDecimal amount){
  try{ bulkhead.executeRunnable(()->circuitBreaker.executeRunnable(()->retry.executeRunnable(()->attempt(amount)))).toCompletableFuture().join(); }
  catch(CompletionException e){ throw translate(e.getCause()); } catch(RuntimeException e){ throw translate(e);} }
protected void attempt(BigDecimal amount){ if(ThreadLocalRandom.current().nextDouble()<0.10) throw new PaymentFailedException("declined"); }
// ApiKeyRateLimiterFilter.java:62,77
RateLimiter limiter=rateLimiterRegistry.rateLimiter(bucketName(apiKey), template);
if(!limiter.acquirePermission()){ writeRateLimited(response, limiter); return; }
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Wiring present
grep -n "RESILIENCE_INSTANCE\|CircuitBreaker\|RetryRegistry\|ThreadPoolBulkhead\|RateLimiterRegistry" \
  src/main/java/com/company/orderapi/domain/service/SimulatedPaymentGateway.java src/main/java/com/company/orderapi/security/ApiKeyRateLimiterFilter.java | head -n 20
grep -n "resilience4j\." src/main/resources/application.yml | head -n 20
grep -n "resilience4j-spring-boot3" pom.xml  # 180-183 2.2.0

# Run with defaults
./mvnw test -Dtest=RateLimitIntegrationTest,OrderServiceTest 2>&1 | tail -n 30
./mvnw spring-boot:run & sleep 15; curl -s http://localhost:8080/actuator/health | jq .; kill %1

# Trigger a payment (10% may decline then retry — expect success within 3 attempts)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '{orderNumber,status,paymentStatus}'

# Observe breaker: force failures by lowering threshold temporarily
#   resilience4j.circuitbreaker.instances.paymentGateway.failure-rate-threshold=10  (open quickly)
#   curl loop 10× while chaos override in test subclass throws PaymentFailedException each attempt → breaker OPEN → next charge => CallNotPermitted → translate 429-like PaymentFailedException fast

# Rate limit manual check (lower limit for demo: set in test profile)
for i in $(seq 1 5); do curl -s -i http://localhost:8080/api/v1/orders -H "X-API-Key: demo-key-1" | head -n 8 | grep -E "HTTP|RateLimit|Retry-After"; done
# After burst > 1000/min (lower to 5 in test profile): expect HTTP/1.1 429 + X-RateLimit-Remaining: 0 + Retry-After: 60 + body {"status":429,"code":"RATE_LIMITED"}

# Metrics
curl -s http://localhost:8080/actuator/prometheus | grep -E "resilience4j_circuitbreaker|resilience4j_ratelimiter|resilience4j_bulkhead|resilience4j_retry" | head -n 30
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls 2>/dev/null | jq . 2>/dev/null | head -n 20
```

```java
// Chaos test pattern — override attempt to drive breaker deterministically
static class AlwaysFailGateway extends SimulatedPaymentGateway{
  AlwaysFailGateway(CircuitBreakerRegistry cb, RetryRegistry r, ThreadPoolBulkheadRegistry b){ super(cb,r,b); }
  @Override protected void attempt(BigDecimal amount){ throw new PaymentFailedException("chaos"); }
}
// 3 retries × fail → 1 breaker failure; 5 such batches → 5 failures /10 window ≥50% → OPEN 5s → next charge CallNotPermitted without calling attempt
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Programmatic composition bulkhead→breaker→retry `54-67` | Explicit ordering visible | Annotation `@CircuitBreaker @Retry @Bulkhead` | Ordering testable, not aspect `Ordered`; chaos hook `attempt74` isolated | Boilerplate join/translate |
| Breaker outer to retry | Batch 3 retries = 1 breaker count | Retry outer | Avoids `3×` inflation tripping early | Failure latency = retry budget before count |
| Thread-pool bulkhead `core1 max2 queue5` | Isolated pool `ThreadPoolBulkhead` | Semaphore bulkhead | Slow provider does not hold `Hikari10:34` nor caller virtual thread | `join60` blocks caller; CompletionStage overhead |
| Retry only `PaymentFailedException:238` `200×2` | `3× exp` | Retry all + fixed wait | Transient decline self-heals; breaker open not retried | Not retrying `BulkheadFull` (correct: no benefit) |
| Counted window `sliding10 min5 rate50` | `10` counted | Time-based `60s` | QPS-independent; learning journey burst low | Low QPS takes `10` calls to re-evaluate |
| Per-API-key `RateLimiter` filter `35` before auth | Per-key bucket `apiKey-<hash>:74` `1000/min` | Global + post-auth | One noisy `dev-api-key:188` cannot starve another; protects auth cost | JWT bypass `56` needs separate gateway quota |
| `translate:84` normalise | All → `PaymentFailedException` | Leak `CallNotPermitted/BulkheadFull` | API sees single domain exception (`GlobalExceptionHandler`) | Loss of exact 429 vs 503 distinction (intentional) |

---

## 7. How to verify

```bash
# Config present
grep -n "circuitbreaker.instances.paymentGateway\|retry.instances.paymentGateway\|thread-pool-bulkhead\|ratelimiter.instances.apiKey" src/main/resources/application.yml  # 212-253
grep -n "RESILIENCE_INSTANCE\|bulkhead.executeRunnable\|circuitBreaker.executeRunnable\|retry.executeRunnable\|BulkheadFull" src/main/java/com/company/orderapi/domain/service/SimulatedPaymentGateway.java
grep -n "ApiKeyRateLimiterFilter\|RATE_LIMITER_NAME\|acquirePermission\|writeRateLimited\|Too many requests" src/main/java/com/company/orderapi/security/ApiKeyRateLimiterFilter.java  # 35 37 63 77
grep -n "resilience4j-spring-boot3" pom.xml  # 182 2.2.0

# Order flow still atomic (Outbox pending in same tx as charge success)
grep -n "OutboxEntry.pending\|placeOrder\|@Retryable" src/main/java/com/company/orderapi/domain/service/OrderService.java | head

# Tests including rate limit
./mvnw test -Dtest=RateLimitIntegrationTest -Dtest=ApiKeyRateLimiterFilterTest 2>&1 | grep -E "Tests run|FAIL|RateLimit"
./mvnw test -Dtest=OrderServiceTest 2>&1 | grep -E "Tests run|SimulatedPaymentGateway|PaymentFailed"

# Live breaker probe (after running)
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers // .components.db'
curl -s http://localhost:8080/actuator/prometheus | grep -E 'resilience4j_circuitbreaker_state.*paymentGateway|resilience4j_circuitbreaker_calls.*paymentGateway' | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New provider call → inject `CircuitBreakerRegistry/RetryRegistry/ThreadPoolBulkheadRegistry` (`SimulatedPaymentGateway:45-50`), compose `bulkhead→breaker→retry→attempt:57-59` with `translate:84`, config `application.yml:212-253`. Override `protected attempt:74` in tests for deterministic breaker/retry coverage. For admission control, copy `ApiKeyRateLimiterFilter:35` pattern (`RateLimiterRegistry:40`, per-key `bucketName hash:74`, `timeout 0s`) before your auth filter; distinguish machine `X-API-Key:188` from `JWT` bypass `56`.
- **Operate:** Scrape `resilience4j_*` via `prometheus:144`; alert `circuitbreaker_state{state="OPEN"} ==1` on `paymentGateway` or `rateLimiterAvailable==0` burst. `breaker OPEN` `5s:228` self-heals; `HALF_OPEN 2 probes:229` validates provider. `bulkhead queue5:251` depth signals PSP saturation before DB `Hikari10:34` exhausts. Rolling deploy with `virtual threads:24` keeps tail latency flat despite provider `2s` stalls because isolation threads absorb them.
- **Interview:** "PR #29: `SimulatedPaymentGateway.java:32 RESILIENCE paymentGateway:36` explicit `bulkhead57→breaker58→retry59→attempt74 →join60 translate84`. Config `application.yml:220-253` `breaker sliding10 223 min5 224 rate50 225 slow50@2s 226-227 wait5s228 half2 229`, `retry3×200×2 exp 232-235 PaymentFailedEx238`, `bulkhead core1 max2 queue5 250-252` thread-pool isolates `Hikari10:34`, `ApiKeyRateLimiterFilter:35 per hash apiKey-<hash>62,74 template apiKey1000/min239-246 timeout0s 429 X-RateLimit68-69 Retry-After83 reset92`. Chaos via `protected attempt74` override; `OrderService77` `@Retryable` for DB distinct from provider retry."

---

## 9. Interview lens — Q&A

**Q1: Why `bulkhead` outermost and `retry` innermost?**
A: Outer bulkhead rejects on `queue full` (`BulkheadFullException:64`) before burning breaker budget; inner retry absorbs transient before breaker counts — `3` failed attempts = `1` breaker failure (`charge:57-59`, `translate:84`).

**Q2: How does the breaker decide to open?**
A: Counted `slidingWindow 10:223` needs `minimum 5:224`; when `failureRate≥50:225` or `slowRate≥50 at 2s:226-227` → `OPEN wait 5s:228` then `HALF_OPEN 2 probes:229` (`application.yml:220-229`).

**Q3: What does `retry` `200ms ×2` mean operationally?**
A: `max 3:232` gives `200,400,800ms` ≈`1.4s` worst; only `PaymentFailedException:238` retried — `CallNotPermitted`/`BulkheadFull` fail fast (§2.4).

**Q4: Thread-pool vs semaphore bulkhead?**
A: Thread-pool `core1 max2 queue5:250-253` isolates provider latency onto its own threads; semaphore would block `OrderService` virtual thread `24` directly (§2.5).

**Q5: How is rate limiting per-client not global?**
A: `ApiKeyRateLimiterFilter:62 bucketName apiKey-<hash>:73-75` lazily creates `RateLimiter per hash`; `1000/min:244` each, `timeout0s:246` no wait, `429` JSON `87-88` + headers `80-84`.

**Q6: How do you chaos-test the breaker?**
A: Subclass and override `protected attempt:74` to always `throw PaymentFailedException` — drive `5` batches of `3× fail` deterministically to hit `50%` threshold `223-225` (§2.4, §5 snippet).

**Q7: What verifies that overload does not exhaust Hikari?**
A: Bulkhead pool `2:250` caps provider threads; `prometheus:144` `resilience4j_bulkhead_available` + `Hikari 34 HikariPool` pending stays flat during PSP `2s` slow-call spike (§2.10-2.11).

---

## 10. Honest limits & next step → PR #30

`10% FAILURE_RATE:39` is synthetic — real PSP error budget needs `register health` and latency histogram. `5s OPEN:228` and `queue 5:251` are learning defaults, not load-tested: production should back with SLO (`p99 <500ms`) and autoscale `HPA`. `translate:84` conflates `CallNotPermitted` (circuit open) and `BulkheadFull` (isolation full) into `PaymentFailedException` — callers cannot distinguish `503` try-later from `429` backpressure. No `TimeLimiter` wrapping (provider timeout via socket, not Resilience4j) — pair `spring.threads.virtual:24` timeout with PSP client timeout. Next PR replaces `bulkhead` `ThreadPool` assumptions with far more concurrent `virtual threads:24` and `Redisson` distributed locks for hot `productId`.

See [`30-virtual-threads-and-concurrency.md`](./30-virtual-threads-and-concurrency.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Isolate provider stall from DB pool | `ThreadPoolBulkhead` | `SimulatedPaymentGateway.java:43,50,57,250-253` | `core1 max2 queue5` → `BulkheadFullException:64` not counted by breaker |
| Absorb transient decline | `Retry 3× exp` | `SimulatedPaymentGateway.java:42,49,59,232-238` | `200ms×2 PaymentFailedEx` only |
| Stop hammering sick PSP | `CircuitBreaker` | `SimulatedPaymentGateway.java:41,48,58,220-229` | `sliding10 min5 rate50 slow50@2s wait5s half2` |
| Per-client admission | `RateLimiterFilter` | `ApiKeyRateLimiterFilter.java:35,62,74,239-246` | `apiKey-<hash> 1000/min 429 Retry-After83` |
| Deterministic tests | `protected attempt` hook | `SimulatedPaymentGateway.java:74` | Subclass chaos without `ThreadLocalRandom` |
| Observe resilience | `Micrometer prometheus` | `application.yml:144 management:139` | `resilience4j_*` series on `prometheus:144` |
