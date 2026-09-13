# 21. DTOs with Java Records (PR #21)

> PR #21 — DTOs with Java Records: `record` request/response DTOs, cross-field validation, validation groups `Create`/`Update`, and `OrderMapper`. Stack: Java 21, Spring Boot 3.x, Hibernate Validator (Bean Validation), Jackson Records, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 21 | DTOs with Java Records |`.

---

## 1. Purpose — what shipped

PR #21 replaces mutable `class` DTOs with **immutable `record` DTOs** for the API boundary and adds operation-scoped validation. Shipped records: `OrderRequest` (`api/dto/OrderRequest.java:16`) with nested `OrderItemRequest`, `CustomerRequest` (`api/dto/CustomerRequest.java:16`), `ProductRequest` (`api/dto/ProductRequest.java:19`), responses `OrderResponse` (`api/dto/OrderResponse.java:16` with nested `OrderItemResponse`), `CustomerResponse`, `ProductResponse`, validation marker interfaces `Create` (`api/dto/Create.java:5`) / `Update` (`api/dto/Update.java:4`), class-level validators `@ValidOrderRequest`/`ValidStock` (`api/dto/validation/ValidOrderRequest.java`, `ValidStock.java` + `ValidStockValidator.java:3`), and mapper `OrderMapper` (`api/dto/OrderMapper.java`) that translates entities (`Order.java:42`) to response records. Controllers bind `record` payloads via Jackson 2.12+ record support and validate with `@Valid` / `@Validated(Create.class)` / `@Validated(Update.class)` per endpoint.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Request/response DTOs were mutable `class` with setters, no-arg constructors for Jackson, and ad-hoc validation (`if (email==null) throw ...`). Field constraints like `@NotBlank` ran on every endpoint regardless of operation — `PUT /customers/42` requiring `phoneNumber` because the creation DTO did, or `POST` allowing missing `email` because the update DTO permitted it for patch semantics. Cross-field rules (no duplicate `productId` in `OrderRequest.items`) had no declarative place. Mutability let handlers modify the inbound DTO after validation, invalidating the guarantee.

**After:** `record CustomerRequest(@NotBlank(groups={Create,Update}) @Email String email, @NotBlank @Size(min=2,max=100, groups=Create) String fullName, @Pattern String phoneNumber)` (`CustomerRequest.java:16-26`) enforces: `email` always required, `fullName` length only on `Create`, `phone` pattern whenever present — enforced by `@Validated(Create.class)` on `POST` and `@Validated(Update.class)` on `PUT` in the controller. `OrderRequest.java:15-24` `@ValidOrderRequest` validates `items` has no duplicate `productId`. Records are immutable after Jackson construction and validation — the guarantee holds through the request lifecycle. Responses are `record OrderResponse(id, orderNumber, orderDate, status, totalAmount, @MaskedPii customerEmail, items)` (`OrderResponse.java:16-33`) — immutable, serializable, mappable.

### Theory — records, validation, and mapping from first principles (100+ lines)

#### 2.1 Why records for DTOs — immutability, identity, and canonical constructor

```java
// OrderRequest.java:16
@ValidOrderRequest
public record OrderRequest(
        @NotNull Long customerId,
        @NotEmpty @Valid List<OrderItemRequest> items) {
    public record OrderItemRequest(@NotNull Long productId, @NotNull Integer quantity) {}
}

// CustomerRequest.java:16
public record CustomerRequest(
        @NotBlank(groups={Create.class, Update.class}) @Email(groups={Create.class, Update.class}) String email,
        @NotBlank(groups={Create.class, Update.class}) @Size(min=2, max=100, groups=Create.class) String fullName,
        @Pattern(regexp="^[0-9+ \\-]{0,20}$", groups={Create.class, Update.class}) String phoneNumber) {}

// OrderResponse.java:16
public record OrderResponse(
        Long id, String orderNumber, LocalDateTime orderDate, String status, BigDecimal totalAmount,
        @MaskedPii(PiiType.ORDER) String customerEmail, List<OrderItemResponse> items) {
    public record OrderItemResponse(Long id, Long productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal totalPrice) {}
}
```

A Java `record` is `final` with `private final` components, a canonical constructor `OrderRequest(Long, List)`, `equals/hashCode/toString` by component value, component accessors `customerId()`/`items()` (not `get...`), and no setters. For DTOs:

