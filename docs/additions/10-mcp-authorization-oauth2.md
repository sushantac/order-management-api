# 10. MCP Authorization: an OAuth 2.1 + PKCE authorization server inside the app

> **PR #47** — Until now `/mcp` had no identity: `McpServerSdkIntegrationTest`
> ran with security disabled and the endpoint was open to unauthenticated
> clients. This PR turns the app into its **own authorization server** — it
> issues OAuth 2.1-compliant JWT bearer tokens, and the same app as a
> **resource server** refuses any `/mcp` call that doesn't carry the `mcp`
> scope. PKCE is mandatory for browsers, confidential clients authenticate with
> secrets, refresh tokens rotate, and discovery metadata is published.

---

## 1. The one-paragraph mental model

OAuth splits one system into three roles:

- **Authorization server (AS)** — the thing that *mints identity*. It owns the
  clients (who may ask for tokens) and the grants (how they may ask).
- **Resource server (RS)** — the thing that *enforces identity*. It trusts the
  AS's signature and checks scope before serving data.
- **Client** — the thing asking permission on behalf of a user or of itself.

In this PR **the same app plays AS and RS** for `/mcp`. A single symmetric HS256
secret signs the JSON Web Tokens (AS side) and verifies them (RS side), so there
is no external IdP to spin up in the learning setup — the whole flow is
observable with `curl`.

---

## 2. Why OAuth 2.1 (and not 2.0 or a plain API key)

The MCP endpoint executes tools against **real order data**. An API key in a
header would authenticate *a client*, but OAuth adds three things a key can't:

- **Scopes** — a token says *what* it may do (`mcp`), not just *who* it is. The
  `/mcp` endpoint rejects tokens that lack SCOPE_mcp.
- **Grants** — *how* a token was obtained matters. A server talking to a server
  uses `client_credentials`; a human in a browser uses an authorization code
  **plus PKCE**. Each grant carries different trust assumptions.
- **Short lifetimes + rotation** — access tokens live 15 minutes; refresh tokens
  are single-use. A leaked token is a small, closing window, not a standing key.

OAuth 2.1 is the current standard's tightening of 2.0: it **removed** the
ambiguous parts (implicit grant, non-rotating refresh tokens, plain-text PKCE)
so the "secure by default" choices are the only choices.

---

## 3. The protocol shapes (what a wire exchange looks like)

### Confidential client, `client_credentials` (a server calling `/mcp`)

```
HTTP/1.1 POST /oauth2/token
Authorization: Basic bWNwLXNlcnZlcjptY3Atc2VydmVyLXNlY3JldC1sZWFybmluZw==
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=mcp
```

```
HTTP/1.1 200 OK

{
  "access_token": "eyJraWQi…(HS256 JWT)…",
  "token_type": "Bearer",
  "expires_in": 900,
  "scope": "mcp"
}
```

The decoded token carries `alg: HS256`, `iss: http://localhost:8080`, and a
`scope` claim of `mcp` — the exact claim the resource server's
`JwtAuthenticationConverter` turns into the `SCOPE_mcp` authority the `/mcp`
route demands.

### Discovery (RFC 8414)

```
GET /.well-known/oauth-authorization-server

{
  "issuer": "http://localhost:8080",
  "authorization_endpoint": "http://localhost:8080/oauth2/authorize",
  "token_endpoint": "http://localhost:8080/oauth2/token",
  ...
}
```

A client can *discover* both endpoints without hard-coding them — the OAuth
bootstrap problem solved the same way the web solved certificates' distribution.

### The public client, `authorization_code` + PKCE

The `mcp-console` client is **public** — it has no secret. Instead:
- The browser session generates a `code_verifier` (random 43-char string),
  sends only its SHA-256 hash (`code_challenge`) to `/oauth2/authorize`, then
  later proves possession by sending the raw verifier to `/oauth2/token`.
- The server registered `requireProofKey=true`, so a code exchange **without**
  the correct verifier is refused. A stolen auth-code is useless without the
  verifier that lived only on the user's device.

---

## 4. The code (what changed)

| File | Change |
|------|--------|
| `authorization/OAuth2AuthorizationServerConfig.java` | The embedded AS: filter chain, registered clients, HS256 JWK source, JWT encoder/generator, IS-Settings, delegating password encoder |
| `security/SecurityConfig.java` | `permitAll` for `/oauth2/**` + `/.well-known/**`; new `hasAuthority("SCOPE_mcp")` gate on `/mcp` |
| `security/SecurityProperties.java` | Nested `OAuth` props: `issuer`, `mcpClientSecret` |
| `application.yml` | `app.security.oauth.issuer` + `app.security.oauth.mcp-client-secret` |
| `pom.xml` | `spring-security-oauth2-authorization-server` (1.4.1) |
| `mcp/McpOAuth2IntegrationTest.java` | 6 end-to-end tests: token→MCP access, 401/403 enforcement, wrong-secret 401, metadata, PKCE registry assertions |

### The filter chain: AS first, RS second

```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE)
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
    OAuth2AuthorizationServerConfigurer configurer =
            OAuth2AuthorizationServerConfigurer.authorizationServer();
    http
            .securityMatcher(configurer.getEndpointsMatcher())
            .csrf(csrf -> csrf.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
            .with(configurer, (oauth2AuthServer) -> { })
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
    return http.build();
}
```

Two security chains now coexist: this **AS chain** matches `/oauth2/**` and
`/.well-known/**`; everything else stays on the existing **RS chain**, which got
`.requestMatchers("/oauth2/**", "/.well-known/**").permitAll()` and
`.requestMatchers("/mcp").hasAuthority("SCOPE_mcp")`. Note `.with(...)` — the
configurer's public API, not `.apply(...)`, which returns the configurer rather
than the `HttpSecurity` and fails to compile.

