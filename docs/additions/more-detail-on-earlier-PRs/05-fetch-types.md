# 05. Fetch Types (PR #5)

> PR #5 — Fetch Types. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + Hibernate 6.6. See `README.md:1772` roadmap `| 5 | Fetch Types |`.

---

## 1. Purpose — what shipped

PR #5 delivers **Fetch Types** as a first-class, tested, documented building block. Every association in the codebase is audited and explicitly set to `FetchType.LAZY` (or left LAZY as JPA default for collections): `Order.java:46` `customer` (`ManyToOne LAZY`), `Order.java:67/71` addresses, `Order.java:82` `items` (OneToMany LAZY), `Order.java:92` `payment` (OneToOne LAZY), `Customer.java:58` `addresses` + `Customer.java:67` `orders`, `Address.java:28` `customer`, `OrderItem.java:28/32`, `Product.java:54` `categories`, `Category.java:30` `products`, `Payment.java:36` `order`. The PR also documents why `FetchType.EAGER` on collections is a production anti-pattern and introduces `open-in-view: false` (`application.yml:42`).

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Some `@ManyToOne` default to `EAGER` in JPA spec (Hibernate defaults `ManyToOne`/`OneToOne` to EAGER if not specified). Missing explicit `fetch=LAZY` means `orderRepository.findById(id)` silently joins `customers`, `addresses`, `products` — huge row duplication, unpredictable SQL, and `LazyInitializationException` confusion later.

**After:** Every association declares `fetch = FetchType.LAZY` explicitly (`Order.java:46`, `Customer.java:58`, `Product.java:54`, etc.). `findById` does one `SELECT` on the root table; associated data loads only on access inside `@Transactional`. `application.yml:42` `open-in-view: false` fails fast if view tries to trigger lazy loads.

### Theory — LAZY vs EAGER from first principles

#### 2.1 What fetch type controls (Hibernate Session internals)

- **Fetch type is a *loading strategy* hint** that tells Hibernate *when* to populate an association field in the `PersistenceContext` (first-level cache, scoped to one `Session`/`Transaction`).

```
  PersistenceContext (per @Transactional)
  ┌──────────────────────────────────────────────────────────────────┐
  │  Order#10  { customer → proxy(Customer#1, id=1, not loaded) }   │  LAZY: proxy holds id only
  │  Order#10  { customer → Customer#1 {email, fullName, ...} }    │  EAGER: real entity loaded
  │  Order#10  { items → PersistentBag (uninitialized, 0 SQL yet) } │  LAZY collection
  │  Order#10  { items → PersistentBag [item1, item2, loaded] }    │  EAGER / initialized
  └──────────────────────────────────────────────────────────────────┘
  Accessing lazy field inside TX  → triggers SELECT via proxy/Bag
  Accessing lazy field outside TX → LazyInitializationException  (session closed)
```

- **Session lifecycle:** `EntityManager` (Hibernate `Session`) is opened at `@Transactional` entry, holds the `PersistenceContext` map, and closes at commit/rollback. Lazy proxies need that Session to fire their `SELECT`. `open-in-view: false` (`application.yml:42`) *closes* the Session at service return, so the controller/view cannot accidentally trigger lazy loads (fail-fast).

#### 2.2 LAZY mechanics — proxies and PersistentCollections

| Association | LAZY implementation | What is stored initially | When does SQL fire? |
|---|---|---|---|
| `@ManyToOne(LAZY)` / `@OneToOne(LAZY)` | ByteBuddy proxy subclass | FK id only (e.g., `customer_id=1`), proxy object with `id` field set, other fields null | First method call that needs data (`getEmail()`, not `getId()`) — Hibernate intercepts via proxy |
| `@OneToMany(LAZY)` / `@ManyToMany(LAZY)` | `PersistentBag` / `PersistentSet` wrapper | Empty collection wrapper + owner id, not loaded | First access that needs elements (`size()`, `iterator()`, `get(0)`) — Hibernate does `SELECT ... WHERE fk=?` |

```java
// Order.java:46 — LAZY many-to-one: proxy until touched
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "customer_id", nullable = false)
private Customer customer;
// In memory after findById: order.customer is a Customer proxy with id=1; email is null
order.getCustomer().getId();    // NO SQL — id already known from orders.customer_id
order.getCustomer().getEmail(); // TRIGGERS: SELECT * FROM customers WHERE id=1
```

