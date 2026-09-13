# 03. JPA Entities with Foreign Key Mappings (PR #3)

> PR #3 — JPA Entities with Foreign Key Mappings. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + Hibernate 6.6. See `README.md:1772` roadmap `| 3 | JPA Entities with Foreign Key Mappings |`.

---

## 1. Purpose — what shipped

PR #3 delivers **JPA Entities with Foreign Key Mappings** as a first-class, tested, documented building block. Eight entities now mirror the eight tables from PR #2: `Customer.java:32` (`@Table(name="customers")`), `Address.java:25` (`addresses`), `Order.java:41` (`orders`), `OrderItem.java:24` (`order_items`), `Product.java:31` (`products`), `Category.java:20` (`categories`), `Payment.java:32` (`payments`), plus join table `product_categories` via `@JoinTable`. `BaseEntity.java:43` (`@MappedSuperclass`) centralises `id` (`GenerationType.IDENTITY:50`) and `version`. Bidirectional associations use owning/inverse distinction; tests persist and navigate the graph without native SQL.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Tables exist but Java has no object graph. `customer.getOrders()` does not exist; queries return `Object[]` via JDBC. No proxy, no dirty checking, no `mappedBy`.

**After:** `README.md:1772` lists `| 3 | JPA Entities with Foreign Key Mappings |` as completed; `Customer.java:58` `addresses` + `Order.java:82` `items` + `Product.java:54` `categories` form a navigable graph; `OrderItem.java:28` and `Payment.java:36` hold the actual FK columns; `BaseEntity.java:50` `IDENTITY` matches `01_create_tables.sql:22` `GENERATED AS IDENTITY`.

### Theory — JPA/Hibernate mapping from first principles

#### 2.1 ORM impedance mismatch — why mapping exists

- Relational = tables + rows + FK columns. Objects = graphs + references + identity. ORM bridges the gap: each `@Entity` maps to one table (`@Table`), each `@Column` to one column, each association (`@ManyToOne` etc.) to a *foreign key column*.
- The core problem: *who writes the FK column?* Exactly one side must own the column. The other side is a *mirror* that reads via join. Getting this wrong causes duplicate `UPDATE` or `null` FK — the most common JPA bug.

```
  Java graph                     Relational rows
  ──────────                     ───────────────
  customer.getAddresses()  ───►  addresses.customer_id  (OWNING side writes)
  customer.addresses       ◀───  mappedBy="customer"    (INVERSE side reads)
```

#### 2.2 Owning vs inverse side — the `mappedBy` rule

| Concept | Owns FK column? | Annotation | Writes to DB? |
|---|---|---|---|
| **Owning side** | Yes (`@JoinColumn`) | `@ManyToOne`, `@OneToOne`(+`@JoinColumn`), `@ManyToMany`+`@JoinTable` (one side) | YES — `INSERT`/`UPDATE` includes FK |
| **Inverse side** | No (`mappedBy`) | `@OneToMany(mappedBy=...)`, `@OneToOne(mappedBy=...)`, `@ManyToMany(mappedBy=...)` | NO — never writes FK, only reads via join |

- **Rule:** `mappedBy` value = *field name* on the owning side, not column name. Example: `Customer.java:58` `@OneToMany(mappedBy="customer")` points to `Address.java:30` `private Customer customer` — the *Java field* name.
- **One-to-Many is always inverse** in this codebase (and idiomatically everywhere): the *many* side holds the FK column, so `@OneToMany` on the "one" is `mappedBy`. Never put `@JoinColumn` on `@OneToMany` (that creates a *join table* or extra update — confusing and inefficient).

#### 2.3 Every association flavour in the codebase (complete catalog)

