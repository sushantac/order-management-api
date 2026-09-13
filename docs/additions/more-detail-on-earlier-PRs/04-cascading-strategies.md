# 04. Cascading Strategies (PR #4)

> PR #4 — Cascading Strategies. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + Hibernate 6.6. See `README.md:1772` roadmap `| 4 | Cascading Strategies |`.

---

## 1. Purpose — what shipped

PR #4 delivers **Cascading Strategies** as a first-class, tested, documented building block. It makes aggregate persistence *automatic*: saving/removing a parent cascades to children per the business lifecycle. Concretely: `Order.java:82` `@OneToMany(mappedBy="order", cascade=CascadeType.ALL, orphanRemoval=true)` + `@BatchSize(20)`, `Order.java:92` `@OneToOne(mappedBy="order", cascade=CascadeType.ALL)`, `Customer.java:58`/`67` `cascade=PERSIST`, `Product.java:54` `cascade=MERGE`. Tests assert that `orderRepository.save(order)` with `addItem()` persists items, that removing an item from `getItems()` deletes the row, and that DB-level `CASCADE` vs JPA cascade interact correctly.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** Every entity must be saved manually: `orderRepository.save(o); orderItemRepository.save(i1); orderItemRepository.save(i2); paymentRepository.save(p)`. Removing an `OrderItem` from `order.getItems()` leaves an orphan row with stale `order_id` — no DELETE.

**After:** `Order.java:82` `cascade=ALL, orphanRemoval=true` makes the aggregate *one unit of persistence*. `customer.addOrder(order)` + `order.addItem(item)` + `order.setPayment(payment)` then single `orderRepository.save(order)` persists the whole graph. Removing an item from the collection issues `DELETE FROM order_items WHERE id=?` on flush.

### Theory — cascading and orphan removal from first principles

#### 2.1 What JPA cascade *is* (and is NOT)

- **Definition:** cascade = *transitive persistence*. When you call `EntityManager.persist(parent)` / `merge` / `remove` / `refresh` / `detach`, Hibernate *automatically* calls the same operation on associated entities that declare `cascade=CascadeType.<X>`.
- **Crucial distinction vs DB `ON DELETE CASCADE`:**

| Layer | Trigger | Scope | Mechanism |
|---|---|---|---|
| **DB `ON DELETE CASCADE`** (`01_create_tables.sql:75,95,120,164`) | `DELETE FROM customers WHERE id=?` (SQL) | Rows in child tables | Foreign-key constraint action, inside the DB engine, no Hibernate involved |
| **JPA `cascade=REMOVE/ALL`** (`Order.java:82`) | `entityManager.remove(order)` or `orderRepository.delete(order)` (Java) | Entities in `PersistenceContext` | Hibernate iterates collection, calls `remove` on each child entity, then flushes `DELETE`s |

- They are *orthogonal*: DB cascade fires on SQL deletes (including `psql` deletes, batch jobs, other services); JPA cascade fires on Java deletes inside a Session. The repo uses *both* where appropriate — belt and suspenders.

```
  Java: em.remove(order)  ── cascade=REMOVE ──▶  em.remove(item1), em.remove(item2)
                                    │
                                    ▼ flush
  SQL:  DELETE FROM orders WHERE id=?  +  DELETE FROM order_items WHERE order_id=?
                                    │
  DB:   ON DELETE CASCADE (if DB row deleted directly) also deletes items
```

#### 2.2 Cascade types — complete catalog

| CascadeType | Operation propagated | Meaning | Used in repo |
|---|---|---|---|
| `PERSIST` | `persist()` (INSERT) | Saving parent saves new children | `Customer.java:58` addresses, `Customer.java:67` orders |
| `MERGE` | `merge()` (re-attach detached) | Merging parent merges detached children (re-attaches without duplicate row) | `Product.java:54` categories |
| `REMOVE` | `remove()` (DELETE) | Deleting parent deletes children | Part of `ALL` on `Order.items`/`payment` |
| `REFRESH` | `refresh()` (reload from DB) | Refreshing parent refreshes children | Part of `ALL` |
| `DETACH` | `detach()` (evict from Session) | Detaching parent detaches children | Part of `ALL` |
| `ALL` | All above | Shorthand for `PERSIST+MERGE+REMOVE+REFRESH+DETACH` | `Order.java:82` items, `Order.java:92` payment |