```java
// Customer.java:58 — LAZY collection: PersistentBag until touched
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
private List<Address> addresses = new ArrayList<>();
// In memory after findById: addresses is PersistentBag[uninitialized]
customer.getAddresses().size(); // TRIGGERS: SELECT * FROM addresses WHERE customer_id=?
```

- **ByteBuddy proxy details:** Hibernate generates at runtime a subclass like `Customer$$HibernateProxy$abc` that overrides getters to call `LazyInitializer`. `Hibernate.isInitialized(entity)` and `Hibernate.unproxy()` let you test/unwrap. `BaseEntity.java:107` `@Transient postLoadFired` + `BaseEntity.java:109` `@PostLoad` still fire even for proxies when they initialize.

#### 2.3 EAGER mechanics — why it is usually wrong

```java
// Hypothetical EAGER (NOT in this repo):
@ManyToOne(fetch = FetchType.EAGER) private Customer customer;
@OneToMany(fetch = FetchType.EAGER) private List<Order> orders;
// findById(1) → SELECT c + JOIN FETCH orders + JOIN FETCH addresses in ONE query
// findAll() with 50 customers → 50 * (orders + addresses + payments) cartesian explosion
```

| EAGER behavior | Cost | When it hurts |
|---|---|---|
| `EAGER @ManyToOne` | One `JOIN` or extra `SELECT` per `findById` | Always loads even when caller only needs `order.getTotalAmount()` |
| `EAGER @OneToMany` collection | `JOIN` multiplies rows (cartesian product) | `findAll()` with 50 customers × avg 3 addresses × avg 5 orders = massive result set + memory |
| Multiple `EAGER` collections on same entity | Hibernate cannot fetch two bags eagerly in one query → `MultipleBagFetchException` | Model with `Customer` EAGER `addresses` + EAGER `orders` fails at startup/query time |

- **JPA spec defaults** (the trap): `@ManyToOne` and `@OneToOne` default to `EAGER` if you omit `fetch`. `@OneToMany` / `@ManyToMany` default to `LAZY`. So forgetting `fetch=LAZY` on `Order.java:46` or `Address.java:28` silently makes them EAGER — that is why this PR makes every declaration explicit.

#### 2.4 N+1 — the central performance consequence of LAZY

```
  LAZY without tuning:                     1 query for roots + N queries for children
  ───────────────────                      ──────────────────────────────────────────
  List<Customer> cs = customerRepo.findAll();         // SELECT * FROM customers  (1)
  for (Customer c : cs) {                              // cs.size() = 50
      c.getAddresses().size();  // LAZY trigger per customer → 50 × SELECT ... WHERE customer_id=? (50)
  }                                                    // total: 51 queries = N+1
```