- **No mutation post-validation:** `@Valid` runs on the constructed record (`ExecutableValidator` validates components); no later `setEmail` invalidates it. Class DTOs allowed `dto.setEmail(null)` after validation before service call — records prevent it.
- **Identity is value identity:** two `OrderRequest` with same `customerId, items` are `equal` — safe as `Map` keys, test assertions.
- **Two slots, not N fields + boilerplate:** a record declares the schema; class needed constructor + `equals/hashCode` + `toString` (unless Lombok). For 4-record API surface (Order/Customer/Product + nested), saves hundreds of lines.
- **Jackson 2.12+ (`jackson-databind`) deserializes records via canonical constructor:** `@JsonProperty` mapping by parameter name requires the compiler parameter-names (`javac -parameters`, enabled by Spring Boot `maven-compiler-plugin` default). No no-arg constructor / setters needed. Serialization writes component accessors as JSON keys.

#### 2.2 Component constraints — where annotations live on a record

On a record, placement matters:

```java
public record CustomerRequest(
        @NotBlank(groups={Create.class, Update.class}) String email, // compact: annotates component + accessor + constructor param
        // ...
)
```

A single annotation on the component is propagated to the `field`, the `accessor` method (`email()`), and the `constructor parameter` by the compiler. Bean Validation's `ExecutableValidator` reads constraints on the constructor parameter (during `validateParameters`) and on the accessor (during `validateProperty`). Either placement validates on `@Valid` record instantiation. Using component-level placement covers both.

#### 2.3 Validation groups — `Create` vs `Update` semantics

Marker interfaces are empty:

```java
// Create.java:8
public interface Create {}
// Update.java:7
public interface Update {}
```

Controller selects the group per HTTP method:

```java
@PostMapping public ResponseEntity<OrderResponse> create(
        @Validated(Create.class) @RequestBody CustomerRequest req) { // validates groups Create+Update as declared
    ... orderService.placeOrder(req.customerId(), req.items()...)
}
@PutMapping("/{id}") public ResponseEntity<OrderResponse> update(
        @PathVariable Long id,
        @Validated(Update.class) @RequestBody CustomerRequest req) { // only Update (+ shared) constraints run
    ...
}
```

Group rules (`CustomerRequest.java:16-26`):

- `@NotBlank(groups={Create.class, Update.class})` on `email` — both operations need a non-blank email.
- `@Size(min=2, max=100, groups=Create.class)` on `fullName` — creating a customer enforces name length; updating allows patch semantics where `fullName` may be governed elsewhere, or omitted if the DTO evolves to `Optional<String>`.
- `@Pattern(regexp="^[0-9+ \\-]{0,20}$", groups={Create.class, Update.class})` on `phoneNumber` — optional column (`phoneNumber` nullable) but if supplied must be phone-shaped, on both operations.

Without groups, `PUT` reuse of the creation DTO would either be too strict (rejecting valid partial updates) or too lax (accepting invalid creates).

#### 2.4 Class-level / cross-field validation — `@ValidOrderRequest`

Some rules involve relation across components (no duplicates, quantity > 0 overall):

```java
// OrderRequest.java:15
@ValidOrderRequest
public record OrderRequest(@NotNull Long customerId, @NotEmpty @Valid List<OrderItemRequest> items) {}

// api/dto/validation/ValidOrderRequest.java — @Constraint(validatedBy = ValidOrderRequestValidator.class)
// Validator: for (OrderItemRequest a,b in request.items()) if (a.productId()==b.productId()) addViolation("duplicate product "+a.productId())
```

The annotation on the record type (not a component) tells Bean Validation to call the type validator after component constraints pass. `ValidOrderRequestValidator` iterates `items` (already `@NotEmpty` + `@Valid` on each `OrderItemRequest` so null/empty items never reach it without a prior violation), checks duplicate `productId` and honours mapping to property path `items` for a field-aligned error message (`field: items, message: duplicate product 42`). Similarly `ValidStock` (`api/dto/validation/ValidStock.java`) on `ProductRequest` validates `stockQuantity >= 0`.

#### 2.5 Nested validation — `@Valid` on `List<OrderItemRequest>`

```java
// OrderRequest.java:18
@NotEmpty @Valid List<OrderItemRequest> items
// OrderItemRequest:  public record OrderItemRequest(@NotNull Long productId, @NotNull Integer quantity) {}
```

