# 24. Exception Handling and Idempotency (PR #24)

> PR #24 — `GlobalExceptionHandler` RFC 9457 `ProblemDetail` + `ErrorSpec` catalog + `Idempotency-Key` guard (`IdempotencyService`/`IdempotencyRecord`). Stack: Java 21, Spring Boot 3.4.1, `spring-boot-starter-web`, `@RestControllerAdvice`, `ProblemDetail` (Spring 6), `domain/idempotency/*` JPA, Jackson `ObjectMapper`. See `README.md:1772` roadmap `| 24 | Exception Handling and Idempotency |`.

---

## 1. Purpose — what shipped

PR #24 centralizes failure mapping. `GlobalExceptionHandler.java:25` (`@RestControllerAdvice`) replaces scattered `try/catch` with one class where every exception becomes `application/problem+json` (`ProblemDetail` RFC 9457) carrying `status/title/detail` plus `code/hint` from a `record ErrorSpec(status,code,title,hint):109`. The `classify(Exception):66` Java 21 pattern-matching `switch` assigns `MethodArgumentNotValidException/ConstraintViolationException → 400 VALIDATION_ERROR:68-73`, `PaymentFailedException → 502 PAYMENT_FAILED:74-77`, `DataIntegrityViolationException → 409 DATA_CONFLICT:78-80`, `OptimisticLockingFailureException → 409 CONCURRENT_MODIFICATION:81-84`, `IllegalArgumentException "Unknown" → 404 RESOURCE_NOT_FOUND:85-87`, else `400 INVALID_ARGUMENT:88-90` / `409 INSUFFICIENT_STOCK/RESOURCE_IN_USE:91-98` / `500 INTERNAL_ERROR:99`. `Auth:32-46` `AccessDeniedException→403` and `AuthenticationException→401` stay distinct (PR #26). Alongside, `OrderController.create:77-103` integrates `IdempotencyService.java:18` + `IdempotencyRecord.java:21` so `POST /api/v1/orders` with `Idempotency-Key` (`OrderController.java:78`/`97`) replays the stored `StoredResponse(status,body):57` (`find:29` / `record:35` via `IdempotencyKeyRepository`) instead of double-executing `orderService.placeOrder:91`.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Each controller mapped exceptions differently (or not at all) — `IllegalArgumentException` sometimes `500`, validation failures were raw `BindingResult`, payment failures leaked internal `PaymentFailedException` stack. Clients string-matched messages to branch. `POST /orders` retries after a network timeout created duplicate orders and double `paymentGateway.charge:112`. No stable `code` for the client, no `hint` for operator action, no `application/problem+json` content type.

**After:** `GlobalExceptionHandler.java:48-51` `@ExceptionHandler(Exception.class)` funnels all → `classify:66` → `problem(status,code,title,hint,ex):54-63` → `ResponseEntity<ProblemDetail>` `contentType APPLICATION_PROBLEM_JSON:61`. `ErrorSpec:109` catalog makes `400/404/409/401/403/502` deterministic. The `default:99` guard maps unknown → `500 INTERNAL_ERROR` without leaking stack. `IdempotencyService:28-37` check-before-execute + `IdempotencyRecord:21` `unique idempotency_key:27` guarantees `Idempotency-Key: fixed` always returns the same `201 + body` (`OrderController:86-87` `fromStored`) or the stored `404/409` — network retries never double-charge.

### Theory — `@ControllerAdvice`, `ProblemDetail`, and Idempotency from first principles (100+ lines)

#### 2.1 What `@RestControllerAdvice` is — a global `HandlerExceptionResolver`

`@RestControllerAdvice` (`GlobalExceptionHandler.java:25`) = `@ControllerAdvice` + `@ResponseBody`. Boot registers it as a `ExceptionHandlerExceptionResolver` bean that inspects every controller throw. Its `@ExceptionHandler(Exception.class):48` runs after `DispatcherServlet` catches the handler exception and before `ResponseEntityExceptionHandler` defaults. Resolution order: specific `AccessDeniedException:32` / `AuthenticationException:42` → generic `Exception:48`. Without the advice, `IllegalArgumentException("Unknown product 42")` bubbles as `500` with a servlet error view; with it the `switch` `85` maps to `404 RESOURCE_NOT_FOUND`.