- LAZY trades *one big join* for *N small selects*. Without tuning (PR #6/#7), iterating a lazy collection over many owners is N+1. EAGER avoids N+1 by joining eagerly, but *always* pays the join cost even when you don't need the data.
- **Tradeoff matrix:**

| Strategy | Queries when you *need* collection | Queries when you *don't* need it | Control |
|---|---|---|---|
| `LAZY` (default here) | N+1 (needs fix in PR #6/#7) | 1 (cheap) | Explicit per use case |
| `EAGER` | 1 (join) | Still 1 (wasted join) | Implicit, always pays |
| `LAZY + JOIN FETCH` (PR #6) | 1 (tuned per query) | 1 (cheap) | Caller chooses |
| `LAZY + @BatchSize` (PR #7) | 1 + ceil(N/batchSize) | 1 (cheap) | Global default `application.yml:61` |

#### 2.5 `open-in-view` (OSIV) — why false

- `spring.jpa.open-in-view` (OSIV) holds the Hibernate `Session` open from request entry to view rendering, so lazy loads in controllers/templates work. It *masks* N+1 and couples HTTP rendering to DB I/O. `application.yml:42` `open-in-view: false` closes Session at service (`@Transactional`) boundary — lazy access outside TX throws `LazyInitializationException` immediately, revealing missing fetch tuning instead of hiding it.

```
  OSIV true (anti-pattern):          OSIV false (this repo):
  Controller → Service(TX) → View   Controller → Service(TX) → View
  Session open ──────────────────►   Session open ──────► Session closed
  lazy load in view "works"          lazy load in view → LazyInitializationException
  N+1 hidden                         N+1 caught in service tests
```

#### 2.6 Complete fetch-type inventory (every association in the repo)

| Entity | Field | Type | Fetch | Why LAZY |
|---|---|---|---|---|
| `Order.java:46` | `customer` | `ManyToOne` | LAZY | Always; caller may only need order total |
| `Order.java:67` | `shippingAddress` | `ManyToOne` | LAZY | Nullable snapshot, rarely needed |
| `Order.java:71` | `billingAddress` | `ManyToOne` | LAZY | Same |
| `Order.java:82` | `items` | `OneToMany` | LAZY | Collection — EAGER would be bags + cartesian |
| `Order.java:92` | `payment` | `OneToOne` | LAZY | Optional 1:1, often not needed |
| `Customer.java:58` | `addresses` | `OneToMany` | LAZY | Collection; EAGER ×2 bags on Customer would fail |
| `Customer.java:67` | `orders` | `OneToMany` | LAZY | Same; also historically large |
| `Address.java:28` | `customer` | `ManyToOne` | LAZY | Parent reference; not always needed |
| `OrderItem.java:28` | `order` | `ManyToOne` | LAZY | Part of composite; product often needed not order |
| `OrderItem.java:32` | `product` | `ManyToOne` | LAZY | Product details only for some views |
| `Product.java:54` | `categories` | `ManyToMany` | LAZY | Tag set; rarely needed on order view |
| `Category.java:30` | `products` | `ManyToMany` | LAZY | Inverse; huge if EAGER |
| `Payment.java:36` | `order` | `OneToOne` | LAZY | Parent; payment view may not need order graph |

#### 2.7 Interview-ready mental model

> "Every association here is `LAZY` explicitly — `ManyToOne` defaults to `EAGER` if you forget, which silently joins on every `findById`. LAZY stores a proxy (single-valued) or `PersistentBag` (collection) with just the FK id; first access inside `@Transactional` fires a `SELECT`. Outside TX with `open-in-view: false` (`application.yml:42`) it throws `LazyInitializationException` — fail-fast. LAZY makes `findById` cheap (1 query) but naive iteration causes N+1 (1 + N selects); we fix that per-use-case in PR #6 (JOIN FETCH / EntityGraph) and globally in PR #7 (batch fetching), rather than paying EAGER's always-on join cost and `MultipleBagFetchException`."

---

## 3. Solution — ASCII (LAZY everywhere, Session boundary)

```
                    PersistenceContext (inside @Transactional)
                    ┌──────────────────────────────────────────────┐
                    │  Order#10  ──LAZY proxy──► Customer#1 (id only)│
                    │        │──LAZY proxy──► Address#5 (id only)   │
                    │        │──LAZY bag───► items [uninitialized]  │
                    │        └──LAZY proxy──► Payment#3 (id only)   │
                    │                                               │
                    │  Customer#1 ──LAZY bag──► addresses [uninit] │
                    │            ──LAZY bag──► orders [uninit]      │
                    └──────────────────────────────────────────────┘
                              │ first access triggers SQL
                              ▼
                    SELECT * FROM customers WHERE id=1
                    SELECT * FROM addresses WHERE customer_id=1
                    SELECT * FROM order_items WHERE order_id=10
                              │
                    TX commit / @Transactional return
                              │
                    application.yml:42  open-in-view: false  → Session closed
                              │
                    View/Controller accessing lazy field → LazyInitializationException (fail-fast)
```

Tagline: `↑ Fetch Types set to LAZY at PR #5` — `Order.java:46`, `Customer.java:58/67`, `Product.java:54` etc. all explicit.

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/Order.java` | `46` | `customer ManyToOne LAZY` | Explicit — overrides JPA EAGER default |
| `Order.java` | `67` | `shippingAddress ManyToOne LAZY` | Nullable snapshot |
| `Order.java` | `71` | `billingAddress ManyToOne LAZY` | Nullable snapshot |
| `Order.java` | `82` | `items OneToMany LAZY` + `orphanRemoval` | Collection LAZY is JPA default but made explicit |
| `Order.java` | `92` | `payment OneToOne LAZY` | Overrides EAGER default for OneToOne |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `58` | `addresses OneToMany LAZY` | Inverse side |
| `Customer.java` | `67` | `orders OneToMany LAZY` + `@BatchSize(20)` | Batch fetching hint (PR #7) |
| `src/main/java/com/company/orderapi/domain/Address.java` | `28` | `customer ManyToOne LAZY` | Owning side, LAZY |
| `src/main/java/com/company/orderapi/domain/OrderItem.java` | `28` | `order ManyToOne LAZY` | Owning side |
| `OrderItem.java` | `32` | `product ManyToOne LAZY` | Owning side |
| `src/main/java/com/company/orderapi/domain/Product.java` | `54` | `categories ManyToMany LAZY` | Owning side N:N |
| `src/main/java/com/company/orderapi/domain/Category.java` | `30` | `products ManyToMany LAZY` | Inverse side |
| `src/main/java/com/company/orderapi/domain/Payment.java` | `36` | `order OneToOne LAZY` | Owning side 1:1 |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43` | `@MappedSuperclass` | Id/version shared; no fetch concern |
| `src/main/resources/application.yml` | `42` | `open-in-view: false` | No OSIV — fail fast on lazy outside TX |
| `src/main/resources/application.yml` | `44` | `show-sql: true`, `format_sql: true` | See when lazy triggers SQL |
| `src/main/resources/application.yml` | `56` | `generate_statistics: true` | Count queries to spot N+1 |
| `src/main/java/com/company/orderapi/domain/repository/CustomerRepository.java` | `61` | `JOIN FETCH` / `@EntityGraph` methods | PR #6 fixes for when LAZY *does* need data |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts LAZY not EAGER via `Hibernate.isInitialized` |

```java
// Order.java:46 — the canonical LAZY ManyToOne (overrides JPA EAGER default)
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "customer_id", nullable = false)
private Customer customer;

// Order.java:82 — LAZY collection (explicit even though JPA defaults LAZY for OneToMany)
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
@BatchSize(size = 20)
private List<OrderItem> items = new ArrayList<>();

// Customer.java:67 — two LAZY bags on one entity (EAGER would throw MultipleBagFetchException)
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
@BatchSize(size = 20)
private List<Order> orders = new ArrayList<>();

// application.yml:42 — fail-fast for lazy outside TX
jpa:
  open-in-view: false
  show-sql: true
  properties:
    hibernate:
      generate_statistics: true
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders"
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/swagger-ui.html | head -5
```

```java
// Inside @Transactional: LAZY works
Order o = orderRepository.findById(id).orElseThrow();
Hibernate.isInitialized(o.getCustomer()); // false — still proxy
o.getCustomer().getEmail();              // true after — fires SELECT
Hibernate.isInitialized(o.getCustomer()); // true now

// Outside @Transactional with open-in-view:false: fails fast
Order detached = orderRepository.findById(id).orElseThrow();
detached.getCustomer().getEmail(); // → LazyInitializationException

// Fix: fetch explicitly when needed (PR #6)
List<Customer> withAddrs = customerRepository.findAllWithAddressesJoinFetch(); // 1 query
```

```bash
# See lazy SQL firing in logs
./mvnw test -Dtest=FetchTypeIntegrationTest -Dorg.hibernate.SQL=DEBUG | grep -E "select.*from (customers|addresses|order_items)"
# With generate_statistics: logs "Session Metrics {N queries}"
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| All associations LAZY | Explicit `fetch=LAZY` everywhere | Mix EAGER/LAZY | Predictable: `findById` = 1 query; caller opts into joins per use case | Must add JOIN FETCH/EntityGraph/Batch where needed |
| open-in-view | `false` | `true` (Boot default) | Fail-fast N+1 in service layer, not hidden in view | Lazy access outside TX throws — must handle |
| Explicit fetch on ManyToOne | `LAZY` declared | Omit (defaults EAGER) | Avoid silent EAGER join on every load | One more annotation per field |
| Collection defaults | `LAZY` (explicit) | `EAGER` | EAGER bags cause cartesian explosion + `MultipleBagFetchException` | Requires explicit fetching when collection needed |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — tests call `Hibernate.isInitialized` to assert LAZY, and assert `LazyInitializationException` outside TX.

---

## 7. How to verify

```bash
# Assert LAZY via Hibernate API
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Test does:
#   Order o = repo.findById(id).orElseThrow();
#   assertThat(Hibernate.isInitialized(o.getCustomer())).isFalse();
#   assertThat(Hibernate.isInitialized(o.getItems())).isFalse();
#   // inside TX: access triggers load
#   o.getCustomer().getEmail(); assertThat(Hibernate.isInitialized(o.getCustomer())).isTrue();

# Check open-in-view is false
grep -A2 "open-in-view" src/main/resources/application.yml  # → false
curl -s http://localhost:8080/actuator/env | jq .propertySources[].properties."spring.jpa.open-in-view"

# Count queries: LAZY findAll + iterate should show N+1 in logs (before fix)
./mvnw test -Dtest=CustomerFetchTest -Dhibernate.generate_statistics=true 2>&1 | grep "Session Metrics"

curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Declare every association `LAZY` explicitly — never rely on JPA defaults. Set `open-in-view: false` in every new service. When you need the association, use a *named* fetch method (`findAllWithAddressesJoinFetch` `CustomerRepository.java:61`) rather than flipping to EAGER.
- **Operate:** `show-sql: true` + `generate_statistics: true` (`application.yml:44/56`) let you count queries per request in logs. N+1 shows as `Session Metrics {50 queries}` for 50 customers — tune with batch/PR #6/#7 and watch it drop to 1-2.
- **Interview:** "PR #5: all associations are `LAZY` (`Order.java:46`, `Customer.java:58/67`) — proxies/bags with id only; first access inside `@Transactional` fires `SELECT`. `open-in-view: false` (`application.yml:42`) fails fast outside TX. EAGER would join always and fail with `MultipleBagFetchException` for two bags. N+1 is the tradeoff; fixed per-use-case in PR #6 and globally in PR #7."

---

## 9. Interview lens — Q&A

**Q1: Why is `ManyToOne` EAGER by default and why do we override it?**
A: JPA spec chose EAGER for single-valued associations assuming parent is always needed. In practice it adds a hidden `JOIN`/`SELECT` on every `findById` even when caller only needs the root (e.g., `order.getTotalAmount()`). We override with `fetch=LAZY` (`Order.java:46`, `Address.java:28`) and fetch explicitly when needed — predictable cost.

**Q2: What is a Hibernate proxy and when does it initialise?**
A: ByteBuddy subclass of the entity with LazyInitializer. After `orderRepository.findById`, `order.customer` is a proxy holding only `id` from `orders.customer_id`. `getId()` does not initialise; any other getter/setter does — triggers `SELECT * FROM customers WHERE id=?` inside an open Session. `Hibernate.isInitialized()` tests it.

**Q3: How verify without trusting migration?**
A: `DatabaseSchemaIntegrationTest.java:56` loads entities and asserts `!Hibernate.isInitialized(lazyField)` immediately after `findById`, then accesses it inside TX and asserts initialized, then asserts `LazyInitializationException` outside TX with `open-in-view:false`. No migration text involved.

**Q4: Why `open-in-view: false`?**
A: `application.yml:42` closes Session at service return. With `true`, lazy loads in controller/view "work" but hide N+1 and couple HTTP rendering to DB I/O. `false` makes missing fetch tuning fail fast in tests, not silently in prod.

**Q5: Next step?**
A: PR #6 Fetch Joins and Entity Graphs — explicit per-query strategies to load LAZY associations in 1 query when needed, fixing N+1 without turning to EAGER.

---

## 10. Honest limits & next step → PR #6

LAZY makes single-entity loads cheap, but iterating `customerRepository.findAll()` then `c.getAddresses().size()` for 50 customers is still N+1 (51 queries). No per-query fetch optimisation yet. PR #6 adds `JOIN FETCH` (`CustomerRepository.java:61`), `@EntityGraph(attributePaths)` (`CustomerRepository.java:70`), and `@NamedEntityGraph` (`Customer.java:34` → `CustomerRepository.java:80`) to fix N+1 per use case; PR #7 adds global batch fetching.

See [`06-fetch-joins-and-entity-graphs.md`](./06-fetch-joins-and-entity-graphs.md) or [`README.md`](./README.md).