`@Valid` triggers recursive validation: Bean Validation traverses the container and validates each `OrderItemRequest.productId`/`quantity` against `@NotNull`. Without `@Valid`, only `@NotEmpty` on the list would run and an `OrderItemRequest(null, null)` would pass.

#### 2.6 `OrderMapper` — entity to record mapping

```java
// OrderMapper.java — conceptual
public final class OrderMapper {
    public static OrderResponse toOrderResponse(Order order) { // Order.java:42
        return new OrderResponse(
            order.getId(), order.getOrderNumber(), order.getOrderDate(),
            order.getStatus().name(), order.getTotalAmount(),
            order.getCustomer().getEmail(), // masked via @MaskedPii on OrderResponse:22
            order.getItems().stream().map(item -> new OrderItemResponse(
                item.getId(), item.getProduct().getId(), item.getProduct().getName(),
                item.getQuantity(), item.getUnitPrice(), item.getTotalPrice()
            )).toList()
        );
    }
    public static ProductResponse toProductResponse(Product p) { return new ProductResponse(p.getId(), p.getName(), ...); }
}
```

Why explicit mapper not MapStruct? For 4 DTOs the static method is trivial, compile-safe (parameter mismatch detected), and record construction is one expression vs annotation processor. Response records snapshot mutable entity graph at mapping time — `OrderItem.unitPrice` copied, not referenced to live `Product.price`, so later `Product.price` change does not alter the past `OrderResponse`.

PII masking (`OrderResponse.java:22` `@MaskedPii(PiiType.EMAIL)` via `security/pii/PiiType.java` and Jackson serializer) replaces `customerEmail` for callers without `SCOPE_pii_read` — record's component accessor is the serialization hook, so masking serializer on the component governs output.

#### 2.7 Jackson + records — module config

Spring Boot's `JacksonAutoConfiguration` registers `ParameterNamesModule` (for record constructor dispatch) and `JodaModule` equivalent for `LocalDateTime` (ISO-8601). No manual `ObjectMapper` customization for records; `objectMapper.writeValueAsString(event)` (`EventStoreService.java:51`) serializes `DomainEvent` records the same way. `OrderRequest` JSON example:

```json

Missing `quantity` → Bean Validation `quantity must not be null` (400). Duplicate `productId:42` twice → `@ValidOrderRequest` violation (400 `duplicate product 42`).

#### 2.8 `@Valid` vs `@Validated` — Spring difference

- `javax.validation.Valid` (handler) → validates with `Default` group.
- `org.springframework.validation.annotation.Validated(Group.class)` → validates with explicit group `Create`/`Update`.

Controllers import `org.springframework.validation.annotation.Validated` for group routing; services (if validating programmatically) use `validator.validate(dto, Create.class)`. Mixing both on a handler param `(@Validated(Create.class) @Valid Record)` needs only `@Validated` — `@Valid` alone would run `Default` group regardless of declaration.


> "PR #21: `record` DTOs (`OrderRequest.java:16` `@ValidOrderRequest` + `OrderItemRequest`, `CustomerRequest.java:16` with `@NotBlank/@Size/@Pattern groups`, `OrderResponse.java:16` nested `OrderItemResponse` + `@MaskedPii:22`). Immutable canonical constructor + accessor vs class setters; Jackson `ParameterNamesModule` constructs via canonical ctor, no no-arg. `@Validated(Create.class)` vs `@Validated(Update.class)` (`Create.java:8`/`Update.java:7`) route `CustomerRequest fullName Size min2/100` only on `Create`. `@Valid` on `List<OrderItemRequest> items:18` recurses; `@ValidOrderRequest:15` checks duplicate `productId` cross-field. `OrderMapper` entity→record snapshots `OrderItem.unitPrice`; service `placeOrder` receives validated immutable `OrderRequest` and never mutates it."

---

## 3. Solution — ASCII

```
Request (JSON)
  POST /api/orders  { "customerId":1, "items":[{ "productId":42,"quantity":2 }] }
         │
         ▼  Jackson → canonical constructor  (ParameterNamesModule, -parameters)
    record OrderRequest  @ValidOrderRequest  api/dto/OrderRequest.java:15-24
      customerId @NotNull
      items @NotEmpty @Valid List<OrderItemRequest(@NotNull productId, @NotNull quantity)>
      class-level validator: items must have no duplicate productId
         │
         ▼  Bean Validation  @Validated(Create.class) on POST handler  (Default vs Create vs Update)
    ConstraintViolation if any → GlobalExceptionHandler → 400 ProblemDetails
         │
         ▼  Controller thin  OrderController.java:22
    orderService.placeOrder(req.customerId(), req.items()...)  // records immutable, never mutated downstream
         │
         ▼  Service → domain → mapper
    OrderMapper.toOrderResponse(Order)  api/dto/OrderMapper.java
      Order.java:42 entity graph (customer, items, payment) ──snapshot──►
        record OrderResponse  api/dto/OrderResponse.java:16
          id, orderNumber, orderDate, status, totalAmount,
          @MaskedPii(PiiType.EMAIL) customerEmail:22  ← serializer redacts unless SCOPE_pii_read
          List<OrderItemResponse(id, productId, productName, quantity, unitPrice, totalPrice):26>
         │
         ▼  Jackson  accessor → JSON  (LocalDateTime ISO-8601)
    Response (JSON)  { "id":42, "orderNumber":"ORD-...", "status":"PLACED", "customerEmail":"a***@...", "items":[...] }