#### 2.2 `ProblemDetail` — RFC 9457 (successor to RFC 7807) as the body contract

Spring 6's `ProblemDetail` (`GlobalExceptionHandler.java:56` `ProblemDetail.forStatusAndDetail`) encodes `type/title/status/detail/instance + extensions`.

```java
// GlobalExceptionHandler.java:54-63
private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, String hint, Exception ex){
  ProblemDetail p = ProblemDetail.forStatusAndDetail(status, messageOf(ex)); //56 detail = ex.getMessage or class name
  p.setTitle(title); p.setProperty("code", code); p.setProperty("hint", hint); //57-59 extensions
  return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p); //60-62
}
```

`code` is the machine-stable catalog key (`VALIDATION_ERROR`, `CONCURRENT_MODIFICATION`, `INSUFFICIENT_STOCK`), `hint` is operator advice ("Re-read and retry with fresh version", "Fix the fields…"). The generic `detail` carries the exception message (`messageOf:104`), safe here because domain messages are curated (`Unknown product 42`, `duplicate product 42`); internal `Exception` messages fallback to `class SimpleName` so no stack.

#### 2.3 The pattern-matching `classify` switch — one row per failure family

```java
// GlobalExceptionHandler.java:66-102
return switch (ex){
  case MethodArgumentNotValidException e -> new ErrorSpec(BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", "Fix fields...");
  case ConstraintViolationException e -> new ErrorSpec(BAD_REQUEST, "VALIDATION_ERROR", ...);
  case PaymentFailedException e -> new ErrorSpec(BAD_GATEWAY, "PAYMENT_FAILED", "Payment could not be processed", "Retry with same Idempotency-Key...");
  case DataIntegrityViolationException e -> new ErrorSpec(CONFLICT, "DATA_CONFLICT", ...);
  case OptimisticLockingFailureException e -> new ErrorSpec(CONFLICT, "CONCURRENT_MODIFICATION", "Concurrent modification", "Re-read ...");
  case IllegalArgumentException e when e.getMessage().contains("Unknown") -> new ErrorSpec(NOT_FOUND, "RESOURCE_NOT_FOUND", ...);
  case IllegalArgumentException e -> new ErrorSpec(BAD_REQUEST, "INVALID_ARGUMENT", ...);
  case IllegalStateException e when e.getMessage().contains("Insufficient stock") -> new ErrorSpec(CONFLICT, "INSUFFICIENT_STOCK", ...);
  case IllegalStateException e when e.getMessage().contains("cannot be removed") -> new ErrorSpec(CONFLICT, "RESOURCE_IN_USE", ...);
  default -> new ErrorSpec(INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error", "Please retry...");
};
```

Java 21 `case Type` with `when` guards collapses the mapping to 10 rows. The alternative `if (ex instanceof X) ... else if` chain is longer and loses exhaustiveness checking. `record ErrorSpec(HttpStatus status, String code, String title, String hint):109` is the immutable tuple returned to `problem(...)`.

#### 2.4 `DispatcherServlet` and resolver order — where the advice sits

```
OrderController.create:80 throws IllegalArgumentException("Unknown product 42") in orderService.placeOrder:91
  → DispatcherServlet catches → loops HandlerExceptionResolver list
    → ExceptionHandlerExceptionResolver finds GlobalExceptionHandler:25 handle(Exception):48
      → switch classifies 85 "Unknown" → NOT_FOUND 404 RESOURCE_NOT_FOUND
      → builds ProblemDetail 56 → ResponseEntity 60 contentType APPLICATION_PROBLEM_JSON 61
    → DispatcherServlet writes response
```

`@ExceptionHandler` methods have the same parameter resolution as controllers (they could inspect `HttpServletRequest`). Unclassified exceptions hit `default:99` `INTERNAL_ERROR 500`. Auth failures are handled earlier by Spring Security's `ExceptionTranslationFilter`, but `@ExceptionHandler(AccessDeniedException:32)` and `AuthenticationException:42` cover method-security paths (`@PreAuthorize` on `OrderService.cancelOrder:202`).

#### 2.5 Idempotency as the `POST` safety net — why `Idempotency-Key` is required