- **Why `Customer` uses only `PERSIST` not `ALL`:** `Customer.java:58` `cascade=PERSIST` lets you `save(new Customer)` with addresses in one call, but `customerRepository.delete(c)` does *not* cascade to orders — orders are historical records that must not vanish because a customer row is removed (there is a `@PreRemove` guard `Customer.java:99` that throws instead). `ALL` would be dangerous here.
- **Why `Product.categories` uses only `MERGE`:** `Product.java:54` `cascade=MERGE` re-attaches an existing `Category` when merging a detached `Product` (e.g., from a REST DTO that references category ids). Using `PERSIST` would try to `INSERT` a category that already exists → unique violation.

#### 2.3 `orphanRemoval` — the other half of aggregate lifecycle

```java
// Order.java:82 — the key line of PR #4
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
@BatchSize(size = 20)
private List<OrderItem> items = new ArrayList<>();
```

- `orphanRemoval=true` means: *if a child is no longer referenced by the parent collection, DELETE it*. Two triggers:
  1. `order.getItems().remove(item)` → on flush, `DELETE FROM order_items WHERE id=?`
  2. `order.getItems().clear()` → deletes all items
  3. `order.setItems(newList)` (replacing collection) → old items not in new list are deleted
- **Without orphanRemoval:** removing from the collection just *breaks the link* (`order_id` would be set NULL if nullable, or constraint violation if NOT NULL). The row stays. You would need explicit `orderItemRepository.delete(item)`.
- **With orphanRemoval:** the collection *owns* the lifecycle — the child cannot exist without the parent (composition, not aggregation). This matches the domain: "an order line has no meaning outside its order."

**State diagram:**

```
  Transient (new OrderItem) ── addItem() + save(order) ──►  Managed (in PersistenceContext)
        │ cascade=PERSIST                                              │
        │                                           remove from list   │
        │                                           orphanRemoval=true │
        │                                                              ▼
        │                                                    Scheduled DELETE
        │                                                              │
        │                                                          flush()
        │                                                              ▼
        │                                                          Deleted (row gone)
        │
  Managed ── em.remove(order) + cascade=REMOVE ──► Deleted (order + all items via cascade)
```

#### 2.4 Cascade vs orphanRemoval — comparison table

| Question | `cascade=REMOVE` | `orphanRemoval=true` |
|---|---|---|
| When does DELETE fire? | When *parent* is deleted | When *child* is removed from collection |
| Needs parent delete? | Yes | No — parent stays |
| Requires `cascade`? | Itself is a cascade type | Implies cascade for remove; also needs `PERSIST` for new items |
| Collection type | Any | Only `@OneToMany` and `@OneToOne` (not `@ManyToMany`/`@ManyToOne`) |
| DB equivalent | `ON DELETE CASCADE` (but at JPA level) | No DB equivalent — pure ORM concept |

- **In this repo:** `Order.items` uses *both*: `cascade=ALL` handles parent delete propagation; `orphanRemoval=true` handles child removal from live order (e.g., customer removes a line before checkout).

#### 2.5 Flush ordering — why it matters

- Hibernate flushes operations in a fixed order: *inserts first, then updates, then deletes* — regardless of the Java call order. This avoids FK violations (insert parent before child that references it; delete child before parent).
- With `cascade=ALL`, persisting `Order` with new `OrderItem`s that reference `Product` works because Hibernate: 1) inserts `orders` row (gets id via `IDENTITY`), 2) inserts `order_items` with `order_id` + `product_id`. No manual ordering needed.
- **Pitfall:** if you `customerRepository.delete(customer)` that still has `orders`, DB `ON DELETE CASCADE` would delete them — but `Customer.java:99` `@PreRemove` throws `IllegalStateException` *before* flush, enforcing the business rule over the physical cascade.

#### 2.6 One-to-One cascade (`Order` ↔ `Payment`)

```java
// Order.java:92 — inverse side 1:1 with cascade
@OneToOne(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, optional = true)
private Payment payment;
// Payment.java:36 — owning side holds order_id FK
@OneToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "order_id", nullable = false, unique = true)
private Order order;
```

- `Order.setPayment(p)` (`Order.java:114`) sets *both* sides; `orderRepository.save(order)` cascades `PERSIST` to `Payment` → single `INSERT` for order + `INSERT` for payment with `order_id`.
- `orphanRemoval` is *not* set on `payment` — replacing `order.setPayment(newP)` does not auto-delete the old payment row (you would null it explicitly). For `items` (list) orphanRemoval makes sense; for single-valued 1:1 it is rarely used because `null` vs `delete` semantics differ.