Customer groups (CustomerRequest.java:16):
  POST /customers  @Validated(Create.class)  → @NotBlank(email) ✓ + @Size(fullName 2..100) ✓ + @Pattern(phone) ✓
  PUT  /customers  @Validated(Update.class)  → @NotBlank(email) ✓ + @Size(fullName) ✗ (Create only) → allows name patch flexibility

Nested:
  @Valid List<OrderItemRequest>  → each item @NotNull productId/quantity validated recursively
  @ValidOrderRequest  record-level  → duplicate productId violation at path "items"

Bridge to projections (PR #17):
  record CustomerOrderTotal(String email, BigDecimal totalSpent) would work as JPQL "select new ... record" — same as class CustomerOrderTotal.java:15
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/api/dto/OrderRequest.java` | `15-24` | Record request + cross-field rule | `@ValidOrderRequest:15` on record type, `customerId @NotNull`, `items @NotEmpty @Valid`, nested `OrderItemRequest(@NotNull productId, @NotNull quantity):21` |
| `src/main/java/com/company/orderapi/api/dto/CustomerRequest.java` | `16-26` | Record with groups | `email:17-18` `Create+Update` `NotBlank+Email`, `fullName:21-22` `Create` `Size 2..100`, `phoneNumber:25` `Pattern` |
| `src/main/java/com/company/orderapi/api/dto/ProductRequest.java` | `19` | Record with `@ValidStock` | `ValidStock` class-level for `stockQuantity >=0`, groups as in `CustomerRequest` pattern |
| `src/main/java/com/company/orderapi/api/dto/Create.java` | `5-8` | Validation group marker `Create` | Empty interface used by `@Validated(Create.class)` |
| `src/main/java/com/company/orderapi/api/dto/Update.java` | `4-7` | Validation group marker `Update` | Empty interface used by `@Validated(Update.class)` |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidOrderRequest.java` | — | Constraint annotation `ValidOrderRequest` | `@Constraint(validatedBy = ValidOrderRequestValidator.class)`, `Target TYPE`, `Retention RUNTIME` |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidStock.java` | — | Constraint `ValidStock` on `ProductRequest` | Similar TYPE-level for `stockQuantity` invariant |
| `src/main/java/com/company/orderapi/api/dto/validation/ValidStockValidator.java` | `3-13` | Validator impl | `implements ConstraintValidator<ValidStock, ProductRequest>`, `isValid(ProductRequest, ctx)` |
| `src/main/java/com/company/orderapi/api/dto/OrderResponse.java` | `16-33` | Record response + nested + PII | `OrderResponse` 7 components `id/orderNumber/date/status/totalAmount/@MaskedPii customerEmail/items`, nested `OrderItemResponse:26` `id/productId/productName/quantity/unitPrice/totalPrice` |
| `src/main/java/com/company/orderapi/api/dto/CustomerResponse.java` | — | Record response for customer | Parallel immutable view |
| `src/main/java/com/company/orderapi/api/dto/ProductResponse.java` | — | Record response for product | `ProductMapper` sibling |
| `src/main/java/com/company/orderapi/api/dto/OrderMapper.java` | — | Entity→record mapper | `toOrderResponse(Order)`, `toProductResponse(Product)` — snapshots `OrderItem.unitPrice` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `42,57-64,151` | Entity source of mapping | `@Table orders`, `totalAmount:57`, `orderNumber:64`, state-machine guards |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `22` | Thin controller | `@RequestBody @Validated(Create.class) OrderRequest`, delegates to `OrderService` |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Context loads with record DTO beans + validation config |

```java
// OrderRequest.java:15-24 — record + cross-field + nested validation
@ValidOrderRequest
public record OrderRequest(
        @NotNull Long customerId,
        @NotEmpty @Valid List<OrderItemRequest> items) {
    public record OrderItemRequest(@NotNull Long productId, @NotNull Integer quantity) {}
}
// CustomerRequest.java:16 — groups per component
public record CustomerRequest(
        @NotBlank(groups={Create.class, Update.class}) @Email(groups={Create.class, Update.class}) String email,
        @NotBlank(groups={Create.class, Update.class}) @Size(min=2,max=100,groups=Create.class) String fullName,
        @Pattern(regexp="^[0-9+ \\-]{0,20}$", groups={Create.class, Update.class}) String phoneNumber) {}
// Update.java:7 — group marker
public interface Update {}

// OrderResponse.java:16 — immutable view with masking
public record OrderResponse(
        Long id, String orderNumber, LocalDateTime orderDate, String status, BigDecimal totalAmount,
        @MaskedPii(PiiType.EMAIL) String customerEmail, List<OrderItemResponse> items) {
    public record OrderItemResponse(Long id, Long productId, String productName, int quantity, BigDecimal unitPrice, BigDecimal totalPrice) {}
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run DTO/validation tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test

# Verify record DTOs exist
grep -rn "record.*Request\|record.*Response\|interface Create\|interface Update" src/main/java/com/company/orderapi/api/dto --include="*.java" | head -n 20

# Show cross-field and stock validators
grep -rn "@ValidOrderRequest\|ValidStock\|ValidOrderRequestValidator" src/main/java --include="*.java" | head

# Show mapper
grep -n "toOrderResponse\|toProductResponse" src/main/java/com/company/orderapi/api/dto/OrderMapper.java | head

# Verify response PII masking annotation
grep -n "MaskedPii\|PiiType" src/main/java/com/company/orderapi/api/dto/OrderResponse.java

# Validate groups wiring in controllers
grep -rn "@Validated\|@Valid" src/main/java/com/company/orderapi/api --include="*.java" | head -n 20
# Expect: @Validated(Create.class) on POST, @Validated(Update.class) on PUT

# Smoke POST with valid payload
curl -s -X POST http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}]}' | jq .orderNumber

