# 25. OpenAPI Documentation (PR #25)

> PR #25 — `springdoc-openapi` (`springdoc-openapi-starter-webmvc-ui:2.7.0`) spec at `/v3/api-docs` + Swagger UI at `/swagger-ui.html`, `OpenApiConfig` declares `bearer-jwt` + `api-key` security schemes, controllers annotate `@Operation/@ApiResponse`. Stack: Java 21, Spring Boot 3.4.1, `springdoc 2.7.0`, `io.swagger.v3.oas.annotations`, `src/main/java/com/company/orderapi/config/OpenApiConfig.java:20` + `api/rest/controller/*:66`. See `README.md:1772` roadmap `| 25 | OpenAPI Documentation |`.

---

## 1. Purpose — what shipped

PR #25 makes the API self-describing. `pom.xml:271-274` adds `springdoc-openapi-starter-webmvc-ui:2.7.0` which auto-registers a `OpenApiWebMvcResource` serving `GET /v3/api-docs` (JSON) and `GET /v3/api-docs.yaml`, plus Swagger UI at `/swagger-ui.html` (`/swagger-ui/index.html`). `OpenApiConfig.java:20` (`@Configuration`) builds the `OpenAPI` bean (`orderManagementApi():24`) with `Info:26 title Order Management API`, `description 28-30 authenticate via bearer JWT or X-API-Key`, `version v1:31`, and two `SecurityScheme`s `bearer-jwt:34-38` (`HTTP bearer JWT SCOPE_order_read/order_write`) and `api-key:39-43` (`APIKEY X-API-Key`). The default `SecurityRequirement:44` `bearer-jwt` applies to all ops. Controllers carry `@Operation(summary,description)` + `@ApiResponses({@ApiResponse(responseCode,description)})` (`OrderController.java:66-73` `201/400/409`) so the spec documents `201 Created + Location:102`, `Idempotency-Key` header:78, and `ETag:139` semantics without reading code. `SecurityConfig.java:60-61` permits `"/v3/api-docs/**", "/swagger-ui/**"` unauthenticated.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Endpoints existed (`OrderController.java:49` order, `ProductController:30` product) but the contract was implicit: `POST /api/v1/orders 201 + Location` (`OrderController:101-103`), idempotent `Idempotency-Key:78`, `If-Match:173` vs `ETag:183`, `Pageable:129` pagination, `ProblemDetail` codes (PR #24 `GlobalExceptionHandler:109`), and auth (`SCOPE_order_write` vs `ROLE_API_KEY:74`) were documented only in code. Client generation was impossible without reading sources.