`POST` is defined non-idempotent (`RFC 9110`): retrying `POST /orders` after a timeout that already succeeded creates two orders and charges twice. The client-supplied `Idempotency-Key` (`OrderController.java:78` `required=false`) makes the operation logically idempotent: the first request stores `key → {status 201, body OrderResponse JSON}` (`IdempotencyService.record:35` `toJson:48-54`); replay with the same key returns the stored result without re-executing (`find:29` → `StoredResponse:57` → `fromStored:106` `objectMapper.treeToValue:109`). The key is `unique` (`IdempotencyRecord.java:27` `idempotency_key unique`) — concurrent duplicate `saveAndFlush` fails `DataIntegrityViolationException` → `409 DATA_CONFLICT` rather than double execution.

#### 2.6 `IdempotencyService` and `IdempotencyRecord` — minimal durable replay

```java
// IdempotencyService.java:28-46
@Transactional(readOnly=true) Optional<StoredResponse> find(String key){ return store.findByIdempotencyKey(key).map(r->new StoredResponse(r.getResponseStatus(), r.getResponseBody())); } //28-32
@Transactional void record(String key, String method, String path, int status, Object body){ store.saveAndFlush(IdempotencyRecord.of(key,method,path,status,toJson(body))); } //34-37
JsonNode parseStoredBody(String body){ return objectMapper.readTree(body); } //40
record StoredResponse(int status, String body){} //57
```

```java
// IdempotencyRecord.java:21,27
@Entity @Table(name="idempotency_keys")  //21
@Column(name="idempotency_key", unique=true, length=64, nullable=false) String idempotencyKey; //27
@Column(name="response_status") int responseStatus; //36
@Column(name="response_body", columnDefinition="text") String responseBody; //39
```

Why JPA not Redis? Consistency with the same Postgres that owns `orders`: `record` inside `OrderController.create` after `placeOrder:91` is in the outer handler `Transaction` (`@Transactional:76`). If `placeOrder` commits and `record` commits together, replay is durable — a Redis write separate from the DB tx could be lost on failure. The unique constraint handles the race where two requests with the same key arrive concurrently: one `saveAndFlush:36` wins, the other `DataIntegrityViolationException:78`.

#### 2.7 Check-before vs record-after — the controller dance

```java
// OrderController.java:83-103
if (key!=null && !key.isBlank()){
  var stored = idempotency.find(key);
  if (stored.isPresent()){ return ResponseEntity.status(stored.get().status()).body(fromStored(stored.get())); } //84-88
}
Order order = orderService.placeOrder(...); //91
OrderResponse resp = OrderMapper.toOrderResponse(order); //95
if (key!=null && !key.isBlank()) idempotency.record(key, request.getMethod(), request.getRequestURI(), 201, resp); //97-100
return ResponseEntity.created(URI.create("/api/v1/orders/"+order.getId())).body(resp); //101-103
```