# Expect 400: duplicate productId (cross-field @ValidOrderRequest violation)
curl -s -X POST http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1},{"productId":1,"quantity":1}]}' | jq .detail
# Expect: duplicate product 1

# Expect 400: @NotNull quantity missing in nested record
curl -s -X POST http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1}]}' | jq .detail

# Expect 400 via group: CustomerRequest fullName too short on Create
curl -s -X POST http://localhost:8080/api/customers -H "X-API-KEY: dev-api-key" -H "Content-Type: application/json" \
  -d '{"email":"a@b.com","fullName":"X","phoneNumber":"123"}' | jq .detail
# Expect: fullName size 2..100 (Create group)

# Response masking: email masked unless SCOPE_pii_read
curl -s http://localhost:8080/api/orders/1 -H "X-API-KEY: dev-api-key" | jq .customerEmail
# Expect: t***@example.com or similar masked value
```

```java
// Programmatic validation by group
@Autowired Validator validator;
CustomerRequest req = new CustomerRequest("a@b.com", "X", "123"); // fullName too short
Set<ConstraintViolation<CustomerRequest>> vCreate = validator.validate(req, Create.class); // one violation
Set<ConstraintViolation<CustomerRequest>> vUpdate = validator.validate(req, Update.class); // zero (@Size only on Create)

