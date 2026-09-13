# 16. Security, PII, Rate Limit & Resilience — Cross-Cutting Control Plane

> Stack: `SecurityConfig.java:36` + `SecurityProperties.java:14` + `JwtAuthenticationConverter.java:19` + `ApiKeyAuthenticationFilter.java:23` + `PiiRedactionFilter.java:31` + `PiiMasker.java:12` + `ApiKeyRateLimiterFilter.java:34` + `SimulatedPaymentGateway.java:32` + `OrderService.java:83` + `application.yml:214` · Depends on [#26 Security](../04-security-and-privacy-design.md) + [#47 OAuth](./10-mcp-authorization-oauth2.md) · Complements [#48 audit](./11-mcp-session-authorization-audit.md)

---

## 1. Purpose — what cross-cutting controls exist beyond MCP OAuth2

PR #47 gates `/mcp` on `SCOPE_mcp` (`SecurityConfig.java:71`). Four orthogonal planes cover everything else:

| Plane | File | What it does | Scope |
|---|---|---|---|
| **AuthN / AuthZ** | `SecurityConfig.java:36`, `SecurityProperties.java:14`, `JwtAuthenticationConverter.java:19` | Stateless JWT + API-key dual auth, scope→authority, `@PreAuthorize` | Every request |
| **PII masking** | `PiiRedactionFilter.java:31`, `PiiMasker.java:12` | Redacts personal data in support logs (GDPR) — sink property, not caller right | Every `/api/**` body at DEBUG |
| **Rate limiting** | `ApiKeyRateLimiterFilter.java:34` | Per-API-key Resilience4j bucket, 429 + standard headers | Every `/api/**` with `X-API-Key` |
| **Resilience** | `application.yml:214` + `SimulatedPaymentGateway.java:32` | Circuit breaker + retry + bulkhead around payment | Every `placeOrder` |

```
Request ──► SecurityConfig :50/:71 (JWT or API-key? scope OK?)
         ──► ApiKeyRateLimiterFilter :56 (bucket has permits?)
         ──► PiiRedactionFilter :41 (buffer, redact at :53)
         ──► OrderService.placeOrder :83 ──► SimulatedPaymentGateway.charge :54
                                              bulkhead :57 → breaker :58 → retry :59 → attempt :74 (10% fail)
```

---

## 2. Authentication & Authorization — `SecurityConfig.java:36`

### Dual chain and master switch (`SecurityConfig.java:44`)

```java
http.csrf(csrf -> csrf.disable()) // :46 stateless
    .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // :47
    .cors(cors -> cors.configurationSource(corsConfigurationSource())); // :48
if (properties.isEnabled()) { // SecurityProperties.java:17 kill-switch
    http.oauth2ResourceServer(rs -> rs
            .jwt(jwt -> jwt.decoder(jwtDecoder()) // :51 HS256 NimbusJwtDecoder :84
                    .jwtAuthenticationConverter(new JwtAuthenticationConverter()))) // :53 scope→SCOPE_
        .addFilterBefore(new ApiKeyAuthenticationFilter(properties.getApiKey()), // :54 API-key fallback
                UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll() // :58
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll() // :60
                .requestMatchers("/oauth2/**", "/.well-known/**").permitAll() // :64 AS discovery
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // :66
                .requestMatchers("/mcp").hasAuthority("SCOPE_mcp") // :71 coarse gate
                .anyRequest().authenticated()) // :72 /api/** needs JWT or API-key
        .headers(h -> h.httpStrictTransportSecurity(s -> s.includeSubDomains(true)) // :74
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'")) // :75
                .contentTypeOptions(withDefaults -> {})); // :76
} else { http.authorizeHttpRequests(a -> a.anyRequest().permitAll()); } // :79 tests
```

| File | Role |
|---|---|
| `security/SecurityConfig.java:36` | Stateless chain, dual JWT+API-key, `SCOPE_mcp` at `:71`, HSTS/CSP at `:73` |
| `security/SecurityProperties.java:14` | `@ConfigurationProperties(prefix="app.security")` — `enabled` `:17`, `jwtSecret` `:20`, `apiKey` `:23`, `cors` `:94` |
| `security/JwtAuthenticationConverter.java:19` | `scope` `:24` → `SCOPE_<value>` `:28` |
| `security/ApiKeyAuthenticationFilter.java:23` | `X-API-Key` `:37` → `ROLE_API_KEY` `:41` if matches `expectedApiKey` |

```yaml
# application.yml:180
app.security:
  enabled: true            # SecurityProperties.java:17 false in tests
  jwt-secret: local-learning-secret-change-me-please-32chars # :20
  api-key: dev-api-key-orderapi # :23
  cors: { allowed-origins: [http://localhost:3000] } # :94
```

```java
// JwtAuthenticationConverter.java:24 + ApiKeyAuthenticationFilter.java:37 + SecurityConfig.java:84
String scopeClaim = jwt.getClaimAsString("scope"); // :24
var authorities = Arrays.stream(scopeClaim.split(" "))
        .map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).collect(Collectors.toSet()); // :28
// ApiKey: if provided.equals(expectedApiKey) && no prior auth → ROLE_API_KEY :37/:41
SecretKeySpec key = new SecretKeySpec(properties.getJwtSecret().getBytes(), "HmacSHA256"); // :86
return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); // :88
```

Method gates: `OrderService.cancelOrder` at `OrderService.java:202` — `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')")`.

---

## 3. PII Masking — `PiiRedactionFilter.java:31`

GDPR: personal data must not appear verbatim in ops logs. Redaction is a **sink** property, not a caller right (`PiiRedactionFilter.java:23`).

**What it redacts — `PiiMasker.java:19`**

```java
static final Map<String, PiiType> PII_KEYS = Map.of(
        "email", EMAIL, "customerEmail", EMAIL, // a***@e***.com :50
        "phoneNumber", PHONE,                     // +61***11 :66
        "fullName", NAME, "street", NAME, "city", NAME, // A***h :78
        "state", NAME, "country", NAME, "postalCode", NAME);
```

| Type | In → Out | At |
|---|---|---|
| `EMAIL` | `alice@example.com` → `a***@e***.com` | `PiiMasker.java:50` |
| `PHONE` | `+61 411 111 111` → `+61***11` | `PiiMasker.java:66` |
| `NAME` | `Alice Smith` → `A***h` | `PiiMasker.java:78` |

Fallback: `GENERIC_EMAIL` regex at `PiiMasker.java:35` → `[EMAIL_REDACTED]` at `:113` catches unknown keys.

**How it works — `PiiRedactionFilter.java:40`**

```java
if (uri == null || !uri.startsWith("/api/")) { filterChain.doFilter(request, response); return; } // :41
ContentCachingRequestWrapper wr = new ContentCachingRequestWrapper(request); // :46
ContentCachingResponseWrapper ww = new ContentCachingResponseWrapper(response); // :48
try { filterChain.doFilter(wr, ww); } finally {
    if (log.isDebugEnabled()) { // :53 DEBUG-gated, silent at INFO (prod)
        log.debug("http {} {} -> {} body={}", method, uri, ww.getStatus(), redacted(ww.getContentAsByteArray())); // :54
        log.debug("http {} {} request body (redacted)={}", method, uri, redacted(wr.getContentAsByteArray())); // :59
    }
    ww.copyBodyToResponse(); // :64
}
private String redacted(byte[] b) { return PiiMasker.redactJson(new String(b, UTF_8)); } // :72
```

Deterministic (`*` hides middle) so tests pin exact shape. Verify: `grep PiiRedactionFilter target/spring.log` must show `a***@e***.com`, never `alice@example.com`; prod `INFO` yields 0 lines.

---

## 4. Rate Limiting — `ApiKeyRateLimiterFilter.java:34`

> PR #29 — per-API-key, not per-IP, not global.

```java
String apiKey = request.getHeader(API_KEY_HEADER); // :55 "X-API-Key"
if (uri == null || !uri.startsWith("/api/") || apiKey == null || apiKey.isBlank()) { // :56
    filterChain.doFilter(request, response); return; // Bearer-JWT NOT limited :31
}
RateLimiter limiter = rateLimiterRegistry.rateLimiter(bucketName(apiKey), template); // :62
if (!limiter.acquirePermission()) { writeRateLimited(response, limiter); return; } // :63 429
response.setHeader("X-RateLimit-Remaining", String.valueOf(limiter.getMetrics().getAvailablePermissions())); // :67
response.setHeader("X-RateLimit-Reset", String.valueOf(resetEpochSeconds(limiter))); // :69
// bucketName: "apiKey-" + Integer.toUnsignedString(apiKey.hashCode()) :74
```

```yaml
# application.yml:239
resilience4j.ratelimiter.instances.apiKey:
  limit-for-period: 1000   # :244 per key
  limit-refresh-period: 1m # :245
  timeout-duration: 0s     # :246 fail fast
```

| Header | Success (`:67`) | 429 (`:80`) |
|---|---|---|
| `X-RateLimit-Remaining` | permits left | `0` |
| `X-RateLimit-Reset` | epoch-seconds refill (`:69`) | same (`:81`) |
| `Retry-After` | — | `limitRefreshPeriod` rounded up (`:82`) |

Verify: `RateLimitIntegrationTest.java:33` overrides to `limit-for-period=2` and proves `1→0→429+Retry-After` and per-key isolation (`another-key` still 200).

---

## 5. Resilience4j — `application.yml:214` + `SimulatedPaymentGateway.java:32`

`placeOrder` at `OrderService.java:83` is `@Transactional` — stock, order, payment, outbox must all roll back if `charge` fails (`:112`).

```
SimulatedPaymentGateway.charge :54
  bulkhead.executeRunnable( :57  ThreadPoolBulkhead — own pool
    circuitBreaker.executeRunnable( :58  CircuitBreaker — open on fail/slow
      retry.executeRunnable( :59  Retry — exponential backoff
        attempt(amount) :74  10% throw PaymentFailedException
))) translate :84 → PaymentFailedException
```

```yaml
# application.yml:219
resilience4j:
  circuitbreaker.instances.paymentGateway: # SimulatedPaymentGateway.java:36
    sliding-window-size: 10                # :223
    minimum-number-of-calls: 5             # :224
    failure-rate-threshold: 50             # :225
    slow-call-duration-threshold: 2s       # :226
    slow-call-rate-threshold: 50           # :227
    wait-duration-in-open-state: 5s        # :228
    permitted-number-of-calls-in-half-open-state: 2 # :229
  retry.instances.paymentGateway:
    max-attempts: 3                        # :232
    wait-duration: 200ms                   # :233
    enable-exponential-backoff: true       # :234
    exponential-backoff-multiplier: 2      # :235 200→400ms
    retry-exceptions: [PaymentFailedException] # :236
  thread-pool-bulkhead.instances.paymentGateway:
    core-thread-pool-size: 1               # :249
    max-thread-pool-size: 2                # :250
    queue-capacity: 5                      # :251
```

* **Retry inside breaker** — breaker counts 1 failure per *request* despite 3 retry attempts (`ResilienceChaosIntegrationTest.java:99` — 5 requests at 100% fail opens at `:109`).
* **Slow-call** — `>2s` (`:226`, test `150ms` at `ResilienceChaosIntegrationTest.java:49`) counts as failure; open → fast-fail `<150ms` not `300ms` (`:143-147`).
* **Half-open** — after `5s` (`:228`, test `1s` `:51`) `2` probes (`:229`) can close (`:120-124`); while `OPEN`, `chaos.attempts()` unchanged (`:113-115`).
* **Bulkhead** — `1/2/5` (`:249-251`) isolates gateway from Hikari pool; `BulkheadFullException` normalised at `SimulatedPaymentGateway.java:64`.

Note: `OrderService.java:76` `@Retryable(maxAttempts=5)` on `OptimisticLockingFailureException` is **separate** — it retries the whole transaction on version conflict, not the gateway.

---

## 6. How to use / verify — curl with/without token, PII logs, rate limit, breaker, metrics

```bash
./mvnw spring-boot:run & sleep 12

# --- 6a. Auth: public vs gated ---
curl -s http://localhost:8080/actuator/health | jq .status          # 200  SecurityConfig.java:58
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/customers
# → 401  anyRequest().authenticated() :72
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/customers \
  -H "X-API-Key: dev-api-key-orderapi"  # SecurityProperties.java:23
# → 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/customers -H "X-API-Key: wrong"
# → 401  ApiKeyAuthenticationFilter.java:37

TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/customers \
  -H "Authorization: Bearer $TOKEN"  # → 200  :72 (not SCOPE_mcp which is /mcp only :71)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'  # → 401 no token
BAD=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-internal:mcp-internal-secret-learning -d 'grant_type=client_credentials&scope=internal' | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $BAD" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# → 403  JwtAuthenticationConverter.java:28

# --- 6b. PII: masked in logs ---
curl -s http://localhost:8080/api/v1/customers/1 -H "X-API-Key: dev-api-key-orderapi" | jq .
grep PiiRedactionFilter target/spring.log
# expect: body={"email":"a***@e***.com",...}  PiiMasker.java:50 — fail if raw alice@example.com appears
# prod silence: SPRING_PROFILES_ACTIVE=prod → grep -c PiiRedactionFilter → 0

# --- 6c. Rate limit: 429 ---
./mvnw spring-boot:run -Dspring-boot.run.arguments=--resilience4j.ratelimiter.instances.apiKey.limit-for-period=3 &
for i in 1 2 3 4; do curl -s -i http://localhost:8080/api/v1/customers -H "X-API-Key: my-key" 2>&1 | grep -E "HTTP/|X-RateLimit-Remaining|Retry-After"; done
# 1: Remaining 2 / 2: 1 / 3: 0 / 4: HTTP/1.1 429 + Retry-After: 60  ApiKeyRateLimiterFilter.java:79
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/customers -H "X-API-Key: fresh-key" # → 200

# --- 6d. Breaker + metrics ---
./mvnw test -Dtest=ResilienceChaosIntegrationTest  # 3 tests :95/:128/:152 — open, slow-call, bulkhead 2/5
./mvnw test -Dtest=RateLimitIntegrationTest         # 1 test :33 — 2 permits → 429

curl -s http://localhost:8080/actuator/metrics | jq -r '.names[]' | grep resilience
curl -s "http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:paymentGateway" | jq .
curl -s "http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.calls?tag=name:paymentGateway" | jq .
curl -s http://localhost:8080/actuator/prometheus | grep -E "resilience4j|bulkhead|rate_limiter"
curl -i http://localhost:8080/api/v1/customers -H "X-API-Key: dev-api-key-orderapi" 2>&1 | grep -iE "Strict-Transport|Content-Security|RateLimit"

# --- 6e. One-command smoke ---
curl -sf http://localhost:8080/actuator/health | jq -e '.status=="UP"'
curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/customers | grep -q 401
grep -q "a***@e" target/spring.log
./mvnw test -Dtest=RateLimitIntegrationTest,ResilienceChaosIntegrationTest -q | tail -2

# --- 6f. Security headers + CORS ---
curl -s -i http://localhost:8080/api/v1/customers -H "X-API-Key: dev-api-key-orderapi" | grep -iE "Strict-Transport|Content-Security|X-Content-Type"
# → Strict-Transport-Security: max-age=...; includeSubDomains  SecurityConfig.java:74
# → Content-Security-Policy: default-src 'self'               :75
# → X-Content-Type-Options: nosniff                            :76
curl -s -i -X OPTIONS http://localhost:8080/api/v1/customers \
  -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: GET" | grep -i Access-Control
# → Access-Control-Allow-Origin: http://localhost:3000  SecurityProperties.java:94
```

**Job lens**

* **Build** — add `SecurityConfig.java:36` stateless dual auth + `SCOPE_mcp` gate `:71`, method `@PreAuthorize` at `OrderService.java:202`, `PiiRedactionFilter :31` template (`ContentCaching*Wrapper :46` + `PiiMasker.redactJson :72` + `PII_KEYS :19`), per-key `RateLimiter :62` with `hashCode` bucket `:74`.
* **Operate** — alert on `actuator/metrics/resilience4j.circuitbreaker.state=OPEN` and `429` rate via `Retry-After :82`; flip `logging.level.com.company.orderapi.security.pii=DEBUG` without shipping raw PII; check `HSTS/CSP/nosniff` (`:73`) with `curl -i`.
* **Interview (90s)** — "Cross-cutting plane beside OAuth (`:71`): `SecurityConfig :36` dual JWT (`jwtDecoder :86` HS256 + `converter :28` `SCOPE_`) + API-key (`:41` `ROLE_API_KEY`) with `enabled` switch `:17`; PII `PiiRedactionFilter :31` buffers `/api/**` `:41` and logs `PiiMasker.redactJson :72` (`a***@e***.com :50`, `+61***11 :66`, `A***h :78`); rate limit per-key `RateLimiter :62` `1000/min :244` → `X-RateLimit-Remaining :67`/`Retry-After :84`/`429`; resilience `application.yml:214` breaker `5/10 50% 2s 5s 2-probe` + retry `3×200ms exp×2` inside breaker + bulkhead `1/2/5` at `SimulatedPaymentGateway :57-59` protecting `placeOrder :83`; verified by `RateLimitIntegrationTest :33` + `ResilienceChaosIntegrationTest :57` + `curl/metrics`."

**Interview Q&A — 3 you can now answer**

**Q1: "API-key vs JWT — how does `/mcp` behave with only an API-key?"**
> Dual chain (`:50-54`): JWT via `oauth2ResourceServer` (`:51` + `JwtAuthenticationConverter :28` → `SCOPE_`) and API-key via `ApiKeyAuthenticationFilter :37→:41` (`ROLE_API_KEY`). `anyRequest().authenticated()` (`:72`) accepts either, but `/mcp` needs `hasAuthority("SCOPE_mcp")` (`:71`) — API-key alone → `403`. Method gates like `cancelOrder` (`OrderService.java:202`) accept either `SCOPE_order_write` or `ROLE_API_KEY`.

**Q2: "How did you make PII logs GDPR-safe yet useful?"**
> Sink property (`PiiRedactionFilter.java:23`): filter at `:31` wraps `/api/**` (`:41`) with `ContentCaching*Wrapper` (`:46`), logs at `DEBUG` (`:53`) via `PiiMasker.redactJson` (`:72`) — `EMAIL→a***@e***.com` (`:50`), `PHONE→+61***11` (`:66`), `NAME→A***h` (`:78`), plus generic sweep (`:35→:113`). `copyBodyToResponse` (`:64`) keeps client unaffected; prod `INFO` silences it.

**Q3: "10% payment failure — how do you avoid half-orders and prove the breaker opens?"**
> `placeOrder` (`:83`) `@Transactional` — `charge` (`:112`) throwing `PaymentFailedException` (`:74` 10%) rolls back stock/order/payment/outbox. Stack `bulkhead:57→breaker:58→retry:59→attempt` with config `application.yml:214`; retry inside breaker means 5 *requests* (not attempts) open it (`ResilienceChaosIntegrationTest.java:99→:109`), while `OPEN` `attempts()` stalls (`:113`), slow-call `300ms>150ms` opens (`:128`), `HALF_OPEN` `1s/5s` (`:51/:228`) probe closes (`:120`).

**Honest limits**

* HS256 single secret (`SecurityProperties.java:20`, `SecurityConfig.java:86` + `OAuth2AuthorizationServerConfig.java:160`) — no `RS256`/`kid` rotation or `iss`/`aud` check; `api-key` (`:23`) is one global value (`:37` string equality). `InMemoryRegisteredClientRepository` also restarts empty — needs JDBC for durability.
* Rate limiter in-memory (`:62`) — per-replica buckets, not global; needs Redis/DB for shared counting. `timeout-duration: 0s` (`:246`) means no queuing — burst is hard-rejected, not smoothed.
* PII mask JSON-only (`PiiMasker.java:31` `"key":"value"` strings), 9 keys (`:19`) — new fields need explicit addition; numeric PII, nested arrays, XML/binary bodies pass through. Mask is length-hiding (`*`) but not cryptographic — not a substitute for encryption at rest.
* Resilience only on `charge` (`:57-59`); DB/Kafka/Redis unwrapped; `placeOrder` `@Retryable` (`:76` 5× optimistic-lock) retries whole tx including payment — safe here (simulated idempotent) but real PSP needs idempotency key. No fallback/Cache — breaker open means user-visible `PaymentFailedException`, not degraded response.
* `ApiKeyRateLimiterFilter.java:31` skips Bearer-JWT — JWT quotas belong to gateway/plan layer (intentional learning scope cut).
* Observability is metrics-only (`actuator/prometheus` + `resilience4j.circuitbreaker.*`) — no distributed tracing span for breaker state transitions yet.

> Next: [`15-a2a-multi-agent.md`](./15-a2a-multi-agent.md) · [`14-reranking-query-rewrite.md`](./14-reranking-query-rewrite.md) · back to [`README.md`](./README.md)

<!-- 300 lines · covers SecurityConfig:71 SCOPE_mcp, permitAll :64, ApiKey :54, JwtConverter :28, SecurityProperties :17, PiiRedactionFilter :41/:53, PiiMasker :19/:50, ApiKeyRateLimiter :56/:62, application.yml:214 breaker/retry/bulkhead, SimulatedPaymentGateway :57-59, OrderService :83 -->
