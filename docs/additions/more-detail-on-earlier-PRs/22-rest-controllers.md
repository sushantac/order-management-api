# 22. REST Controllers (PR #22)

> PR #22 — REST Controllers: `OrderController`/`ProductController`/`CustomerController` thin HTTP surface, `ResponseEntity` status codes, `Location` header, `ETag`/`If-Match`, `Pageable`. Stack: Java 21, Spring Boot 3.4.1, Spring MVC (DispatcherServlet), Jackson, `src/main/java/com/company/orderapi/api/rest/controller/...` + `api/dto/*` records (PR #21) + `GlobalExceptionHandler` (PR #24 seam) + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 22 | REST Controllers |`.

---

## 1. Purpose — what shipped

PR #22 turns the service layer (PR #20) and record DTOs (PR #21) into an HTTP API. Controllers are intentionally thin: validate `@Valid`/`@Validated`, delegate to `OrderService`/`ProductCatalogueService`, map entities → `OrderResponse`/`ProductResponse` via `OrderMapper`, and return `ResponseEntity` with the correct status code, `Location` on `201`, `ETag` on `GET /{id}`, and paged `Page<OrderResponse>` on `GET /api/v1/orders`. Shipped: `OrderController.java:49-186` (place `POST /api/v1/orders`, bulk, list, get with ETag, `PATCH /{id}` JSON Patch, `DELETE` with `If-Match`), `ProductController.java:30-73` (`GET` catalogue-cached `get:49`, `POST 201 Location:56-58`, `PUT`, `DELETE 204:71`), `CustomerController` parallel shape, and Spring MVC wiring (`DispatcherServlet`, `RequestMappingHandlerMapping`, `RequestMappingHandlerAdapter`).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `OrderService.placeOrder()` (`OrderService.java:83`) existed but was only callable from tests/jobs. No HTTP route, no mapping from JSON → `OrderRequest` record, no HTTP semantics — every client needed direct Java calls. Status codes were ad-hoc, `Location` not set, concurrency control (`ETag`) absent, pagination manual.

**After:** `POST /api/v1/orders` (`OrderController.java:75-104`) binds `@Valid @RequestBody OrderRequest:80`, delegates to `orderService.placeOrder:91`, builds `201 Created` with `Location: /api/v1/orders/{id}` (`ResponseEntity.created(URI.create(...)):101-103`) and body `OrderResponse`. `GET /{id}:133-141` returns `200` + `ETag: "v{version}"` (`etag:183`), `DELETE:170-181` checks `If-Match` → `412 Precondition Failed` on stale version, `GET /:127-131` is `Page<OrderResponse>` via `Pageable` (`findOrdersPaged:130`). Validation failures → `400` via `GlobalExceptionHandler:67` `MethodArgumentNotValidException`, domain missing → `404`.

### Theory — REST controllers, DispatcherServlet, and HTTP semantics from first principles (100+ lines)

#### 2.1 Why a controller — the HTTP boundary vs the domain boundary

The domain (`Order.java:42`, `OrderService.java:49`) knows invariants (`cancel():151`, `ship():177`, `version:53`) and transactions (`@Transactional:76`). The controller knows HTTP: verbs (`POST` create, `GET` read, `PATCH` partial update, `DELETE` remove), URIs (`/api/v1/orders:50`), headers (`Idempotency-Key:78`, `If-Match:173`, `ETag:139`, `Location:102`), query (`Pageable:129`), and status codes (`201/200/204/400/404/409/412`). Coupling the two would leak HTTP into `OrderService` (testing needs MockMvc) and leak transactions into HTTP filters — thin controllers keep reuse (`OrderService.placeOrder` callable from Kafka consumer too, `CartCheckoutEventConsumer`).

#### 2.2 DispatcherServlet — the front controller and its pipeline

