# 23. Validation (PR #23)

> PR #23 — Bean Validation: `jakarta.validation` (Hibernate Validator) at the controller boundary, `@Valid` cascading, validation groups `Create`/`Update`, container constraints, and custom class-level constraints (`@ValidOrderRequest`, `@ValidStock`). Stack: Java 21, Spring Boot 3.4.1, `spring-boot-starter-validation`, Hibernate Validator 8, `src/main/java/com/company/orderapi/api/dto/...` + `GlobalExceptionHandler` (PR #24) + Jackson records. See `README.md:1772` roadmap `| 23 | Validation |`.

---

## 1. Purpose — what shipped

PR #23 moves validation from ad-hoc `if (x==null) throw` into declarative Bean Validation on the immutable `record` DTOs introduced in PR #21. Shipped: field constraints on `OrderRequest.java:16-24` (`@NotNull customerId:17`, `@NotEmpty @Valid items:18`), nested `OrderItemRequest:21` (`@NotNull productId/quantity`), `CustomerRequest.java:16-27` grouped constraints (`@NotBlank/@Email/@Size/@Pattern` with `groups=Create/Update`), marker groups `Create.java:8`/`Update.java:7`, class-level cross-field validators `ValidOrderRequest.java:15`/`ValidOrderRequestValidator` (no duplicate `productId`) and `ValidStock.java`/`ValidStockValidator.java:3` (stock≥0), and the `@Valid`/`@Validated` wiring in controllers (`OrderController.java:80` `@Valid`, `CustomerController` `@Validated(Create/Update)`).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Controllers used imperative checks (`if (customerId==null) throw new IllegalArgumentException("...")`). Rules scattered across handlers, not reusable, bypassable if a new endpoint forgot them. Cross-field invariants (no duplicate product in one order) lived as controller `if (hasDup(...))` boilerplate without a field path for error messages. Creation vs update both reused the same unguarded shape — `PUT` wrongly requiring creation-only `fullName` length or allowing invalid creates. Nesting like `List<OrderItemRequest>` was not recursively validated.

