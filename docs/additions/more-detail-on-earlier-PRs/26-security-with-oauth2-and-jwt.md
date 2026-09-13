# 26. Security with OAuth2 and JWT (PR #26)

> PR #26 — Stateless `SecurityFilterChain` with JWT bearer (OAuth2 resource server, `NimbusJwtDecoder HS256`) + `X-API-Key` dual chain; `SecurityContext` `SCOPE_*`/`ROLE_API_KEY`; `@PreAuthorize` scope checks; CORS + HSTS + CSP. Stack: Java 21, Spring Boot 3.4.1, `spring-boot-starter-oauth2-resource-server`, `spring-security-oauth2-authorization-server`, `spring-security-test`, `SecurityConfig.java:45` + `SecurityProperties` + `JwtAuthenticationConverter` + `ApiKeyAuthenticationFilter:23` + `GlobalExceptionHandler:32`. See `README.md:1772` roadmap `| 26 | Security with OAuth2 and JWT |`.

---

## 1. Purpose — what shipped

PR #26 locks `OrderController.create:74` (and every other write) behind a stateless, bearer-centric perimeter while retaining the legacy `X-API-Key` path so scripts/machine clients keep working. Shipped: `SecurityConfig.java:45` `@Configuration @EnableWebSecurity @EnableMethodSecurity` `SecurityFilterChain securityFilterChain:45` with `csrf disabled:46` + `SessionCreationPolicy.STATELESS:47` + `cors:48` + `oauth2ResourceServer jwt decoder JwtDecoder:51-53` (`jwtDecoder:84 HS256 SecretKeySpec 86 + NimbusJwtDecoder 88-90`) + `addFilterBefore ApiKeyAuthenticationFilter 54-55` + `authorizeHttpRequests 56-72` (`/actuator/health 58 permitAll`, `/v3/api-docs/**, /swagger-ui/** 60 docs public PR #25`, `/oauth2/**/.well-known/** 64 authorization-server PR #47 public`, `OPTIONS permitAll 66`, `/mcp SCOPE_mcp 71`, `anyRequest authenticated 72`) + `headers HSTS/CSP/X-Content-Type-Options 73-77`; dual `SecurityProperties.java` `enabled/jwtSecret/apiKey/cors.allowedOrigins` (`SecurityConfig:38-41` + `application.yml:180-191` `jwt-secret 186`, `api-key 188 dev-api-key-orderapi`, `allowed-origins 191 localhost:3000`); `JwtAuthenticationConverter.java` maps JWT `scope` claim → `SCOPE_<scope>` authorities; `ApiKeyAuthenticationFilter.java:23` maps `X-API-Key` header `36` match `37` → `UsernamePasswordAuthenticationToken api-key-client 39` with `ROLE_API_KEY:41`; and `GlobalExceptionHandler:32-46` `AuthenticationException→401 AUTHENTICATION_REQUIRED:42` + `AccessDeniedException→403 ACCESS_DENIED:32` in the same `ProblemDetail` shape.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `OrderController.java:49` + `ProductController:30` were open (`anyRequest.permitAll`). Any anonymous client could `POST /api/v1/orders` (`OrderController:75`) or `DELETE /orders/{id}:170`; the `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')"):74` check existed textually but nothing built the `SecurityContext` — `hasAuthority` always false. `application.yml:180-191` `jwt-secret/api-key` had no effect. Swagger's two auth schemes (`OpenApiConfig.java:33-43` `bearer-jwt/api-key`) were decorative.