### Registered clients demonstrate OAuth 2.1 in one file

| Client | Type | Grant | PKCE | Secret |
|--------|------|-------|------|--------|
| `mcp-server` | confidential | `client_credentials` | — | `{noop}mcp-server-secret-learning` |
| `mcp-console` | public | `authorization_code` + `refresh_token` | **required** | none |
| `mcp-internal` | confidential | `client_credentials` | — | `{noop}mcp-internal-secret-learning`, scope `internal` (the control) |

`reuseRefreshTokens(false)` turns on **refresh-token rotation** — every refresh
issues a new token and invalidates the old one (the 2.1 replacement for the
2.0 "everlasting refresh token"). `mcp-internal` exists purely as a negative
control: it gets a valid token with scope `internal`, and the `/mcp` route must
still return **403**.

### One secret signs and verifies (the embedded-AS trick)

```java
SecretKeySpec key = new SecretKeySpec(properties.getJwtSecret().getBytes(UTF_8), "HmacSHA256");
OctetSequenceKey jwk = new OctetSequenceKey.Builder(key)
        .keyID("orderapi-learning-hs256")
        .algorithm(JWSAlgorithm.HS256)
        .build();
return new ImmutableJWKSet<>(new JWKSet(jwk));
```

Because AS and RS share the process, a symmetric key is not a security weakness —
the "shared secret" is one bean. A customizer also emits the `scope` claim as the
**space-delimited string** `JwtAuthenticationConverter` splits, so a token minted
here verifies *and* authorizes downstream without any adapter.

---

## 5. Key decisions & the failure that taught the most

### The 401 that wasn't a 401 (the debugging story)

The token endpoint returned `401 + WWW-Authenticate: Bearer` for **everything** —
no auth, correct secret, wrong secret. First hypothesis: client authentication
isn't running. TRACE logging showed it **was**: `ClientSecretAuthenticationProvider
- Authenticated client secret`. The real failure sat one layer deeper, in the JWT
encoder, and surfaced as the same generic 401:

- **`NimbusJwtEncoder` defaults its JWS header to RS256** (`DEFAULT_JWS_HEADER`),
  and spring-authorization-server's `JwtGenerator` **hard-codes RS256 for access
  tokens**.
- The JWK source held *only* an HS256 octet key, so key selection returned empty →
  `JwtEncodingException: Failed to select a JWK signing key` → caught by the AS →
  INVALID_CLIENT-style 401.

Fix: the `OAuth2TokenCustomizer<JwtEncodingContext>` already runs before the
header is built, and `JwsHeader.Builder.algorithm(...)` merely overwrites the map
entry — so the customizer explicitly signs with the symmetric secret:

```java
if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
    context.getJwsHeader().algorithm(MacAlgorithm.HS256);   // override RS256 default
    context.getClaims().claim("scope", String.join(" ", context.getAuthorizedScopes()));
}
```

**Lesson:** an auth failure's *surface* (the 401) can hide a completely different
cause (JWT key selection). Log at the right level before trusting the error code.

### Why three separate client secrets?

`InMemoryRegisteredClientRepository` rejects duplicate secrets across clients.
`mcp-server` and `mcp-internal` therefore use distinct secrets — a real system
would give each client its own anyway.

### Why the redirect URI has no `{port}` template

`http://127.0.0.1:8080/callback` — a literal URI. OAuth redirect templates
(`{port}`) are handy in SDK samples but the AS's `redirect-uri` validator
rejects anything it can't parse as a real URI or that contains a fragment.

### Why `mcp-internal` exists

A positive test proves the happy path; a **negative control** proves enforcement.
Without it, a "403 for the wrong scope" test would be asserting the absence of a
bug nobody proved would have happened.

---

## 6. What to try

```bash
# 1. Get a token as the confidential client
TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u mcp-server:mcp-server-secret-learning \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&scope=mcp' | jq -r .access_token)

# 2. Decode it (Hal the header/claims)
JWT=$TOKEN python3 -c 'import os,sys,base64,json;p=os.environ["JWT"].split(".")[1];print(json.dumps(json.loads(base64.urlsafe_b64decode(p+"=="))))'

# 3. Call /mcp WITH the token (expect listTools JSON-RPC)
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 4. Call /mcp WITHOUT it (expect 401) and with an "internal" token (expect 403)
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 5. Fetch server metadata (RFC 8414 discovery)
curl -s http://localhost:8080/.well-known/oauth-authorization-server | jq .
```

---

## 7. Honest limits

1. **In-memory clients + in-memory authorizations.** Clients (`RegisteredClient`)
   and issued authorizations live in memory — a restart wipes both. The shape is
   there; persistence is a JDBC-backed `RegisteredClientRepository` +
   `OAuth2AuthorizationService` swap away.

2. **One symmetric secret for everything.** Fine for a single-process learning
   setup; a real deployment would use RSA/ECDSA keys and rotate them (see
   `/oauth2/jwks`).

3. **Scope is coarse.** A token either has `mcp` or it doesn't; there is no
   per-user, per-order authorization yet. That's exactly the gap PR #48
   (session-scoped authorization + agent-identity audit) will close.

4. **No user-facing consent UI.** `mcp-console` has `requireAuthorizationConsent
   (false)`, so the authorization-code flow doesn't render an approval screen —
   acceptable for a public demo client, never for production.

---

*Next: PR #48 — session-scoped authorization + agent-identity audit, closing the
coarse-scope gap from limit 3.*