| # | Relation | Owning side (writes FK) | Inverse side (`mappedBy`) | FK column | Delete rule (DB) |
|---|---|---|---|---|---|
| 1 | Customer 1—N Address | `Address.java:28` `@ManyToOne` + `@JoinColumn(name="customer_id")` | `Customer.java:58` `@OneToMany(mappedBy="customer")` | `addresses.customer_id` | CASCADE |
| 2 | Customer 1—N Order | `Order.java:46` `@ManyToOne` + `@JoinColumn(name="customer_id")` | `Customer.java:67` `@OneToMany(mappedBy="customer")` | `orders.customer_id` | CASCADE |
| 3 | Order N—1 Address (ship) | `Order.java:67` `@ManyToOne` + `@JoinColumn(name="shipping_address_id")` | — (unidirectional) | `orders.shipping_address_id` | SET NULL |
| 4 | Order N—1 Address (bill) | `Order.java:71` `@ManyToOne` + `@JoinColumn(name="billing_address_id")` | — | `orders.billing_address_id` | SET NULL |
| 5 | Order 1—N OrderItem | `OrderItem.java:28` `@ManyToOne` + `@JoinColumn(name="order_id")` | `Order.java:82` `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval)` | `order_items.order_id` | CASCADE |
| 6 | Product N—1 OrderItem | `OrderItem.java:32` `@ManyToOne` + `@JoinColumn(name="product_id")` | — | `order_items.product_id` | RESTRICT |
| 7 | Product N—N Category | `Product.java:54` `@ManyToMany` + `@JoinTable(name="product_categories")` | `Category.java:30` `@ManyToMany(mappedBy="categories")` | `product_categories.(product_id,category_id)` | RESTRICT both |
| 8 | Order 1—1 Payment | `Payment.java:36` `@OneToOne` + `@JoinColumn(name="order_id", unique=true)` | `Order.java:92` `@OneToOne(mappedBy="order")` | `payments.order_id` UNIQUE | CASCADE |

**ASCII overview:**

```
 Customer 1──────────N Address          (Address owns FK: customer_id)
    │ 1                   │
    │                     │  Customer 1──────────N Order  (Order owns FK: customer_id)
    │                     │       │ 1
    │                     │       ├──N OrderItem ──N 1 Product N──N Category
    │                     │       │   (OrderItem owns both FKs)  (Product owns join table)
    │                     │       │
    │                     │       └──1 Payment  (Payment owns FK: order_id, inverse is Order.payment)
    │                     │
    └─────────────────────┼──── Address also referenced optionally by Order.shipping/billing (SET NULL)
```

#### 2.4 `@JoinColumn` vs `@JoinTable` — when each is used

- `@JoinColumn(name="customer_id", nullable=false)` (`Address.java:30`, `Order.java:47`, `OrderItem.java:29`) — *"this table has a FK column"*. JPA writes the FK value from the associated entity's id on flush.
- `@JoinTable(name="product_categories", joinColumns=@JoinColumn(name="product_id"), inverseJoinColumns=@JoinColumn(name="category_id"))` (`Product.java:55`) — *"there is a third table holding two FKs"*. Only `@ManyToMany` uses it; one side declares the table, the other uses `mappedBy`. The table has *no entity*.

#### 2.5 Hibernate Session, proxies and FetchType.LAZY (why LAZY is default here)

```
  PersistenceContext (1st-level cache, per Session/Transaction)
  ┌─────────────────────────────────────────────────────────┐
  │  id=1 Customer  ──LAZY──►  addresses: PersistentBag    │  not loaded yet
  │                   ─LAZY──►  orders:   PersistentBag    │  not loaded yet
  │  id=10 Order      ─LAZY──►  customer: proxy (id only)  │  ByteBuddy subclass
  │  id=100 OrderItem ─LAZY──►  product:  proxy (id only)  │
  └─────────────────────────────────────────────────────────┘
  Accessing customer.getAddresses().size() → fires SELECT ... WHERE customer_id=?
  Outside @Transactional → LazyInitializationException (session closed)
```

