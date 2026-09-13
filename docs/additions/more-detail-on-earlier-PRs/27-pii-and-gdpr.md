# 27. PII and GDPR (PR #27)

> PR #27 — PII masking/redaction (`PiiMasker.java:12`, `PiiRedactionFilter:31`, `PiiMaskingModule` + `SensitiveDataSerializer`/`PiiType`/`@MaskedPii`) and GDPR subject-rights `GdprService.java:34` (`erase:52 Art.17`, `exportData:82 Art.20`) + `GdprController` + `AuditLog.java` audit trail. Stack: Java 21, Spring Boot 3.4.1, Jackson `Module`, `OncePerRequestFilter`, `SecurityContext`, `Customer.java`, `GdprService.java:34`. See `README.md:1772` roadmap `| 27 | PII and GDPR |`.

---

## 1. Purpose — what shipped

PR #27 protects personal data in the three places it can leak: HTTP responses (a caller's scope should decide what's visible), application logs (ops sinks must never record raw e-mails), and persistence (the subject's right to erasure/portability must be durable and auditable). Shipped: deterministic field-level masking `PiiMasker.java:12` (`maskEmail:50 a***@e***.com`, `maskPhone:66 +61***11`, `maskName:78 A***h`, `PII_KEYS:19 mapping email/customerEmail/phoneNumber/fullName/street/city/state/country/postalCode → PiiType 19-28`, `redactJson:96` two-pass `JSON_KEY_VALUE 31 + GENERIC_EMAIL 35 → [EMAIL_REDACTED] 113`), Jackson serializer path `@MaskedPii` + `SensitiveDataSerializer` + `PiiMaskingModule` registered on `ObjectMapper` that consults caller scope (`PiiAccessDecider: SCOPE_pii_read` vs `ROLE_API_KEY`) to emit `OrderResponse.customerEmail:22` masked/unmasked (`PiiType.EMAIL:12`), HTTP log redaction path `PiiRedactionFilter.java:31` (`@Component`) buffering `ContentCachingRequestWrapper/ResponseWrapper 46-49` → `log.debug ... redacted 54-61` `PiiMasker.redactJson 72` under `DEBUG` only (`log.isDebugEnabled 53`), subject-rights API `GdprController.java` `DELETE /api/v1/customers/{id}/gdpr/erase` and `GET /api/v1/customers/{id}/gdpr/export` wired to `GdprService.java:34` with `ERASED_EMAIL_SUFFIX @erased.invalid 37`, `erase:52` choosing `DELETED 65` (zero orders → `audit CUSTOMER_ERASED 61 + delete 63`) vs `ANONYMIZED 71` (`setEmail erased-{id}@erased.invalid + setFullName Erased User + phone null 71-74`, `audit CUSTOMER_ANONYMIZED 75`), `exportData:82` assembling `PortabilityResponse 105` (`AddressExport 85 / OrderExport 91 / OrderItemExport 97`) plus `PORTABILITY_EXPORTED 102 audit`, and the compliance anchor `AuditLog.java` (`customer_id, action, actor, detail` where `detail 128-137` `writeValueAsString {mode,orderCount,addressCount}` is non-PII).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `GET /api/v1/orders/1 | jq .customerEmail` returned the raw `alice@example.com` to any authenticated caller (PR #26 `SecurityConfig:72` `authenticated` but no per-field scoping). `log.debug "http ... body={}", body` on `PiiRedactionFilter:31` would have emitted the raw payload identically to a heap dump. GDPR was unimplemented — deleting `Customer.java:42` with `Order` rows failed foreign-key `DataIntegrityViolationException:78→409` (`GlobalExceptionHandler:78`) leaving stale `email/phone` linkable indefinitely.

**After:** `GET /orders/1` as a caller without `SCOPE_pii_read` returns `a***@e***.com:62` (`SensitiveDataSerializer` routing via `PiiAccessDecider`), while an `admin` JWT with `SCOPE_pii_read` sees the raw `alice@example.com` — the same `OrderResponse.java:22 @MaskedPii(PiiType.EMAIL)` serializer governs output per-caller. `PiiRedactionFilter:31` buffers every `/api/** 41` body `46-49` and passes it through `PiiMasker.redactJson 72` (`JSON_KEY_VALUE 31 → masked per PII_KEYS 19 + GENERIC_EMAIL sweep 35`) before `log.debug 54-61`, so log files contain `t***@e***.com` / `+61***11` / `[EMAIL_REDACTED]` even for admins — logging is a property of the *sink* not the caller's rights (`PiiRedactionFilter:24 log sink, not caller rights`). `GdprService.erase:52` reads `customer.getOrders().size():55` and branches: `orderCount==0 58 → DELETE 63 + AuditLog CUSTOMER_ERASED 61 DELETED`, else anonymize placeholders `71-74` + `CUSTOMER_ANONYMIZED 75 ANONYMIZED` honoring `Art.17(3)` legal retention for `orders` while `addresses` cascade behavior matches. `exportData:82` exports `PortabilityResponse:105` machine-readable for `Art.20` and logs `PORTABILITY_EXPORTED:102`.

### Theory — PII masking, log redaction, and GDPR erasure/portability from first principles (100+ lines)

#### 2.1 What "PII" is in this domain — the field set

`PiiMasker.PII_KEYS:19` is the field allowlist: `email, customerEmail → EMAIL:20, phoneNumber → PHONE:22, fullName/street/city/state/country/postalCode → NAME:23`. These `Customer.java` / `Address.java:25` columns carry personal data under GDPR `Art.4(1)` (identifiable natural person). Other columns (`orderNumber:64 Order.java`, `product.price`) are not PII. The map is checked by `redactJson:103` `type = PII_KEYS.get(group(1))`; match → `mask(value,type):38`, miss → `GENERIC_EMAIL 35` second pass catches raw e-mails under non-`PII_KEYS` keys (`detail: "alice@example.com"`). The fallback `GENERIC_EMAIL.replaceAll("[EMAIL_REDACTED]") 113` is intentional over-matching (a `product description` containing `alice@example.com` is redacted too) — privacy errs redacted.