// Mapping usage
Order order = orderService.placeOrder(req.customerId(), req.items().stream().map(i -> new OrderService.OrderLine(i.productId(), i.quantity())).toList());
OrderResponse resp = OrderMapper.toOrderResponse(order); // snapshot → immutable record response
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| `record` for request/response | `OrderRequest.java:16`, `CustomerRequest.java:16`, `OrderResponse.java:16` | Mutable `class` + Lombok | Immutable post-validation, canonical constructor, value identity, no setters to break invariant; less boilerplate | No subclassing; Hibernate cannot use record as `@Entity`; add-only evolution (remove component is breaking) |
| Grouped constraints `Create`/`Update` | `CustomerRequest.java:17-22` `groups`, `Create.java:8`/`Update.java:7` | Separate `CreateCustomerRequest` vs `UpdateCustomerRequest` classes | One record with groups vs class explosion (`Create*`, `Update*` × 3 DTOs); Spring `@Validated(group)` routes | Single DTO declaration carries group complexity; Jackson must map both shapes |
| `@Valid` on `List<OrderItemRequest>` | `OrderRequest.java:18` `@NotEmpty @Valid` | Manual loop validation in controller | Declarative recursion covers every item `productId`/`quantity` automatically; error path by property | Container `List` validation requires Hibernate Validator container support (present) |
| Class-level `@ValidOrderRequest` | `OrderRequest.java:15` + validator | Controller `if (hasDup) throw` | Cross-field rule centralized in Bean Validation lifecycle, reusable via single annotation, integrated with `GlobalExceptionHandler` 400 handling | Validator class per constraint (extra file) |
| Nested record `OrderItemRequest` inside `OrderRequest` | `OrderRequest.java:21` static nested record | Separate top-level `OrderItemRequest` file | Cohesion — request and line are one shape; usage site `OrderRequest.OrderItemRequest` clarifies ownership | Deeper import path |
| Static `OrderMapper` vs MapStruct | `OrderMapper` static method | `@Mapper(componentModel="spring")` | Hand-written one-liner per DTO, compile-safe, no annotation processor, trivial for record construction | Manual field copy vs codegen; record component rename requires manual update |
| `@MaskedPii` on `OrderResponse.customerEmail:22` | Jackson serializer masking per caller scope | Separate `AdminOrderResponse` | Single record with serializer conditionally masks `EMAIL` → no DTO explosion for permissioned view | Custom Jackson serializer wiring (PII module) |

---

## 7. How to verify

