# 10. MCP Authorization — Embedded OAuth 2.1 AS/RS (PR #47)

> PR: [#47 — Embedded OAuth 2.1 Authorization Server + Resource Server for `/mcp`](https://github.com/anomalyco/order-management-api/pull/47) · Stack: Spring Authorization Server 1.4.1 + `oauth2-resource-server` (HS256) + `SecurityFilterChain` ordering · Depends on [#37 MCP server at `POST /mcp`](./02-agentic-tool-calling.md) + [#26 SecurityConfig](../04-security-and-privacy-design.md) · Enables [#48 session audit](./11-mcp-session-authorization-audit.md)

---

## 1. Purpose — what shipped

PR #47 makes the **same app** both sides of OAuth for `/mcp`:

* **AS** — mints JWT bearer tokens at `POST /oauth2/token` (plus `GET /oauth2/authorize` for browsers) from an in-process `RegisteredClientRepository` (`OAuth2AuthorizationServerConfig.java:99`).
* **RS** — validates those JWTs on every `POST /mcp` and enforces `hasAuthority("SCOPE_mcp")` (`SecurityConfig.java:71`).

No external IdP. One symmetric HS256 secret (`SecurityProperties.java:20`) signs *and* verifies (`OAuth2AuthorizationServerConfig.java:160`, `SecurityConfig.java:84`), so the full RFC 6749 / 7636 / 8414 loop is observable with `curl` on `localhost:8080`.

After the PR: `POST /oauth2/token` + `GET /oauth2/authorize` + `GET /oauth2/jwks` + `GET /.well-known/oauth-authorization-server` (RFC 8414) via the AS chain (`OAuth2AuthorizationServerConfig.java:82`); `POST /mcp` is **401 without token, 403 with wrong scope** (`SecurityConfig.java:71`, `JwtAuthenticationConverter.java:24`); three clients in one file demo OAuth 2.1: `mcp-server` (confidential, `client_credentials`), `mcp-console` (public, PKCE `authorization_code` + rotating refresh), `mcp-internal` (confidential, scope `internal` negative control) (`OAuth2AuthorizationServerConfig.java:104-144`).

---

## 2. Problem — no identity

Before PR #47 the MCP transport was open to anyone with network reach:

| Before (no auth) | After (PR #47) |
|---|---|
| `McpServerSdkIntegrationTest` ran with `app.security.enabled=false` — no bearer, no scope check | `McpOAuth2IntegrationTest.java:49` runs security **on** — every `/mcp` needs `Authorization: Bearer <JWT>` |
| `/mcp` allowed any authenticated caller (API-key or JWT, any scope) | `SecurityConfig.java:71` gates `/mcp` on `SCOPE_mcp` — `JwtAuthenticationConverter.java:28` maps `scope` → `SCOPE_<value>` |
| No caller identity — audit could only log "anonymous" | Token `sub`/`client_id` becomes `mcp_actor` in PR #48 |
| No discovery — clients hard-coded `/oauth2/token` | `GET /.well-known/oauth-authorization-server` publishes `issuer`, `token_endpoint` (`AuthorizationServerSettings.java:202`) |
| Only long-lived, unscoped API keys | 15-min JWTs (`:111`), single-use rotating refresh (`:130`), per-client scopes |

The project had real tools (`order_status`, `product_search`, `cancel_order`) reachable without proving *how* you earned the right to call them. OAuth separates "who minted the proof" (AS) from "who checks it" (RS).

---

## 3. Solution — architecture with ASCII diagrams

### 3.1 One app, two roles, one secret (AS + RS same process, HS256)

```
                    ┌──────────────────────────────────────────────┐
                    │   order-management-api (single JVM)          │
  Client            │  ┌─────────────────────┐  same HS256 secret  │
  mcp-server  ──────┼─►│  AS  /oauth2/token  │──┐  ┌─────────────┐ │
  (confidential)    │  │  /oauth2/authorize  │  │  │  RS  /mcp   │ │
  mcp-console ──────┼─►│  /.well-known/*     │──┼─►│  SCOPE_mcp │ │
  (public PKCE)     │  │  /oauth2/jwks       │──┘  │  gate :71  │ │
                    │  └─────────────────────┘     └─────────────┘ │
                    │     JwtEncoder:171  JwtDecoder:84  JWKSource:160│
                    └──────────────────────────────────────────────┘
                              HS256 shared key — no external IdP
```

### 3.2 Three clients + `client_credentials` flow

```
 RegisteredClientRepository :99 (InMemory, 3 entries)
 mcp-server   :104  CLIENT_SECRET_BASIC  client_credentials          scope:mcp       secret {noop}mcp-server-secret-learning
 mcp-console  :116  NONE (public)        authorization_code+refresh  scope:mcp       PKCE requireProofKey=true :124, rotate reuseRefreshTokens=false :130
 mcp-internal :135  CLIENT_SECRET_BASIC  client_credentials          scope:internal  secret mcp-internal-secret-learning :79 → 403 on /mcp
```

```
 CLIENT                         AS (/oauth2/token)                   RS (/mcp)
   │── POST /oauth2/token ──────►│                                    │
   │   Basic mcp-server:secret   │  verifies {noop} :106              │
   │   grant_type=client_credentials&scope=mcp                       │
   │◄── 200 { access_token: JWT(HS256, iss, scope=mcp, exp 900s) } ──┤  JwtGenerator HS256 :182 scope :183
   │── POST /mcp ────────────────────────────────────────────────────►│  JwtDecoder HS256 :88 + Converter :24 → SCOPE_mcp :28 → :71 OK
   │◄── 200 { jsonrpc tools/list }                                    │
```

### 3.3 Discovery (RFC 8414) + PKCE

```
 GET /.well-known/oauth-authorization-server  ──►  AuthorizationServerSettings :202
 ◄── { "issuer":"http://localhost:8080", "token_endpoint":"…/oauth2/token",
       "authorization_endpoint":"…/oauth2/authorize", "jwks_uri":"…/oauth2/jwks" }
```

Public client: browser creates `code_verifier` (43+ chars), sends `code_challenge=BASE64URL(SHA256(verifier))` to `/oauth2/authorize`; later posts `verifier` to `/oauth2/token`. `requireProofKey=true` (`:124`) makes a stolen `code` useless without the verifier — S256 only, `plain` removed in OAuth 2.1.

---

## 4. How it is implemented — file map + annotated snippets with file:line

### File map

| File | Role |
|---|---|
| `authorization/OAuth2AuthorizationServerConfig.java:76` | **Embedded AS** — `authorizationServerSecurityFilterChain()` (`:82`), `registeredClientRepository()` (`:99`), `jwkSource()` (`:160`), `jwtEncoder()` (`:171`), `jwtTokenCustomizer()` (`:176`), `oAuth2TokenGenerator()` (`:189`), `authorizationServerSettings()` (`:201`) |
| `security/SecurityConfig.java:36` | **RS + dual-chain** — `oauth2ResourceServer(jwt.decoder(jwtDecoder()).jwtAuthenticationConverter(...))` (`:51`), `permitAll` for `/oauth2/**`+`/.well-known/**` (`:64`), `hasAuthority("SCOPE_mcp")` on `/mcp` (`:71`), `jwtDecoder()` HS256 (`:84`) |
| `security/SecurityProperties.java:14` | **Config** — `jwtSecret` (`:20`), `OAuth.issuer` (`:72`) + `mcpClientSecret` (`:75`) shared by AS and RS |
| `security/JwtAuthenticationConverter.java:19` | **Scope → authority** — `scope` string (`:24`) → `SCOPE_<value>` (`:28`) |
| `mcp/McpOAuth2IntegrationTest.java:57` | **E2E proof** — 8 tests: token→MCP, 401/403, wrong secret, RFC 8414, PKCE, plus 2 audit (PR #48) |
| `pom.xml:134` | `spring-security-oauth2-authorization-server` 1.4.1 |

### Snippet 1 — AS chain must run first (`OAuth2AuthorizationServerConfig.java:82-96`)

```java
// src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java:82
@Bean @Order(Ordered.HIGHEST_PRECEDENCE) // :83 — AS before RS
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer configurer = OAuth2AuthorizationServerConfigurer.authorizationServer(); // :87
    http.securityMatcher(configurer.getEndpointsMatcher()) // :89 only /oauth2/** + /.well-known/**
        .csrf(csrf -> csrf.ignoringRequestMatchers(configurer.getEndpointsMatcher())) // :92
        .with(configurer, (oauth2AuthServer) -> { }) // :93 .with not .apply — see §6
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated()); // :94
    return http.build();
}
```
RS chain (`SecurityConfig.java:64-71`) pairs with `permitAll()` so `/oauth2/token` never hits the JWT gate:

```java
// src/main/java/com/company/orderapi/security/SecurityConfig.java:64
.requestMatchers("/oauth2/**", "/.well-known/**").permitAll() // :64-65
.requestMatchers("/mcp").hasAuthority("SCOPE_mcp") // :71
```

### Snippet 2 — Three clients encode OAuth 2.1 (`OAuth2AuthorizationServerConfig.java:99-146`)

```java
// :104 confidential machine-to-machine
RegisteredClient mcpServer = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("mcp-server").clientSecret("{noop}" + mcpSecret) // :106
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC) // :107
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS) // :108
        .scope("mcp").tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build()) // :109-110
        .build();
// :116 public browser — PKCE mandatory, rotating refresh
RegisteredClient mcpConsole = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("mcp-console").clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // :118
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE) // :119
        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN) // :120
        .redirectUri("http://127.0.0.1:8080/callback") // :121 literal — no {port}
        .scope("mcp").clientSettings(ClientSettings.builder().requireProofKey(true) // :124
                .requireAuthorizationConsent(false).build()) // :125
        .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)) // :128
                .refreshTokenTimeToLive(Duration.ofDays(1)).reuseRefreshTokens(false).build()) // :129-130
        .build();
// :135 negative control — wrong scope must 403
RegisteredClient mcpInternal = RegisteredClient.withId(UUID.randomUUID().toString())
        .clientId("mcp-internal").clientSecret("{noop}" + MCP_INTERNAL_SECRET) // :137
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC) // :138
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("internal") // :139-140
        .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build()).build();
return new InMemoryRegisteredClientRepository(mcpServer, mcpConsole, mcpInternal); // :146
```

### Snippet 3 — One HS256 key signs and verifies (`OAuth2AuthorizationServerConfig.java:160` + `SecurityConfig.java:84`)

```java
// AS — JWK source the encoder signs with :160
@Bean public JWKSource<SecurityContext> jwkSource(SecurityProperties p) {
    SecretKeySpec key = new SecretKeySpec(p.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"); // :161
    OctetSequenceKey jwk = new OctetSequenceKey.Builder(key).keyID("orderapi-learning-hs256").algorithm(JWSAlgorithm.HS256).build(); // :163-165
    return new ImmutableJWKSet<>(new JWKSet(jwk)); // :167
}
@Bean public JwtEncoder jwtEncoder(JWKSource<SecurityContext> s) { return new NimbusJwtEncoder(s); } // :172
// RS — same secret verifies :84
@Bean public JwtDecoder jwtDecoder() {
    SecretKeySpec key = new SecretKeySpec(properties.getJwtSecret().getBytes(), "HmacSHA256"); // :86
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); // :88-90
}
```

### Snippet 4 — Override RS256 default + emit `scope` string (`OAuth2AuthorizationServerConfig.java:176`)

```java
// src/main/java/com/company/orderapi/authorization/OAuth2AuthorizationServerConfig.java:176
@Bean public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
    return ctx -> {
        if (OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) { // :179
            ctx.getJwsHeader().algorithm(MacAlgorithm.HS256); // :182 override hard-coded RS256
            ctx.getClaims().claim("scope", String.join(" ", ctx.getAuthorizedScopes())); // :183
        }
    };
}
```
Must be space-delimited string — `JwtAuthenticationConverter.java:24-28` splits it into `SCOPE_mcp`.

---

## 5. How to use — curl over `POST /mcp` (Streamable HTTP)

### Prerequisites

```bash
./mvnw spring-boot:run
# issuer http://localhost:8080  SecurityProperties.java:72
# jwtSecret local-learning-secret-change-me-please-32chars
```

### 1. Obtain a JWT via `client_credentials` (confidential client)

```bash
# mints 15-min HS256 token with scope=mcp  OAuth2AuthorizationServerConfig.java:104
curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq .

TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
echo ${TOKEN:0:40}...
# { "access_token":"eyJ…", "token_type":"Bearer", "expires_in":900, "scope":"mcp" }
# wrong secret → 401  McpOAuth2IntegrationTest.java:124
```

### 2. Decode the JWT — verify `alg`, `iss`, `scope`

```bash
echo "$TOKEN" | cut -d. -f1 | base64 -d 2>/dev/null | jq .
# { "alg":"HS256", "kid":"orderapi-learning-hs256" }  :164
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# { "iss":"http://localhost:8080", "sub":"mcp-server", "scope":"mcp", "exp":... }

# python (handles padding)
python3 -c 'import os,base64,json; p=os.environ["TOKEN"].split(".")[1]; print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"==")), indent=2))'
```

### 3. Call `/mcp` with `Authorization: Bearer`

```bash
# tools/list — McpOAuth2IntegrationTest.java:89
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | jq .

# tools/call
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"api_health","arguments":{}}}' | jq .
```

### 4. Negative checks — 401 and 403

```bash
# no token → 401  :99
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# wrong scope → 403 — mcp-internal scope internal only  :135
BAD=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-internal:mcp-internal-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=internal' | jq -r .access_token)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $BAD" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# 403  McpOAuth2IntegrationTest.java:109
```

### 5. Discovery — RFC 8414

```bash
curl -s http://localhost:8080/.well-known/oauth-authorization-server | jq .
# { "issuer":"http://localhost:8080", "authorization_endpoint":"…/oauth2/authorize",
#   "token_endpoint":"…/oauth2/token", "jwks_uri":"…/oauth2/jwks",
#   "code_challenge_methods_supported":["S256"],
#   "token_endpoint_auth_methods_supported":["client_secret_basic","none"] }
#   AuthorizationServerSettings.java:202

curl -s http://localhost:8080/oauth2/jwks | jq .
# { "keys": [{ "kty":"oct", "kid":"orderapi-learning-hs256", "alg":"HS256" }] }  :164
```

---

## 6. Key decisions — why these choices win (and the traps avoided)

**`HIGHEST_PRECEDENCE` chain** — `OAuth2AuthorizationServerConfig.java:83` must claim `/oauth2/**`+`/.well-known/**` via `configurer.getEndpointsMatcher()` (`:89`) *before* the RS chain (`SecurityConfig.java:44`) sees them. Without it, RS would JWT-validate `POST /oauth2/token` itself — every token request 401s for "missing scope". Pair with `permitAll()` on those paths (`SecurityConfig.java:64`).

**`.with` vs `.apply`** — `http.with(configurer, …)` (`:93`) returns `HttpSecurity`; `http.apply(configurer)` returns the configurer. The latter leaves `HttpSecurity` unbuilt — AS endpoints silently missing, no runtime log, just 404s.

**`MacAlgorithm.HS256` override — the 401 that disguised a JWK failure** — `JwtGenerator` hard-codes `RS256`; `NimbusJwtEncoder` defaults JWS header to `RS256`; `jwkSource` (`:160`) only holds HS256 `OctetSequenceKey`. Key selection empty → `JwtEncodingException: Failed to select a JWK signing key` → AS returns `401 Bearer` identical to wrong-secret. TRACE showed `ClientSecretAuthenticationProvider - Authenticated` succeeded — secret was fine. Fix is `jwtTokenCustomizer` (`:182`): `context.getJwsHeader().algorithm(MacAlgorithm.HS256)` overwrites the map before freeze, plus `scope` string for `JwtAuthenticationConverter.java:26`.

**`{port}` gotcha** — `mcp-console` uses literal `http://127.0.0.1:8080/callback` (`:121`). The server rejects URI templates like `http://127.0.0.1:{port}/callback` or fragments as unparseable. Literal survives `RegisteredClient` validation without a custom `RedirectUriValidator`.

**Three clients including the one that must fail** — `InMemoryRegisteredClientRepository` rejects duplicate secrets, so `mcp-server` and `mcp-internal` need distinct secrets (`MCP_INTERNAL_SECRET` :79). `mcp-internal` (`:135`) is the negative control — valid JWT with `scope="internal"` must still `403` on `/mcp` (`McpOAuth2IntegrationTest.java:109`).

**`requireProofKey` + `reuseRefreshTokens`** — OAuth 2.1 removed `plain` PKCE and non-rotating refresh. `requireProofKey(true)` (`:124`) mandates `S256`; `reuseRefreshTokens(false)` (`:130`) makes refresh single-use — leaked token is one-shot.

---

## 7. How to verify — curl + integration tests + logs

### 1. Curl — four assertions in 30s

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)
echo "$TOKEN" | cut -d. -f1 | base64 -d | jq .alg   # "HS256"
echo "$TOKEN" | cut -d. -f2 | base64 -d | jq .scope # "mcp"
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' # 200
```

### 2. Integration tests — `McpOAuth2IntegrationTest.java:57`

```bash
./mvnw test -Dtest=McpOAuth2IntegrationTest
# 8 cases (6 PR #47 + 2 PR #48 audit):
#  clientCredentialsTokenGrantsMcpAccess        :79  HS256+iss+scope → McpSyncClient.listTools() OK
#  noTokenIsRejectedOnTheMcpEndpoint            :99  no Bearer → 401
#  tokenWithoutMcpScopeCannotCallTheMcpEndpoint :109 mcp-internal/internal → 403
#  tokenEndpointRejectsWrongClientSecret        :124 wrong secret → 401
#  authorizationServerMetadataIsPublished       :130 GET /.well-known/… → 200 contains issuer/token_endpoint
#  publicClientRequiresPkceAndCarriesNoSecret   :146 mcp-console secret==null requireProofKey==true
#  toolInvocationCreatesAuditTrail              :158 api_health → mcp_tool_audit actor=mcp-server
#  failedToolInvocationRecordsError             :180 failed order_status → success=false
# Uses RANDOM_PORT + Testcontainers postgres:16-alpine :61, HttpClientStreamableHttpTransport :254
```

### 3. Logs — where the real error hides

```bash
curl -s http://localhost:8080/.well-known/oauth-authorization-server | jq .issuer
# "http://localhost:8080"  SecurityProperties.java:72
# If /oauth2/token always 401: logging.level.org.springframework.security=TRACE
# expect: ClientSecretAuthenticationProvider - Authenticated client secret (secret OK)
# and: JwtEncodingException: Failed to select a JWK signing key (HS256 fix missing :182)
```

---

## 8. How this helps you on the job — build / operate / interview

* **Build — embedded AS/RS without external IdP.** Copy `OAuth2AuthorizationServerConfig.java:76` → `@Order(HIGHEST_PRECEDENCE)` chain (`:82`) + `RegisteredClientRepository` (`:99`) with `client_credentials` (machine) + `authorization_code+PKCE` (browser, `:116`) → wire `JWKSource`+`JwtEncoder`+`JwtDecoder` (`:160`,`:171`,`:84`) sharing `jwtSecret` (`:20`) → add `OAuth2TokenCustomizer` (`:176`) to override `RS256→HS256` and emit `scope` string → gate `SecurityConfig.java:71` on `SCOPE_<scope>` via `JwtAuthenticationConverter.java:28`. RFC 8414 discovery via `AuthorizationServerSettings:202`.

* **Operate — scope, rotation, debug signal.** `SCOPE_mcp` is coarse gate; per-order auth is PR #48. Rotate by changing `app.security.jwt-secret` + `oauth.mcp-client-secret` (`SecurityProperties.java:72-75`) and restarting — `InMemoryRegisteredClientRepository` reloads. Access 15 min (`:111`), refresh 1 day rotating single-use (`:130`). On-call: `401 Bearer` on `/oauth2/token` is *not* always bad secret — check `Failed to select a JWK signing key` first. Monitor `.well-known` 200s and `/oauth2/token` 401 / `/mcp` 403 ratios.

* **Interview — 90s whiteboard.** "PR #47: app is own AS+RS sharing HS256 via `jwkSource` (`:160`) + `jwtDecoder` (`SecurityConfig.java:84`). AS chain `HIGHEST_PRECEDENCE` (`:83`) claims `/oauth2/**`+`/.well-known/**` via `getEndpointsMatcher()` (`:89`) with `.with` (`:93`) not `.apply`; RS `permitAll` on `/.well-known/**` + `hasAuthority("SCOPE_mcp")` on `/mcp` (`:71`) via `JwtAuthenticationConverter` (`:28`). Three clients (`:104-144`): `mcp-server` `client_secret_basic`+`client_credentials` `mcp`, `mcp-console` `NONE`+`authorization_code`+`refresh_token` `requireProofKey=true` (`:124`) + `reuseRefreshTokens=false` (`:130`) S256 + rotating, `mcp-internal` `internal` 403 control. `jwtTokenCustomizer` (`:182`) fixes RS256→HS256 disguised 401. RFC 8414 discovery. Verified by `McpOAuth2IntegrationTest.java:57` + `curl token→decode→Bearer /mcp→discovery`."

---

## 9. Interview lens — 3 Q&A you can now answer

**Q1: "Every `POST /oauth2/token` returned 401 `Bearer` even with the right secret — how did you diagnose it?"**

> "Generic 401, so TRACE `org.springframework.security`. Log showed `ClientSecretAuthenticationProvider - Authenticated client secret` — secret was correct. Deeper: `NimbusJwtEncoder` defaults JWS header `RS256`, `JwtGenerator` hard-codes `RS256`, but `jwkSource` (`OAuth2AuthorizationServerConfig.java:160`) only held HS256 `OctetSequenceKey`. Key selection empty → `JwtEncodingException: Failed to select a JWK signing key` → AS mapped to `INVALID_CLIENT` 401. Fixed in `jwtTokenCustomizer` (`:176-182`): `context.getJwsHeader().algorithm(MacAlgorithm.HS256)` overwrites the map, plus `scope` string for `JwtAuthenticationConverter.java:24`. Lesson: auth 401 can hide JWT key-selection bug."

**Q2: "Why two chains, `HIGHEST_PRECEDENCE`, and `.with` not `.apply`?"**

> "Two chains: `authorizationServerSecurityFilterChain` (`:82`) matches only `/oauth2/**`+`/.well-known/**` via `configurer.getEndpointsMatcher()` (`:89`); rest falls to `SecurityConfig.securityFilterChain` (`:44`) which `permitAll` on those paths (`:64`) and gates `/mcp` on `SCOPE_mcp` (`:71`). `HIGHEST_PRECEDENCE` ensures AS claims its endpoints first — otherwise RS would JWT-validate `/oauth2/token` and 401 for missing scope. `.with(configurer,…)` (`:93`) keeps `HttpSecurity` return type; `.apply` returns the configurer and leaves `HttpSecurity` unbuilt — endpoints silently missing."

**Q3: "Why HS256 in one process, and why three clients with PKCE + rotating refresh?"**

> "HS256 is the learning trick: one JVM, one `SecretKeySpec(HmacSHA256)` signing+verifying (`:161`+`SecurityConfig.java:86`) avoids external IdP — `kid=orderapi-learning-hs256` (`:164`) symmetric; production swaps to RS256/ECDSA via `jwks_uri`. Three clients prove OAuth 2.1 in one file (`:104-144`): `mcp-server` confidential `client_secret_basic`+`client_credentials` server→server; `mcp-console` public `NONE`+`authorization_code`/`refresh_token` with `requireProofKey=true` (`:124`) S256 PKCE (plain removed) + `reuseRefreshTokens=false` (`:130`) single-use rotation; `mcp-internal` (`:135`) negative control — scope `internal` valid JWT must still `403` on `/mcp`, proving enforcement isn't 'any valid token'."

---

## 10. Honest limits & next steps — what it doesn't do, where PR #48+ picks up

**What PR #47 alone does NOT do:**

* **In-memory clients + authorizations.** `InMemoryRegisteredClientRepository` (`:146`) + `InMemoryOAuth2AuthorizationService` (`:151`) — restart wipes state. Swap to JDBC-backed repos + persisted `JWKSource` for durability.
* **One symmetric secret, no rotation.** `SecurityProperties.jwtSecret` (`:20`) single HS256, no `kid` rotation, no `RS256`/`ES256`. Real deployment publishes `RS256` JWKS at `/oauth2/jwks` and validates `iss`/`aud`.
* **Coarse scope only.** Token has `mcp` or not (`:71`). No per-user/per-order/per-tool auth — holder of `mcp` can call any `tools/call`. Fixed by PR #48 `mcp_actor`/`mcp_session_id` + `mcp_tool_audit`.
* **No consent screen.** `mcp-console` `requireAuthorizationConsent(false)` (`:125`) — `authorization_code` never renders approval UI; never for production.
* **Literal redirect URI.** `http://127.0.0.1:8080/callback` (`:121`) hard-coded; multi-redirect needs extra `redirectUris` or custom validator.

**Where it picks up:** PR #48 binds `client_id`/`sub` via `contextExtractor` → `mcp_actor` + `mcp_session_id` per tool call and immutable `mcp_tool_audit` rows — coarse `SCOPE_mcp` becomes auditable per-session identity; PR #49 adds `AiMetrics`/`OTel` over those calls.

> Next: [`11-mcp-session-authorization-audit.md`](./11-mcp-session-authorization-audit.md) (PR #48) · or back to [`README.md`](./README.md) · high-level companion [`docs/additions/10-mcp-authorization-oauth2.md`](../../10-mcp-authorization-oauth2.md).