#### 2.7 Cascade pitfalls — when it goes wrong

- **Pitfall 1 — `cascade=ALL` on `Customer.orders`:** deleting a customer would delete historical orders — silent data loss. That is why `Customer.java:58/67` use `PERSIST` only and `Customer.java:99` guards with `@PreRemove`.
- **Pitfall 2 — `cascade=PERSIST` on `Product.categories` with new Category:** would `INSERT` a category that already exists → `uq_categories_name` unique violation (`01_create_tables.sql:55`). Using `MERGE` re-attaches instead.
- **Pitfall 3 — forgetting owning-side helper:** `order.getItems().add(item)` without `item.setOrder(order)` leaves `order_items.order_id` NULL →Flush fails. Helpers (`Order.java:108` `addItem()`) prevent it.
- **Pitfall 4 — `orphanRemoval` on `@ManyToMany`:** not allowed (throws at bootstrap) because `Product↔Category` is not composition — categories live independently. Only `@OneToMany`/`@OneToOne` support orphanRemoval.
- **Pitfall 5 — mixing DB and JPA cascade unintentionally:** `DELETE FROM customers WHERE id=?` via `psql` fires DB `CASCADE` regardless of JPA `PERSIST`-only — unless the `@PreRemove` callback runs (it only runs for `em.remove`). Direct SQL bypasses all entity callbacks.

#### 2.8 Flush, dirty checking and cascading order

1. You call `order.getItems().remove(0)` (collection now dirty) or `em.remove(order)`.
2. At flush (commit or `flush()`), Hibernate's `ActionQueue` sorts: `OrphanRemovalAction` before `EntityDeleteAction` before `CollectionRemoveAction`.
3. It generates `DELETE FROM order_items WHERE id=?` for orphans *before* `DELETE FROM orders` for the parent when cascading REMOVE — so FK constraints are satisfied in the correct order without deferring.
4. `BaseEntity.java:50` `IDENTITY` means `INSERT` for new children happens *immediately* to get ids, then FKs wired; other strategies (`SEQUENCE`) can batch inserts.

#### 2.9 Interview-ready mental model

> "Cascade is transitive persistence: `PERSIST`/`REMOVE`/etc. propagate from parent to children declared with `cascade`. `orphanRemoval` is composition: removing a child from the collection deletes its row even though the parent lives. Customer uses `PERSIST` only (don't delete orders when deleting customer — plus a `@PreRemove` guard), Product uses `MERGE` (re-attach existing categories), Order uses `ALL + orphanRemoval` for items and `ALL` for payment because an order owns its lines. DB `ON DELETE CASCADE` is orthogonal — it fires on SQL deletes regardless of Hibernate, so the repo uses both for defence in depth."

---

## 3. Solution — ASCII (which cascade lives where)