```bash
# Records present and group annotations on correct components
grep -n "record\|groups.*Create\|@ValidOrderRequest" src/main/java/com/company/orderapi/api/dto/*.java src/main/java/com/company/orderapi/api/dto/validation/*.java | head -n 30

# Validators present
grep -rn "ConstraintValidator.*Request\|isValid" src/main/java/com/company/orderapi/api/dto/validation --include="*.java" | head

# Mapper snapshots entities → records
grep -n "toOrderResponse\|toProductResponse\|record.*Response" src/main/java/com/company/orderapi/api/dto/OrderMapper.java src/main/java/com/company/orderapi/api/dto/OrderResponse.java

# Controllers wire groups
grep -rn "@Validated\|Create.class\|Update.class" src/main/java/com/company/orderapi/api --include="*.java"

# Jackson record support auto-registered (Spring Boot)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep -i "ParameterNamesModule\|records" | head

# Tests: validation violations produce 400 vs domain violations 404/409
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test 2>&1 | grep -E "400|422|duplicate|NotBlank|NotNull" | head

# Reflection: records are final, components are accessors
javap -p src/main/java/com/company/orderapi/api/dto/OrderRequest.java 2>/dev/null | grep -E "record|class"
# or: grep "record OrderRequest" src/main/java/com/company/orderapi/api/dto/OrderRequest.java
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New endpoint → `record FooRequest(@NotBlank(groups={Create,Update}) String field, @Valid Nested nested)`. `POST` → `@Validated(Create.class) @RequestBody FooRequest`, `PUT` → `@Validated(Update.class)`. Add cross-field invariant → new `@FooConstraint` on record type + `ConstraintValidator<FooConstraint, FooRequest>`. Map with `OrderMapper`-style `FooMapper.toFooResponse(entity)` constructing the record via canonical ctor; never reuse entity getter `Payment` lazily after response mapping. Records are immutable — never `req.customerId = 2` after validation.
- **Operate:** Monitor `GlobalExceptionHandler` 400 rate — validation failures vs accepted requests. Group routing mis-wiring shows as `Create` constraints firing on `PUT` or missing on `POST` — check `@Validated` import (`org.springframework.validation.annotation.Validated`, not `jakarta.validation.Valid`). PII masking (`@MaskedPii:22`) verified by `curl` as non-privileged caller returns `t***@…` — regression if Jackson serializer module not registered.
- **Interview:** "PR #21: `record OrderRequest.java:16` (`OrderItemRequest:21` `@NotNull`, `items @NotEmpty @Valid:18`, `@ValidOrderRequest:15` duplicate productId), `CustomerRequest.java:16` per-component `groups` (`Create.java:8` `email {Create,Update}`, `fullName Create Size 2..100`, `phone Pattern`), `Update.java:7` marker. Controller `POST` `@Validated(Create.class)` vs `PUT` `@Validated(Update.class)`. `OrderResponse.java:16` record + nested `OrderItemResponse:26` + `@MaskedPii:22` on `customerEmail`. Immutable canonical ctor, Jackson `ParameterNamesModule`, no setters — invariant holds. Mapper `OrderMapper` snapshots (`toOrderResponse`). Next: PR #22 REST controllers route these records over `OrderController:22`."

---

## 9. Interview lens — Q&A

**Q1: Why records for DTOs over classes?**
A: Immutable after Jackson canonical construction (`OrderRequest.java:16`), canonical ctor + value identity + accessors, no setters to violate post-validation guarantee; Jackson 2.12 `ParameterNamesModule` (§2.1).

**Q2: Where do component constraints live on a record?**
A: On the component (`CustomerRequest.java:17` `@NotBlank(groups=...) String email`) — compiler propagates to field+accessor+constructor param; Bean Validation reads either (§2.2).

**Q3: How do `Create` vs `Update` groups route validation?**
A: Marker interfaces `Create.java:8`/`Update.java:7`; controller `POST` `@Validated(Create.class)` vs `PUT` `@Validated(Update.class)`; `CustomerRequest:21` `fullName @Size(2..100, Create)` only fires on create (§2.3). `@Valid` alone runs `Default` group.

**Q4: What is `@Valid` on `List<OrderItemRequest> items` for?**
A: `OrderRequest.java:18` recurses into each `OrderItemRequest` (`productId`/`quantity @NotNull:21`); without `@Valid` only `@NotEmpty` runs and null nested fields pass (§2.5).

**Q5: How is duplicate `productId` rejected?**
A: Record-level `OrderRequest.java:15` `@ValidOrderRequest` type validator iterates `items` and adds violation on `items` path if duplicate — cross-field rule beyond single component (§2.4).

**Q6: How does `OrderMapper` relate to record DTOs?**
A: Static `toOrderResponse(Order:42)` builds `OrderResponse:16` via canonical ctor snapshotting (`unitPrice`, masked `customerEmail:22`); PII serializer on component governs output, not a separate `Admin` DTO (§2.6).

---

## 10. Honest limits & next step → PR #22

Records are final — no builder pattern for partial construction (handy for patch). JSON field rename requires `@JsonProperty` on component (parameter names bound). Large nested `items` list validated per-element scales `O(N)`. PR #21 gives the payload shapes; PR #22 gives the REST surface that carries them — `OrderController.java:22` routing, HTTP verbs, status codes, and `GlobalExceptionHandler` mapping of validation `400` / domain `409` to RFC 9457 `ProblemDetail`.

See [`22-rest-controllers.md`](./22-rest-controllers.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| API boundary immutable DTO | `record OrderRequest/CustomerRequest` | `OrderRequest.java:16` / `CustomerRequest.java:16` | No mutation post-validation |
| Shared field, different per-op rules | Groups `Create`/`Update` | `CustomerRequest.java:17-22` + `Create.java:8`/`Update.java:7` | One record, strict create vs lax update |
| Nested item validation | `@Valid` + nested `record OrderItemRequest` | `OrderRequest.java:18,21` | Each `productId/quantity` validated |
| Cross-field (duplicate) | Class-level `@ValidOrderRequest` | `OrderRequest.java:15` + `validation/*` | Iterator over `items` |
| Entity → API mapping | Static `OrderMapper.toOrderResponse` | `OrderMapper.java` + `OrderResponse.java:16` | Canonical record ctor snapshot |