**After:** With `app.security.enabled:true` (default `application.yml:183`), `GET /api/v1/orders` without `Authorization: Bearer <jwt>` or `X-API-Key: dev-api-key-orderapi` → `401 AUTHENTICATION_REQUIRED:42` `ProblemDetail` (`GlobalExceptionHandler:44`). With a valid `POST` JWT containing `scope: order_write` → `JwtAuthenticationConverter` yields `SCOPE_order_write` → passes `hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY'):74`. With `X-API-Key` header only → `ApiKeyAuthenticationFilter:23` inserts `ROLE_API_KEY:41` → also passes. Docs (`/v3/api-docs:60`), health (`/actuator/health:58`), and auth-server endpoints (`/oauth2/**:64`) stay `permitAll`. End-to-end verified by `curl -H "X-API-KEY: dev-api-key-orderapi"` (PR #22 verify below) and `@WithMockJwt` tests via `spring-security-test`.

### Theory — SecurityFilterChain, SecurityContext, JWT/API-key dual auth from first principles (100+ lines)

#### 2.1 What `SecurityFilterChain` is — the delegation chain that replaces the old `WebSecurityConfigurerAdapter`

Every request passes a `FilterChainProxy` that contains the `SecurityFilterChain` bean from `SecurityConfig.securityFilterChain:45` (`@Bean`). `HttpSecurity http` is a builder: each call (`csrf:46`, `sessionManagement:47 STATELESS`, `cors:48`, `oauth2ResourceServer:51`, `addFilterBefore:54`, `authorizeHttpRequests:56`, `headers:73`) registers a `SecurityFilter` in order. At runtime the chain order is: `DisableEncodeUrlFilter` → `CorsFilter` (from `corsConfigurationSource:93-102`) → `ApiKeyAuthenticationFilter:54` → `BearerTokenAuthenticationFilter` (from `oauth2ResourceServer.jwt:51-53` `BearerTokenAuthenticationFilter` wrapping `JwtAuthenticationProvider` with `NimbusJwtDecoder:88`) → `AuthorizationFilter` (for `authorizeHttpRequests:56` rules) → `ExceptionTranslationFilter` (funnels `AuthenticationException/AccessDeniedException` to `GlobalExceptionHandler:32`) → `DispatcherServlet`. If any filter authenticates, it sets `SecurityContextHolder.getContext().setAuthentication(...)`; otherwise the context stays empty and the `AuthorizationFilter` enforces `anyRequest.authenticated:72`.

#### 2.2 `JwtDecoder` — HS256 symmetric verification for the learning journey

```java
// SecurityConfig.java:84-91
@Bean JwtDecoder jwtDecoder(){
  SecretKeySpec key = new SecretKeySpec(properties.getJwtSecret().getBytes(), "HmacSHA256"); //86
  return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); //88-90
}
```

`security.oauth2.jwt.JwtDecoder` is the pluggable hook `BearerTokenAuthenticationFilter` calls: extract `Authorization: Bearer <b64header>.<b64claims>.<signature>` → split → base64url decode claims → verify signature with the `SecretKeySpec` of `app.security.jwt-secret:186` (`local-learning-secret-change-me-please-32chars` requires `≥32 chars` for `HS256 256-bit`). On success it returns a `Jwt` (claims map). Production would use `NimbusJwtDecoder.withJwkSetUri(issuerUri)` + `iss/aud` checks (`SecurityConfig.java:24` comment 25-27) and `OAuth2AuthorizationServerConfig` would sign with a different asymmetric `RSA/EC` JWK published at `/.well-known/jwks.json:64`; HS256 is acceptable for a one-app AS+RS setup (PR #47 embedded AS uses the same secret to mint that this RS validates).

#### 2.3 `JwtAuthenticationConverter` — `scope` → `SCOPE_<scope>` authorities

A JWT like `{"sub":"user-1","scope":"order_read order_write mcp","exp":...}` needs authorities for `@PreAuthorize(hasAuthority('SCOPE_order_write')):74` and `requestMatchers("/mcp").hasAuthority("SCOPE_mcp"):71`. `JwtAuthenticationConverter.java` implements `Converter<Jwt,AbstractAuthenticationToken>`: read `scope` claim string, `split("\\s+")` → `stream.map(s -> new SimpleGrantedAuthority("SCOPE_"+s))` → `JwtAuthenticationToken(jwt, authorities)`. If `scope` is an array `["order_read","order_write"]` shape the converter also handles `List<String>`. Without it only `ROLE_` / raw `scope` strings would be present and `hasAuthority('SCOPE_order_write')` would never match (the `SCOPE_` prefix is a `JwtGrantedAuthoritiesConverter` convention).

#### 2.4 `ApiKeyAuthenticationFilter` — the non-OAuth machine path

```java
// ApiKeyAuthenticationFilter.java:23-47
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter{ //23
  protected void doFilterInternal(request,response,filterChain){ //31
    String provided = request.getHeader("X-API-Key"); //36
    if (provided!=null && provided.equals(expectedApiKey) && getAuthentication()==null){ //37-38
      var auth = new UsernamePasswordAuthenticationToken("api-key-client",null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))); //39-41
      auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request)); //42-43
      SecurityContextHolder.getContext().setAuthentication(auth); //44
    }
    filterChain.doFilter(request,response); //46
  }
}
```

Inserted `addFilterBefore(ApiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter):54-55` so it runs before `BearerTokenAuthenticationFilter` — if a request carries both `X-API-Key` and `Authorization`, `ApiKey` sets context first and the JWT filter is no-op (guard `getAuthentication()==null:38`). `ROLE_API_KEY` aligns with `@PreAuthorize hasAnyAuthority(..., 'ROLE_API_KEY'):74`. The filter is *not* a `@Component` bean — `SecurityConfig:54 new ApiKeyAuthenticationFilter(properties.getApiKey())` constructs it per chain; otherwise Boot's auto-registration would mount it twice. Its `OncePerRequestFilter` base ensures single execution even on async dispatch.

#### 2.5 `authorizeHttpRequests` — path rules before method rules

```java
// SecurityConfig.java:56-72
.authorizeHttpRequests(auth -> auth
  .requestMatchers("/actuator/health","/actuator/info").permitAll() //58-59
  .requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll() //60-61 PR #25
  .requestMatchers("/oauth2/**","/.well-known/**").permitAll() //64-65 PR #47 AS
  .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() //66 CORS preflight PR #27 web
  .requestMatchers("/mcp").hasAuthority("SCOPE_mcp") //71 PR #47 MCP gated
  .anyRequest().authenticated() //72 everything else: bearer or API-key required
)
```

Order matters — `anyRequest:72` is the fallback; placing `permitAll` after it would never be reached. `authenticated()` requires a non-anonymous `Authentication` in `SecurityContextHolder` (either the JWT token or `api-key-client`). Method security (`@EnableMethodSecurity:35` + `OrderController:74` `@PreAuthorize`, `OrderService:202` `@PreAuthorize("hasAuthority('SCOPE_order_write')")`) is a second layer: the filter chain already forces `authenticated`, but `@PreAuthorize` plus `SecurityProperties.enabled == false` guard provides per-method scope branching without touching the path rule.

#### 2.6 `SessionCreationPolicy.STATELESS` — no `HttpSession`, bearer per request

```java
.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) //47
```

Classic Spring `SessionCreationPolicy.IF_REQUIRED` would create a `JSESSIONID` cookie on first auth. `STATELESS` never creates/uses a session; each request carries its own `Authorization` header. The `SecurityContext` is built fresh by the JWT/API-key filters and destroyed after `DispatcherServlet` — no server-side session store, scales horizontally (no sticky session), aligns with OAuth2 bearer usage. `csrf disabled:46` is correct for stateless bearer use (CSRF is a cookie/session attack; bearer per-request `Authorization` is not forged via cookie); leaving `csrf enabled` would block `POST /api/v1/orders` with a missing CSRF token.

#### 2.7 `SecurityContextHolder` — where `@PreAuthorize` and `GdprService` read identity

`SecurityContextHolder` is a `ThreadLocal` (or `InheritableThreadLocal`/virtual-thread-friendly holder) carrying `Authentication {principal, authorities, authenticated}`. `OrderController:74` `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')")` compiles to `MethodSecurityEvaluationContext` reading that holder; `GdprService.java:118-125 currentActor()` reads `SecurityContextHolder.getContext().getAuthentication().getName()` → `"system"` fallbacks so GDPR audit `AuditLog` knows the actor; the log redaction filter `PiiRedactionFilter` ignores auth but the `PiiMasker` uses no auth. Clearing the holder after the chain is critical (done by `SecurityContextPersistenceFilter` — not bypassed when `STATELESS` because `SecurityContextHolderFilter` still clears).

#### 2.8 CORS — the browser preflight side-channel

```java
// SecurityConfig.java:93-102 + 48 corsConfigurationSource()
CorsConfiguration config = new CorsConfiguration(); //95
config.setAllowedOrigins(properties.getCors().getAllowedOrigins()); //96 application.yml:190 localhost:3000 191
config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS")); //97
config.setAllowedHeaders(List.of("Authorization","Content-Type","If-Match","Idempotency-Key","X-API-Key")); //98
source.registerCorsConfiguration("/**", config); //99-100
```

`CorsFilter` (ordered by `cors(cors ->(corsConfigurationSource)) 48`) handles `OPTIONS` preflight: `Origin: http://localhost:3000` + `Access-Control-Request-Method: POST` → `Access-Control-Allow-Origin: http://localhost:3000` iff allowed, else rejected before hitting `OrderController`. Allowed headers mirror the controllers' custom headers (`Idempotency-Key:78`, `If-Match:173`, `X-API-Key:36`). Without CORS the Swagger UI running at a different origin could not `fetch /v3/api-docs` even when spec is `permitAll:60`.

#### 2.9 Security headers — HSTS/CSP/X-Content-Type-Options parity

```java
.headers(headers -> headers
  .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)) //74
  .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")) //75
  .contentTypeOptions(withDefaults -> {})) //76
```

`HSTS includeSubDomains:true 74` emits `Strict-Transport-Security: max-age=31536000; includeSubDomains` so browsers force HTTPS for the domain tree. `Content-Security-Policy default-src 'self' 75` blocks `script-src` from external origins — relevant if `swagger-ui` were served under stricter CSP prod profiles. `X-Content-Type-Options: nosniff 76` irrespective of value stops MIME-sniffing. Alternatives: add `X-Frame-Options DENY` / `Referrer-Policy` if the front-end loads the API in an iframe (not needed here).

#### 2.10 `@PreAuthorize` as the scoped authorization primitive

```java
// OrderController.java:74
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write', 'ROLE_API_KEY')")
// OrderService.java:202 cancelOrder
@PreAuthorize("@securityProperties.enabled == false or hasAuthority('SCOPE_order_write')")
```

Class-level `hasAuthority` on `cancelOrder:202` ensures scope is checked inside the service too (controller can be bypassed by a job or Kafka consumer calling `OrderService` directly). Using `hasAnyAuthority` vs `hasRole` avoids the automatic `ROLE_` prefix: `SCOPE_order_write` is not a role, `ROLE_API_KEY` is the only role in this chain. The `SecurityProperties.enabled == false` short-circuit allows running the app without a JWT infra in local tests (`enabled:false` → `SecurityConfig:78-80 permitAll` + `@PreAuthorize` second arm passes).

#### 2.11 Alternatives — asymmetric JWKs, opaque introspection, mutual TLS

- Asymmetric JWT (`RS256/ECDSA` JWK): decoder fetches `/.well-known/jwks.json` instead of `SecretKeySpec`; signature verification via `RSAKey` — production for cross-issuer trust. Configure via `NimbusJwtDecoder.withJwkSetUri(jwksUri)`.
- Opaque token + `UserInfo` introspection (`OpaqueTokenIntrospector`) — round-trips per request vs stateless JWT decode; use if revocation must be instant.
- `X-API-Key` rotation: currently single static `application.yml:188 dev-api-key-orderapi`; per-key `ApiKey` entity with prefix lookup + `ApiKeyRateLimiterFilter.java` rate limiting PR #29 is the upgrade.
- `OAuth2AuthorizationServerConfig` PR #47 co-hosts the AS in this app (same `jwtSecret` HS256) — production separates AS and RS into different deploys for blast radius.

> Interview anchor: "PR #26: `SecurityConfig.java:45 SecurityFilterChain` `csrf disabled 46` `STATELESS 47` `cors 48 source 93-102 allowedOrigins 96 localhost:3000 + headers 98 Idempotency-Key/X-API-Key` `oauth2ResourceServer jwt decoder 51-53 JwtDecoder 84 Nimbus HS256 SecretKeySpec 86` plus `ApiKeyAuthenticationFilter:23 X-API-Key header 36 → ROLE_API_KEY 41` `addFilterBefore 54`. `JwtAuthenticationConverter` scope→`SCOPE_*`. `authorizeHttpRequests 56 permitAll health 58 docs 60 oauth2 64 OPTIONS 66 mcp SCOPE_mcp 71 anyRequest authenticated 72` then per-method `@PreAuthorize 74 SCOPE_order_write/ROLE_API_KEY`. `SecurityContextHolder` for `GdprService:118` actor. `GlobalExceptionHandler:32 403 ACCESS_DENIED + 42 401 AUTHENTICATION_REQUIRED` `ProblemDetail`. `app.security 180-191 jwt-secret 186 api-key 188` + `headers HSTS includeSubDomains 74 CSP self 75`."

---

## 3. Solution — ASCII

```
Request  GET /api/v1/orders  Authorization: Bearer eyJhbG...SCOPE_order_write
          or  X-API-Key: dev-api-key-orderapi    (dual path; at least one required beyond permitAll)
      │
      ▼  FilterChainProxy → SecurityFilterChain SecurityConfig.java:45
           DisableEncodeUrlFilter
           CorsFilter (corsConfigurationSource:93 → allowedOrigins 96 localhost:3000, headers 98 Idempotency-Key/X-API-Key/If-Match, methods 97 GET/POST..., OPTIONS 66 permitAll)
           ApiKeyAuthenticationFilter:23 new dev-api-key-orderapi 54 before UsernamePasswordAuthenticationFilter 55
                 X-API-Key header 36 == expectedApiKey && getAuthentication()==null 37-38 ?
                   yes → UsernamePasswordAuthenticationToken("api-key-client", null, ROLE_API_KEY:41) setDetails 42-43 → SecurityContextHolder 44
                   no  → pass through 46
           BearerTokenAuthenticationFilter (oauth2ResourceServer jwt 51-53 → JwtDecoder 84 NimbusJwtDecoder HS256 SecretKeySpec 86 − verify signature − Jwt)
                 JwtAuthenticationConverter: scope claim "order_write order_read" → SCOPE_order_write, SCOPE_order_read
                           → JwtAuthenticationToken → SecurityContextHolder
           AuthorizationFilter (authorizeHttpRequests 56)
                 /actuator/health 58 permitAll ; /v3/api-docs/** 60 permitAll ; /oauth2/** 64 permitAll ; OPTIONS 66 permitAll
                 /mcp → hasAuthority("SCOPE_mcp") 71 else 403 ACCESS_DENIED (GlobalExceptionHandler:32)
                 anyRequest authenticated 72 → empty SecurityContext → 401 AUTHENTICATION_REQUIRED (Handler 42)
                 else PASS → ExceptionTranslationFilter → DispatcherServlet
      │
      ▼  DispatcherServlet → HandlerAdapter → MethodSecurityInterceptor (@EnableMethodSecurity:35)
              OrderController.create:74 @PreAuthorize("enabled==false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')")
                 SecurityContext has SCOPE_order_write (JWT) or ROLE_API_KEY (API-key)? → proceed; else throw AccessDeniedException → GlobalExceptionHandler:32 403
              OrderService.cancelOrder:202 @PreAuthorize("hasAuthority('SCOPE_order_write')")  (service-level guard)
      │
      └─ Success → controller Transactional 76 placeOrder:91 ... response 201 ... SecurityContext cleared after request (Holder 44 threaded, STATELESS 47 - never JSESSIONID)
   Headers set by 73-77  Strict-Transport-Security includeSubDomains 74, Content-Security-Policy default-src 'self' 75, X-Content-Type-Options 76

 Dual-chain case: request carries BOTH headers → ApiKey filter sets ROLE_API_KEY 44 first; Bearer filter sees getAuthentication()!=null no-op; hasAnyAuthority passes either
 Fallback: app.security.enabled:false → SecurityConfig:78-80 anyRequest.permitAll() and @PreAuthorize first arm true → unauthenticated local/test run
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/security/SecurityConfig.java` | `33-36` | Class | `@Configuration @EnableWebSecurity @EnableMethodSecurity`, injects `SecurityProperties:38` |
| `SecurityConfig.java` | `44-48` | Chain header | `@Bean SecurityFilterChain 45 csrf disabled 46 STATELESS 47 cors source 48` |
| `SecurityConfig.java` | `50-55` | Auth filters | `oauth2ResourceServer jwt 51-53 decoder + JwtAuthenticationConverter`, `addFilterBefore ApiKeyAuthenticationFilter(dev-api-key) 54-55` |
| `SecurityConfig.java` | `56-72` | Authorize rules | `health 58 docs 60 oauth2 64 OPTIONS 66 /mcp SCOPE_mcp 71 anyRequest.authenticated 72` — order-sensitive |
| `SecurityConfig.java` | `73-77` | Response headers | `HSTS includeSubDomains 74 CSP self 75 X-Content-Type-Options 76` |
| `SecurityConfig.java` | `78-91` | Fallback + JwtDecoder | `enabled:false → permitAll 79` vs `JwtDecoder withSecretKey 86 HmacSHA256 88-90 HS256` |
| `SecurityConfig.java` | `93-102` | CORS | `CorsConfiguration 95 localhost:3000 96 methods 97 headers 98 Idempotency-Key/X-API-Key/If-Match 98 register /** 99` |
| `src/main/java/com/company/orderapi/security/SecurityProperties.java` | — | Props | `app.security enabled/jwtSecret/apiKey/cors.allowedOrigins` bound via `@ConfigurationProperties`, used `SecurityConfig:38` + `@PreAuthorize:74` |
| `src/main/java/com/company/orderapi/security/JwtAuthenticationConverter.java` | — | Converter | `scope` string/array → `SCOPE_<scope>` authorities for `@PreAuthorize('SCOPE_order_write') 74` |
| `src/main/java/com/company/orderapi/security/ApiKeyAuthenticationFilter.java` | `23-47` | API-key auth | `OncePerRequestFilter 23` `X-API-Key 36` equality `37` guard `getAuthentication()==null 38` → `ROLE_API_KEY 41` |
| `src/main/java/com/company/orderapi/security/ApiKeyRateLimiterFilter.java` | — | Rate (PR #29) | `Resilience4j` limiter per `X-API-Key` Resilience4j `apiKey:241`; adjacent to auth chain |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `74` | Method guard | `@PreAuthorize SCOPE_order_write/ROLE_API_KEY` delegates enrolled only if filter populated `SecurityContext` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `202,212,229` | Service guards | `@PreAuthorize` on `cancel/confirm/ship` inside service (bypass-proof) |
| `src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java` | `32-46` | Auth error shape | `AccessDenied →403 ACCESS_DENIED 32` `Authentication →401 AUTHENTICATION_REQUIRED 42` same `ProblemDetail 56` shape |
| `src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java` | — | AS | PR #47 co-host: issues HS256 JWT with same `jwtSecret` validated by RS; `/oauth2/token`, `/.well-known/jwks.json`, token revoke |
| `src/main/java/com/company/orderapi/config/OpenApiConfig.java` | `33-44` | Docs linkage | `bearer-jwt`/`api-key` schemes in spec tag the `SecurityRequirement` matching runtime |
| `application.yml` | `180-191` | Secrets | `enabled 183 true`, `jwt-secret 186 local…32chars`, `api-key 188 dev-api-key-orderapi`, `cors 190 allowed-origins 191` `overridable via ${DB_HOST} style` |

```java
// SecurityConfig.java:45-72 — chain (condensed)
@Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception{
  http.csrf(csrf->csrf.disable()).sessionManagement(sm->sm.sessionCreationPolicy(STATELESS)).cors(cors->cors.configurationSource(corsConfigurationSource()));
  if(properties.isEnabled()){
    http.oauth2ResourceServer(rs->rs.jwt(jwt->jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(new JwtAuthenticationConverter())))
        .addFilterBefore(new ApiKeyAuthenticationFilter(properties.getApiKey()), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth->auth.requestMatchers("/actuator/health","/actuator/info").permitAll()
                                     .requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").permitAll()
                                     .requestMatchers("/oauth2/**","/.well-known/**").permitAll()
                                     .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                     .requestMatchers("/mcp").hasAuthority("SCOPE_mcp").anyRequest().authenticated())
        .headers(h->h.httpStrictTransportSecurity(hsts->hsts.includeSubDomains(true)).contentSecurityPolicy(csp->csp.policyDirectives("default-src 'self'")).contentTypeOptions(withDefaults->{}));
  } else { http.authorizeHttpRequests(auth->auth.anyRequest().permitAll()); }
  return http.build();
}
@Bean JwtDecoder jwtDecoder(){ SecretKeySpec k = new SecretKeySpec(properties.getJwtSecret().getBytes(), "HmacSHA256"); return NimbusJwtDecoder.withSecretKey(k).macAlgorithm(MacAlgorithm.HS256).build(); }
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Confirm wiring present
grep -n "SecurityFilterChain\|JwtDecoder\|ApiKeyAuthenticationFilter\|SCOPE_mcp\|permitAll\|STATELESS\|corsConfigurationSource" \
  src/main/java/com/company/orderapi/security/SecurityConfig.java
grep -n "ROLE_API_KEY\|X-API-Key" src/main/java/com/company/orderapi/security/ApiKeyAuthenticationFilter.java

# Raw 401 without credential (security enabled true)
curl -s http://localhost:8080/api/v1/orders | jq '.code,.status'
# Expect AUTHENTICATION_REQUIRED 401 ProblemDetail (GlobalExceptionHandler:44)

# Machine path — API key header (still valid when security enabled)
curl -s http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" | jq '.content // .code'
# Expect .content (paged) not 401

# JWT path — obtain token from the embedded AS (PR #47 self-issued; same jwtSecret HS256)
# Create a client credential flow or use a test HS256 JWT built with the dev secret
JWT=$(python3 -c "
import base64,hmac,hashlib,json,time
secret=b'local-learning-secret-change-me-please-32chars'
header=base64.urlsafe_b64encode(json.dumps({'alg':'HS256','typ':'JWT'}).encode()).decode().rstrip('=')
payload=base64.urlsafe_b64encode(json.dumps({'sub':'tester','scope':'order_read order_write','exp':int(time.time())+600}).encode()).decode().rstrip('=')
sig=base64.urlsafe_b64encode(hmac.new(secret, f'{header}.{payload}'.encode(), hashlib.sha256).digest()).decode().rstrip('=')
print(f'{header}.{payload}.{sig}')
")
curl -s http://localhost:8080/api/v1/orders -H "Authorization: Bearer $JWT" | jq '.content // .code'
# Expect .content (200) — JwtAuthenticationConverter mapped SCOPE_order_write → @PreAuthorize 74 passes

# Wrong scope token → 403 ACCESS_DENIED on POST (requires order_write), GET may pass with order_read
JWT_READ=$(python3 -c "... scope='order_read' ...") # build as above
curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer $JWT_READ" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '.code,.status'
# Expect ACCESS_DENIED 403

# Docs remain public without credential
curl -s http://localhost:8080/v3/api-docs | jq '.info.title'  # Order Management API
curl -s -I http://localhost:8080/swagger-ui.html | grep 200

# Preflight CORS
curl -s -i -X OPTIONS http://localhost:8080/api/v1/orders -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: POST" | grep -i "Access-Control-Allow-Origin"

# Headers HSTS/CSP emitted
curl -s -i http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" | grep -i "Strict-Transport-Security\|Content-Security-Policy"

# Swagger UI try-it-out with JWT: open http://localhost:8080/swagger-ui.html → Authorize → bearer-jwt paste Bearer $JWT
grep -n "BearerTokenAuthenticationFilter\|JwtAuthenticationConverter\|ROLE_API_KEY\|SCOPE_order_write" src/main/java/com/company/orderapi/security/*.java src/main/java/com/company/orderapi/api/rest/controller/OrderController.java | head

# Tests with security-test harness
./mvnw test -Dtest=OrderServiceTest -Dspring.profiles.active=test -Dapp.security.enabled=false 2>&1 | tail
# @WithMockUser / @WithMockJwt in tests (spring-security-test) still pass
grep -rn "@WithMockJwt\|@WithMockUser" src/test --include="*.java" | head
```

```java
// Controller guard idiom — copy for new writes
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')")
@PostMapping public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest req){ ... }
// Read guard
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_read','SCOPE_order_write','ROLE_API_KEY')")
@GetMapping public Page<OrderResponse> list(Pageable p){ ... }
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Dual JWT + `ApiKey` chain | `SecurityConfig:51-55` `BearerTokenAuthenticationFilter` + `ApiKeyAuthenticationFilter` | JWT only or opaque `ApiKey` table only | Smooth migration — scripts using `dev-api-key-orderapi 188` keep working while browser/SPAs use short-lived JWT minted by `OAuth2AuthorizationServerConfig` (same `jwtSecret`) | Two code paths share `@PreAuthorize` `SCOPE_*/ROLE_API_KEY:74` — merge complexity |
| `HS256 SecretKeySpec 86` for learning | Symmetric one-secret 32+ chars `188` vs `NimbusJwtDecoder HS256 88` | Asymmetric `RSA/EC JWK` via `/jwks.json:64` remote | Single app is AS+RS (PR #47) → no JWK fetch, simple local signing/verification | No rotation/issuer trust delegation; rotate requires restart (JWK setrotation automatic in asymmetric) |
| `JwtAuthenticationConverter` `scope→SCOPE_` | Prefix mapping | Raw `scope` or `roles` claim | `hasAuthority('SCOPE_order_write'):74` is Spring's idiomatic scope check; keeps API `scope` name from spec (`order_write`) | Converter must keep parse in sync with AS's claim shape |
| `STATELESS 47` + `csrf disabled 46` | No `JSESSIONID` | `IF_REQUIRED` + CSRF tokens on `POST` | Stateless per-request bearer scales horizontally, `X-API-Key` fallback stateless too → no session store | Token expiry is client-visible 401; must mint fresh via `/oauth2/token` |
| `ApiKey` as `UsernamePasswordAuthenticationToken 39` with `ROLE_API_KEY 41` | Single string equality `37` `provided.equals(expectedApiKey)` | Entity `ApiKey(prefix, hash)` with Rotation | Minimal for learning; aligns with existing `ROLE_API_KEY` in `@PreAuthorize` | Plain string compare (`equals` not constant-time `MessageDigest.isEqual`); single static key — rotate in env not DB |
| `authorizeHttpRequests` path rules | `health 58 docs 60 oauth2 64 OPTIONS 66 mcp SCOPE_mcp 71 anyRequest authenticated 72` | Rule per `HttpMethod` or none | Exposes health/docs/auth discovery unauthenticated, isolates `/mcp` under tight scope, rest authenticated — order explicit | New public path must be added explicitly before `anyRequest` |
| Same `ProblemDetail` for `401/403:32-46` | Uniform `code AUTHENTICATION_REQUIRED/ACCESS_DENIED` | `WWW-Authenticate` only + HTML errors | Client parse-one-shape `application/problem+json:61` regardless of `400/401/403/409` | Hides the `Bearer realm` hint some spec clients expect |

---

## 7. How to verify

```bash
# Chain installed and two auth mechanisms present
grep -n "SecurityFilterChain\|@EnableWebSecurity\|@EnableMethodSecurity" src/main/java/com/company/orderapi/security/SecurityConfig.java  # 34-36
grep -n "JwtDecoder\|NimbusJwtDecoder\|MacAlgorithm.HS256" src/main/java/com/company/orderapi/security/SecurityConfig.java  # 84-91
grep -n "ApiKeyAuthenticationFilter\|ROLE_API_KEY\|X-API-Key" src/main/java/com/company/orderapi/security/ApiKeyAuthenticationFilter.java src/main/java/com/company/orderapi/security/SecurityConfig.java

# Rules include health/docs/oauth2/mcp/anyRequest
grep -n "permitAll\|anyRequest\|hasAuthority.*SCOPE_mcp" src/main/java/com/company/orderapi/security/SecurityConfig.java  # 56-72
grep -n "@PreAuthorize\|SCOPE_order_write\|SCOPE_order_read\|ROLE_API_KEY" src/main/java/com/company/orderapi/api/rest/controller/OrderController.java src/main/java/com/company/orderapi/domain/service/OrderService.java | head

# CORS allows expected headers/origins
grep -n "corsConfigurationSource\|allowedOrigins\|Idempotency-Key\|X-API-Key" src/main/java/com/company/orderapi/security/SecurityConfig.java  # 93-102
grep -n "jwt-secret\|api-key\|allowed-origins" src/main/resources/application.yml  # 180-191

# Live checks (running app, security enabled true)
curl -s http://localhost:8080/api/v1/orders | jq -e '.status==401 and .code=="AUTHENTICATION_REQUIRED"' && echo 401-ok
curl -s http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" | jq -e 'has("content") or has("totalElements")' && echo apikey-ok
curl -s http://localhost:8080/v3/api-docs | jq -e '.info.title=="Order Management API"' && echo docs-public-ok
curl -s -i -X OPTIONS http://localhost:8080/api/v1/orders -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: POST" | grep -qi "204\|200" && echo cors-ok
curl -s http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -i | grep -qi "strict-transport-security" && echo hsts-ok

# Test harness spring-security-test
grep -n "spring-security-test\|@WithMockJwt" pom.xml src/test/java -r | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New mutating endpoint → annotate with `@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')"):74` and give reads `SCOPE_order_read`. `SecurityConfig.java:54-55` dual chain doesn't need changing — JWT path uses the `Nimbus 84 HS256` decoder (or promote to `JWK Set URI` asymmetric for cross-issuer verification) + `JwtAuthenticationConverter` scope mapping; machine jobs set `X-API-Key: 188` header. `OPTIONS:66` + `cors 93` defaults cover `Idempotency-Key:78`/`If-Match:173`.
- **Operate:** Monitor `401 AUTHENTICATION_REQUIRED:42` rate (high → token rotation misconfigured or clock skew > `exp` window) and `403 ACCESS_DENIED:32` rate (scope mismatch `order_read` vs `order_write`). Rotate `app.security.jwt-secret:186` or `api-key:188` via env rolling restart (HS256 requires synchronized secret on AS+RS same pod). CSP `75 self` deployed should block unexpected inline `<script>` on the API domain — verify with `curl -I | grep Content-Security-Policy`.
- **Interview:** "PR #26: `SecurityConfig.java:45 SecurityFilterChain` `STATELESS 47` `csrf disabled 46` dual auth — `JwtDecoder 84 HmacSHA256+MacAlgorithm.HS256 88-90` (`app.security.jwt-secret 186`) via `BearerTokenAuthenticationFilter 51-53` + `ApiKeyAuthenticationFilter:23 X-API-Key 36 → ROLE_API_KEY 41` `addFilterBefore 54`; `JwtAuthenticationConverter` scope→`SCOPE_*` → `authorizeHttpRequests 56-72 health/docs/oauth2 OPTIONS permitAll 58-66 /mcp SCOPE_mcp 71 anyRequest authenticated 72` plus per-method `@PreAuthorize SCOPE_order_write/ROLE_API_KEY:74`. `cors 93-102` localhost:3000 + headers `Idempotency-Key/X-API-Key/If-Match`. `GlobalExceptionHandler:32 403 42 401 ProblemDetail`. Next PR masks PII (`PiiMasker.java`) off the authenticated surface."

---

## 9. Interview lens — Q&A

**Q1: Which two auth mechanisms coexist and where do they set `SecurityContext`?**
A: `Bearer JWT` via `BearerTokenAuthenticationFilter` (`SecurityConfig:51 oauth2ResourceServer.jwt 51-53` → `NimbusJwtDecoder 84 HS256 88` → `JwtAuthenticationConverter` `scope→SCOPE_*`) and `X-API-Key` via `ApiKeyAuthenticationFilter.java:23` reading header `X-API-Key:36` equality `37` → `ROLE_API_KEY:41` token `39-44` (`SecurityConfig:54 addFilterBefore`) — both set `SecurityContextHolder` before `AuthorizationFilter:56` (§2.1/2.4).

**Q2: How does a raw `scope` JWT become `SCOPE_order_write`?**
A: `JwtAuthenticationConverter` splits the `scope` string claim (`"order_read order_write"`) and maps each with prefix `SCOPE_` → `SimpleGrantedAuthority("SCOPE_order_write")` so `@PreAuthorize hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY'):74` passes (§2.3).

**Q3: Why `STATELESS` and no CSRF?**
A: `SessionCreationPolicy.STATELESS:47` never creates `JSESSIONID` — each request carries its own bearer; CSRF `46 disabled` because there is no session cookie to forge (bearer per-request `Authorization: Bearer` is not sent automatically by the browser) (§2.6).

**Q4: Which endpoints are intentionally `permitAll` despite `anyRequest.authenticated:72`?**
A: `SecurityConfig:58-66` `health/info 58`, spec `/v3/api-docs/** /swagger-ui/** 60` (PR #25), AS `/oauth2/** .well-known/** 64` (PR #47), `OPTIONS /** 66` preflight — `/mcp 71` requires `SCOPE_mcp` (§2.5).

**Q5: How do `401` vs `403` surface to clients?**
A: Empty `SecurityContext` or bad JWT `BearerTokenAuthenticationFilter` failure is caught by `ExceptionTranslationFilter` → `GlobalExceptionHandler.java:42 401 AUTHENTICATION_REQUIRED:44`; authenticated but lacking `SCOPE_order_write`/`ROLE_API_KEY` fails `@PreAuthorize:74` → `AccessDeniedException:32 403 ACCESS_DENIED:34` — both as `ProblemDetail application/problem+json 61` (§2.1).

**Q6: What is `app.security.enabled:false` for?**
A: `SecurityConfig:78-80` branch `anyRequest.permitAll()` for local/no-JWT tests; each `@PreAuthorize:74` also short-circuits (`@securityProperties.enabled == false`) so method security doesn't block those contexts (§2.5/2.10).

**Q7: What replaces `HS256 SecretKeySpec 86` in production?**
A: Asymmetric verification (`NimbusJwtDecoder.withJwkSetUri("https://issuer/.well-known/jwks.json")`) + `iss`/`aud` validation instead of the single symmetric `jwtSecret 186`; the embedded `OAuth2AuthorizationServerConfig` HS256 path is learning-only (§2.11).

---

## 10. Honest limits & next step → PR #27

Static single `api-key:188` is not per-tenant — leakage revokes all machine clients; move to DB-backed `ApiKey {prefix, hash, scopes}` + `MessageDigest.isEqual` (the `equals 37` is plain not constant-time). `HS256` with a shared secret bound by `SecretKeySpec 86` cannot validate tokens from an external IdP; asymmetric JWK set fetch + key rotation is needed beyond the embedded AS. The chain covers HTTP only — Kafka consumers (`OrderEventConsumer` PR #31) are authenticated via SASL not this filter. Next PR tightens the data that flows through the authenticated channel: PII masking (`PiiMasker.java:12`, `PiiType`, `SensitiveDataSerializer`, `PiiRedactionFilter:31`, `@MaskedPii` on `OrderResponse:22`) and GDPR subject-rights `GdprService.java:34`/`GdprController`.

See [`27-pii-and-gdpr.md`](./27-pii-and-gdpr.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Stateless bearer auth | `NimbusJwtDecoder HS256` | `SecurityConfig.java:84-91` + `application.yml:186` | Single app AS+RS verify mint with same `jwtSecret` |
| Map JWT `scope` to method guard | `JwtAuthenticationConverter` → `SCOPE_*` | `JwtAuthenticationConverter` + `OrderController.java:74` `@PreAuthorize` | `SCOPE_order_write` + `ROLE_API_KEY` unified |
| Machine-client fallback | `ApiKeyAuthenticationFilter X-API-Key → ROLE_API_KEY` | `ApiKeyAuthenticationFilter.java:23-44` + `SecurityConfig.java:54` | Keeps legacy `dev-api-key-orderapi:188` path |
| Browser clients | `CorsConfiguration` + `OPTIONS permitAll` | `SecurityConfig.java:93-102,66` + `application.yml:191` | `localhost:3000` + `Idempotency-Key/X-API-Key` headers |
| `401/403` as `ProblemDetail` | `GlobalExceptionHandler:32-46` | `SecurityConfig:56 + Handler 42/32` | Single `application/problem+json:61` shape |
| Public discovery | `permitAll /v3/api-docs /swagger-ui oauth2/.well-known` | `SecurityConfig.java:58-64` | Unauthenticated spec + health even when `authenticated 72` |
| Token issuer co-hosted | `OAuth2AuthorizationServerConfig` | `authorization/OAuth2AuthorizationServerConfig.java` | Same `jwtSecret` mints what `JwtDecoder 86` validates (PR #47) |