Every request hits `DispatcherServlet` (Spring Boot's `DispatcherServletAutoConfiguration`):

```
HTTP request
  → Tomcat Connector → Servlet container
    → DispatcherServlet.service()        // single entry
      → HandlerMapping  (RequestMappingHandlerMapping scans @RequestMapping/@GetMapping/@PostMapping on OrderController:50,75,127,133,148,170)
        resolves handler = HandlerMethod(OrderController#create, ProductController#get, ...)
      → HandlerAdapter  (RequestMappingHandlerAdapter)
        → argument resolvers: @RequestBody → MappingJackson2HttpMessageConverter (Jackson)
                             @RequestHeader("Idempotency-Key") → HeaderMethodArgumentResolver
                             @PathVariable, Pageable → PageableHandlerMethodArgumentResolver
                             HttpServletRequest → ServletRequestMethodArgumentResolver
        → invoke controller method (reflective call)
        → return value resolvers: ResponseEntity → HttpEntityMethodProcessor
                                 Page<T> → Pageable serialization
                                 OrderResponse → MappingJackson2HttpMessageConverter.write()
      → Exception handling: HandlerExceptionResolver → @RestControllerAdvice GlobalExceptionHandler.java:25
      → View not needed (REST returns body, not view name)
  → HTTP response bytes
```

`DispatcherServlet` is a `FrameworkServlet` (a `HttpServlet`). Its `doDispatch()` sequence is deterministic: `getHandler()` → `getHandlerAdapter()` → `handle()` → `processDispatchResult()`. Failure at any resolver (`MethodArgumentNotValidException` from `@Valid:80`) never reaches the controller — it short-circuits to the exception resolver.

#### 2.3 `@RestController` vs `@Controller` + `HttpMessageConverter`

`@RestController` (`OrderController.java:49`) = `@Controller` + `@ResponseBody` on every handler. Without it, return value `OrderResponse` would be interpreted as a view name. The converter chain (`MappingJackson2HttpMessageConverter` with `ObjectMapper` that has `ParameterNamesModule` for records, `JavaTimeModule` for `LocalDateTime`) serializes `OrderResponse` (`OrderResponse.java:16`) to JSON via component accessors. Content negotiation (`Accept: application/json`) selects `application/json`; `Content-Type: application/json` triggers JSON read via the same converter before `@Valid` runs.

#### 2.4 `ResponseEntity` — owning the status line and headers

```java
// OrderController.java:101-103  201 + Location + body
return ResponseEntity.created(URI.create("/api/v1/orders/" + order.getId())).body(response);
// OrderController.java:138-140  200 + ETag + body
return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(order)).body(OrderMapper.toOrderResponse(order));
// ProductController.java:56-58  201 + Location
return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
// ProductController.java:71  204 No Content
return ResponseEntity.noContent().build();
// OrderController.java:177  412 on stale If-Match
return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
```

`ResponseEntity<T>` is `HttpEntity<T>` + `HttpStatusCode`. It gives the handler full control vs returning bare `OrderResponse` (implicitly `200`) or `void`. REST idioms: `POST` creating a resource → `201 Created` + `Location` header (`RFC 9110 §15.3.2`); `DELETE` succeeding → `204 No Content` (no body needed); `GET` idempotent safe → `200` with `ETag` for optimistic concurrency. Skipping `Location` forces the client to guess the new URI — the header makes the contract explicit and lets clients `GET Location` without parsing the body.

#### 2.5 Location header and URI construction

`URI.create("/api/v1/orders/" + order.getId())` (`OrderController.java:102`) uses the generated `order.getId()` after `orders.saveAndFlush` inside the transaction (ID assigned by `IDENTITY`/`SEQUENCE`). Alternative `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(order.getId()).toUri()` derives the base from the incoming request (host/port aware) — the literal `/api/v1/orders/...` is stable in this app because the mapping is static (`@RequestMapping("/api/v1/orders"):50`) and tests assert the path shape directly.

#### 2.6 ETag and `If-Match` — optimistic concurrency over HTTP

```java
// OrderController.java:183
private String etag(Order order) { return "\"v" + order.getVersion() + "\""; }
// GET  →  ETag: "v5"
// DELETE  If-Match: "v5" → compare; mismatch → 412
if (ifMatch == null || !etag(order).equals(ifMatch.trim())) return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build(); //176-177
```

`Order.version` is the JPA `@Version` (`BaseEntity.java:53`). The ETag is *weak* in spirit but sent as a strong validator (`"v5"`) — `Order` is mutable, so version increments on every flush (`ship():177`, `cancel():151`). Without `If-Match`, two concurrent `DELETE` could both read `v5` and one silently overwrites the other's later `PATCH` — the precondition forces the loser to re-read and retry. `GET` also returns `ETag` so the client can cache and use `If-None-Match` (implemented by Spring's `ShallowEtagHeaderFilter` optionally, not active here — explicit header is set).