**After:** `GET /v3/api-docs` emits a machine-readable OpenAPI 3.1 JSON covering every route (`/api/v1/orders`, `/api/v1/products`, `/api/v1/customers`) with schemas derived from `OrderRequest:15`/`CustomerRequest:16` records, response `OrderResponse:16`/`ProductResponse`, validation constraints (PR #23), and examples. `GET /swagger-ui.html` renders an interactive explorer with "Authorize" for either `bearer-jwt` or `api-key:39-43`. `@Operation:66` "Place an order ... in ONE transaction. Idempotent when an Idempotency-Key is sent." plus `@ApiResponse:69-73` maps to the documented `201/400/409`.

### Theory — OpenAPI, springdoc, and Swagger UI from first principles (100+ lines)

#### 2.1 What OpenAPI is — contract first, then code or vice versa

OpenAPI 3.x is a JSON/YAML document describing `info, servers, paths, components/schemas, securitySchemes`. It lists each `GET /api/v1/orders/{id}` with `parameters` (`id integer`), `requestBody` (`OrderRequest` schema from `OrderRequest.java:16` record components + `jakarta.validation` constraints), `responses` (`201` with `application/json` `OrderResponse:16`, `400` with `application/problem+json` `ProblemDetail` PR #24, `412`), and `security` (`bearer-jwt` or `api-key`). With it, generators (`openapi-generator`) produce client SDKs (TypeScript, Java) that stay in sync — changing `ProductRequest.price` type is caught at generation, not runtime.

#### 2.2 springdoc — runtime scanner, not code generation

`springdoc-openapi-starter-webmvc-ui` (vs Swagger 2 `springfox`) introspects the Spring context at startup:

```java
// springdoc at boot
OpenApiWebMvcResource → OpenAPIService.build()
  reads @Configuration OpenApiConfig.java:20 → OpenAPI bean 24 (Info:26 + Components SecuritySchemes 33-43 + SecurityRequirement 44)
  scans DispatcherServlet HandlerMappings → every @RestController method (OrderController:66, ProductController:30)
    per method reads @Operation 66, @ApiResponses 69-73, @Parameter/Pageable, @RequestBody 80 record OrderRequest:16 (components: field types + @NotNull etc.)
          @SecurityRequirement inherited from OpenApiConfig:44 default or per-method override
          @Schema inferred from record components (OrderResponse.java:16 bigDecimal totalAmount etc. + @MaskedPii 22 not exposed as schema detail)
    produces OpenAPI JSON at /v3/api-docs (cached in OpenApiResource)
SwaggerWelcomeCommon / SwaggerUiConfigProperties serve static swagger-ui webjar at /swagger-ui.html
```

No YAML to maintain — the spec is derived from the code that runs. Adding `POST /api/v1/widgets` with `@Operation` automatically extends `/v3/api-docs` on next boot. Alternative `openapi-generator` *code-first* (maintain `openapi.yaml` and generate server stubs) enforces hand-written spec discipline but diverges quickly; springdoc's *code as spec* is simpler for an internal learning journey.

#### 2.3 `OpenApiConfig` — global Info + securitySchemes

```java
// OpenApiConfig.java:24-45
@Bean OpenAPI orderManagementApi(){
  return new OpenAPI()
    .info(new Info().title("Order Management API").description("Production-grade ... Authenticate with bearer JWT or X-API-Key ...").version("v1").license(...)) //26-32
    .components(new Components()
      .addSecuritySchemes("bearer-jwt", new SecurityScheme().type(HTTP).scheme("bearer").bearerFormat("JWT").description("Scopes: order_read, order_write.")) //34-38
      .addSecuritySchemes("api-key", new SecurityScheme().type(APIKEY).in(HEADER).name("X-API-Key").description("Static API key for machine clients.")) //39-43
    )
    .addSecurityItem(new SecurityRequirement().addList("bearer-jwt")); //44 default requires bearer
}
```

`Components.SecurityScheme` values map to the OpenAPI `components/securitySchemes` object. `Type.HTTP bearer` tells Swagger UI to render a "Bearer token" input that adds `Authorization: Bearer <token>` to "Try it out". `Type.APIKEY in HEADER name X-API-Key:42` → input for the `X-API-Key` header (`SecurityConfig.java:54` `ApiKeyAuthenticationFilter`). `SecurityRequirement` default `bearer-jwt:44` means every operation shows a lock icon requiring auth unless overridden (`@SecurityRequirement(name="bearer-jwt", scopes=...)` per method). Keeping `ApiKey` declared but not default allows Swagger UI users to pick either.

#### 2.4 `@Operation` and `@ApiResponse` — co-locating docs with the handler

```java
// OrderController.java:66-77
@Operation(summary="Place an order", description="Deducts stock, creates the order and charges the payment in ONE transaction. Idempotent when an Idempotency-Key is sent.")
@ApiResponses({
  @ApiResponse(responseCode="201", description="Order created"),
  @ApiResponse(responseCode="400", description="Validation or payment failure"),
  @ApiResponse(responseCode="409", description="Stock conflict / data conflict")
})
@PreAuthorize("@securityProperties.enabled == false or hasAnyAuthority('SCOPE_order_write','ROLE_API_KEY')") //74
@PostMapping @Transactional //75-76
public ResponseEntity<OrderResponse> create(@RequestHeader(value="Idempotency-Key",required=false) String key:78, HttpServletRequest req:79, @Valid @RequestBody OrderRequest reqBody:80)
```

`@Operation.summary` → OpenAPI `summary`; `description` → `description` — becomes the Swagger UI operation title and expanded docs. `@ApiResponse.responseCode="201"` maps to the success `ResponseEntity.created(...):102` `201+Location`. The `Idempotency-Key` param and `ETag`/`If-Match:173` are documented by springdoc automatically from `@RequestHeader` metadata (parameter name, required). `ProductController.java:52-58` lacks explicit `@ApiResponse` — springdoc still infers `201` from `ResponseEntity<ProductResponse>` but the textual hint is missing; adding `@Operation` there would improve the product spec similarly.

#### 2.5 Schema inference — records, Page, and ProblemDetail

Field schemas come from Java types + Jackson + Bean Validation: `OrderRequest.customerId:17 Long @NotNull → type: integer, format: int64, required: true`, `items:18 @NotEmpty @Valid List<OrderItemRequest> → type: array, minItems:1, items: $ref '#/components/schemas/OrderItemRequest'`, `OrderItemRequest.quantity:23 @NotNull Integer → integer required`. `Page<OrderResponse>:129` is unwrapped by springdoc's `Pageable` plugin (`Pageable` → query params `page, size, sort`). `ProblemDetail` (`GlobalExceptionHandler:56`) is inferred as `application/problem+json` for every `4xx/5xx` — no `@ApiResponse` needed to expose the `code/hint/title/detail` shape, though declaring it refines the spec.

#### 2.6 Swagger UI — interactive explorer over the spec

`GET /swagger-ui.html` serves the `swagger-ui` webjar (JS bundle). It `fetch(/v3/api-docs)` and renders tag-grouped operations (`order-controller`, `product-controller`). Each operation has "Try it out" → edit `customerId/items` JSON, set `Idempotency-Key` or `X-API-Key`, "Execute" → `curl` preview + live response body + headers (`Location:102`, `ETag:139`). The "Authorize" button shows both `bearer-jwt` and `api-key` schemes (`OpenApiConfig.java:33-43`). Grouping by `OpenApiConfig` `Info.version v1:31` aligns with `/api/v1` prefix; bumping to `v2` on a breaking change would require a new `GroupOpenApi` bean filtered by `pathsToMatch("/api/v2/**")`.

#### 2.7 SecurityConfig and the docs as public — `.permitAll()`

```java
// SecurityConfig.java:60-61
.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
```

Docs are `permitAll` even when `app.security.enabled:180 true` — unauthenticated `curl` to `/v3/api-docs` works (no `401` via `GlobalExceptionHandler:42`). This is deliberate: spec is not secret, and Swagger UI itself is static assets. Production may add `management.endpoints` style gating or hide `/swagger-ui` behind an `api-docs` feature flag (`app.features.reporting` pattern PR #34).

#### 2.8 How springdoc 2.7.0 vs 2.3.0 incompatibility surfaced

`pom.xml:271` pins `springdoc-openapi-starter-webmvc-ui 2.7.0` because `2.3.0` returned `500` on `/v3/api-docs` after Spring Boot upgrade PR #37 `3.2.1 → 3.4.1` (Spring Framework 6.2 requirement of `mcp-spring-webmvc 0.18.4`). The boot+springdoc compatibility matrix matters: `2.7.x` aligns with Boot `3.4`; older springdoc's `HandlerMethod` introspection broke on new `RequestMappingInfo` types.

#### 2.9 Validation → schema `required` mapping

Placing `@NotNull/@NotBlank` on record components (`OrderRequest:17`, `CustomerRequest:17`) makes springdoc emit `required: ["customerId","items"]` automatically — the API contract shows which fields clients must send. Dropping `groups` from the schema is correct: `required` is `Default` group semantics; `Create` vs `Update` nuance (`CustomerRequest.fullName Size(Create):22`) appears as descriptive constraint not strict `required`, which Swagger UI annotates via the field's allowed pattern/length.

#### 2.10 Alternatives — Swagger 2, static OpenAPI YAML, or no spec

- Swagger 2 / springfox (`@ApiOperation`) — legacy, heavier, deprecated for Boot 3; springdoc is its successor.
- Hand-maintained `openapi.yaml` checked in + `openapi-generator` server stubs — strongest contract-first discipline but overhead for an evolving learning repo.
- No spec, rely on README — clients guess and tests provide the only truth; OK early but unscalable once `ProductRequest/CustomerRequest` evolve and consumers are external.
- Keeping `@Operation` minimally on write (`POST/PUT/PATCH/DELETE`) and letting `GET` be inferred is pragmatic for read-heavy surfaces.

> Interview anchor: "PR #25: `pom springdoc-openapi-starter-webmvc-ui 2.7.0:271` auto-registers `OpenApiWebMvcResource` → `GET /v3/api-docs` + `/swagger-ui.html`; `OpenApiConfig.java:24 OpenAPI` `Info:26 Title Order Management API v1:31` + `Components bearer-jwt 34-38 HTTP bearer SCOPE_order_read/write + api-key 39-43 X-API-Key HEADER` `SecurityRequirement bearer-jwt:44`; `@Operation:66` + `@ApiResponses 69-73 201/400/409` on `OrderController create:77` documents `201+Location:102` `Idempotency-Key:78` `ETag:139`; schema inferred from `OrderRequest:16/CustomerRequest:16` records+constraints; `SecurityConfig:60 permitAll` for docs; Boot 3.4 + springdoc 2.7 compatibility; `Pageable:129` plugin."

#### 2.11 OpenAPI generators — client SDKs from the spec

`openapi-generator` reads `/v3/api-docs` (`OpenApiConfig:24`) and emits `typescript-fetch`, `java`, `go` clients where `OrderRequest` becomes a typed `interface OrderRequest { customerId: number; items: OrderItemRequest[] }` with required fields derived from `@NotNull:17`. CI runs `curl /v3/api-docs -o openapi.json && diff` to fail the build on implicit schema change (new `required` without migration). The pattern scales: front-end `localhost:3000` (`application.yml:191`) auto-syncs via generated `openapi.json`.

#### 2.12 Versioning the spec — v1 prefix vs header negotiation

`/api/v1` (`OrderController:50`) ties OpenAPI grouping toURI path. GroupOpenApi beans filtered by `pathsToMatch("/api/v2/**")` would expose `v2` alongside `v1` during migration. Header `Accept: application/vnd.company.v1+json` would decouple URL from version at the cost of Swagger UI complexity (multiple `Accept`s). Path version here matches `Info.version v1:31` semantic.

#### 2.13 Security schemes in practice — try-it-out scoping

`OpenApiConfig:33-44` dual `bearer-jwt` + `api-key` lets Swagger UI store both schemes simultaneously; try-it-out sends `Authorization: Bearer <jwt>` or `X-API-Key: dev-api-key-orderapi` depending on the entered credentials. `SecurityConfig:72` `anyRequest authenticated` means unauthenticated try-it-out correctly receives `401 AUTHENTICATION_REQUIRED:42` rendered as `application/problem+json:61`, teaching the caller auth failure shape interactively.


---

## 3. Solution — ASCII

```
Spring Boot 3.4.1 runtime (mcp-spring-webmvc 0.18.4 → forces Boot 3.2.1→3.4.1 PR #37)
      │
      ├─ pom.xml:271-274  springdoc-openapi-starter-webmvc-ui:2.7.0
      │      auto-config: OpenApiWebMvcResource + SwaggerWelcomeCommon + swagger-ui webjar
      │            │
      │            ├─ GET /v3/api-docs        → OpenApiWebMvcResource → OpenAPIService.build()
      │            │                              1) read @Bean OpenAPI orderManagementApi OpenApiConfig.java:24
      │            │                                  Info title Order Management API v1:26 description 28-30
      │            │                                  Components bearer-jwt 34 type HTTP bearerFormat JWT
      │            │                                            api-key 39 type APIKEY in HEADER name X-API-Key 42
      │            │                                  SecurityRequirement bearer-jwt 44 (default lock per op)
      │            │                              2) scan HandlerMappings → @RestController OrderController.java:49 ProductController:30
      │            │                                  per handler: @Operation 66 summary/description, @ApiResponses 69-73 201/400/409,
      │            │                                  @RequestBody OrderRequest:80 → schema component OrderRequest (Long customerId @NotNull 17, items @NotEmpty @Valid 18, OrderItemRequest @NotNull 21-23)
      │            │                                  Pageable:129 → query params page,size,sort; ProblemDetail:56 default for 4xx/5xx
      │            │                              → JSON OpenAPI 3.1 document {openapi,info,paths/components/schemas/securitySchemes,tags}
      │            │
      │            └─ GET /swagger-ui.html   → SwaggerWelcome (redirect to /swagger-ui/index.html)
      │                 → swagger-ui bundle fetch(/v3/api-docs) → interactive op list with "Authorize bearer-jwt / api-key" + "Try it out"
      │                    POST /api/v1/orders shows 201 description "Order created" + schema OrderResponse:16 + fields Idempotency-Key etc.
      │
      └─ SecurityConfig.java:58-61  permitAll /v3/api-docs/** /swagger-ui/** even when auth enabled
             @RestControllerAdvice GlobalExceptionHandler:25 provides ProblemDetail 56 application/problem+json per 409/400 etc. visible in spec

 Request:  curl /v3/api-docs | jq '.openapi, .info.version, .paths."/api/v1/orders".post.security, .components.securitySchemes'
           → { openapi:"3.0.1", version:"v1", securitySchemes:{ bearer-jwt:{type:"http",scheme:"bearer"}, api-key:{type:"apiKey",in:"header",name:"X-API-Key"}}}
 Browser:  open http://localhost:8080/swagger-ui.html → Authorize (paste JWT or dev-api-key-orderapi) → Try POST /api/v1/orders
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `pom.xml` | `271-274` | springdoc dependency | `springdoc-openapi-starter-webmvc-ui:2.7.0` compatible with Boot `3.4.1` PR #37, replaces `2.3.0` |
| `src/main/java/com/company/orderapi/config/OpenApiConfig.java` | `20` | Config | `@Configuration OpenApiConfig`, produces `OpenAPI` bean |
| `OpenApiConfig.java` | `24-32` | `OpenAPI` Info | `Info:26` `title Order Management API:27`, `description 28-30` JWT or `X-API-Key`, `version v1:31`, `license:32` |
| `OpenApiConfig.java` | `33-45` | SecuritySchemes | `Components:33` `bearer-jwt:34` `HTTP bearer JWT 35-38` + `api-key:39` `APIKEY header X-API-Key:42` + default `SecurityRequirement bearer-jwt:44` |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `66-77` | Operation docs | `@Operation:66` summary+description ("ONE transaction… Idempotent…"), `@ApiResponses:69-73` `201/400/409`, `@PreAuthorize:74` SCOPE/API_KEY, `@PostMapping:75` |
| `OrderController.java` | `78-80` | Doc-relevant params | `Idempotency-Key:78` header, `HttpServletRequest:79`, `@Valid @RequestBody OrderRequest:80` → schema with `NotNull/NotEmpty` `17-18` |
| `OrderController.java` | `101-103,133-141` | Response idioms shown in spec | `201+Location:101-103`, `ETag:139`, `412:177` map to `ApiResponse` descriptions |
| `src/main/java/com/company/orderapi/api/rest/controller/ProductController.java` | `30,53,62` | Product ops inferred | No explicit `@Operation` (inferred from types), docs still list `POST 201 Location:56-58` etc. — candidate for `@Operation` annotation |
| `src/main/java/com/company/orderapi/security/SecurityConfig.java` | `60-61` | Docs permit-all | `requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()` even with `enabled:true` |
| `src/main/java/com/company/orderapi/api/dto/OrderRequest.java` | `15-24` | Schema source | Record + `@ValidOrderRequest:15` + `@NotNull/@NotEmpty @Valid:17-18` → OpenAPI required/minItems shapes |
| `src/main/java/com/company/orderapi/api/dto/OrderResponse.java` | `16-33` | Response schema | `OrderResponse`+`OrderItemResponse:26` inferred as response components |

```java
// OpenApiConfig.java:24-45 — single place declaring title + auth schemes
@Bean OpenAPI orderManagementApi(){
  return new OpenAPI()
    .info(new Info().title("Order Management API").description("... bearer JWT (scopes order_read/order_write) or X-API-Key ...").version("v1").license(new License().name("Learning project")))
    .components(new Components()
      .addSecuritySchemes("bearer-jwt", new SecurityScheme().type(HTTP).scheme("bearer").bearerFormat("JWT").description("OAuth2 ... order_read, order_write."))
      .addSecuritySchemes("api-key", new SecurityScheme().type(APIKEY).in(HEADER).name("X-API-Key").description("Static API key for machine clients.")))
    .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
}
// OrderController.java:66 — handler-documented
@Operation(summary="Place an order", description="Deducts stock ... Idempotent when an Idempotency-Key is sent.")
@ApiResponses({@ApiResponse(responseCode="201", description="Order created"), @ApiResponse(responseCode="400", description="Validation or payment failure"), @ApiResponse(responseCode="409", description="Stock conflict")})
public ResponseEntity<OrderResponse> create(@RequestHeader(value="Idempotency-Key",required=false) String key, @Valid @RequestBody OrderRequest req){...}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Build and boot
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.profiles.active=test
./mvnw spring-boot:run &

# Raw spec — verify it exists and is not 500 (regression from 2.3.0 vs Boot 3.4)
curl -s http://localhost:8080/v3/api-docs | jq '.openapi, .info.title, .info.version'
# Expect openapi:"3.0.1", title:"Order Management API", version:"v1"

# Security schemes declared
curl -s http://localhost:8080/v3/api-docs | jq '.components.securitySchemes'
# Expect bearer-jwt {type:http,scheme:bearer,bearerFormat:JWT} + api-key {type:apiKey,in:header,name:X-API-Key}

# Paths include orders/products/customers with expected methods
curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'
# Expect ["/api/v1/orders", "/api/v1/orders/{id}", "/api/v1/products", ...]

# Order POST annotated summary visible in spec
curl -s http://localhost:8080/v3/api-docs | jq '.paths."/api/v1/orders".post.summary'
# Expect "Place an order" (OrderController:66) or inferred

# Schemas include record DTOs and required constraints
curl -s http://localhost:8080/v3/api-docs | jq '.components.schemas | keys | map(select(contains("OrderRequest") or contains("OrderResponse")))'
curl -s http://localhost:8080/v3/api-docs | jq '.components.schemas.OrderRequest.required'
# Expect ["customerId","items"] inferred from @NotNull/@NotEmpty OrderRequest.java:17-18

# YAML alternative also served
curl -s http://localhost:8080/v3/api-docs.yaml | head -n 20

# Swagger UI static assets reachable without auth despite SecurityConfig permitAll:60-61
curl -s -I http://localhost:8080/swagger-ui.html | grep "200"
curl -s http://localhost:8080/swagger-ui.html | head -n 5
open http://localhost:8080/swagger-ui.html  # browser → Authorize → paste JWT or dev-api-key-orderapi → Try POST /api/v1/orders

# Generate a client SDK from the served spec (requires openapi-generator or springdoc generator)
curl -s http://localhost:8080/v3/api-docs > /tmp/openapi.json
# npx @openapitools/openapi-generator-cli generate -i /tmp/openapi.json -g typescript-fetch -o /tmp/client && ls /tmp/client

# Add a new operation doc in 30 seconds
# @Operation(summary="Refund an order", description="Reverses the charge ...")  @ApiResponse(responseCode="200"...)

# Branch check
grep -rn "OpenApiConfig\|@Operation\|@ApiResponse\|springdoc" src --include="*.java" --include="*.xml" | head
```

```java
// Document a new product operation — same pattern as OrderController:66
@Operation(summary="Create a product", description="Creates a catalogue entry. Cache evicted (ProductCatalogueService:60).")
@ApiResponses({@ApiResponse(responseCode="201", description="Product created"), @ApiResponse(responseCode="400", description="Validation failure")})
@PostMapping public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req){ ... }
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `springdoc-openapi-starter-webmvc-ui 2.7.0` | `pom 271` scanner | `springfox swagger2` or hand `openapi.yaml` | Actively maintained for Boot `3.4`, `OpenApiWebMvcResource` scans `@Operation`+types → spec without YAML upkeep | Bound to boot version matrix `2.7.0` ↔ `3.4.1` (old `2.3.0` gave `500` on `/v3/api-docs` PR #37) |
| Global `OpenApiConfig:20` `bearer-jwt + api-key` schemes | `Components 33-43` + default `SecurityRequirement bearer-jwt 44` | No `SecurityConfig` tie-in, rely on `@SecurityRequirement` per method | One place declares "auth is bearer JWT or API key" (`SecurityConfig:54 ApiKey filter`) — Swagger UI Authorize matches runtime | `api-key` not default in `SecurityRequirement` → must pick it manually in UI unless added |
| `@Operation/@ApiResponses` on `OrderController:66-73` | Co-located docs on write methods | External `openapi.yaml` or no annotation (pure inference) | Rich `201/400/409` descriptions next to the `201+Location:102` code; failures are searchable from the spec | New handlers must remember to add `@Operation` ; `GET` inference is generic |
| `permitAll` `/v3/api-docs/**` + `/swagger-ui/**` `SecurityConfig:60-61` | Public docs even with `enabled:true` | Protect `/v3/api-docs` with `SCOPE_openapi` | Spec is not secret; unauthenticated discovery helps onboarding | May hide spec in locked prod if desired → replace `permitAll` with scope |
| Records as schemas `OrderRequest:16/CustomerRequest:16` | Component-derived `required` from `@NotNull/@NotBlank` `17-18` | Separate `Schema` objects or `example` annotations | Validation annotations double as documentation (`required`, `minItems`, `pattern`) | `groups Create vs Update` (`CustomerRequest:17` `groups`) not reflected as `required` strictness in one schema |
| Swagger UI dispatched by default | Included `starter-webmvc-ui` (JSON+yaml+UI) | `springdoc-openapi-starter-webmvc-api` (JSON only) | Try-it-out against live `dev-api-key-orderapi` or JWT same origin without extra tooling | UI bundle increases classpath; disable with `springdoc.swagger-ui.enabled=false` |

---

## 7. How to verify

```bash
# Dependency pinned
grep -n "springdoc-openapi" pom.xml  # 271 2.7.0

# Config present
grep -n "OpenApiConfig\|OpenAPI\|SecurityScheme\|bearer-jwt\|api-key\|SecurityRequirement" src/main/java/com/company/orderapi/config/OpenApiConfig.java

# Controllers document writes
grep -n "@Operation\|@ApiResponse" src/main/java/com/company/orderapi/api/rest/controller/*.java

# Security allows spec without auth
grep -n "v3/api-docs\|swagger-ui" src/main/java/com/company/orderapi/security/SecurityConfig.java  # 60-61 permitAll

# Live checks (running app)
curl -s http://localhost:8080/v3/api-docs | jq -e '.info.title=="Order Management API" and .info.version=="v1"' && echo spec-ok
curl -s http://localhost:8080/v3/api-docs | jq -e '.components.securitySchemes."bearer-jwt" and .components.securitySchemes."api-key"' && echo schemes-ok
curl -s http://localhost:8080/v3/api-docs | jq -e '.paths."/api/v1/orders".post.responses."201"' && echo 201-doc-ok
curl -s -I http://localhost:8080/swagger-ui.html | grep -q "200" && echo ui-ok
curl -s http://localhost:8080/v3/api-docs.yaml | grep -q "openapi:" && echo yaml-ok

# No 500 regression (the 2.3.0 bug on Boot 3.4)
curl -s -w "%{http_code}" http://localhost:8080/v3/api-docs -o /tmp/spec.json; grep -q '"openapi"' /tmp/spec.json && echo ok
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Every new `POST/PUT/PATCH/DELETE` handler add `@Operation(summary, description=what transaction does + idempotency/ETag caveat)` + `@ApiResponses(responseCode, description)` mapping the `ResponseEntity` codes (`OrderController.java:66-73` model) → `GlobalExceptionHandler.java:66 ProblemDetail` codes appear as `400/404/409/502` consumers can codegen around. `OrderRequest:16` record constraints `@NotNull/@NotEmpty` auto-enforce `required` in the schema; no separate docs YAML. Generate SDK with `curl /v3/api-docs > openapi.json` + `openapi-generator`.
- **Operate:** Monitor spec drift: `curl /v3/api-docs | jq hashes` in CI and fail on unexpected `required` or `securitySchemes` change. Hide `swagger-ui` in hardened prod by setting `springdoc.swagger-ui.enabled=false` or guarding `60-61` `permitAll` → scoped. The `Info:26` description doubles as the unauthenticated "how to authenticate" page visible even when API is `enabled:true`.
- **Interview:** "PR #25: `pom 271 springdoc-openapi 2.7.0` (upgraded from `2.3.0` after Boot `3.4.1` PR #37) registers `OpenApiWebMvcResource` `GET /v3/api-docs` + Swagger UI `/swagger-ui.html`; `OpenApiConfig.java:24` `OpenAPI` `Info v1:31` + `Components securitySchemes bearer-jwt 34 HTTP bearer JWT + api-key 39 HEADER X-API-Key 42` `SecurityRequirement bearer-jwt:44`; `@Operation 66` + `@ApiResponses 69-73 201/400/409` on `OrderController.create:77` documents `201+Location:102` `Idempotency-Key:78` `ETag:139`; schemas inferred from `OrderRequest:16`/`CustomerRequest:16` + `Pageable:129`; `SecurityConfig:60 permitAll` exposes docs; compatibility pin Boot 3.4↔springdoc 2.7."

---

## 9. Interview lens — Q&A

**Q1: Where does `/v3/api-docs` come from?**
A: `pom 271 springdoc:2.7.0` auto-config `OpenApiWebMvcResource` `OpenAPIService.build()` reads `@Bean OpenAPI:24` (`Info:26` + `Components` `bearer-jwt:34`/`api-key:39`) and scans `DispatcherServlet` `HandlerMappings` (`OrderController:66` etc.) to emit JSON at `/v3/api-docs` (§2.2).

**Q2: How do auth schemes in `SecurityConfig` appear in Swagger UI?**
A: Declared as `SecurityScheme` `bearer-jwt:34` `HTTP bearerFormat JWT` and `api-key:39` `APIKEY HEADER X-API-Key:42` (`OpenApiConfig.java:33-43`) — UI shows Authorize for both, `ApiKeyAuthenticationFilter:23` and `NimbusJwtDecoder` (`SecurityConfig:84`) are the runtime enforcement of those schemes (§2.3).

**Q3: What does `@Operation` + `@ApiResponse` add over inference?**
A: `OrderController.java:66-73` `@Operation` summary/description + `@ApiResponses 201/400/409` map the handler `201+Location:102`, `400` validation (PR #23), `409` `CONCURRENT_MODIFICATION/INSUFFICIENT_STOCK` (PR #24 `GlobalExceptionHandler:81,91`) explicitly vs bare inferred `200` (§2.4).

**Q4: How are DTO constraints reflected in the spec?**
A: `@NotNull/@NotEmpty/@Email/@Size/@Pattern` on `OrderRequest:17-18`/`CustomerRequest:17-25` set `required`, `minItems`, `pattern`, `minLength` etc. via Jackson+springdoc introspection; changing a constraint changes the spec on next boot without editing YAML (§2.5).

**Q5: Why is `/v3/api-docs` permitted despite security `enabled:true`?**
A: `SecurityConfig.java:60-61` `requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()` — spec/ UI are unauthenticated discovery. Harden by changing to scoped auth if needed (§2.7).

**Q6: Why `2.7.0` not `2.3.0`?**
A: `2.3.0` broke returning `500` on `/v3/api-docs` after Boot `3.4.1` broke `RequestMappingInfo` introspection; `2.7.0` is the `Boot 3.4` compatible line (pom `270-274` comment, PR #37 MCP `3.4.1` collateral) (§2.8).

---

## 10. Honest limits & next step → PR #26

Inference covers only described operations: `ProductController:30` without `@Operation` is generically listed; field-level `example` values not populated for records unless `@Schema(example=…)` added. `Idempotency-Key:78` header `required=false` appears as "optional" even though idempotent clients *should* send it; marking it `required` per spec would lie for backwards-compatible callers. Groups `Create vs Update` (`CustomerRequest:17 groups`) share one `components/schemas/CustomerRequest` schema — `required` cannot be strict per group. Next PR is the runtime for the schemes just declared: `SecurityFilterChain` (`SecurityConfig.java:45 SecurityFilterChain`), `NimbusJwtDecoder:84 JwtDecoder HS256`, `ApiKeyAuthenticationFilter:23 ROLE_API_KEY`, and `JwtAuthenticationConverter` mapping `scope/order_write` → `SCOPE_order_write`.

See [`26-security-with-oauth2-and-jwt.md`](./26-security-with-oauth2-and-jwt.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Machine spec at `/v3/api-docs` | `springdoc 2.7.0` | `pom:271`, `OpenApiConfig.java:24` | Scanner-derived, no YAML drift |
| Interactive explorer with auth | `swagger-ui` webjar | `pom:271` + `SecurityConfig.java:60-61 permitAll` | "Try it out" with `bearer-jwt` or `X-API-Key` same origin |
| Declare `Authorization` vs `X-API-Key` | `SecurityScheme` `bearer-jwt`/`api-key` | `OpenApiConfig.java:34-43` | UI Authorize matches `SecurityConfig:54` runtime |
| Document `201+Location` + `Idempotency` + `ETag` | `@Operation` + `@ApiResponses` | `OrderController.java:66-80,101-103,139` | Client sees `201/400/409` without reading source |
| Keep schema tied to validation | Record `@NotNull etc.` → `required` | `OrderRequest.java:17-18` | Single source for runtime and contract |
| Upgrade proof against `500 /v3/api-docs` | `2.3.0→2.7.0` for Boot `3.4.1` | `pom 270-274 comment` | Matrix compatibility with `mcp-spring-webmvc 0.18.4` |