**After:** `OrderRequest` declaratively states the schema (`@ValidOrderRequest:15`, `customerId @NotNull`, `items @NotEmpty @Valid:18`, each item's `productId`/`quantity @NotNull:21-23`). `CustomerRequest:17-25` carries per-component `groups` so `POST @Validated(Create.class)` enforces `fullName @Size(2..100, Create)` while `PUT @Validated(Update.class)` ignores length and only checks `email @NotBlank {Create,Update}`. Failures are caught by `MethodArgumentNotValidException` / `ConstraintViolationException` → `GlobalExceptionHandler.java:67-73` `400 VALIDATION_ERROR application/problem+json` with `code/hint` — controllers never see invalid data.

### Theory — Bean Validation, groups, and Validator from first principles (100+ lines)

#### 2.1 What Bean Validation replaces — constraints as metadata vs imperative ifs

Bean Validation (`jakarta.validation` API, Hibernate Validator impl from `spring-boot-starter-validation`) treats constraints as annotations on the type/field/component/parameter — checked by a `Validator` without handler code. `CustomerRequest.java:17-25` says "an invalid `CustomerRequest` record *cannot exist* at the service boundary" vs "the controller must remember to check email". Violations carry `propertyPath` (`email`, `items[0].productId`, `items`) so the error shape is structured and test-assertable; string `IllegalArgumentException` messages are fragile.

#### 2.2 Validator, ValidatorFactory, and MethodValidationPostProcessor

Boot's `ValidationAutoConfiguration` creates a `LocalValidatorFactoryBean` (`ValidatorFactory` + `Validator` + `MessageInterpolator`). `MethodValidationPostProcessor` wraps `CustomerController`/`OrderController` beans with an AOP interceptor: before `create(@Validated(Create.class) @RequestBody CustomerRequest)` executes, the interceptor looks up constraints on the parameter type. `@Valid` alone validates group `Default`; `@Validated(Create.class)` validates group `Create.class`. Failure throws `ConstraintViolationException` (method validation) or `MethodArgumentNotValidException` (deserialized `@RequestBody`). Both are mapped `400` in `GlobalExceptionHandler:67-73`.

#### 2.3 `@Valid` vs `@Validated` — which groups run

```java
// OrderController.java:80  no group — Default
public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest req)
// CustomerController  creation — Create group
public ResponseEntity<CustomerResponse> create(@Validated(Create.class) @RequestBody CustomerRequest req)
// CustomerController  update — Update group
public ResponseEntity<CustomerResponse> update(@PathVariable Long id, @Validated(Update.class) @RequestBody CustomerRequest req)
```

`@Valid` (`jakarta.validation.Valid`) says "validate this object with the `Default` group". `@Validated` (`org.springframework.validation.annotation.Validated`) says "validate with the explicit groups `Create.class` etc." Mixing both on a handler param is redundant — `@Validated` alone suffices. `@Valid` on a `List<OrderItemRequest> items:18` means *traverse the container* and validate each element (Hibernate Validator's `TraversableResolver` knows `List` is a container). Single annotation on `items` covers `NotEmpty` (container itself) and `@Valid` (elements). Without `@Valid`, an `OrderItemRequest(null,null)` passes `@NotEmpty` because the list is non-empty.

#### 2.4 Where a constraint lives on a record — component propagation

```java
public record CustomerRequest(
  @NotBlank(groups={Create.class,Update.class}) @Email(groups={Create.class,Update.class}) String email, //17-19
  @NotBlank(groups={Create.class,Update.class}) @Size(min=2,max=100, groups=Create.class) String fullName, //21-23
  @Pattern(regexp="^[0-9+ \\-]{0,20}$", groups={Create.class,Update.class}) String phoneNumber) //25
```

On a `record`, the annotation on the component is propagated to the `field`, the `accessor` (`email()`), and the *constructor parameter* (via `ExecutableValidator` for canonical ctor validation). Bean Validation during `validate(dto, Create.class)` reads either placement, so a single component-level declaration suffices. The alternative — annotating the canonical constructor parameter explicitly — duplicates the annotation. Compiler flag `-parameters` (Boot's `maven-compiler-plugin` default) preserves parameter names so `MethodArgumentNotValidException` reports `customerId` not `arg0`.

#### 2.5 Validation groups — one record, two shapes

```java
// Create.java:8  marker
public interface Create {}
// Update.java:7
public interface Update {}
```

Groups avoid DTO explosion (`CreateCustomerRequest` + `UpdateCustomerRequest` × 3 resources = 6 classes). One `CustomerRequest` covers both by assigning `groups` per constraint: `email` `{Create,Update}` (always), `fullName` `{Create}` (`Size 2..100` only on create, allowing `PUT` patch where name may be unchanged), `phoneNumber` `{Create,Update}` optional (`null` passes `@Pattern`? No — `@Pattern` on nullable field: `null` passes, only non-null checked; pairing with `@NotBlank` would require non-null). The controller chooses the group; services doing programmatic `validator.validate(req, Create.class)` can assert either shape in tests.

#### 2.6 Nested and container validation — `@Valid` + `@ValidOrderRequest`

```java
@ValidOrderRequest                          //15 type-level cross-field
public record OrderRequest(
  @NotNull Long customerId,                 //17
  @NotEmpty @Valid List<OrderItemRequest> items) { //18
    public record OrderItemRequest(
      @NotNull Long productId,              //22
      @NotNull Integer quantity) {}         //23
}
```

Pipeline: (1) type-level `@ValidOrderRequest` validator runs (sees whole record, can check duplicate `productId`); (2) `customerId @NotNull` field; (3) `items` `NotEmpty` on list; (4) `@Valid` traverses `List` → validates each `OrderItemRequest.productId/quantity @NotNull`. Step 4 only matters because of `@Valid` — removing it silently lets `OrderItemRequest(null,null)` pass. Order controllers can rely on "if we are inside the method, every `productId`+`quantity` is present".

#### 2.7 Custom constraints — class-level `ValidOrderRequest` / `ValidStock`

```java
// ValidOrderRequest.java — annotation
@Constraint(validatedBy = ValidOrderRequestValidator.class)
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrderRequest { String message() default "duplicate product"; ... }
// ValidOrderRequestValidator.java
public class ValidOrderRequestValidator implements ConstraintValidator<ValidOrderRequest, OrderRequest> {
  public boolean isValid(OrderRequest req, ConstraintValidatorContext ctx){
    if (req==null || req.items()==null) return true; // @NotEmpty handles null
    var seen=new HashSet<Long>(); for(var i: req.items()){ if(!seen.add(i.productId())){ ctx.buildConstraintViolationWithTemplate("duplicate product "+i.productId()).addPropertyNode("items").addConstraintViolation(); return false; } } return true;
  }
}
```

Likewise `ValidStock.java` + `ValidStockValidator.java:3` checks `stockQuantity >= 0` on `ProductRequest`. Pattern: annotation on the *type* (`TYPE`) tells Validator to call the type validator after field validators. The validator adds the violation to path `items` so the error JSON aligns with the input field. Tradeoff: one small `ConstraintValidator` class per cross-field rule vs controller `if` that has no reusable property-path.

#### 2.8 Message interpolation, payload, and property paths

`message` defaults like `"{jakarta.validation.constraints.NotBlank.message}"` are interpolated via `ValidationMessages.properties`. Overriding `message="duplicate product {productId}"` plus `addPropertyNode("items")` gives `field: items, message: duplicate product 42` → `GlobalExceptionHandler:68` serializes as `ProblemDetail` with `detail` containing the message and `status 400`. Tests assert `detail` or `field` not string-matching the whole body.

#### 2.9 Validation and Jackson — failing fast on malformed JSON

Jackson deserialization (`MappingJackson2HttpMessageConverter`) runs before validation. Missing type (`"items": "not-a-list"`) → `HttpMessageNotReadableException` (not `MethodArgumentNotValidException`) → also `400` via `GlobalExceptionHandler:48` `default → 400/500`. Missing `quantity: null` inside valid JSON → passes deserialization (record ctor receives `null`) then `quantity @NotNull:23` violation `400`. Invalid enum `"status":"BAD"` in `PATCH` `JsonNode` → `IllegalArgumentException` `valueOf` failure `400` via classify `INVALID_ARGUMENT`.

#### 2.10 Performance and placement — validate at the boundary only

Validation is `O(fields + items)` per request (`OrderRequest` with 50 `items` checks 100 fields + one duplicate scan `O(N)` with `HashSet`). It belongs at the controller entry, not in `OrderService.placeOrder` — the service can assume its `lines` are already non-null/quantity-present; domain guards (`Order.cancel():151` business rules) are distinct from structural validation. Re-validating inside the service duplicates work; reusing validated `record` immutability guarantees the checks hold through the tx.

#### 2.11 Alternatives and misuses

- Manual `if (email==null) throw` — forgettable, non-reusable, no property path.
- Separate create/update DTO classes — correct but `2 × resources` boilerplate; groups scale better; use separate classes only when shapes truly diverge (different fields entirely).
- Hibernate `@Valid` on `@Entity` — validates before `flush`; useful as defense-in-depth but primary gate is the API `record`; entity validation failure still causes `500` unless the exception chain is unwrapped.
- `@Validated` at class level with `groups` — validates all handlers in the class; per-parameter `@Validated(Create.class)` is more precise.

> Interview anchor: "PR #23: `OrderRequest.java:15` `@ValidOrderRequest` type + `17 @NotNull`, `18 @NotEmpty @Valid List<OrderItemRequest>` (`21-23 @NotNull productId/quantity`), `CustomerRequest.java:17-25` per-component `groups {Create,Update}` (`Create.java:8`/`Update.java:7`), `@Valid` recurses vs bare `NotEmpty` does not, `ValidStockValidator.java:3` for `ProductRequest`, `MethodValidationPostProcessor` → `MethodArgumentNotValidException` → `GlobalExceptionHandler.java:67-73` `400 VALIDATION_ERROR`, record component propagation + `-parameters` for property names."

#### 2.12 Validation traversal order — type → field → container and fail-fast vs report-all

Hibernate Validator validates type-level `-->` field-level `-->` container-element (`@Valid`) sequentially. On `OrderRequest:15-18` the duplicate-product type validator runs even if `customerId` null - error collection is `all` by default (`failFast=false`). Setting `hibernate.validator.fail_fast=true` would short-circuit at first violation faster but produce single-error client feedback loops. The all-errors mode plus `GlobalExceptionHandler:68` serializes field → message array so the client can fix `customerId` + duplicate in one retry.

#### 2.13 I18n of messages — ValidationMessages.properties and locale

Constraint `message` keys (`{jakarta.validation.constraints.NotEmpty.message}`) are interpolated per `Locale` via Spring's `MessageSource` (`I18nConfig.java`). Switching `Accept-Language: de` returns German violations without handler change. Overriding `ValidOrderRequest.message` requires a new bundle entry; code never formats strings manually - this keeps `PII_KEYS`-style logging safe (see PR #27 `PiiMasker.java` never interpolates raw PII).

#### 2.14 Testing validation — Validator vs MockMvc

Direct `validator.validate(dto, Create.class)` asserts per-group `ConstraintViolation` set size (no HTTP layer). `MockMvc` `post("/api/v1/orders").content(json)` `andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))` asserts the end-to-end (Jackson ctor `-parameters` + Validator + `GlobalExceptionHandler:67` `ProblemDetail` 400). Both levels are used: unit Validator for groups, integration MockMvc for wiring.


---

## 3. Solution — ASCII

```
HTTP JSON  { "customerId":1, "items":[{productId:42,quantity:2},{productId:42,quantity:1}] }  (duplicate)
        │
        ▼  Jackson → record canonical ctor  (-parameters kept, ParameterNamesModule)
           OrderRequest(customerId=1, items=[OrderItemRequest(42,2),OrderItemRequest(42,1)])
        │
        ▼  MethodValidationPostProcessor  checks @Valid / @Validated(Create|Update)
           on handler param  OrderController.java:80 @Valid, CustomerController @Validated(Create/Update)
              │
              ├─ Validator (LocalValidatorFactoryBean)  Hibernate Validator 8
              │     field: customerId @NotNull ✓   items @NotEmpty ✓
              │     container traverse: @Valid List → each OrderItemRequest @NotNull productId/quantity ✓
              │     groups: CustomerRequest.java:17 email {Create,Update} ✓  fullName Size(Create) only-on-POST
              │     type-level: @ValidOrderRequest:15 → ValidOrderRequestValidator iterates items Set → duplicate 42 ✗
              │
              ├─ violation? → MethodArgumentNotValidException / ConstraintViolationException
              │               → GlobalExceptionHandler.java:67-73
              │               → 400 ProblemDetail {status:400, code:"VALIDATION_ERROR", detail:"duplicate product 42", hint:"Fix fields..."} (application/problem+json)
              │
              └─ no violation? → controller body executes  (OrderService receives non-null, non-empty, deduped lines)
                                 record immutable — no setCustomerId() post-validation invalidation

 CustomerRequest.java:16 split:
   POST /api/customers  @Validated(Create.class) → email NotBlank+Email ✓ + fullName NotBlank+Size(2..100) ✓ + phone Pattern ✓
   PUT  /customers/{id} @Validated(Update.class) → email NotBlank+Email ✓ + fullName NotBlank (no Size) → "X" passes on update

 ProductRequest  @ValidStock  ValidStockValidator.java:3  stockQuantity>=0 else violation at path stockQuantity
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/api/dto/OrderRequest.java` | `15-24` | Record request with field+container+type constraints | `@ValidOrderRequest:15`, `customerId @NotNull:17`, `items @NotEmpty @Valid:18`, nested `OrderItemRequest:21-23` `productId/quantity @NotNull` |
| `src/main/java/com/company/orderapi/api/dto/CustomerRequest.java` | `16-27` | Grouped validation record | `email:17-19` `{Create,Update}` `NotBlank+Email`, `fullName:21-23` `NotBlank{Create,Update}` + `Size(Create)` `2..100`, `phoneNumber:25` `Pattern {Create,Update}` |
| `src/main/java/com/company/orderapi/api/dto/ProductRequest.java` | `19` | Record with `@ValidStock` | `ValidStock` type-level stock≥0 similar to `ValidOrderRequest` |
| `src/main/java/com/company/orderapi/api/dto/Create.java` | `5-8` | Group marker `Create` | Empty interface `Create` for `@Validated(Create.class)` |
| `src/main/java/com/company/orderapi/api/dto/Update.java` | `4-7` | Group marker `Update` | Empty interface `Update` |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidOrderRequest.java` | — | Constraint annotation | `@Constraint(validatedBy=ValidOrderRequestValidator.class)`, `Target TYPE`, `Retention RUNTIME` |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidOrderRequestValidator.java` | — | Validator impl | Iterates `items`, `HashSet productId` → duplicate → `addPropertyNode("items")` violation |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidStock.java` | — | Constraint annotation stock | Same TYPE pattern for `ProductRequest` |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidStockValidator.java` | `3-13` | Validator stock≥0 | `ConstraintValidator<ValidStock,ProductRequest> isValid(ProductRequest,ctx)` → `stockQuantity>=0` |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `80` | Thin handler validates | `@Valid @RequestBody OrderRequest`, exception never reaches body on violation |
| `src/main/java/com/company/orderapi/api/rest/controller/CustomerController.java` | — | Group routing | `POST @Validated(Create.class)`, `PUT @Validated(Update.class)` CustomerRequest |
| `src/main/java/com/company/orderapi/api/rest/controller/ProductController.java` | `53,62` | Validates ProductRequest | `@Valid @RequestBody ProductRequest:53,62` |
| `src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java` | `67-73` | `400` mapping | `MethodArgumentNotValidException/ConstraintViolationException → 400 VALIDATION_ERROR` with `code/hint` |
| `pom.xml` | `98-102` | Dependency | `spring-boot-starter-validation` → Hibernate Validator 8 + `jakarta.validation-api` |

```java
// OrderRequest.java:15-24 — record + cross-field + nested
@ValidOrderRequest
public record OrderRequest(@NotNull Long customerId, @NotEmpty @Valid List<OrderItemRequest> items){
  public record OrderItemRequest(@NotNull Long productId, @NotNull Integer quantity){}
}
// CustomerRequest.java:16 — groups per component
public record CustomerRequest(
  @NotBlank(groups={Create.class,Update.class}) @Email(groups={Create.class,Update.class}) String email,
  @NotBlank(groups={Create.class,Update.class}) @Size(min=2,max=100,groups=Create.class) String fullName,
  @Pattern(regexp="^[0-9+ \\-]{0,20}$", groups={Create.class,Update.class}) String phoneNumber){}
// ValidStockValidator.java:3 — custom
public class ValidStockValidator implements ConstraintValidator<ValidStock,ProductRequest>{
  public boolean isValid(ProductRequest req, ConstraintValidatorContext ctx){ return req==null || req.stockQuantity()>=0; }
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Confirm constraints and groups present
grep -n "record\|@Valid\|@Not\|groups.*Create\|@ValidOrderRequest\|@ValidStock" \
  src/main/java/com/company/orderapi/api/dto/*.java src/main/java/com/company/orderapi/api/dto/validation/*.java | head -n 30

# Show controllers route groups
grep -rn "@Validated\|@Valid\|Create.class\|Update.class" src/main/java/com/company/orderapi/api --include="*.java" | head -n 20
# Expect POST @Validated(Create.class) CustomerRequest, PUT @Validated(Update.class), OrderController @Valid OrderRequest

# Live validation 400 paths (need app + api key)
# Missing required field → 400 VALIDATION_ERROR
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":null,"items":[]}' | jq '.code,.status,.title,.detail'

# Nested missing quantity → 400 per-element violation
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1}]}' | jq '.detail'

# Cross-field duplicate productId → 400 duplicate product 1
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1},{"productId":1,"quantity":1}]}' | jq '.detail'
# Expect duplicate product 1 (ValidOrderRequestValidator property path items)

# Group Create only: fullName too short 1 char fails on POST but passes on PUT shape (programmatic vs live)
curl -s -X POST http://localhost:8080/api/customers -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"email":"a@b.com","fullName":"X","phoneNumber":"123"}' | jq '.detail'  # Create Size violation

# Stock negative → 400 via @ValidStock
curl -s -X POST http://localhost:8080/api/v1/products -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"name":"W","price":9.99,"stockQuantity":-1,"description":"x"}' | jq '.detail'
```

```java
// Programmatic validation by group (unit-test pattern)
@Autowired Validator validator;
CustomerRequest req = new CustomerRequest("a@b.com","X","123");
Set<ConstraintViolation<CustomerRequest>> onCreate = validator.validate(req, Create.class); // 1: fullName Size(2..100)
Set<ConstraintViolation<CustomerRequest>> onUpdate = validator.validate(req, Update.class); // 0: Size only on Create
assert onCreate.size()==1 && onUpdate.isEmpty();

// Apply same rule to a new DTO
public record WidgetRequest(
  @NotBlank(groups={Create.class,Update.class}) String name,
  @Positive(groups=Create.class) Integer stock){}
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Declarative `@Not*` on record components | `OrderRequest.java:17-18`, `CustomerRequest.java:17-25` | Imperative `if (x==null) throw` in controllers | Reusable metadata with property path, AOP-enforced before body, structured `field+message` | Constraints only as expressive as the annotation set |
| `Create`/`Update` groups on one record | `CustomerRequest:17-25` + `Create.java:8`/`Update.java:7` `@Validated(Create/Update)` | Two DTO classes `CreateCustomerRequest` / `UpdateCustomerRequest` | One record vs class explosion `2×DTOs`; group routing at the handler keeps shape together | Single record carries cross-group complexity; Jackson maps both |
| `@Valid` on `List<OrderItemRequest>` `OrderRequest.java:18` | Traverses container → validates each item `21-23` | Manual loop over `items` in controller/service | Declarative, covers every item `productId/quantity @NotNull`, composes with `@NotEmpty` | Container validation requires HV support (present) |
| Type-level `@ValidOrderRequest:15` + `ValidStock` | Cross-field `Valid*Validator` per rule | Controller `if (hasDup)` + `throw IllegalArgumentException` | Centralized lifecycle, reusable, `addPropertyNode("items")` aligns error with field, tested via `validator.validate` | Extra annotation+validator file per rule |
| Nested `OrderItemRequest` inside `OrderRequest:21` | Cohesive ownership | Top-level `OrderItemRequest.java` | `OrderRequest.OrderItemRequest` usage clarifies scope | Deeper import |
| Component-level placement on records | Single annotation per component covers field+accessor+ctor | Explicit ctor-parameter annotations | Less duplication; `ExecutableValidator` + `getConstraintsForClass` both covered | Must remember record propagation semantics |
| Fail via `GlobalExceptionHandler:67` `400` | Unified `ProblemDetail` `VALIDATION_ERROR` | Per-controller `try/catch` | Consistent `code/hint/detail` `application/problem+json` across endpoints | Generic handler must classify correctly |

---

## 7. How to verify

```bash
# Annotations present and correctly grouped
grep -n "groups.*Create\|@Not\|@Email\|@Size\|@Pattern\|@ValidOrderRequest\|@ValidStock" \
  src/main/java/com/company/orderapi/api/dto/*.java src/main/java/com/company/orderapi/api/dto/validation/*.java | head -n 40

# Validator implementations exist
grep -rn "ConstraintValidator.*Request\|ValidStockValidator\|ValidOrderRequestValidator\|isValid" \
  src/main/java/com/company/orderapi/api/dto/validation --include="*.java" | head

# Controllers wire @Valid/@Validated
grep -rn "@Valid\|@Validated\|Create.class\|Update.class" src/main/java/com/company/orderapi/api --include="*.java"

# Handler classifies validation as 400 not 500
grep -n "MethodArgumentNotValidException\|ConstraintViolationException\|VALIDATION_ERROR" \
  src/main/java/com/company/orderapi/api/exception/GlobalExceptionHandler.java  # 67-73

# Live negative tests (require running app)
curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":null,"items":[]}' | jq -e '.status==400 and .code=="VALIDATION_ERROR"' && echo ok

curl -s -X POST http://localhost:8080/api/v1/orders -H "X-API-KEY: dev-api-key-orderapi" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1},{"productId":1,"quantity":1}]}' | jq -e '.status==400' && echo duplicate-caught

# Starter present
grep -A2 "spring-boot-starter-validation" pom.xml
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New endpoint with `record FooRequest(@NotBlank(groups={Create,Update}) String name, @NotNull(groups=Create.class) Integer stock, @Valid Nested nested)`. Wire `POST` as `@Validated(Create.class) @RequestBody FooRequest` and `PUT` as `@Validated(Update.class)`. Cross-field invariant? Add `@FooConstraint` on the record type (`TYPE`) + `ConstraintValidator<FooConstraint,FooRequest>` iterating the whole record and `addPropertyNode("items")`. Never re-validate in the service (`OrderService.java:91` already assumes validated `OrderLine`). Keep records immutable so post-validation mutation cannot invalidate.
- **Operate:** Validation `400 VALIDATION_ERROR` rate in `GlobalExceptionHandler:68` is an SLO: high `400` on a new client SDK indicates schema drift. `groups` mis-wiring shows as `Create` firing on `PUT` — check controller `@Validated` import is `org.springframework.validation.annotation.Validated`. `detail` strings are interpolated via `ValidationMessages.properties` — localise warnings there, not in controller code.
- **Interview:** "PR #23: `OrderRequest.java:15` `@ValidOrderRequest` + `17 @NotNull`, `18 @NotEmpty @Valid List<OrderItemRequest>` (`21-23 @NotNull`), `CustomerRequest.java:17-25` per-component `groups` (`Create.java:8`/`Update.java:7`). `@Valid` traverses container vs bare `NotEmpty` does not. `ValidStockValidator.java:3` for `ProductRequest`. `LocalValidatorFactoryBean` + `MethodValidationPostProcessor` throws `MethodArgumentNotValidException` → `GlobalExceptionHandler:67-73` `400 VALIDATION_ERROR` `ProblemDetail`. Next: PR #24 ProblemDetail+idempotency."

---

## 9. Interview lens — Q&A

**Q1: `@Valid` vs `@Validated` — when which on a controller?**
A: `@Valid` (`jakarta`) validates `Default` group (`OrderController.java:80` `OrderRequest`); `@Validated(Create.class)` (`Spring`) validates group `Create` (`CustomerRequest.java:17` `Create+Update` vs `Size(Create)` only) — wiring `POST Create` vs `PUT Update` with one record (§2.3).

**Q2: Why does `@Valid` on `List<OrderItemRequest> items` matter?**
A: `OrderRequest.java:18` `@NotEmpty @Valid` — `@Valid` traverses the `List` container and validates each `OrderItemRequest:21-23` `productId/quantity @NotNull`; without it only list non-emptiness is checked and a `null` quantity passes (§2.6).

**Q3: Where do component constraints live on a `record`?**
A: On the component (`CustomerRequest.java:17` `@NotBlank String email`) — compiler propagates to field+accessor+ctor-param, `Validator` reads either; need `-parameters` for the name to appear in `propertyPath` (§2.4).

**Q4: How is duplicate `productId` rejected declaratively?**
A: Type-level `OrderRequest.java:15` `@ValidOrderRequest` validator (`ValidOrderRequestValidator` `HashSet` over `items`, `addPropertyNode("items")` + `"duplicate product 42"`) — `400` field `items` (§2.7). Same pattern `ValidStock` `ValidStockValidator.java:3` for stock≥0.

**Q5: What status/body does the client see on a violation?**
A: `MethodArgumentNotValidException` / `ConstraintViolationException` → `GlobalExceptionHandler.java:67-73` `400 VALIDATION_ERROR` `application/problem+json` with `code/hint/detail` (detail is interpolated `message` and property path), not a stack trace (§2.2).

**Q6: Why not validate again inside `OrderService.placeOrder`?**
A: Structural validation belongs at the controller boundary (`OrderController:80`) over the `record`; by the time `OrderService.java:91` sees `OrderLine`, `customerId/items/quantity` are already guaranteed — re-checking duplicates logic and domain guards (`Order.cancel():151`) are the service's invariant, not `NotNull` (§2.10).

---

## 10. Honest limits & next step → PR #24

Bean Validation cannot express every business rule (stock availability, `customerId` must exist — checked `OrderService.java:84` `findById orElseThrow Unknown` → `404` not `400`). Type-level validators see only the DTO, not the DB, so "productId exists" still needs a DB lookup. JSON malformation (`"items":"bad"`) becomes `HttpMessageNotReadableException` outside the Bean Validation classify — mapped `400` via the generic `default` classify unless explicitly `ProblemDetail` for readability. Large `items` list validates `O(N)` per request; no streaming validation. Next PR makes every failure shape consistent: `GlobalExceptionHandler.java:25` RFC 9457 `ProblemDetail` (`status/title/detail + code/hint`) and the `Idempotency-Key` guard (`IdempotencyService.java:18`/`IdempotencyRecord.java:21`) that makes `POST` retries safe.

See [`24-exception-handling-and-idempotency.md`](./24-exception-handling-and-idempotency.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| Structural field required | `@NotNull/@NotBlank/@Email/@Pattern` on component | `CustomerRequest.java:17-25`, `OrderRequest.java:17-18` | Declarative, `propertyPath` in error |
| Per-op different rules | `groups` `Create/Update` + `@Validated(group)` | `Create.java:8`/`Update.java:7`, `CustomerRequest.java:21` `Size(Create)` | One record, strict create vs lax update |
| Validate each list element | `@Valid` on container `List` | `OrderRequest.java:18` | Traverses to `OrderItemRequest:21` |
| Cross-field duplicate | Type `@ValidOrderRequest` + validator | `OrderRequest.java:15` + `validation/ValidOrderRequest*` | Sees whole `items`, adds `items` path error |
| Custom stock invariant | `@ValidStock` + validator | `ValidStock.java` + `ValidStockValidator.java:3` | Reusable, test-assertable |
| Consistent 400 shape | `@Valid/@Validated` → `MethodArgumentNotValidException` → handler | `GlobalExceptionHandler.java:67-73` `400 VALIDATION_ERROR` | `ProblemDetail code/hint` uniform |