- Every `FetchType.LAZY` field (`Order.java:46`, `Customer.java:58`, `Product.java:54`) is *not* loaded with the owner. Hibernate injects a `PersistentBag`/`PersistentSet` or ByteBuddy proxy that holds just the FK id. First traversal triggers a SQL `SELECT` *within an open Session* (i.e., inside `@Transactional`).
- `BaseEntity.java:107` `@Transient postLoadFired` + `BaseEntity.java:109` `@PostLoad` shows the callback fires once per entity load — inherited by all eight entities.
- **Why LAZY everywhere here:** `FetchType.EAGER` would load the whole graph on every `findById` — catastrophic for `Customer` with many orders/items/payments. LAZY + explicit fetch strategies (PR #5-#7) is the production pattern.

#### 2.6 `@MappedSuperclass` + IDENTITY + `@Version`

- `BaseEntity.java:43` `@MappedSuperclass` — not an entity itself, but every entity inherits `id` (`BaseEntity.java:50` `IDENTITY` ↔ `01_create_tables.sql:22` `GENERATED AS IDENTITY`), `version` (`BaseEntity.java:53` `@Version` for optimistic locking PR #8), and auditing fields (`createdAt`, `updatedBy` PR #10).
- `GenerationType.IDENTITY` = Postgres generates id on `INSERT`. Hibernate must `INSERT` immediately to know the id (no pre-allocation) — relevant for batch-insert tuning.
- `equals/hashCode` are *not* overridden yet (comment `BaseEntity.java:38`): id is `null` until flush, so id-based equality is unsafe for transient objects. Identity (`==`) is correct inside one `PersistenceContext`.

#### 2.7 Bidirectional helper methods — keeping both sides in sync

```java
// Customer.java:82 — the canonical pattern: link BOTH sides at once
public void addAddress(Address address) {
    addresses.add(address);
    address.setCustomer(this);
}
// Order.java:108 — same for Order ↔ OrderItem
public void addItem(OrderItem item) { items.add(item); item.setOrder(this); }
// Order.java:114, Product.java:71 — same for Order↔Payment, Product↔Category
```

- If you only do `customer.getAddresses().add(a)` without `a.setCustomer(c)`, the *owning* side (`Address.customer`) is still `null` → FK `NULL` on flush → constraint violation. Helpers make the graph consistent in memory *before* Hibernate flushes.

#### 2.8 Uni- vs bidirectional — when to add the inverse side

| Choice | What it gives | Cost |
|---|---|---|
| Unidirectional `@ManyToOne` only (e.g., `Order.customer` without `Customer.orders`) | Simple; no sync needed | Can't navigate `customer.getOrders()` — needs query |
| Bidirectional with `mappedBy` + helper | Navigable both ways; graph traversal in memory | Must keep both sides in sync via `addX()` |

- This repo chooses *bidirectional* for aggregates that are traversed both ways (`Customer↔Address`, `Order↔OrderItem`, `Product↔Category` via `Product.java:54` vs `Category.java:30`) and *unidirectional* for snapshots (`Order`→`Address` ship/bill — `Address` doesn't need `getShippingOrders()`).
- Rule: add the inverse side when the domain *reads* that direction often; otherwise keep it unidirectional to reduce helper/sync burden.

#### 2.9 How Hibernate detects owning side at bootstrap

1. Scans every `@Entity` (`Order.java:41`, `Customer.java:32`, etc.) and collects associations.
2. For each `@OneToMany`/`@OneToOne`/`@ManyToMany` with `mappedBy`, records "this side is inverse — do not create a FK column."
3. For each `@ManyToOne`/`@JoinColumn`/`@JoinTable`, creates a `JoinColumn` or `JoinTable` mapping that owns the FK.
4. Validates against `01_create_tables.sql` columns when `ddl-auto: validate` (`application.yml:48`): column name, nullability, length must match, or startup fails — fail-fast mapping errors.

```
  @OneToMany(mappedBy="customer")  ──► Hibernate: "no column for Customer.addresses"
  @ManyToOne @JoinColumn(customer_id) ──► Hibernate: "FK column addresses.customer_id exists, nullable=false"
  @ManyToMany @JoinTable(product_categories) ──► Hibernate: "join table product_categories with (product_id, category_id)"
```

#### 2.10 Interview-ready mental model

> "JPA associations mirror FK columns: the owning side (`@ManyToOne`/`@JoinColumn` or `@JoinTable`) writes the FK, the inverse side (`mappedBy`) is read-only. One-to-Many is always inverse because the many side holds the FK. Many-to-Many needs a join table owned by one side. LAZY injects proxies/bags that load on first access inside a Session; EAGER loads the graph eagerly and is almost never correct for collections. `BaseEntity` centralises IDENTITY + version. And you always write `addX()` helpers that set both sides, otherwise the FK is null."

---

## 3. Solution — ASCII (which annotation lives where)

```
 [Client] → Controller → Service (@Transactional) → Repository → DB
               ↑ JPA Entities with Foreign Key Mappings added at PR #3
                    │
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
 Customer ──1:N──► Address    Customer ──1:N──► Order ──1:N──► OrderItem ──N:1──► Product
  @OneToMany       @ManyToOne   @OneToMany      @ManyToOne    @ManyToOne      @ManyToOne
  mappedBy         @JoinColumn  mappedBy        @JoinColumn   @JoinColumn     @JoinColumn
  Customer.java:58 Address.java:28 Customer.java:67 Order.java:82 OrderItem.java:28

 Product ──N:N──► Category        Order ──1:1──► Payment
  @ManyToMany      @ManyToMany    @OneToOne       @OneToOne
  @JoinTable       mappedBy       mappedBy       @JoinColumn(unique)
  Product.java:54  Category.java:30 Order.java:92 Payment.java:36
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43` | `@MappedSuperclass` | Shared `id` + `version` + auditing |
| `BaseEntity.java` | `50` | `@GeneratedValue(IDENTITY)` | Matches `GENERATED AS IDENTITY` every table |
| `BaseEntity.java` | `53` | `@Version` | Optimistic locking (PR #8 uses it) |
| `BaseEntity.java` | `109` | `@PostLoad` | Sets `postLoadFired` transient flag |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `33` | `@Table(name="customers")` | Root entity |
| `Customer.java` | `34` | `@NamedEntityGraph("Customer.addresses")` | Reusable fetch recipe (PR #6) |
| `Customer.java` | `58` | `@OneToMany(mappedBy="customer") addresses` | Inverse side, `cascade=PERSIST` |
| `Customer.java` | `67` | `@OneToMany(mappedBy="customer") orders` + `@BatchSize(20)` | Inverse side, batch fetching (PR #7) |
| `Customer.java` | `82` | `addAddress()` | Bidirectional helper — sets both sides |
| `Customer.java` | `99` | `@PreRemove` | Business guard: refuse delete if orders exist |
| `src/main/java/com/company/orderapi/domain/Address.java` | `28` | `@ManyToOne(LAZY) + @JoinColumn(customer_id)` | **Owning** side of Customer-Address |
| `src/main/java/com/company/orderapi/domain/Order.java` | `46` | `@ManyToOne(LAZY) customer` | Owning side Customer-Order |
| `Order.java` | `67` | `@ManyToOne shippingAddress` | Optional FK `shipping_address_id` |
| `Order.java` | `71` | `@ManyToOne billingAddress` | Optional FK `billing_address_id` |
| `Order.java` | `82` | `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval)` | Inverse side Order→OrderItem (PR #4) |
| `Order.java` | `84` | `@BatchSize(20)` | Batch fetch OrderItems |
| `Order.java` | `92` | `@OneToOne(mappedBy="order") payment` | Inverse side 1:1 Payment |
| `Order.java` | `108` | `addItem()` | Helper links both sides |
| `src/main/java/com/company/orderapi/domain/OrderItem.java` | `28` | `@ManyToOne(order)` + `@ManyToOne(product)` | **Owning** side of both relations |
| `src/main/java/com/company/orderapi/domain/Product.java` | `54` | `@ManyToMany @JoinTable(product_categories)` | **Owning** side N:N |
| `Category.java` | `30` | `@ManyToMany(mappedBy="categories")` | Inverse side N:N |
| `src/main/java/com/company/orderapi/domain/Payment.java` | `36` | `@OneToOne @JoinColumn(order_id, unique)` | **Owning** side 1:1 |
| `src/main/java/com/company/orderapi/domain/Category.java` | `20` | `@Table(name="categories")` | Inverse side entity |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | Hibernate validates mapping vs `01_create_tables.sql` |

```java
// Address.java:28 — owning side: writes addresses.customer_id
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "customer_id", nullable = false)
private Customer customer;

// Customer.java:58 — inverse side: reads via join, never writes FK
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
private List<Address> addresses = new ArrayList<>();

// OrderItem.java:28 — owns TWO FKs (order_id, product_id)
@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "order_id", nullable = false)
private Order order;
@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id", nullable = false)
private Product product;

// Product.java:54 — owning side of N:N via join table
@ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.MERGE)
@JoinTable(name = "product_categories",
    joinColumns = @JoinColumn(name = "product_id"),
    inverseJoinColumns = @JoinColumn(name = "category_id"))
private Set<Category> categories = new LinkedHashSet<>();

// Payment.java:36 — owning side of 1:1 (UNIQUE enforces it)
@OneToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "order_id", nullable = false, unique = true)
private Order order;
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Boot + verify Hibernate validates entities vs tables
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.jpa.hibernate.ddl-auto=validate

# psql still shows same FKs — entities didn't alter schema
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d order_items"

# Quick Java snippet (inside @Transactional test/service)
Customer c = new Customer("alice@example.com", "Alice");
Address a = new Address(c, "1 Main St", "Springfield", "USA");
c.addAddress(a); // ← must use helper (both sides)
customerRepository.save(c); // cascades PERSIST to addresses

Order o = new Order(c, OrderStatus.PLACED, new BigDecimal("99.99"));
Product p = productRepository.findById(productId).orElseThrow();
OrderItem item = new OrderItem(p, 2, p.getPrice());
o.addItem(item); // ← both sides
orderRepository.save(o);

# Verify graph navigation
curl -s http://localhost:8080/api/orders -H "X-API-KEY: dev-api-key" | jq .
curl -s http://localhost:8080/api/customers -H "X-API-KEY: dev-api-key" | jq .
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen |
|---|---|---|---|
| One-to-Many mapping | `mappedBy` inverse on "one" | `@JoinColumn` on `@OneToMany` | Owning on many avoids extra UPDATE/join table |
| Many-to-Many owner | `Product` owns `@JoinTable` | `Category` owns | Products tag categories, not vice versa — business ownership |
| FetchType | `LAZY` everywhere | `EAGER` on collections | LAZY keeps `findById` cheap; EAGER loads full graph catastrophically |
| Bidirectional vs unidirectional | Bidirectional + helpers | Unidirectional | Navigable both ways; helpers prevent null-FK bug |
| Address→Customer | `optional=false` | `optional=true` | Every address must belong to a customer (DB NOT NULL) |
| Order→Address (ship/bill) | `optional=true` (nullable FK) | `optional=false` | Order may exist before address chosen; SET NULL on delete |
| BaseEntity | `@MappedSuperclass` | `@Entity` with `@Inheritance` | No extra table; id/version/auditing shared via inheritance |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — tests persist the graph and assert associations load correctly within a TX.

---

## 7. How to verify

```bash
# Integration: persist graph and assert FKs written correctly
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Tests assert: customerRepository.save(c) with addAddress → addresses.customer_id populated
#               orderRepository.save(o) with addItem  → order_items.order_id/product_id populated

# Check Hibernate didn't alter schema (validate only)
./mvnw test -Dspring.jpa.hibernate.ddl-auto=validate
# No "alter table" in logs; org.hibernate.SQL shows only SELECT/INSERT for tests

# Manual: FK column populated?
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT a.id, a.customer_id, c.email FROM addresses a JOIN customers c ON c.id=a.customer_id LIMIT 5;
  SELECT oi.order_id, oi.product_id, o.customer_id FROM order_items oi JOIN orders o ON o.id=oi.order_id LIMIT 5;
"

# Actuator + Prometheus still healthy
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Copy the owning/inverse table (§2.3) for your next domain. Always use `mappedBy` on `@OneToMany`; always write `addX()` helpers that set both sides. Use `@JoinTable` only for N:N and pick one owner.
- **Operate:** `ddl-auto: validate` (`application.yml:48`) means a mapping typo fails fast at startup, not silently in prod. Check `postLoadFired` (`BaseEntity.java:107`) to debug load counts.
- **Interview:** "PR #3: eight entities mirror eight tables; owning side writes FK (`@JoinColumn`/`@JoinTable`), inverse uses `mappedBy` and never writes. One-to-Many is always inverse. Product owns N:N join table. All LAZY — proxies load inside `@Transactional`. Verified by persisting via `addAddress()`/`addItem()` helpers and asserting FKs."

---

## 9. Interview lens — Q&A

**Q1: Why is `@OneToMany` always `mappedBy` in this codebase?**
A: The FK lives in the *many* table (`addresses.customer_id`, `order_items.order_id`). The "one" side has no column to write. Putting `@JoinColumn` on `@OneToMany` would make Hibernate manage the FK from the wrong side — either an extra `UPDATE` after `INSERT` or a hidden join table. See `Customer.java:58` vs `Address.java:28`.

**Q2: How verify without trusting migration?**
A: `DatabaseSchemaIntegrationTest.java:56` persists the graph via `customerRepository.save(c)` with `addAddress()` then asserts `addresses.customer_id` is non-null via JDBC query and that navigating `customer.getAddresses()` inside TX returns the data — it tests *mapping* not migration text, plus `psql \d` shows live FKs.

**Q3: What happens if you forget `item.setOrder(this)` in `addItem()`?**
A: `Order.java:82` is inverse (`mappedBy="order"`), so Hibernate ignores `order.items` for FK writing. Only `OrderItem.order` (`OrderItem.java:28`) writes `order_items.order_id`. Forgetting the owning side → `order_id` is NULL → `NOT NULL` constraint violation on flush.

**Q4: Why LAZY not EAGER?**
A: `FetchType.EAGER` on `Customer.orders` would join and load every order + items + payments on *every* `findById` — O(entire graph). LAZY (`Customer.java:58`, `Order.java:46`) defers loading until accessed inside `@Transactional`; explicit fetch strategies (JOIN FETCH / EntityGraph / @BatchSize PR #5-#7) load only what's needed per use case.

**Q5: Next step?**
A: PR #4 Cascading Strategies — add `cascade = CascadeType.ALL, orphanRemoval = true` to `Order.java:82` so persisting/removing an Order carries its items/payment.

---

## 10. Honest limits & next step → PR #4

Not end-to-end; that is PR #4. Entities map FKs but *lifecycle* is manual: you must `save` each entity separately; removing an `OrderItem` from `order.getItems()` does not delete the row — it becomes an orphan. Next PR adds `cascade = ALL` + `orphanRemoval` so the aggregate lifecycle is managed.

See [`04-cascading-strategies.md`](./04-cascading-strategies.md) or [`README.md`](./README.md).