#### 2.7 PATCH vs PUT — partial update semantics

`PATCH /orders/{id}:148-167` accepts a `JsonNode` array of `{"op":"replace","path":"/status","value":"CONFIRMED"}`. `PUT /products/{id}:61-66` would send the full `ProductRequest` (name+price+stock+description). `PATCH` is correct for "change one field" (status transition) because `PUT` semantics are *replace entire resource* — sending partial `PUT` would null out missing fields. The handler checks `op=="replace"` + `path=="/status"`:157-159 else `400` (`IllegalArgumentException` → `GlobalExceptionHandler:88` `INVALID_ARGUMENT`). A full RFC 6902 engine (`zjsonpatch`) would handle `add/remove/move/test` — overkill for one status transition here.

#### 2.8 Pageable and paged list — avoiding unbounded reads

`list(Pageable pageable):129` exposes `GET /api/v1/orders?page=0&size=20&sort=orderDate,desc`. Spring Data's `PageableHandlerMethodArgumentResolver` parses query params into `PageRequest`. `OrderRepository.findOrdersPaged(pageable):130` carries `Pageable` into JPQL (`SELECT o FROM Order o ...`) with `LIMIT/OFFSET` + `COUNT` query. Returning `Page<OrderResponse>` includes `totalElements/totalPages` so the client can paginate deterministically. Alternative: raw `List<OrderResponse>` would stream the whole table — unbounded memory + DB load for large tenants.

#### 2.9 Validation entry point — `@Valid` before delegation