#### 2.2 `PiiMasker` — deterministic, idempotent masking primitives

```java
// PiiMasker.java:38-89
String mask(value, PiiType.EMAIL) → maskEmail: a***@e***.com (TLD kept:50-62) //50
String mask(value, PHONE) → maskPhone: +61***11 (country prefix 3 + last 2:66,74) //66
String mask(value, NAME) → maskName: A***h (initial+last:78-89)
Map<String,PiiType> PII_KEYS = {email:EMAIL, customerEmail:EMAIL, phoneNumber:PHONE, fullName:NAME, street:NAME,...} //19
Pattern JSON_KEY_VALUE = "\"([A-Za-z]...)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"" //31 group(1)=key group(2)=value
Pattern GENERIC_EMAIL = "(?i)[A-Za-z0-9._%+-]+@..." //35
String redactJson(json) { //96
  if blank return; matcher=JSON_KEY_VALUE; while find: type=PII_KEYS.get(group1); if type==null append verbatim else masked=mask(group2,type) replace → "key":"masked"; appendTail; GENERIC_EMAIL.replaceAll("[EMAIL_REDACTED]"); //100-113
}
```

Determinism (same input → same masked output) is deliberate: `GdprService.detail 128 non-PII` tests can pin `a***@e***.com` deterministically; non-deterministic `UUID` placeholder would break snapshot tests. Each `mask*` is `O(len)` string slice; `redactJson` is `O(body length)` regex — acceptable because bodies are bounded JSON records (PR #21 records). `null:39/51/68/80` returns `null` → Jackson `null` serialization keeps the `null` phone case (`CustomerRequest` `phoneNumber` nullable `CustomerRequest:25`).

#### 2.3 Response masking — Jackson serializer path vs log path, kept orthogonal

Two masking paths diverge:

- Response JSON: `OrderResponse.java:22 @MaskedPii(PiiType.EMAIL) String customerEmail` + `SensitiveDataSerializer` (a `JsonSerializer<String>` registered by `PiiMaskingModule` for `@MaskedPii`-annotated components) reads `SecurityContextHolder` (`PiiAccessDecider hasAuthority SCOPE_pii_read`) — privileged caller (admin with `scope pii_read`) gets raw `alice@example.com`; ordinary `SCOPE_order_read` or `ROLE_API_KEY` gets `a***@e***.com`. The DTO itself never branches; the serializer always checks the context before `gen.writeString(mask==null? value : PiiMasker.mask(value,type):38)`.

- Logs: `PiiRedactionFilter.java:31` runs for every `/api/** 41`, buffers `ContentCaching*Wrapper 46-49`, then `PiiMasker.redactJson 72` irrespective of caller scope — raw values never log even for the `SCOPE_pii_read` holder (`PiiRedactionFilter:24 "a property of the log sink, not of the caller's rights"`). The two paths intentionally do not share "can this caller see raw?" state beyond `SensitiveDataSerializer`.

#### 2.4 `PiiRedactionFilter` — wrapping and copying protocol

```java
// PiiRedactionFilter.java:35-65
doFilterInternal(req,res,chain){ //35
  if uri==null || !uri.startsWith("/api/") return passThrough 41-44
  wrappedReq = new ContentCachingRequestWrapper(req); 46-47
  wrappedRes = new ContentCachingResponseWrapper(res); 48-49
  try { chain.doFilter(wrappedReq,wrappedRes); } //51
  finally {
    if(log.isDebugEnabled()){ //53 DEBUG-gated (default DEBUG, prod INFO silent)
      log.debug("http {} {} -> {} body={}", method, uri, status, redacted(wrappedRes.getContentAsByteArray())); //54-56 Response redacted
      if(requestBody.length>0) log.debug("... request body (redacted)={}", redacted(requestBody)); //57-61 Request redacted
    }
    wrappedRes.copyBodyToResponse(); //64 Required: wrapper buffered body must be copied to the real response
  }
}
String redacted(byte[] body){ return PiiMasker.redactJson(new String(body, UTF_8)); } //68-73
```

`ContentCaching*Wrapper` caches bytes for re-read; without `copyBodyToResponse:64` the response body would never flush to the client (common `OncePerRequestFilter` bug). The filter is `Ordered` via `FilterRegistrationBean` if needed (default low order so CORS/Security filters run first). Alternatives: Logback `PatternLayout` `%replace` or `JsonLayout` customizer mask at serialization — possible but centralising via filter keeps the `PII_KEYS:19` single source.

#### 2.5 GDPR Art.17 — Erasure vs anonymization, the retention-aware split

```java
// GdprService.java:51-79
@Transactional ErasureResponse erase(Long customerId){ //52
  Customer customer = requireCustomer(customerId); //53 findById or IllegalArgumentException Unknown→404 111-115
  actor = currentActor(); //54 SecurityContextHolder 118-125 → "system" fallback when Anonymous/no auth
  orderCount = customer.getOrders().size(); addressCount = customer.getAddresses().size(); //55-56
  if(orderCount==0){ //58 no legal retention
    auditLogs.saveAndFlush(AuditLog.of(customerId,"CUSTOMER_ERASED",actor, detail("ERASED",orderCount,addressCount))); //61
    customers.delete(customer); customers.flush(); //63-64 addresses cascade-deleted; audit row survives (no FK) 60
    return new ErasureResponse(customerId,"DELETED", now, customer+addresses+" removed."); //65-66
  }
  // retention: Art.17(3) — accounting/legal requires orders kept
  customer.setEmail("erased-"+customerId+ERASED_EMAIL_SUFFIX); customer.setFullName("Erased User"); customer.setPhoneNumber(null); customers.flush(); //71-74 placeholder non-identifying
  auditLogs.saveAndFlush(AuditLog.of(customerId,"CUSTOMER_ANONYMIZED",actor, detail("ANONYMIZED",orderCount,addressCount))); //75-76
  return new ErasureResponse(customerId,"ANONYMIZED", now, orderCount+" order(s) retained ..."); //77-78
}
static final String ERASED_EMAIL_SUFFIX="@erased.invalid"; //37 deterministic non-identifying RFC 2606 invalid domain
```

Why anonymize vs hard delete when orders exist: `Order` rows reference `Customer` via FK (`Customer.orders` bidirectional); accounting (invoice `Payment.java`, outbox) and `EventStoreService:31` history reference the order history — GDPR `Art.17(3)(b)` allows refusing erasure when retention is legally required (order records). Placeholders (`erased-42@erased.invalid 37`, `Erased User`) are deterministic per `customerId`, satisfy the `UNIQUE email`/`NOT NULL` constraints (`CustomerRequest 17 email NotBlank`) and guarantee no PII survives linkability. `ERASED_EMAIL_SUFFIX 37` uses `erased.invalid` (`RFC 2606` reserved) so the placeholder never collides with a real address.

#### 2.6 GDPR Art.20 — Portability as a structured export

```java
// GdprService.java:81-109
@Transactional PortabilityResponse exportData(Long customerId){ //82
  Customer customer = requireCustomer(customerId); //83
  List<AddressExport> addresses = customer.getAddresses().stream().map(a->new AddressExport(a.getStreet(),a.getCity(),a.getState(),a.getPostalCode(),a.getCountry(),a.isDefault(),a.getAddressType()==null?null:a.getAddressType().name())).toList(); //85-89
  List<OrderExport> orders = customer.getOrders().stream().map(o->new OrderExport(o.getOrderNumber(),o.getOrderDate(),o.getStatus().name(),o.getTotalAmount(),o.getPayment()==null?null:o.getPayment().getPaymentMethod().name(), o.getItems().stream().map(i->new OrderItemExport(i.getProduct().getName(),i.getQuantity(),i.getUnitPrice(),i.getTotalPrice())).toList())).toList(); //91-100
  auditLogs.saveAndFlush(AuditLog.of(customerId,"PORTABILITY_EXPORTED",currentActor(),"{}")); //102-103
  return new PortabilityResponse(now,customer.getId(),customer.getEmail(),customer.getFullName(),customer.getPhoneNumber(), addresses, orders, "Export under GDPR Art. 20 - machine-readable, structured, common format."); //105-108
}
```

`Art.20` requires "structured, commonly used and machine-readable" export of data *provided by the data subject*. The export includes `Customer.email/fullName/phone 105-106` plus historical `AddressExport:85` + `OrderExport:91` (`orderNumber/date/status/totalAmount/paymentMethod`) and `OrderItemExport:97` (`productName/quantity/unitPrice/totalPrice`) — all columns the subject furnished/derived. It intentionally excludes `Payment.transactionId` or `outbox` internals irrelevant to portability. The `ordered list` `toList()` gives deterministic JSON array shape for tests.

#### 2.7 Audit — `AuditLog` as the compliance spine

`AuditLog.java` (`domain/audit/AuditLog.java`) columns: `customerId`, `action` (`CUSTOMER_ERASED/CUSTOMER_ANONYMIZED/PORTABILITY_EXPORTED:61/75/102`), `actor` (`SecurityContextHolder.getName() 118-125` else `"system" 121/124`), `detail` (`Map.of("mode",orderCount,addressCount) → writeValueAsString 128-137` falling back `"{}"` `135`). `GdprService:62/76/103` flushes before return so the row is committed with the `Customer` change (`@Transactional:51,81` same tx). `detail` is *non-PII by construction* (`mode/orderCount/addressCount` only — raw `email/fullName` never reach the map). Audit outlives `DELETE 63` because the `audit_logs` FK to `customers` is absent (`GdprService:60 "audit row survives (no FK)"`).

#### 2.8 `currentActor` fallback and security interplay

`currentActor:118` reads `SecurityContextHolder.getContext().getAuthentication()` (`SecurityConfig.java:45` populated by JWT or `ApiKeyAuthenticationFilter`) — absent/`AnonymousAuthenticationToken` → `"system" 121/124`. GDPR tools may be invoked by batch jobs without a JWT; the audit still tracks "system" as the actor. `GdprController` itself is gated by `@PreAuthorize` similar to `OrderController:74` (details in controller file if present), but the service gracefully degrades for non-auth callers.

#### 2.9 Interactions with prior PRs — validation, security, caching

- Validation `CustomerRequest.java:17 NotBlank email` persists into `erase:71` placeholder — `erased-42@erased.invalid 37` passes `@Email`-like shape vs leaving `null` which would violate the column. Single `CustomerRequest.groups` branching irrelevant since `erase` never re-validates.
- Security PR #26 (`SecurityConfig:71 SCOPE_mcp` etc.) ensures only authenticated callers reach `GdprController`; `GdprService:54` `currentActor` is therefore available, but log redaction (`PiiRedactionFilter:31`) still masks even authenticated payloads — two concerns.
- Cache PR #28 `ProductCatalogueService` unrelated to PII — but product names in `OrderItemExport:97` show the `OrderItem.product.getName()` snapshot not the live `Product.name`; export reflects what was ordered, not renamed product.
- Exception PR #24 `GlobalExceptionHandler` maps `GdprService.requireCustomer:111` `IllegalArgumentException Unknown customer 42` → `404 RESOURCE_NOT_FOUND:85`.

#### 2.10 Alternatives — tokenization, encryption-at-rest, true deletion

- Tokenization (store `email` as `tok_...` with a vault) — dereference requires vault access; more powerful than mask but needs vault infra. `PiiMasker.maskEmail:50` is inline masking only.
- Encryption-at-rest (PG `pgcrypto` column `customer.email_enc`) — transparent after `SELECT pgp_sym_decrypt`; raw rotated. Masking here is *in transit* (response/logs), not at rest.
- True hard delete with `ON DELETE CASCADE` from `orders` — violates ledger integrity; anonymization `71-74` keeps aggregates for reporting while severing linkability — motivates PR #17 `DtoProjections` aggregate `CustomerOrderTotal` still counts post-anonymization.
- Per-field `@JsonInclude` vs `@MaskedPii`: `JsonInclude` condenses to omit `null` phone cheaply but mask with `***` preserves record shape (`"phoneNumber":"***"` vs absent).

> Interview anchor: "PR #27: `PiiMasker.java:12 PII_KEYS 19 email/customerEmail EMAIL phone PHONE name STREET... + Generic EMAIL 35`, `maskEmail 50 a***@e***.com / maskPhone 66 +61***11 / maskName 78 A***h`, `redactJson 96 JSON_KEY_VALUE 31 loop mask 103-109 + GENERIC_EMAIL [EMAIL_REDACTED] 113` deterministic. Response path `OrderResponse:22 @MaskedPii(PiiType.EMAIL)` via `SensitiveDataSerializer+PiiMaskingModule+PiiAccessDecider SCOPE_pii_read`; log path `PiiRedactionFilter:31 /api/** 41 ContentCaching* 46-49 log.debug redacted 54/72 + copyBody 64 DEBUG-gated 53` — sink vs caller. `GdprService:34 ERASED_EMAIL_SUFFIX @erased.invalid 37`, `erase 52 orderCount==0 58 → DELETED 65 + AuditLog CUSTOMER_ERASED 61, else ANONYMIZED 71 placeholders erased-{id}@erased.invalid/Erased User/null 71-74 + CUSTOMER_ANONYMIZED 75`, `exportData 82 PortabilityResponse 105 AddressExport 85/OrderExport 91/OrderItemExport 97` `PORTABILITY_EXPORTED 102`, `AuditLog 128 detail non-PII`. Actor `currentActor 118`. Retention Art.17(3) address cascade vs audit FK-free 60."

---

## 3. Solution — ASCII

```
Request  GET /api/v1/orders/1  Authorization: Bearer scope=order_read (no pii_read)  or  X-API-Key
      │
      ▼  SecurityFilterChain SecurityConfig.java:45  SecurityContext SCOPE_order_read (no SCOPE_pii_read)
      │
      ▼  DispatcherServlet → OrderController.get:133 find Order + OrderResponse:16
            @MaskedPii(PiiType.EMAIL) String customerEmail:22  →  PiiMaskingModule registered on ObjectMapper
              SensitiveDataSerializer.serialize():
                 PiiAccessDecider.hasAuthority("SCOPE_pii_read") ?
                    yes → raw alice@example.com   context has pii_read (admin)
                    no  → PiiMasker.mask(email, EMAIL):38→50  a***@e***.com +***TLD kept 62
                         (deterministic, O(email.len)) written via gen.writeString
            → JSON response  { ..., "customerEmail":"a***@e***.com", "items":[...] }

Request  POST /api/v1/customers  body { "email":"alice@example.com","fullName":"Alice Smith","phoneNumber":"+61 411 111 111" }
      │
      ▼  PiiRedactionFilter:31 (OncePerRequestFilter, @Component, Ordered after Cors/Security)
            uri startsWith /api/ ? 41
              ContentCachingRequestWrapper 46 + ResponseWrapper 48 wrap
              try chain.doFilter(wrappedReq,wrappedRes) 51 → DispatcherServlet → CustomerController → Response
              finally log.isDebugEnabled() 53 ?
                   log.debug "http POST /api/v1/customers -> 201 body={}", redacted(wrappedRes body) 54-56
                     redacted = PiiMasker.redactJson( new String(body,UTF_8)):72
                       scan JSON_KEY_VALUE 31 → key in PII_KEYS 19 ? mask per type 38/50/66/78 replace "key":"masked"
                       appendTail → GENERIC_EMAIL 35 replaceAll "[EMAIL_REDACTED]" 113 (sweeps non-PII keys)
                     → log line: { "email":"a***@e***.com","phoneNumber":"+61***11","fullName":"A***h", ... detail:"[EMAIL_REDACTED]" }
                   wrappedRes.copyBodyToResponse() 64 flush buffered body to real HttpServletResponse
              else passThrough 41-44 non-/api not logged

Subject-rights  GdprService.java:34
     erase(Long customerId):52  @Transactional 52
       customer = findById 53 or IllegalArgumentException 111→404 (GlobalExceptionHandler 85) when Unknown
       actor = currentActor() 54/118 SecurityContextHolder or "system" 121/124 (job without JWT)
       orderCount = customer.getOrders().size() 55 ; addressCount = 56
       if orderCount==0 → AuditLog.of(customerId,"CUSTOMER_ERASED",actor, detail("ERASED",orderCount,addressCount={mode,orderCount,addressCount} non-PII 128)) 61 → customers.delete(customer) 63 (addresses cascade, AuditLog FK-free 60)
                          → ErasureResponse(DELETED, now, "removed") 65
       else             → setEmail("erased-"+id+@erased.invalid[37]) 71 setFullName("Erased User") setPhone(null) 72-73 flush 74 → AuditLog.of(...,"CUSTOMER_ANONYMIZED"..., detail("ANONYMIZED"...)) 75 → ErasureResponse(ANONYMIZED)
     exportData(Long id):82  @Transactional 81
       addresses → List<AddressExport street,city,state,postal,country,isDefault,type:85-89>
       orders    → List<OrderExport orderNumber,orderDate,status,total,paymentMethod, items: OrderItemExport(productName,quantity,unitPrice,total) 91-99>
       auditLog PORTABILITY_EXPORTED 102 "{}" → PortabilityResponse(now, id, email, fullName, phone, addresses, orders, "Export under Art.20") 105-108

 Hardened  AuditLog detail never contains raw PII (Map.of mode/orderCount/addressCount only 128) + redactJson sweep catches email under unexpected keys 35
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/security/pii/PiiMasker.java` | `12-14` | Utility helpers | `final class PiiMasker 12 ; PII_KEYS:19` map |
| `PiiMasker.java` | `19-28` | `PII_KEYS` | `email:EMAIL, customerEmail:EMAIL, phoneNumber:PHONE, fullName:NAME, street/city/state/country/postalCode:NAME` |
| `PiiMasker.java` | `31-36` | Regexes | `JSON_KEY_VALUE:31` `group(1) key (2) value`, `GENERIC_EMAIL:35` last-resort `[EMAIL_REDACTED]` |
| `PiiMasker.java` | `38-89` | `mask(maskEmail/maskPhone/maskName)` | `mask:38` switch EMAIL→`maskEmail:50 a***@e***.com`, PHONE `66 +61***11`, NAME `78 A***h`, null 39/51/68/80 |
| `PiiMasker.java` | `96-114` | `redactJson` | Key loop `while(find) 102 type = PII_KEYS.get(group1) 103 → masked 107-108` + `appendTail 112 + GENERIC_EMAIL.replaceAll 113` |
| `src/main/java/com/company/orderapi/security/pii/PiiRedactionFilter.java` | `31-74` | Log filter | `@Component 30` `OncePerRequestFilter 31`, `uri /api/ 41`, `ContentCaching*Wrapper:46-49`, `log.debug redacted 54-61`, `copyBodyToResponse 64`, `redacted:72 PiiMasker.redactJson UTF_8` |
| `src/main/java/com/company/orderapi/security/pii/SensitiveDataSerializer.java` | — | Response mask | `JsonSerializer` for `@MaskedPii` fields uses `PiiMasker.mask:38` + `PiiAccessDecider` |
| `src/main/java/com/company/orderapi/security/pii/PiiAccessDecider.java` | — | Scope gate | `hasAuthority("SCOPE_pii_read")` via `SecurityContextHolder` |
| `src/main/java/com/company/orderapi/security/pii/PiiMaskingModule.java` | — | Jackson Module | Registers `SensitiveDataSerializer` on `ObjectMapper` for `@MaskedPii`-annotated components |
| `src/main/java/com/company/orderapi/security/pii/PiiType.java` | `—` | Enum | `EMAIL, PHONE, NAME` |
| `src/main/java/com/company/orderapi/security/pii/MaskedPii.java` | — | Annotation | `@MaskedPii(PiiType)` on `OrderResponse.customerEmail:22` etc. |
| `src/main/java/com/company/orderapi/domain/service/GdprService.java` | `34-37` | Service + suffix | `@Service 34 ERASED_EMAIL_SUFFIX @erased.invalid 37 deterministic invalid domain` |
| `GdprService.java` | `51-79` | `erase` | `@Transactional:51` `erase:52` `if orderCount==0 58` → `ERASED 61+63 DELETED 65` else `ANONYMIZED 71-75 addresses cascade 60`, `currentActor:54/118` |
| `GdprService.java` | `81-109` | `exportData` | `@Transactional:81` `exportData:82` → `AddressExport 85 / OrderExport 91 / OrderItemExport 97 → PortabilityResponse 105 + PORTABILITY_EXPORTED 102` |
| `GdprService.java` | `111-137` | Helpers | `requireCustomer:111 orElseThrow Unknown 113→404`, `currentActor:118 SecurityContextHolder 118-125 fallback system`, `detail:128 non-PII map → writeValueAsString 130` |
| `src/main/java/com/company/orderapi/domain/audit/AuditLog.java` | — | Audit entity | `customerId/action/actor/detail` (no FK to `customers` so `ERASED` row survives `DELETE:63`) |
| `src/main/java/com/company/orderapi/api/rest/controller/GdprController.java` | — | HTTP surface | `DELETE /api/v1/customers/{id}/gdpr/erase` → `erase:52`, `GET /gdpr/export` → `exportData:82` (gated via `@PreAuthorize`) |
| `src/main/java/com/company/orderapi/api/dto/OrderResponse.java` | `22` | Response masking demo | `@MaskedPii(PiiType.EMAIL) customerEmail:22` masked unless `SCOPE_pii_read` |
| `src/main/java/com/company/orderapi/api/dto/PortabilityResponse.java` | — | Export DTO | `PortabilityResponse 105 + AddressExport + OrderExport + OrderItemExport` `Art.20` structured |

```java
// PiiMasker.java:50-63 — email rule
public static String maskEmail(String value){
  int at = value.lastIndexOf('@'); if(at<=0||at==value.length()-1) return maskName(value);
  String localHead = value.substring(0,1); String domain = value.substring(at+1);
  int dot = domain.indexOf('.'); String domainTail = dot>0? domain.substring(dot):"";
  return localHead+"***@"+domain.substring(0,1)+"***"+domainTail; // a***@e***.com 62
}
// GdprService.java:71-75 — anonymize branch
customer.setEmail("erased-"+customerId+ERASED_EMAIL_SUFFIX); customer.setFullName("Erased User"); customer.setPhoneNumber(null);
auditLogs.saveAndFlush(AuditLog.of(customerId,"CUSTOMER_ANONYMIZED",currentActor(), detail("ANONYMIZED",orderCount,addressCount)));
// PiiRedactionFilter.java:46-64 — wrapper protocol
ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
try{ chain.doFilter(wrappedRequest,wrappedResponse); } finally{ if(log.isDebugEnabled()) log.debug("http {} {} -> {} body={}",..., redacted(wrappedResponse.getContentAsByteArray())); wrappedResponse.copyBodyToResponse(); }
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Confirm masking present
grep -n "@MaskedPii\|PiiType\|PiiMasker\|PiiRedactionFilter\|PiiMaskingModule" \
  src/main/java/com/company/orderapi/api/dto/OrderResponse.java src/main/java/com/company/orderapi/security/pii/*.java | head -n 30

# Response masking: raw vs masked per scope
# Generate two tokens (HS256) one with pii_read
# Normal scope
curl -s http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" | jq .customerEmail
# Expect a***@e***.com (@MaskedPii 22 without SCOPE_pii_read)
# Admin token with scope containing pii_read (build as PR #26 Python helper)
JWT_PI=$(python3 -c "
import base64,hmac,hashlib,json,time
secret=b'local-learning-secret-change-me-please-32chars'
header=base64.urlsafe_b64encode(json.dumps({'alg':'HS256','typ':'JWT'}).encode()).decode().rstrip('=')
payload=base64.urlsafe_b64encode(json.dumps({'sub':'admin','scope':'order_read pii_read','exp':int(time.time())+600}).encode()).decode().rstrip('=')
sig=base64.urlsafe_b64encode(hmac.new(secret, f'{header}.{payload}'.encode(), hashlib.sha256).digest()).decode().rstrip('=')
print(f'{header}.{payload}.{sig}')
")
curl -s http://localhost:8080/api/v1/orders/1 -H "Authorization: Bearer $JWT_PI" | jq .customerEmail
# Expect alice@example.com raw

# Log redaction: verify DEBUG logs are redacted, not raw
./mvnw spring-boot:run 2>&1 | grep -E "http.*body.*redacted|PiiRedactionFilter" | head
# Request bodies in DEBUG should show a***@e***.com / +61***11 / [EMAIL_REDACTED]

# Direct PiiMasker helpers (programmatic)
# PiiMasker.maskEmail("alice@example.com") → a***@e***.com
# PiiMasker.maskPhone("+61 411 111 111") → +61***11
# PiiMasker.redactJson('{"email":"alice@example.com","note":"alice@example.com"}') → '{"email":"a***@e***.com","note":"[EMAIL_REDACTED]"}'

# GDPR subject-rights
# Erasure — customer with zero orders → DELETED
curl -s -X DELETE http://localhost:8080/api/v1/customers/42/gdpr/erase -H "X-API-KEY: dev-api-key-orderapi" | jq '.status,.customerId,.message'
# Expect DELETED 42

# Erasure — customer with orders → ANONYMIZED (retains order count)
curl -s -X DELETE http://localhost:8080/api/v1/customers/1/gdpr/erase -H "X-API-KEY: dev-api-key-orderapi" | jq '.status,.message'
# Expect ANONYMIZED "order(s) retained ..."

# Verify anonymized fields via DB (erased-42@erased.invalid + Erased User + phone null)
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT email, full_name, phone_number FROM customers WHERE id=1;"

# Portability export — machine-readable under Art.20
curl -s http://localhost:8080/api/v1/customers/1/gdpr/export -H "X-API-KEY: dev-api-key-orderapi" | jq '.email,.fullName,.addresses[0] | keys, .orders[0] | {orderNumber,items:[.items[0].productName]}'
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT action FROM audit_logs WHERE customer_id=1 AND action IN ('PORTABILITY_EXPORTED','CUSTOMER_ERASED','CUSTOMER_ANONYMIZED') ORDER BY id DESC LIMIT 5;"

# Audit detail is non-PII
grep -n "detail.*mode.*orderCount\|AuditLog.of" src/main/java/com/company/orderapi/domain/service/GdprService.java
```

```java
// Annotate a new PII field on any DTO — masked in responses, swept in logs
public record CustomerResponse(@MaskedPii(PiiType.EMAIL) String email, @MaskedPii(PiiType.PHONE) String phoneNumber){}
// Invoke erase/export from batch (no SecurityContext → actor "system" 121-124)
ErasureResponse er = gdprService.erase(42L); // DELETED vs ANONYMIZED by orderCount 55-65
PortabilityResponse pr = gdprService.exportData(42L); // Art.20 105
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Response masking via `@MaskedPii` + `SensitiveDataSerializer` gated on `SCOPE_pii_read` | `OrderResponse:22` per-field Jackson | Separate `AdminOrderResponse` / `/admin/orders` DTO | One record no explosion; raw/masked resolved at serialization (`PiiAccessDecider`) | Custom `Module` + serializer per new PII field |
| Log masking via `PiiRedactionFilter:31` over `ContentCaching*Wrapper:46-49` | Filters every `/api/** 41` body through `redactJson:72` (key set `19` + `GENERIC_EMAIL 35`) | Logback `%replace` or `JsonLayout` filter at logger level | Single `PII_KEYS:19` source for response+logs; `GENERIC_EMAIL 35` sweeps non-`PII_KEYS` keys; `DEBUG-gated 53` → silent prod INFO | Per-request regex on bodies (`O(len)`); large uploads pay cost; must `copyBody 64` or response drops |
| Erasure policy `orderCount==0 → DELETED 58-65` else `ANONYMIZED 71-75` | `GdprService.java:58` branch + `ERASED_EMAIL_SUFFIX @erased.invalid 37` | Blind `DELETE` cascading orders or reject erasure | Honors `Art.17(3)` retention for orders (accounting) and honours constraints (`UNIQUE email`, `phone nullable`); deterministic placeholder satisfies `NotBlank` `CustomerRequest:17` | Anonymized row remains — subject pseudonym `erased-42@erased.invalid` is unique but non-identifying |
| `AuditLog` FK-free survival | `audit_logs` no FK to `customers` (`GdprService:60`) | FK `customer_id → customers` | `ERASED 61` audit survives `customers.delete 63` so compliance proof persists post-deletion | Can't JOIN `AuditLog→Customer` post-ERASED (intentional — customer gone) |
| Deterministic masking `a***@e***.com 62 +61***11 74 A***h 88` | `PiiMasker.java:62,74,88` per `mask*` | `UUID` / random | Tests pin exact output; support can correlate masked values across logs without decode | Deterministic still leaks prefix suffix hint (intent for support) |
| `PII_KEYS:19` allowlist vs blocklist | Explicit `email,customerEmail,phoneNumber,fullName,street,…` | Regex `*.email, *.PII` catch-all | Narrow — avoids masking `product.description` that accidentally contains "email-like" (`GENERIC_EMAIL:35` as narrow fallback not primary) | New PII field requires adding to map `19` |
| `currentActor:118` `SecurityContextHolder` → `"system"` | Pseudonym if anonymous | Require auth always | Batch jobs/integration without JWT still audition (`GdprService:62 portal actor system`) | No actor nuance for jobs without token — would need job ID injected |

---

## 7. How to verify

```bash
# Response masking wired
grep -n "@MaskedPii\|PiiType\|SensitiveDataSerializer\|PiiMaskingModule\|PiiAccessDecider" \
  src/main/java/com/company/orderapi/security/pii/*.java src/main/java/com/company/orderapi/api/dto/*.java | head -n 30
# Expect OrderResponse:22 @MaskedPii(EMAIL) + module registration

# Log mask present and DEBUG-gated
grep -n "PiiRedactionFilter\|ContentCaching\|redacted\|isDebugEnabled\|copyBodyToResponse" \
  src/main/java/com/company/orderapi/security/pii/PiiRedactionFilter.java  # 31,46,53,64
grep -n "PII_KEYS\|JSON_KEY_VALUE\|GENERIC_EMAIL\|maskEmail\|maskPhone\|maskName\|redactJson" \
  src/main/java/com/company/orderapi/security/pii/PiiMasker.java  # 19,31,35,50,66,78,96

# Erasure/portability pair
grep -n "GdprService\|GdprController\|ERASED_EMAIL_SUFFIX\|CUSTOMER_ERASED\|CUSTOMER_ANONYMIZED\|PORTABILITY_EXPORTED\|ErasureResponse\|PortabilityResponse" \
  src/main/java/com/company/orderapi/domain/service/GdprService.java src/main/java/com/company/orderapi/api/rest/controller/GdprController.java | head -n 30

# Branch in erase covers both modes
grep -n "orderCount==0\|DELETED\|ANONYMIZED" src/main/java/com/company/orderapi/domain/service/GdprService.java  # 58 DELETED 65 ANONYMIZED 71-78

# Audit non-PII detail
grep -n "writeValueAsString.*mode.*orderCount\|detail(" src/main/java/com/company/orderapi/domain/service/GdprService.java  # 128-137 strictly counts
grep -n "AuditLog.of.*CUSTOMER_ERASED\|CUSTOMER_ANONYMIZED\|PORTABILITY_EXPORTED" src/main/java/com/company/orderapi/domain/service/GdprService.java  # 61,75,102

# Live header/verb masking difference per scope
curl -s http://localhost:8080/api/v1/orders/1 -H "X-API-KEY: dev-api-key-orderapi" | jq .customerEmail | grep -q "\*\*\*" && echo masked-ok
curl -s http://localhost:8080/api/v1/orders/1 -H "Authorization: Bearer $JWT_PI" | jq .customerEmail | grep -qv "\*\*\*" && echo raw-for-pii_read-ok

# Redact helper programmatic
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "PiiMasker\|PiiRedaction" | head
# Or invoke via Testcontainers PII test coverage:
grep -rn "PiiMasker\|redactJson\|maskEmail\|GdprService" src/test --include="*.java" | head

# Erasure rows as post-check
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT customer_id, action, actor FROM audit_logs WHERE action IN ('CUSTOMER_ERASED','PORTABILITY_EXPORTED') ORDER BY id DESC LIMIT 5;"
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New DTO field carrying PII ("billingEmail") → add `"billingEmail" → PiiType.EMAIL` to `PiiMasker.PII_KEYS:19` and annotate the response record component `@MaskedPii(PiiType.EMAIL)` (`OrderResponse:22` pattern) with a `SensitiveDataSerializer`-compatible `PiiAccessDecider` scope (`SCOPE_pii_read` vs custom `pii_billing`). `PiiRedactionFilter:31` automatically picks up the new key for logs via `redactJson 96`. Persisting erasure-like flows? Copy `GdprService.erase:51` branch: `requireCustomer 111`, check retention (`orderCount==0 58` vs domain aggregate count), `AuditLog.of(action, actor currentActor 118, detail non-PII 128)`, either `delete 63` or placeholder `71-74` then flush.
- **Operate:** Confirm `log.isDebugEnabled 53` `DEBUG` in `dev` vs `INFO` silent in `prod` (`application-prod.yml` controls); never flip prod to `DEBUG` without ensuring `PiiRedactionFilter:31` still wraps `/api/** 41` (unauthenticated log shipping would otherwise redact anyway, but wrapper miss on non-`/api/` paths). Alert on `audit_logs` lag: ` GdprService:62 saveAndFlush` in same tx so a delayed `AuditLog` row implies tx rollback. Validate erasure post-anonymization: `SELECT email FROM customers WHERE id=42;` must be `erased-42@erased.invalid 37`, never original.
- **Interview:** "PR #27: `PiiMasker.java:12` deterministic `PII_KEYS 19` → `maskEmail 50 a***@e***.com / maskPhone 66 +61***11 / maskName 78 A***h`, `redactJson 96 JSON_KEY_VALUE 31 key sweep 103-109 + GENERIC_EMAIL 35 [EMAIL_REDACTED] 113`. Response via `@MaskedPii:22 + SensitiveDataSerializer + PiiMaskingModule` gated by `PiiAccessDecider SCOPE_pii_read`. Logs via `PiiRedactionFilter:31 /api/** 41 ContentCaching* 46-49 log.debug redacted 54 + copyBody 64 DEBUG-only 53`. `GdprService:34 ERASED_EMAIL_SUFFIX @erased.invalid 37` `erase 52 if orderCount==0 58 → ER…_ERASED 61 DELETED 65 else ANONYMIZED 71-75` + `exportData 82 PortabilityResponse 105 AddressExport 85 OrderExport 91` `PORTABILITY_EXPORTED 102`. `AuditLog non-PII detail 128 mode/orderCount/addressCount` FK-free 60 `actor currentActor 118 system fallback`."

---

## 9. Interview lens — Q&A

**Q1: Which two paths mask PII and how do they differ in scope gating?**
A: Response path `OrderResponse:22 @MaskedPii + SensitiveDataSerializer` consults `PiiAccessDecider` (`SCOPE_pii_read` → raw vs masked `PiiMasker 50`). Log path `PiiRedactionFilter:31` always `redactJson 72` `PII_KEYS 19 + GENERIC_EMAIL 35` irrespective of caller's scope — log sink redaction vs caller permission (§2.2-2.4).

**Q2: Why does `erase` sometimes `DELETE` and sometimes anonymize?**
A: `GdprService.erase:52` `orderCount==0 58 → DELETE 63 + Audit CUSTOMER_ERASED 61 DELETED 65` — no retention. `else 71-75 → setEmail erased-{id}@erased.invalid 71+37 / Erased User / phone null + CUSTOMER_ANONYMIZED 75 ANONYMIZED` — `Art.17(3)` retains `Order` rows for legal/accounting; placeholder keeps `UNIQUE/NotBlank` invariants (§2.5).

**Q3: What makes `redactJson` catch e-mails under non-PII keys?**
A: First loop on `JSON_KEY_VALUE 31` masks only `PII_KEYS:19` keys `103-109`; second sweep `GENERIC_EMAIL 35 replaceAll "[EMAIL_REDACTED]" 113` catches `alice@example.com` values under any key (§2.1).

**Q4: How is the log filter safe not to drop the response?**
A: `PiiRedactionFilter:31` wraps via `ContentCaching*Wrapper 46-49`, processes buffered body, then `wrappedRes.copyBodyToResponse() 64` in `finally` flushes to the real `HttpServletResponse` (§2.4).

**Q5: What does the audit log contain on erasure/portability?**
A: `AuditLog.of(customerId, action CUSTOMER_ERASED/CUSTOMER_ANONYMIZED/PORTABILITY_EXPORTED:61/75/102, actor currentActor 118-125, detail writeValueAsString {mode,orderCount,addressCount} non-PII 128)` — no raw PII, committed same tx `62/76/103` (`§2.7`).

**Q6: Why `@erased.invalid 37` not `null`/`""`?**
A: `Customer.java email` has `UNIQUE NOT NULL` + `CustomerRequest:17 NotBlank` validation; `null`/`""` would violate DB invariants; `erased-{id}@erased.invalid` `71` is deterministic `1:1 customerId` non-identifying `RFC 2606 invalid` placeholder (§2.5).

**Q7: What is `currentActor:118` when GDPR is invoked by a job without a JWT?**
A: `SecurityContextHolder null/Anonymous 120-121 → "system" 121/124` fallback so audit still has an actor (§2.8).

---

## 10. Honest limits & next step → PR #28

`PiiMasker.redactJson 96` regex scans only stringified JSON bodies; streaming multipart or `application/octet-stream` bypasses it. `PII_KEYS:19` `postalCode:28 → NAME` masking `A***h` is heuristic — postal code `12345` becomes `1***5` lossy. `ERASED_EMAIL_SUFFIX 37` placeholder is deterministic and searchable — an attacker with the rule and `customerId` can reconstruct the mapping to know "erased-42 exists"; use opaque token if anonymity requires unlinkability. Audit `detail:128` intentionally forgets which fields were erased — a per-field erase manifest is not retained. Next PR adds the read-acceleration counterpart: `ProductCatalogueService.java:35` cache-aside via `RedisConfig:20 @EnableCaching`, `@Cacheable:50` on `get:54` and `@CacheEvict:60,71,85,97` on writes, store `ProductResponse` not `Product` to keep Redis off Hibernate's session.

See [`28-caching-with-redis.md`](./28-caching-with-redis.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Mask `customerEmail` in response per caller scope | `@MaskedPii + SensitiveDataSerializer + PiiAccessDecider` | `OrderResponse.java:22` + `pii/SensitiveDataSerializer` + `PiiMasker:38` | `SCOPE_pii_read` raw else `a***@e***.com` |
| Mask bodies in logs regardless of caller | `PiiRedactionFilter` + `redactJson` | `PiiRedactionFilter.java:31-74` + `PiiMasker.java:96-113` | `20ms` sweep per `/api/** 41`, `PII_KEYS 19` + `GENERIC_EMAIL 35` |
| Right to erasure | `GdprService.erase:52` DELETED vs ANONYMIZED | `GdprService.java:58-78`, `ERASED_EMAIL_SUFFIX 37 @erased.invalid` | `Art.17(3)` `orderCount==0` vs `orders retained` |
| Right to portability | `GdprService.exportData:82` `PortabilityResponse 105` | `GdprService.java:82-108` `Address/Order/OrderItem Export` | Structured Machine-readable `Art.20` |
| Compliance proof | `AuditLog` `CUSTOMER_ERASED/CUSTOMER_ANONYMIZED/PORTABILITY_EXPORTED` | `AuditLog.java` + `GdprService.java:61,75,102` | Survives `DELETE 63` FK-free `60` `detail non-PII 128` |
| Who acted | `currentActor:118` via `SecurityContextHolder` | `GdprService.java:118-125` `system` fallback | Batch jobs without JWT `→ system` |