Check-before is the read; `record` is the write. The gap between them is not atomic in this code — concurrent identical keys could both miss `find` and both `placeOrder` → the unique constraint on `IdempotencyRecord:27` then fails one `record` `saveAndFlush` but the order was already created twice. True exactly-once requires holding a `UNIQUE` reservation row *before* `placeOrder` (`INSERT idempotency_keys key,status=PROCESSING` with `ON CONFLICT` handling) or advisory locking (`DistributedLockService` PR #30 `RedissonConfig`). This impl is *at-least-replay* (handles network timeout replay after the fact, not simultaneous twin `POST`).

#### 2.8 Payment failure as `502` with Idempotency hint

`PaymentFailedException` (`OrderService.java:112` inside `placeOrder`) is `BAD_GATEWAY 502` `PAYMENT_FAILED` (`74-77`) with hint "Retry with the same Idempotency-Key - no charge was recorded." `OrderService:76` rolls back stock/order/outbox on this exception, so retry does not see a phantom order; and `IdempotencyService` has not yet stored a `201`, so replay of the same key after retry can recreate the order cleanly.

#### 2.9 Auth edge cases carried through the same `ProblemDetail` shape

`AuthenticationException:42` `401 AUTHENTICATION_REQUIRED` "Send a valid bearer token or API key" and `AccessDeniedException:32` `403 ACCESS_DENIED` "Your token/role lacks the required authority" share `problem(...):54` so the client treats `401/403` with the same `application/problem+json` parse as `400/409` — uniform contract across `SecurityConfig.java:45` `SecurityFilterChain`.

#### 2.10 Alternatives and costs

- Per-controller `try/catch` + custom `ErrorResponse` class — duplicative, status varies by copy.
- Bare `ResponseStatusException(HttpStatus.NOT_FOUND)` without `code/hint` — client cannot branch without string-matching `detail`.
- Idempotency via Redis `SETNX key response TTL` — faster, survives DB restart separately, but loses atomicity with order tx; use when latency matters and DB is not the bottleneck.
- Idempotency key in the body field instead of header — harder to apply to `GET`/`DELETE` and to middleware that couldn't deserialize.
- Exact dedup via `INSERT ... ON CONFLICT DO NOTHING` + `SELECT FOR UPDATE` on the key row before `placeOrder` — more complex, pays a row-level lock; warranted only for concurrent duplicate POST storm.

> Interview anchor: "PR #24: `GlobalExceptionHandler.java:25` `@RestControllerAdvice` `@ExceptionHandler(Exception):48` pattern-match `switch 66` → `ErrorSpec:109` → `ProblemDetail 56 application/problem+json 61` rows `400 VALIDATION_ERROR 68` / `502 PAYMENT_FAILED 74` / `409 DATA_CONFLICT/CONCURRENT_MODIFICATION 78/81` / `404 RESOURCE_NOT_FOUND when Unknown 85` / `409 INSUFFICIENT_STOCK 91` etc., `401/403 32/42` via Security. `OrderController:78,97` `Idempotency-Key` check `IdempotencyService.find:29` replay `StoredResponse:57`/`fromStored:106` else `record:35` `IdempotencyRecord:21 unique idempotency_key:27` durable in Postgres with the order tx; gap between check and record is best-effort concurrent dupe via unique constraint failure."

---

## 3. Solution — ASCII

```
HTTP Request  POST /api/v1/orders  Idempotency-Key: abc-123  body {customerId:1,items:[{productId:1,qty:1}]}
      │
      ▼  DispatcherServlet → OrderController.create:77
         83 find(Idempotency-Key)
         │    ├─ hit?  StoredResponse(status=201, body=OrderResponse JSON) → parseStoredBody:40 → treeToValue:109 → ResponseEntity.status(201).body(replay) //86-88 no placeOrder
         │    └─ miss? → orderService.placeOrder:91  // OrderService:83 @Transactional+Retryable
         │              ├─ success → Order saved + outbox → OrderResponse resp:95
         │              │         record(key, POST, /api/v1/orders, 201, resp):98-100 → IdempotencyRecord:49 ofSaveAndFlush unique idempotency_key:27 (text body)
         │              │         return 201 Created Location /api/v1/orders/{id} body resp :101-103
         │              └─ throws
         │                   IllegalArgumentException "Unknown product 42" ─┐
         │                   IllegalStateException "Insufficient stock ..." ─┤→ catch in DispatcherServlet
         │                   DataIntegrityViolationException               ─┤  → HandlerExceptionResolver
         │                   OptimisticLockingFailureException             ─┤  → GlobalExceptionHandler:25
         │                   MethodArgumentNotValidException (@Valid:80)   ─┘     classify(Exception):66 switch
         │                                                                       ErrorSpec 109 (status,code,title,hint)
         │                                                                       problem:54 → ProblemDetail 56 setProperty code/hint 58-59
         └─────────────────────────────────────────────→ ResponseEntity<ProblemDetail> 60 contentType APPLICATION_PROBLEM_JSON 61
                                                               { status:404, title:"Resource not found", detail:"Unknown product 42", code:"RESOURCE_NOT_FOUND", hint:"The requested resource does not exist." }
                                                               { status:409, code:"INSUFFICIENT_STOCK", detail:"Insufficient stock ...", hint:"Reduce quantity..." }
                                                               { status:400, code:"VALIDATION_ERROR", hint:"Fix the fields..." }  (via PR #23)
                                                               { status:502, code:"PAYMENT_FAILED", hint:"Retry with same Idempotency-Key..." }

 Auth seam: Security filter chain SecurityConfig.java:45
    AuthenticationException → GlobalExceptionHandler:42 → 401 AUTHENTICATION_REQUIRED
    AccessDeniedException → 32 → 403 ACCESS_DENIED   (both via same problem() shape)

 Idempotency store:  IdempotencyRecord.java:21  @Entity idempotency_keys
    idempotency_key unique 64:27  | http_method 10:30 | request_path 255:33 | response_status:36 | response_body text:39 | created_at:42
    Concurrent duplicate key → DataIntegrityViolationException → 409 DATA_CONFLICT (classify 78)
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java` | `25` | Global advice | `@RestControllerAdvice`, centralizes all `@ExceptionHandler` |
| `GlobalExceptionHandler.java` | `32-36` | `403` | `@ExceptionHandler(AccessDeniedException):32` → `problem(FORBIDDEN,ACCESS_DENIED):34-35` |
| `GlobalExceptionHandler.java` | `42-46` | `401` | `@ExceptionHandler(AuthenticationException):42` → `UNAUTHORIZED AUTHENTICATION_REQUIRED:44` |
| `GlobalExceptionHandler.java` | `48-52` | Generic funnel | `@ExceptionHandler(Exception):48` → `classify(ex):50` → `problem(spec.status(),spec.code()…):51` |
| `GlobalExceptionHandler.java` | `54-63` | `ProblemDetail` builder | `forStatusAndDetail:56`, `setTitle:57`, `setProperty code/hint:58-59`, `APPLICATION_PROBLEM_JSON:61` |
| `GlobalExceptionHandler.java` | `66-102` | Catalog `classify` | `switch 67` rows `VALIDATION_ERROR:69`/`PAYMENT_FAILED:75`/`DATA_CONFLICT:79`/`CONCURRENT_MODIFICATION:82`/`RESOURCE_NOT_FOUND 86`/`INVALID_ARGUMENT 88-90`/`INSUFFICIENT_STOCK 93`/`RESOURCE_IN_USE 97`/`INTERNAL_ERROR 99` with `when` guards |
| `GlobalExceptionHandler.java` | `104-110` | Helpers | `messageOf:104` null→class name, `record ErrorSpec:109` `(status,code,title,hint)` |
| `src/main/java/com/company/orderapi/domain/idempotency/IdempotencyService.java` | `18-59` | Service | `find:28-32` `readOnly` → `Optional StoredResponse:57`, `record:34-37` `saveAndFlush`, `parseStoredBody:40` `readTree`, `toJson:48` `writeValueAsString` |
| `src/main/java/com/company/orderapi/domain/idempotency/IdempotencyRecord.java` | `21,27-43` | Entity | `@Entity idempotency_keys:21`, `idempotency_key unique 64:27`, `httpMethod:30`, `requestPath:33`, `responseStatus:36`, `responseBody text:39`, `createdAt:42`, factory `of:49-58`, not a `BaseEntity` (no version) |
| `src/main/java/com/company/orderapi/domain/idempotency/IdempotencyKeyRepository.java` | — | Repo | `findByIdempotencyKey` derived query |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `77-104` | Guarded write | `@RequestHeader Idempotency-Key:78`, check `IdempotencyService.find:84` → replay `fromStored:86` (`parseStoredBody:40`+`treeToValue:109`) else `placeOrder:91` → `record:98-99` → `201 Location:101-103` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `91,112` | Throw site | `findById orElseThrow IllegalArgumentException:91 "Unknown product"` → `404`, `charge:112` → `PaymentFailedException` `502`, stock `IllegalStateException` → `409` |
| `src/main/resources/db/changelog/v1.0/*_idempotency.sql` | — | Migration | `CREATE TABLE idempotency_keys (idempotency_key VARCHAR(64) UNIQUE ...)` |

```java
// GlobalExceptionHandler.java:32-63 — catalog + ProblemDetail
@ExceptionHandler(Exception.class) public ResponseEntity<ProblemDetail> handle(Exception ex){
  ErrorSpec spec = switch(ex){
    case MethodArgumentNotValidException e -> new ErrorSpec(BAD_REQUEST,"VALIDATION_ERROR",...);
    case OptimisticLockingFailureException e -> new ErrorSpec(CONFLICT,"CONCURRENT_MODIFICATION","Concurrent modification","Re-read...");
    case IllegalArgumentException e when e.getMessage().contains("Unknown") -> new ErrorSpec(NOT_FOUND,"RESOURCE_NOT_FOUND",...);
    default -> new ErrorSpec(INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Internal error","Please retry...");
  };
  ProblemDetail p = ProblemDetail.forStatusAndDetail(spec.status(), messageOf(ex));
  p.setTitle(spec.title()); p.setProperty("code", spec.code()); p.setProperty("hint", spec.hint());
  return ResponseEntity.status(spec.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
}
// IdempotencyService.java:34 — record
@Transactional public void record(String key, String method, String path, int status, Object body){
  store.saveAndFlush(IdempotencyRecord.of(key,method,path,status,toJson(body)));
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Exception→ProblemDetail shape live
# 404 unknown product
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"customerId":9999,"items":[{"productId":9999,"quantity":1}]}' | jq '.code,.status,.title,.hint,.detail'
# Expect RESOURCE_NOT_FOUND 404 detail="Unknown customer 9999" or "Unknown product ..."

# 400 validation (PR #23)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":null,"items":[]}' | jq '.code,.status'
# VALIDATION_ERROR 400 application/problem+json

# 409 insufficient stock (when product has 0)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":99999}]}' | jq '.code,.status'
# INSUFFICIENT_STOCK 409

# List all codes
grep -n "\"[A-Z_]*\"" src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java

# Idempotency: same key returns same response, no double order
KEY="demo-$(date +%s)"
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '.id,.orderNumber' | tee /tmp/first.json
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '.id,.orderNumber' | tee /tmp/second.json
diff /tmp/first.json /tmp/second.json && echo "idempotent replay ok"

# Verify stored rows
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT idempotency_key, response_status, left(response_body,80) FROM idempotency_keys ORDER BY id DESC LIMIT 5;"

# 502 payment failure hint (triggered when simulated gateway fails, e.g. total too high or chaos flag)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq '.code,.hint'  # PAYMENT_FAILED on failure

# 401/403 via SecurityConfig.java:45 (missing/invalid token when security.enabled=true, no X-API-KEY)
curl -s -H "Content-Type: application/json" http://localhost:8080/api/v1/orders | jq '.code,.status'  # AUTHENTICATION_REQUIRED 401
curl -s -H "X-API-KEY: wrong" http://localhost:8080/api/v1/orders | jq '.code,.status'
```

```java
// Controller extension with custom error
throw new IllegalStateException("Insufficient stock: product 42 has 0, need 5"); // → 409 INSUFFICIENT_STOCK via guard 91-94
throw new IllegalArgumentException("Unknown customer 7"); // → 404 RESOURCE_NOT_FOUND guard 85
// New exception family → add case to classify:67
case MyBusinessException e -> new ErrorSpec(HttpStatus.UNPROCESSABLE_ENTITY, "MY_CODE","My title","My hint")
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `ProblemDetail` + `ErrorSpec` catalog | `GlobalExceptionHandler.java:56,109` `status+code+title+hint` | Per-controller `ResponseEntity<String>` ad-hoc | Machine-parseable `code` + operator `hint` stable across endpoints; `APPLICATION_PROBLEM_JSON` standard | Must maintain `switch 67` rows for each family |
| Java 21 `switch` pattern-matching with `when` | `case IllegalArgumentException e when msg.contains("Unknown")` `85` | `if instanceof` chain | Declarative catalog, exhaustiveness visible | `when` string guard fragile vs typed exceptions (`CustomerNotFoundException`) |
| `Idempotency-Key` header + `IdempotencyRecord:27` unique | `OrderController:78,97` + `IdempotencyService:28,34` + JPA unique `64` | Redis `SETNX` or no dedup | Durable with order tx (same Postgres), survives DB+app crash together; `DataIntegrityViolationException →409` on concurrent dupe | Extra row per guarded POST; check-before/record-after not contended-safe without reservation row |
| `StoredResponse(status,body):57` replay via `parseStoredBody:40` | JSON `response_body text:39` parsed with `objectMapper.readTree` | Store typed `OrderResponse` columns | Generic — can store `201/404/409` for any guarded endpoint, body is the real response JSON | Text column serializes whole response even on large payloads |
| Check `Idempotency-Key` optional `required=false:78` | Unkeyed POST always executes | Mandatory key | Backward compatible; machine clients that want safety send the key | Unkeyed retries can duplicate |
| `401/403` through same `problem()` `32/42` | Uniform `application/problem+json` even for auth | Spring Security default `WWW-Authenticate` only | One parser for the client (code/hint handling) | Default `BasicErrorController` not used |
| Record not a `BaseEntity` | `IdempotencyRecord.java:21` no `@Version`/audit | Reuse `BaseEntity` | Bookkeeping row, not domain aggregate — no locking/auditing needed | Different lifecycle from domain |

---

## 7. How to verify

```bash
# Global advice exists and covers all families
grep -n "@RestControllerAdvice\|@ExceptionHandler\|ProblemDetail\|APPLICATION_PROBLEM_JSON\|ErrorSpec" \
  src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java

# Catalog rows
grep -n "VALIDATION_ERROR\|PAYMENT_FAILED\|DATA_CONFLICT\|CONCURRENT_MODIFICATION\|RESOURCE_NOT_FOUND\|INSUFFICIENT_STOCK\|INTERNAL_ERROR" \
  src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java

# ProblemDetail properties code/hint
grep -n "setProperty.*code\|setProperty.*hint" src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java

# Idempotency contract
grep -n "Idempotency-Key\|find.*idempotency\|record.*idempotency\|StoredResponse\|fromStored" \
  src/main/java/com/company/orderapi/api/rest/controller/OrderController.java src/main/java/com/company/orderapi/domain/idempotency/*.java

# Unique constraint on key
grep -n "idempotency_key.*unique" src/main/java/com/company/orderapi/domain/idempotency/IdempotencyRecord.java src/main/resources/db/changelog -r

# Live content-type  application/problem+json
curl -s -i -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":9999,"items":[]}' | grep -i "content-type.*problem"

# Handler unit: placeOrder failure surfaces mapped status
./mvnw test -Dtest=OrderServiceTest -Dspring.profiles.active=test 2>&1 | grep -i "PAYMENT\|Insufficient"

# DB unique handles concurrent dupe (use psql race or RateLimit/Idempotency test if present)
grep -rn "idempotency\|Idempotency" src/test --include="*.java" | head
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New endpoint throws domain `IllegalArgumentException("Unknown X "+id)` for missing → `404` via `GlobalExceptionHandler:85` guard; `IllegalStateException("Insufficient stock …")` → `409 INSUFFICIENT_STOCK:91`. Add a new typed failure (e.g. `QuoteExpiredException`) → add `case QuoteExpiredException e -> new ErrorSpec(BAD_REQUEST,"QUOTE_EXPIRED",…)` `66`. Guard any non-idempotent `POST` with `IdempotencyService`: header `@RequestHeader(value="Idempotency-Key",required=false) String key:78`, check `find:28` before `placeOrder:91`, `record:34` after — or factor into a `HandlerInterceptor` for cross-cutting use.
- **Operate:** Monitor `code` histogram filtered from `GlobalExceptionHandler` (log `500/502` `code INTERNAL_ERROR/PAYMENT_FAILED`). `code` field enables client-side circuit branching (retry `502 PAYMENT_FAILED` with same `Idempotency-Key`, do not retry `400 VALIDATION_ERROR`). `application/problem+json` lets generic HTTP libraries parse `title/detail` without custom error DTOs. `IdempotencyRecord:27` `unique idempotency_key` `409 DATA_CONFLICT:78` indicates duplicate concurrent post — alert and advise clients to reuse the same key on timeout.
- **Interview:** "PR #24: `GlobalExceptionHandler.java:25` `@RestControllerAdvice` `@ExceptionHandler(Exception):48` `switch:66` → `ErrorSpec:109` → `ProblemDetail.forStatusAndDetail:56` `APPLICATION_PROBLEM_JSON:61` `code/hint:58-59` — rows `VALIDATION_ERROR:69` `PAYMENT_FAILED 502:74` `CONCURRENT_MODIFICATION 409:81` `Unknown→404:85` `INSUFFICIENT_STOCK 409:91` `500:99`. `401:42`/`403:32` via same. `OrderController:78,97` `Idempotency-Key` check `IdempotencyService.find:28 StoredResponse:57` replay else `placeOrder:91` then `record:34 IdempotencyRecord:21 unique 27` durable with tx; concurrent dupe → `DataIntegrityViolationException→409`. Next: PR #25 OpenAPI via `@Operation:66`."

---

## 9. Interview lens — Q&A

**Q1: Why `@RestControllerAdvice` + `ProblemDetail` over per-controller `try/catch`?**
A: Single `GlobalExceptionHandler.java:25` classifies every throw `66` into deterministic `status+code+title+hint` `ErrorSpec:109` → `ProblemDetail 56 application/problem+json 61` so all endpoints speak the same contract (§2.1-2.2).

**Q2: How does the `switch` avoid string-matching externally?**
A: The guard `when e.getMessage().contains("Unknown") 85` or `"Insufficient stock" 91` is inside the handler, but the response exposes stable `code` `RESOURCE_NOT_FOUND/INSUFFICIENT_STOCK` `58` — clients switch on `code`, not `detail` (§2.3).

**Q3: What makes `POST /orders` safe to retry?**
A: Client `Idempotency-Key` header `OrderController:78`; `IdempotencyService.find:29` returns stored `201+body` if present `86-88` without `placeOrder`, else runs `placeOrder:91` then `record:98-99` `IdempotencyRecord.java:27` unique — network timeout retry with same key cannot double-charge (§2.5-2.7).

**Q4: What happens to concurrent identical `Idempotency-Key`s?**
A: One `saveAndFlush:36` wins; other hits `unique idempotency_key:27` → `DataIntegrityViolationException 78 → 409 DATA_CONFLICT`. Exact single-order on concurrent twin POST would need a pre-reservation row before `placeOrder` (gap noted §2.7).

**Q5: Why `502 PAYMENT_FAILED` not `400`?**
A: `PaymentFailedException:74` is upstream (`paymentGateway.charge:112` fails) — gateway is a dependency, so `502 Bad Gateway` signals "our dependency failed, retry with same Idempotency-Key safe because nothing was persisted" (`OrderService:76` transaction rolled back).

**Q6: How are validation and auth failures funneled identically?**
A: `MethodArgumentNotValidException→400 VALIDATION_ERROR:68`, `ConstraintViolationException:71` too (PR #23), plus `AccessDeniedException→403:32` and `AuthenticationException→401:42` via same `problem():54` → uniform `application/problem+json` (§2.9).

---

## 10. Honest limits & next step → PR #25

`when msg.contains("Unknown")` `85` is heuristic — `IllegalArgumentException` without "Unknown" maps `400 INVALID_ARGUMENT` instead of `404`; typed `CustomerNotFoundException` would be exact. `messageOf:104` exposes the exception message verbatim (`Unknown product 42`) — safe here but internal messages (`SQL state 23505`) would leak if any unexpected `DataIntegrityViolationException` carries a DB hint; sanitize `default:99` path in hardened prod. Idempotency stores the full `response_body text:39` — large `OrderResponse` with many items bloats the row; retention (`DELETE FROM idempotency_keys WHERE created_at < NOW()-7days`) not wired. Next PR documents this surface so callers know the codes without reading source: `@Operation/@ApiResponse` (`OrderController:66-73`) and `OpenApiConfig.java:20` (`/v3/api-docs` + `/swagger-ui.html`).

See [`25-openapi-documentation.md`](./25-openapi-documentation.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Consistent failure body | `ProblemDetail` + `ErrorSpec` + `code/hint` | `GlobalExceptionHandler.java:54-63,109` | `application/problem+json` uniform contract |
| Map `throw` to status | Pattern-matching `switch 66` per family | `GlobalExceptionHandler.java:67-102` | One catalog row per failure, 400/404/409/502/500 explicit |
| Safe retry of `POST` | `Idempotency-Key` + `IdempotencyRecord unique:27` | `OrderController.java:78-99`, `IdempotencyService.java:18`, `IdempotencyRecord.java:21` | Check before, record after, `409` on concurrent dupe |
| `401/403` same shape | `@ExceptionHandler` Auth handled via `problem()` | `GlobalExceptionHandler.java:32-46` | Client parses `problem+json` for auth too |
| Validation `400` same shape | `MethodArgumentNotValidException→400 VALIDATION_ERROR` | `GlobalExceptionHandler.java:68-73` | Bridges PR #23 into the catalog |