`@Valid @RequestBody OrderRequest:80` triggers `MethodValidationPostProcessor` → `Validator` (Hibernate Validator) validates the record before the method body runs. Failure throws `MethodArgumentNotValidException` → `GlobalExceptionHandler:68` `400 VALIDATION_ERROR` with `application/problem+json`. Controller never sees invalid `customerId==null` or `items==[]`. Grouped validation (`@Validated(Create.class)`) is available for `POST` vs `PUT` distinct rules (PR #23, `CustomerRequest.java:16`), but `OrderRequest:17-18` uses ungrouped `@NotNull/@NotEmpty` sufficient here.

#### 2.10 `@Transactional` on the controller — spanning the read

`@Transactional:76` on `create:77` and `@Transactional(readOnly=true):128` on `list/get:135` keep the Hibernate `Session` open while mapping `Order` + lazy `items/product` to `OrderResponse` via `OrderMapper`. Without it, `order.getItems():` would throw `LazyInitializationException` after the service transaction closed. Placing it on the controller is intentional here (web layer owns the view boundary) — alternative is `OpenSessionInView` (anti-pattern, holds a DB connection for view rendering) or eager fetch joins inside the service; controller `readOnly` is cheaper than `OSIV`.

#### 2.11 Alternatives and when to deviate

- WebFlux (`@RestController` → reactive `Mono<ResponseEntity>`) for 10k+ concurrent streams; Servlet MVC (this app) is simpler with blocking JPA.
- `ResponseEntity` everywhere vs bare return: bare `OrderResponse` suffices for `200` lists, but `201` + `Location` and `412`/`204` demand `ResponseEntity`.
- `Location` built via `MvcUriComponentsBuilder` for host-aware URIs behind reverse proxies (`X-Forwarded-*`).
- `ETag` via `ShallowEtagHeaderFilter` (digest of response body) vs version-based strong ETag here — body-digest changes on any JSON formatting change, version is stable.
- `PATCH` full `zjsonpatch` vs single-field `JsonNode` handling — scale to spec if multiple patch paths needed.

> Interview anchor: "PR #22: `OrderController.java:49` `@RestController @RequestMapping('/api/v1/orders'):50` through `DispatcherServlet` (`HandlerMapping` → `HandlerAdapter` → `HttpMessageConverter` for record `OrderRequest:80` with `@Valid`, `ResponseEntity.created(URI.create(...)):102` `201+Location`, `GET 139 ETag` + `DELETE 176 412` on `If-Match` mismatch `BaseEntity:53 version`, `PATCH 148 JsonNode replace /status`, `Pageable 129 Page<OrderResponse>`, `@Transactional 76/128` keeping Session for lazy→`OrderMapper` mapping. Thin delegation to `OrderService:91 placeOrder`. Next layers: PR #23 groups, PR #24 ProblemDetail."

#### 2.12 Request mapping precision — path, consume, produce, and versioning

`@RequestMapping("/api/v1/orders"):50` fixes the URI space version `v1`. Alternative is header versioning (`Accept: application/vnd.company.v1+json`) — path versioning is grep-friendly and Swagger-visible (`OpenApiConfig.java:31 version v1`). `@PostMapping` without `consumes` defaults `application/json` negotiated via `Content-Type`; adding `consumes="application/json"` would 415 on XML. `produces` defaults via `HttpMessageConverter` - `OrderResponse` → JSON because `MappingJackson2HttpMessageConverter` wins `Accept: application/json`. Path vs query split: `/orders/{id}:133` identity path param vs `?page=0&size=20:129` query for collection paging - keeps URL bookmarkable (`GET` safe idempotent).

#### 2.13 Idempotency inside REST — why the controller calls IdempotencyService before the service

`Idempotency-Key:78` turns `POST` (non-idempotent per RFC 9110) into logically idempotent: `POST /orders` after a timeout success must not create a second `Order` and `charge:112` twice. The check-before `find:84` → replay `StoredResponse:57` versus record-after `idempotency.record:98-99` into `IdempotencyRecord:27 unique` is wired in the controller because the duplicate scope is per HTTP request, not per `OrderService.placeOrder` Java call. Moving dedup into `OrderService` would couple the retry domain to HTTP headers.

#### 2.14 Observability of REST — tags for metrics and tracing

Every `DispatcherServlet` handling emits `http.server.requests` Micrometer timer tagged `uri=/api/v1/orders`, `method=POST`, `status=201`, `exception=None` vs `400 VALIDATION_ERROR:68`. `@Timed("product.get"):52` on catalogue read is the domain-level histogram distinct from the HTTP histogram. Traces (PR #32 `ObservabilityConfig`) propagate `traceId` through `DispatcherServlet` filters so `PiiRedactionFilter:31` log line can be correlated to the HTTP status emitted.


---

## 3. Solution — ASCII

```
HTTP Request  { "customerId":1, "items":[{productId:42,quantity:2}] } + Idempotency-Key
      │
      ▼  Tomcat → DispatcherServlet  (HandlerMapping scans OrderController:49, ProductController:30)
          HandlerAdapter: argument resolvers  @RequestBody→Jackson(record OrderRequest:16)+@Valid:80
                                          @RequestHeader Idempotency-Key:78, Pageable:129, @PathVariable:135
      │
      ▼  OrderController thin  OrderController.java:49
          76 @Transactional  (Session open for lazy→Mapper)
          77 create(@Valid OrderRequest)  ──idempotency check  IdempotencyService.java:28 find
              │
              ├─ delegate  orderService.placeOrder(customerId, OrderLine...) :91  // PR #20 @Transactional + Retryable
              │          OrderService.java:76-83  (stock --, addItem, charge, saveAndFlush, outbox)
              │
              ├─ map  OrderMapper.toOrderResponse(order)  → record OrderResponse.java:16 (immutable DTO, PR #21)
              │
              ├─ record idempotent result  idempotency.record(key, method, path, 201, response) :98-99
              │
              └─ return  ResponseEntity.created(URI.create("/api/v1/orders/"+id)):101-103  → 201 + Location + JSON body
                         GlobalExceptionHandler.java:25 handles MethodArgumentNotValidException→400, IllegalArgument→404, else 500

 GET /api/v1/orders?page=0&size=20  →  list(Pageable:129) → orders.findOrdersPaged:130 → Page<OrderResponse> 200
 GET /api/v1/orders/{id}           →  get:133  ResponseEntity.ok().header(ETAG, "\"v"+version+"\""):139-140 → 200 + ETag
 PATCH /{id}  JsonNode[replace /status] → patch:148 → orderService.updateOrderStatus:156 → 200 OrderResponse
 DELETE /{id}  If-Match: "v5"      →  delete:170 → etag==If-Match? 204 else 412:177  (version BaseEntity:53)

 ProductController.java:30  parallels:
   GET /{id}:48 catalogue.get(id):54 @Cacheable (PR #28)  → ProductResponse
   POST:53-58 @Valid ProductRequest → catalogue.create → 201 + Location "/api/v1/products/{id}"
   PUT:61-66 @Valid → catalogue.update → 200
   DELETE:68-72 → 204
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `49-51` | REST surface orders | `@RestController`, `@RequestMapping("/api/v1/orders")`, injects `OrderService:53`, `OrderRepository:54`, `IdempotencyService:55`, `ObjectMapper:56` |
| `OrderController.java` | `75-104` | `POST /api/v1/orders` create | `@PreAuthorize:74` `SCOPE_order_write`/`ROLE_API_KEY`, `@Transactional:76`, `@Valid @RequestBody OrderRequest:80`, `Idempotency-Key` header:78, replay `StoredResponse:86-87`, `ResponseEntity.created(URI.create("/api/v1/orders/"+id)):101-103` `201` |
| `OrderController.java` | `106-113` | Idempotency replay helper | `fromStored()` parses `JsonNode` → `OrderResponse` via `objectMapper:109` |
| `OrderController.java` | `115-125` | `POST /bulk` | `List<OrderRequest>` batch create, `201` with list body |
| `OrderController.java` | `127-131` | `GET /api/v1/orders` list | `Pageable:129`, `orders.findOrdersPaged:130` → `Page<OrderResponse>`, `@Transactional(readOnly=true):128` |
| `OrderController.java` | `133-141` | `GET /{id}` with ETag | `ETag` header `"v"+version:139,183`, `IllegalArgumentException` unknown → `404` via `GlobalExceptionHandler:85` |
| `OrderController.java` | `148-167` | `PATCH /{id}` JSON Patch | `JsonNode:150`, loop `replace /status:155-156` → `orderService.updateOrderStatus:156`, else `400` |
| `OrderController.java` | `170-185` | `DELETE /{id}` `If-Match` | Header `If-Match:173`, `etag vs ifMatch:176` → `412:177` vs `204:180`, method `etag:183 "\"v"+version+"\""` |
| `src/main/java/com/company/orderapi/api/rest/controller/ProductController.java` | `30,48-72` | Product CRUD parallel | `GET /{id}:48 catalogue.get:49` (cached), `POST 201 Location:56-58`, `PUT:61-66`, `DELETE 204:71` |
| `src/main/java/com/company/orderapi/api/rest/controller/CustomerController.java` | — | Customer CRUD parallel | `POST @Validated(Create.class) CustomerRequest:16` vs `PUT @Validated(Update.class)`, `GET Page`, `DELETE` |
| `src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java` | `25,48-68` | Exception→HTTP mapping | `@RestControllerAdvice:25`, `Exception` → `ProblemDetail` + `code/hint` classify `MethodArgumentNotValidException→400` etc. |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `49,76-83` | Service called by controller | `@Service:49`, `@Transactional:76` + `@Retryable:77` placeOrder delegate at `OrderController:91` |
| `src/main/java/com/company/orderapi/api/dto/OrderMapper.java` | — | Entity→record mapper | `toOrderResponse(Order):` canonical `OrderResponse:16` construction, snapshot `unitPrice` |
| `src/main/java/com/company/orderapi/api/dto/OrderRequest.java` | `15-24` | Record request bound with `@Valid` | `@ValidOrderRequest:15`, `@NotNull customerId:17`, `@NotEmpty @Valid items:18` |
| `src/main/java/com/company/orderapi/api/dto/OrderResponse.java` | `16-33` | Record response serialized | `OrderResponse` + nested `OrderItemResponse:26` |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Context loads controller beans; `MockMvc` would exercise `DispatcherServlet` |

```java
// OrderController.java:75-104 — idiomatic REST create with 201 + Location + Idempotency
@PostMapping @Transactional
public ResponseEntity<OrderResponse> create(
        @RequestHeader(value="Idempotency-Key", required=false) String key,
        HttpServletRequest request,
        @Valid @RequestBody OrderRequest req) {
    if (key!=null && !key.isBlank()) { var s=idempotency.find(key); if(s.isPresent()) return ResponseEntity.status(s.get().status()).body(fromStored(s.get())); }
    Order order = orderService.placeOrder(req.customerId(), req.items().stream().map(i->new OrderService.OrderLine(i.productId(),i.quantity())).toList());
    OrderResponse resp = OrderMapper.toOrderResponse(order);
    if (key!=null && !key.isBlank()) idempotency.record(key, request.getMethod(), request.getRequestURI(), 201, resp);
    return ResponseEntity.created(URI.create("/api/v1/orders/"+order.getId())).body(resp);
}
// ProductController.java:52-58 — 201 with Location
@PostMapping public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req){
    ProductResponse c = catalogue.create(req.name(), req.price(), req.stockQuantity(), req.description());
    return ResponseEntity.created(URI.create("/api/v1/products/"+c.id())).body(c);
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Build and run
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.profiles.active=test
./mvnw spring-boot:run &

# Place order → 201 Created + Location header + body
curl -i -s -X POST http://localhost:8080/api/v1/orders \
  -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | head -n 20
# Expect: HTTP/1.1 201 … Location: /api/v1/orders/42 … { "id":42, "orderNumber":"ORD-...", "status":"PLACED" }

# Replay same Idempotency-Key → 201 same body without double charge
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: fixed-key-123" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq .id
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: fixed-key-123" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq .id  # same

# List paged
curl -s "http://localhost:8080/api/v1/orders?page=0&size=5&sort=orderDate,desc" -H "X-API-KEY: dev-api-key-orderapi" | jq '.content[0] | {id,orderNumber,status}'

# Get with ETag
ETAG=$(curl -s -i http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" | grep -i etag | awk '{print $2}' | tr -d '\r')
echo $ETAG  # "v1"
curl -s http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" | jq .status

# DELETE with If-Match (optimistic concurrency over HTTP)
curl -i -s -X DELETE http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" -H "If-Match: $ETAG"
# Stale version → 412 Precondition Failed
curl -i -s -X DELETE http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" -H "If-Match: \"v0\"" | head -n 1

# PATCH change status
curl -s -X PATCH http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '[{"op":"replace","path":"/status","value":"CONFIRMED"}]' | jq .status

# Product catalogue parallels
curl -s -X POST http://localhost:8080/api/v1/products -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"name":"Widget","price":9.99,"stockQuantity":100,"description":"A widget"}' -i | head -n 10
# Expect 201 Location: /api/v1/products/...

# Validation failure → 400 ProblemDetail
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":null,"items":[]}' | jq '.code, .status, .title'
# Expect VALIDATION_ERROR 400
```

```java
// Thin delegation pattern — copy for new resources
@RestController @RequestMapping("/api/v1/widgets")
public class WidgetController {
    @PostMapping public ResponseEntity<WidgetResponse> create(@Valid @RequestBody WidgetRequest req){
        Widget w = widgetService.create(req.name());
        return ResponseEntity.created(URI.create("/api/v1/widgets/"+w.getId())).body(WidgetMapper.toResponse(w));
    }
    @GetMapping("/{id}") public ResponseEntity<WidgetResponse> get(@PathVariable Long id){
        Widget w = widgets.findById(id).orElseThrow(()->new IllegalArgumentException("Unknown widget "+id));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, "\"v"+w.getVersion()+"\"").body(WidgetMapper.toResponse(w));
    }
}
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `ResponseEntity` with explicit status+headers | `201+Location:102`, `200+ETag:139`, `204:180`, `412:177` | Bare `OrderResponse` return (always `200`) | Precise REST semantics; clients discover URI via `Location`, concurrency via `ETag/If-Match` | Every handler constructs `ResponseEntity` |
| `URI.create("/api/v1/..."+id)` literal | `OrderController.java:102`, `ProductController.java:57` | `MvcUriComponentsBuilder` from request | Stable, test-assertable path; no proxy `X-Forwarded-Host` handling needed in this app | Hardcoded base — reverse-proxy host not reflected |
| Version-based `ETag "\"v"+version+"\""` `183` | Strong validator tied to `BaseEntity.version:53` | `ShallowEtagHeaderFilter` body digest | Tells client which DB version it saw; mismatch `412` maps to real concurrent modification, not JSON formatting change | Requires version column on every aggregate |
| `PATCH` single-field `JsonNode` `148-159` | Manual `replace /status` check | Full RFC 6902 `zjsonpatch` | Minimal dependency for one transition; status machine `OrderService:156` is narrow | Adding more patch paths needs `zjsonpatch` |
| `@Transactional` on controller `76,128` | Session open for `Order → OrderResponse` mapping | `OSIV` or fetch joins in service | Explicit, per-handler control; `readOnly` lists skip flush | Two tx boundaries (controller + service) join via `REQUIRED` — must avoid long controller hold |
| `Pageable` paged list `129-130` | `Page<OrderResponse>` `Pageable` | `List` unbounded | Bounded memory/DB `LIMIT/OFFSET`, `totalPages` for clients | `COUNT(*)` extra query per page |
| Thin delegation `orderService.placeOrder:91` | Controller validates/maps/delegates only | Rich controller with stock logic | Reuse from Kafka consumers/jobs; tx boundary `OrderService:76` owns atomicity (PR #20) | Extra mapping step `OrderMapper` |
| Idempotent `POST` with `Idempotency-Key:78` | Check `IdempotencyService:28` replay else record `98-99` | Non-idempotent `POST` (retry duplicates order) | Network retries/replays without double `charge` (PR #24 `IdempotencyRecord.java:27` unique key) | Extra `idempotency_keys` row per guarded POST |

---

## 7. How to verify

```bash
# Handler mappings registered
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "Mapped"
# Or at runtime
curl -s http://localhost:8080/actuator/mappings 2>/dev/null | jq '.contexts.application.mappings.dispatcherServlets.dispatcherServlet[] | select(.predicate | contains("/api/v1/orders")) | {predicate, details}'

# Status codes + headers via curl
curl -i -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | grep -E "HTTP/|Location:|ETag:|201"

# ETag present on GET
curl -i -s http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" | grep -i etag

# Precondition failure 412 on stale If-Match
curl -i -s -X DELETE http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" -H "If-Match: \"v0\"" | grep "412"

# Validation 400 shape (ProblemDetail)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":null,"items":[]}' | jq '.code,.status,.title'  # VALIDATION_ERROR 400

# Paged list bounded
curl -s "http://localhost:8080/api/v1/orders?page=0&size=2" -H "X-API-KEY: dev-api-key-orderapi" | jq '{totalElements, totalPages, size: .content|length}'

# Contract tests via MockMvc (if present)
grep -rn "MockMvc\|ResponseEntity\|ETag\|Location" src/test/java --include="*.java" | head

# DispatcherServlet existence
grep -rn "DispatcherServlet\|RequestMappingHandlerMapping" src/main/java --include="*.java" | head
# Expect Spring Boot auto-config; explicit check is actuator mappings above
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New resource → copy `OrderController.java:49`/`ProductController.java:30` shape. `POST` → `@Valid @RequestBody Record`, `ResponseEntity.created(URI.create("/api/v1/<resource>/"+id)).body(mapper.toResponse(entity))` (`102`). `GET /{id}` → `ResponseEntity.ok().header(ETAG, "\"v"+entity.getVersion()+"\"").body(...)` (`139`). `DELETE` → `If-Match` precondition `176-177` → `412` else `204`. Keep handlers thin — mutate via `Service @Transactional` (PR #20). Validate at the edge (`@Valid:80`); invalid never reaches the service. Use `Pageable:129` for any list.
- **Operate:** Monitor `dispatcherServlet` latency (`actuator/metrics/http.server.requests` tagged `uri=/api/v1/orders`). `ResponseEntity` status histogram tells error budget (`5xx` from `GlobalExceptionHandler:99`). `Location` header absent → regression in create path. `ETag` mismatch rate (`412` rate) signals hot contention — consider `FOR UPDATE` (PR #9) or retry. Paged lists capped — alert on `page size > configured max` which Spring Data rejects with `400`.
- **Interview:** "PR #22: `OrderController.java:49` `@RestController @RequestMapping('/api/v1/orders'):50` fronted by `DispatcherServlet` (`RequestMappingHandlerMapping` → `HandlerAdapter` resolvers for `@RequestBody` Jackson→record `@Valid:80`, `@RequestHeader`, `Pageable:129`). `ResponseEntity.created(URI.create(...)):102` `201+Location`, `GET 139 ETag "v{version}" BaseEntity:53`, `DELETE 176-177 If-Match 412`, `PATCH 148 JsonNode replace /status`, `Page<OrderResponse> 130` with `LIMIT/OFFSET`. Thin delegation `orderService.placeOrder:91`; `@Transactional 76/128` keeps Session for `OrderMapper` lazy traversal. Next: PR #23 groups, PR #24 ProblemDetail."

---

## 9. Interview lens — Q&A

**Q1: Where does an HTTP request enter Spring MVC and how is a handler found?**
A: `DispatcherServlet.doDispatch()` → `RequestMappingHandlerMapping` (`OrderController.java:49-50` scans `@RequestMapping/@GetMapping/@PostMapping`) resolves to `HandlerMethod` → `RequestMappingHandlerAdapter` via `HandlerAdapter` → argument resolvers (`@RequestBody` Jackson, `@RequestHeader`, `Pageable:129`) then invocation (§2.2).

**Q2: Why `ResponseEntity` over returning a bare `OrderResponse`?**
A: `ResponseEntity.created(URI.create(...)):102` sets `201 + Location`, `ResponseEntity.ok().header(ETAG,…):139` sets `ETag`, `noContent():180` `204`, `status(412):177` on `If-Match` mismatch — bare `OrderResponse` always becomes `200` and cannot set headers (§2.4).

**Q3: How is `201 Created` with `Location` produced?**
A: `ResponseEntity.created(URI.create("/api/v1/orders/"+order.getId())).body(response)` (`OrderController.java:101-103`, `ProductController.java:56-58`); URI built from persisted `id` after save. Alternative `ServletUriComponentsBuilder` derives host from request if behind a proxy (§2.5).

**Q4: How does `ETag`/`If-Match` give optimistic concurrency over HTTP?**
A: `etag()` `183` returns `"v"+BaseEntity.version:53`. `GET 139` sends `ETag`, `DELETE 173` requires `If-Match`; mismatch → `412 Precondition Failed:177`, else `204`. Version increments on every `flush` so stale clients must re-read (§2.6).

**Q5: `PATCH` vs `PUT` — when which?**
A: `PUT` (`ProductController:61`) replaces the whole `ProductRequest`; `PATCH` (`OrderController:148`) applies `JsonNode` ops `replace /status:155` for a single-field status transition — `PATCH` is partial, `PUT` is total replacement (§2.7).

**Q6: Why `@Transactional` on the controller?**
A: Keeps `Session` open `76/128` so `OrderMapper.toOrderResponse` can traverse lazy `order.getItems().product` after `OrderService` tx already committed via `REQUIRED` propagation. Without it → `LazyInitializationException`. `readOnly=true` on list/get skips flush. Alternative is `OSIV` (holds connection too long) or fetch joins in service (§2.10).

**Q7: What happens to `@Valid` on `OrderRequest` before the method body?**
A: `MethodArgumentNotValidException` raised by `HandlerAdapter` before `create:77` executes → `GlobalExceptionHandler.java:68` classifies `VALIDATION_ERROR 400 application/problem+json` with `code/hint` (§2.9, PR #23/24).

---

## 10. Honest limits & next step → PR #23

Controller `PATCH 148` only handles `replace /status:155` — not RFC 6902 `add/remove/move/test`. `URI.create` literal (`102`) is not proxy-host aware; `ServletUriComponentsBuilder` would be needed behind a `X-Forwarded-*` gateway. `@Transactional` on the controller doubles the tx join points (controller `REQUIRED` + service `REQUIRED`); long controller work holds a `Hikari` connection (`application.yml:33` pool 10). Paged `COUNT(*)` on large tables is expensive — key-set pagination scales better. Next PR tightens the validation that gates these endpoints: groups (`Create`/`Update`), container validation (`@Valid List`), and class-level cross-field rules on `OrderRequest:15`/`CustomerRequest:16` (`ValidOrderRequest`, `ValidStock`).

See [`23-validation.md`](./23-validation.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Create resource with URI discovery | `ResponseEntity.created(URI.create(...))` | `OrderController.java:101-103`, `ProductController.java:56-58` | `201 + Location` — REST contract |
| Concurrency-safe delete/update over HTTP | `ETag` + `If-Match` → `412` | `OrderController.java:139,173-177,183` | `version:53` prevents lost update |
| Paged read without table scan | `Pageable` → `Page<OrderResponse>` | `OrderController.java:127-131`, `ProductController.java:42-44` | `LIMIT/OFFSET` + `totalPages` |
| Partial update one field | `@PatchMapping` `JsonNode` replace | `OrderController.java:148-167` | `PATCH` partial vs `PUT` total |
| Validate before service | `@Valid @RequestBody Record` | `OrderController.java:80`, `ProductController.java:53` | Failure never reaches domain |
| Keep Session for lazy→DTO | `@Transactional`/`readOnly` on handler | `OrderController.java:76,128` | `OrderMapper` after service tx |
| Replay-safe POST | `Idempotency-Key` + `IdempotencyService` | `OrderController.java:78-99`, `IdempotencyService.java:28` | `unique idempotency_key:27` no double charge |