```
                    ┌──────────────────────────────┐
                    │  Customer (root)             │
                    │  addresses: cascade=PERSIST  │──▶ Address (composition, but no orphanRemoval;
                    │  orders:    cascade=PERSIST  │    deletion guarded by @PreRemove:99)
                    └──────────────┬───────────────┘
                                   │ 1:N  cascade=PERSIST
                                   ▼
                    ┌──────────────────────────────┐
                    │  Order (aggregate root)      │
                    │  items: cascade=ALL          │──▶ OrderItem  orphanRemoval=true
                    │         orphanRemoval=true   │    remove from list → DELETE
                    │         @BatchSize(20)       │    delete order    → DELETE all items
                    │  payment: cascade=ALL        │──▶ Payment  (1:1, DELETE with order)
                    │  shipping/billing: no cascade│──▶ Address  (no lifecycle coupling)
                    └──────────────────────────────┘
                                   │
                    ┌──────────────┴───────────────┐
                    │  Product                     │
                    │  categories: cascade=MERGE   │──▶ Category (re-attach, don't duplicate)
                    └──────────────────────────────┘

  DB layer (also active):  customers ON DELETE CASCADE → addresses, orders
                           orders    ON DELETE CASCADE → order_items, payments
                           order_items RESTRICT → products  (never auto-delete)
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/domain/Order.java` | `82` | `@OneToMany cascade=ALL orphanRemoval=true` | Aggregate root owns items lifecycle |
| `Order.java` | `84` | `@BatchSize(20)` | Batches lazy load of items (PR #7) |
| `Order.java` | `92` | `@OneToOne cascade=ALL` payment | Payment follows order lifecycle |
| `Order.java` | `108` | `addItem()` | Helper sets both sides before cascade |
| `Order.java` | `114` | `setPayment()` | Helper sets both sides before cascade |
| `src/main/java/com/company/orderapi/domain/Customer.java` | `58` | `cascade=PERSIST` addresses | Save customer+addresses atomically |
| `Customer.java` | `67` | `cascade=PERSIST` orders | Save customer+orders atomically; no REMOVE |
| `Customer.java` | `99` | `@PreRemove` guard | Refuses delete if orders exist — business rule over DB CASCADE |
| `src/main/java/com/company/orderapi/domain/Product.java` | `54` | `cascade=MERGE` categories | Re-attach existing categories on merge |
| `src/main/java/com/company/orderapi/domain/OrderItem.java` | `28` | Owning side `order` | FK `order_id` NOT NULL — orphanRemoval needs this |
| `src/main/java/com/company/orderapi/domain/Payment.java` | `36` | Owning side `order_id` UNIQUE | 1:1 FK, cascade target |
| `src/main/java/com/company/orderapi/domain/Address.java` | `28` | Owning side `customer_id` | FK `customer_id` NOT NULL |
| `src/main/resources/db/changelog/v1.0/01_create_tables.sql` | `75` | `ON DELETE CASCADE` addresses | DB-level counterpart to JPA cascade |
| `01_create_tables.sql` | `95` | `ON DELETE CASCADE` orders | DB cascade for customer→orders |
| `01_create_tables.sql` | `120` | `ON DELETE CASCADE` order_items | DB cascade for order→items |
| `01_create_tables.sql` | `164` | `ON DELETE CASCADE` payments | DB cascade for order→payment |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `50` | `IDENTITY` | Id available immediately after INSERT for FK wiring |

```java
// Order.java:82 — the heart of PR #4
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, orphanRemoval = true)
@BatchSize(size = 20)
private List<OrderItem> items = new ArrayList<>();

// Order.java:92 — 1:1 cascade (no orphanRemoval — single-valued)
@OneToOne(mappedBy = "order", fetch = FetchType.LAZY,
        cascade = CascadeType.ALL, optional = true)
private Payment payment;

// Customer.java:58 — PERSIST only (not ALL) — deliberate
@OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
private List<Address> addresses = new ArrayList<>();

// Product.java:54 — MERGE only — re-attach categories
@ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.MERGE)
@JoinTable(name = "product_categories", joinColumns = @JoinColumn(name = "product_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id"))
private Set<Category> categories = new LinkedHashSet<>();

// Customer.java:99 — business guard beats DB CASCADE
@PreRemove void assertRemovalAllowed() {
    if (orders != null && !orders.isEmpty())
        throw new IllegalStateException("Customer " + getId() + " cannot be removed: still has " + orders.size() + " order(s)");
}
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
// Persist whole aggregate in ONE save (cascade=PERSIST via ALL)
Customer c = customerRepository.findById(customerId).orElseThrow();
Order order = new Order(c, OrderStatus.PLACED, new BigDecimal("120.00"));
Product p1 = productRepository.findById(p1Id).orElseThrow();
Product p2 = productRepository.findById(p2Id).orElseThrow();
order.addItem(new OrderItem(p1, 2, p1.getPrice()));
order.addItem(new OrderItem(p2, 1, p2.getPrice()));
Payment pay = new Payment(order, order.getTotalAmount(), PaymentMethod.CARD);
order.setPayment(pay);
orderRepository.save(order); // ← cascades to items + payment

// Orphan removal: remove a line without explicit delete
Order managed = orderRepository.findById(order.getId()).orElseThrow();
managed.getItems().remove(0); // ← orphanRemoval will DELETE on flush
orderRepository.save(managed); // or just flush within @Transactional

// MERGE: re-attach category references
Product detached = new Product("Widget", BigDecimal.TEN, 100);
detached.getCategories().add(existingCategory); // existingCategory is detached
productRepository.save(detached); // cascade=MERGE re-attaches without duplicate row
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Risk if wrong |
|---|---|---|---|---|
| Order.items cascade | `ALL + orphanRemoval` | `PERSIST` only | Lines have no life outside order (composition) | Orphans accumulate; stale `order_id` rows |
| Order.payment cascade | `ALL` (no orphanRemoval) | `ALL+orphanRemoval` | Single-valued; null vs delete semantics differ | Accidental delete on nulling field |
| Customer cascades | `PERSIST` only | `ALL` | Don't delete orders when deleting customer; history + `@PreRemove` | `ALL` would silently destroy order history |
| Product.categories | `MERGE` only | `ALL`/`PERSIST` | Re-attach existing categories; don't insert duplicates | Duplicate category rows / unique violation |
| DB + JPA cascade together | Both | One or the other | JPA for app deletes, DB for direct SQL / other services | Direct SQL leaves orphans or violates FK |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — tests assert both "save cascades" and "remove from collection deletes".

---

## 7. How to verify

```bash
# Cascade persist + orphan removal integration tests
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
./mvnw test -Dtest=OrderServiceTest
# Expected: Tests run: N, Failures: 0, Errors: 0

# SQL-level: show child rows were cascaded
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT o.id, oi.id AS item_id, p.order_id AS payment_order
  FROM orders o LEFT JOIN order_items oi ON oi.order_id=o.id
                LEFT JOIN payments p ON p.order_id=o.id
  ORDER BY o.id LIMIT 10;"

# Prove orphanRemoval: remove item then check row gone
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT count(*) FROM order_items WHERE order_id=<orderId>; -- before
"
# (run Java removal code above)
# psql count after → decremented by 1

curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Use the cascade table (§2.2) to pick the right type per association. `ALL+orphanRemoval` for composition (Order→items), `PERSIST` for aggregation (Customer→orders), `MERGE` for re-attaching references (Product→categories). Always add bidirectional helpers.
- **Operate:** Check orphans: `SELECT * FROM order_items WHERE order_id NOT IN (SELECT id FROM orders)` should be zero. If non-zero, orphanRemoval or DB CASCADE is missing.
- **Interview:** "PR #4: `Order.items` is `ALL+orphanRemoval` — composition, removing from list deletes row; `Customer` is `PERSIST` only — plus `@PreRemove:99` guards against history loss; `Product.categories` is `MERGE` — re-attach. DB `CASCADE` (`01_create_tables.sql:120`) is orthogonal, fires on SQL deletes. Verified by saving aggregate in one `save()` and asserting child rows exist, then removing from collection and asserting row gone."

---

## 9. Interview lens — Q&A

**Q1: Difference between `cascade=REMOVE` and `orphanRemoval=true`?**
A: `REMOVE` fires when *parent* is deleted (`em.remove(order)` → deletes items). `orphanRemoval` fires when *child is removed from collection* while parent lives (`order.getItems().remove(i)` → deletes that one item even though order stays). See §2.4 table. `Order.java:82` needs both, so it uses `ALL+orphanRemoval`.

**Q2: Why doesn't Customer cascade REMOVE?**
A: Orders are history — deleting a customer must not silently destroy past orders. `Customer.java:58/67` use `PERSIST` only, and `Customer.java:99` `@PreRemove` throws if orders exist. The DB `ON DELETE CASCADE:95` would do it physically, but the business callback refuses first — defence in depth (§2.5).

**Q3: How verify without trusting migration?**
A: Tests persist the aggregate via `order.addItem()` + single `save()` then query `order_items` / `payments` tables to assert rows exist with correct FKs; then remove an item from the list, flush, and assert the row is gone in the DB. That proves JPA cascade + orphanRemoval, independent of migration text.

**Q4: What if you forget `orphanRemoval=true`?**
A: Removing from `order.getItems()` just breaks the in-memory link; Hibernate would try to set `order_items.order_id = NULL` (if nullable) or throw constraint violation (it is `NOT NULL` `OrderItem.java:29`). The row stays — orphan. With `orphanRemoval=true` it issues `DELETE`.

**Q5: Next step?**
A: PR #5 Fetch Types — make every association `LAZY` and reason about proxies vs eager graphs.

---

## 10. Honest limits & next step → PR #5

Composition cascades work for the aggregate, but *loading* is still naive: accessing `customer.getOrders()` fires one query per lazy collection if done wrong (N+1). No fetch tuning yet — every `LAZY` is still per-owner. PR #5 dissects `LAZY` vs `EAGER` and why LAZY is correct default, then PR #6/#7 fix N+1.

See [`05-fetch-types.md`](./05-fetch-types.md) or [`README.md`](./README.md).
