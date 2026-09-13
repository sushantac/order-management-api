# 02. Database Schema with Foreign Keys (PR #2)

> PR #2 — Database Schema with Foreign Keys. Stack: Java 21, Spring Boot, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers + PostgreSQL 16. See `README.md:1772` roadmap `| 2 | Database Schema with Foreign Keys |`.

---

## 1. Purpose — what shipped

PR #2 delivers **Database Schema with Foreign Keys** as a first-class, tested, documented building block. Eight tables are created by Liquibase (`src/main/resources/db/changelog/v1.0/01_create_tables.sql:1`, master `db.changelog-master.xml:1`): `customers`, `products`, `categories`, `addresses`, `orders`, `order_items`, `product_categories`, `payments`. Every FK has an explicit `ON DELETE` rule, every FK column has an index, and CHECK constraints guard money/stock/status invariants. `application.yml:28` now points at a real Postgres (docker-compose `postgresql:16`) and `application.yml:45` sets `ddl-auto: validate` — Liquibase owns the schema, Hibernate only validates.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** No tables. `psql \d` returns nothing; `DatabaseSchemaIntegrationTest` has nothing to assert; application boots with excluded `DataSourceAutoConfiguration`.

**After:** `README.md:1772` lists `| 2 | Database Schema with Foreign Keys |` as completed; `01_create_tables.sql:21` (`customers`), `01_create_tables.sql:62` (`addresses`), `01_create_tables.sql:82` (`orders`), `01_create_tables.sql:110` (`order_items`), `01_create_tables.sql:134` (`product_categories`), `01_create_tables.sql:151` (`payments`) all exist; FKs, unique and check constraints are verifiable via `\d` and `information_schema`; `DatabaseSchemaIntegrationTest.java:56` passes.

### Theory — foreign keys, delete rules and indexes from first principles

#### 2.1 What a foreign key *is* (relational theory)

- A FK is a *referential integrity* constraint: value in `child.fk_col` must exist in `parent.pk` (or be NULL if nullable). It is an invariant enforced by the *storage engine* on every `INSERT`/`UPDATE`/`DELETE`, regardless of which application wrote the row.
- Formally, if `R(child)` and `S(parent)`, then `π_{fk}(R) ⊆ π_{pk}(S) ∪ {NULL}`. The DB rejects any transaction that would violate the subset relation — *before* commit.
- **Why not just enforce in Java?** Application bugs, direct SQL (`psql`, ETL, migrations), concurrent writers, and multiple services all bypass Java. The DB is the *last line of defence* — defence in depth. `01_create_tables.sql:3` comment says exactly this.

```
  Application layer (JPA)          Database layer (FK)
  ─────────────────────           ────────────────────
  Order.setCustomer(c)  ───────►  orders.customer_id → customers.id
  Java checks optional=true        Postgres CHECKS on every write
  bypassable via SQL               unbypassable (enforced at commit)
```

#### 2.2 The four ON DELETE rules — a complete decision table

When a parent row is deleted, the DB must decide what happens to children. The repo uses three of the four deliberately:

| Rule | Semantics | Used in `01_create_tables.sql` | When to choose |
|---|---|---|---|
| `CASCADE` | Delete children automatically | `addresses.customer_id:75` `orders.customer_id:95` `order_items.order_id:120` `payments.order_id:164` | Child has no meaning without parent (order lines, addresses of a deleted customer) |
| `RESTRICT` | Refuse parent delete while children exist | `order_items.product_id:124` `product_categories.*:142` | Historical integrity (never delete a product referenced by an order) |
| `SET NULL` | Keep child, null the FK | `orders.shipping_address_id:97` `orders.billing_address_id:99` | Reference is optional snapshot (order survives even if address deleted) |
| `NO ACTION` | Like RESTRICT but checked at commit (deferred) | Not used here | Rare; needed for circular FKs with deferred constraints |

**Interview key:** `CASCADE` vs `RESTRICT` is a *business* decision, not technical. Ask "does the child have independent existence?" If no → CASCADE; if yes → RESTRICT/SET NULL.

```
 customers (parent)                addresses (child)        orders (child)
 ┌─────────────┐  CASCADE          ┌──────────────┐  SET NULL  ┌────────┐
 │ id=1 Alice  │─────────────────▶ │ customer_id=1│◀──────────│ ship_id│
 └─────────────┘  delete Alice     │ street=...   │  null it  │ = addr │
                  deletes addrs     └──────────────┘           └────────┘
                                    order_items (child)
 products (parent)  RESTRICT        ┌──────────────┐
 ┌─────────────┐  refuse!          │ product_id=5 │
 │ id=5 Widget │◀───────────────── │ order_id=10  │
 └─────────────┘  can't delete     └──────────────┘
                  while referenced
```

#### 2.3 Why every FK column needs an index (physical design)

- **Without index:** deleting a parent requires a *full table scan* of every child table to find referencing rows (to CASCADE or RESTRICT). That is `O(N_child)` I/O per delete — catastrophic at scale.
- **With index:** lookup is `O(log N)` via B-tree. Joins on FK columns are also fast — the FK *is* the join key.
- `01_create_tables.sql:181` creates seven indexes explicitly:

```sql
-- 01_create_tables.sql:181 — every FK column indexed
CREATE INDEX idx_addresses_customer_id          ON addresses (customer_id);          -- :182
CREATE INDEX idx_orders_customer_id             ON orders (customer_id);             -- :183
CREATE INDEX idx_orders_shipping_address_id     ON orders (shipping_address_id);     -- :184
CREATE INDEX idx_orders_billing_address_id      ON orders (billing_address_id);      -- :185
CREATE INDEX idx_order_items_order_id           ON order_items (order_id);           -- :186
CREATE INDEX idx_order_items_product_id         ON order_items (product_id);         -- :187
CREATE INDEX idx_product_categories_category_id ON product_categories (category_id); -- :188
-- Deliberately NOT indexed (comment :177):
--   product_categories.product_id — already leading column of PK (covers it)
--   payments.order_id — already UNIQUE constraint (which creates an index)
```

- **Duplicate-index trap:** adding an index on `(product_id)` when PK is `(product_id, category_id)` wastes disk + slows every `INSERT` (extra B-tree to maintain) for zero query benefit.

#### 2.4 Composite primary key vs surrogate key (product_categories)

- `product_categories:140` uses `PRIMARY KEY (product_id, category_id)` — a *natural composite PK* that doubles as: (a) uniqueness guard (no duplicate product-category rows), (b) index for `WHERE product_id=?` lookups, (c) FK target for nothing (join tables are never referenced). Alternative would be a surrogate `id BIGSERIAL` + separate unique constraint — extra 8 bytes/row + extra index for no gain.

```
 product_categories PK = (product_id, category_id)
 ┌────────────┬─────────────┐
 │ product 5  │ category 3  │  ← one row enforces uniqueness
 │ product 5  │ category 7  │
 │ product 9  │ category 3  │  B-tree ordered by product_id first
 └────────────┴─────────────┘  so WHERE product_id=5 is index-only
```

#### 2.5 CHECK constraints — invariants at the storage layer

| Constraint | Line | Guard |
|---|---|---|
| `ck_products_price_non_negative` | `01_create_tables.sql:42` | `price >= 0` — money never negative |
| `ck_products_stock_non_negative` | `01_create_tables.sql:43` | `stock_quantity >= 0` |
| `ck_orders_status` | `01_create_tables.sql:103` | `status IN ('PLACED',...)` — enum closed |
| `ck_orders_total_non_negative` | `01_create_tables.sql:105` | `total_amount >= 0` |
| `ck_order_items_quantity_positive` | `01_create_tables.sql:125` | `quantity > 0` |
| `ck_payments_amount_non_negative` | `01_create_tables.sql:165` | `amount >= 0` |

- Even hand-written `INSERT` via `psql` cannot violate these. JPA `@Column` nullable/precision checks are *advisory*; CHECK is *enforced*.

#### 2.6 Liquibase vs Flyway vs Hibernate ddl-auto — tradeoff

| Approach | Versioned | Reviewable | Rollback | Risk |
|---|---|---|---|---|
| Liquibase (`db.changelog-master.xml:1` + `01_*.sql:1`) | Yes (changesets) | Yes (SQL in PR) | Per-changeset `rollback` | Extra file |
| Flyway | Yes (V1__*.sql) | Yes | Undo (paid) | Similar |
| `hibernate.ddl-auto: update/create` | No | No | None | Dev-only; may drop data |

- `application.yml:48` `ddl-auto: validate` is the production choice: Liquibase migrates, Hibernate validates mapping matches DB — never auto-alters.

#### 2.7 Identity vs Sequence generations

- `01_create_tables.sql:22` `BIGINT GENERATED BY DEFAULT AS IDENTITY` → `BaseEntity.java:50` `GenerationType.IDENTITY`. Postgres generates the id on `INSERT` (sequence behind the scenes). Hibernate does *not* pre-allocate; it needs the row to be inserted to know the id. Tradeoff: simple, but batch inserts cannot pre-allocate ids (relevant for performance tuning).

#### 2.8 Interview-ready mental model

> "FKs are subset invariants enforced at commit, not advisory. CASCADE means child has no standalone life (addresses, order_items); RESTRICT protects history (products referenced by orders); SET NULL keeps the aggregate but unlinks an optional reference (order addresses). Every FK column is indexed or the delete becomes a full scan — the repo indexes seven FKs explicitly and avoids duplicates where PK/UNIQUE already covers. CHECKs close the enum and money invariants. Liquibase owns migration, Hibernate only validates (`ddl-auto: validate`)."

---

## 3. Solution — ASCII (which FK points where)

```
                         ┌─────────────┐
                         │  customers  │  PK id
                         └──────┬──────┘
                CASCADE         │         CASCADE
              ┌─────────────────┼─────────────────┐
              ▼                 │                 ▼
     ┌────────────────┐  CASCADE│        ┌────────────────┐
     │   addresses    │◀────────┼───────▶│    orders      │
     │ PK id          │         │        │ PK id          │
     │ FK customer_id │         │        │ FK customer_id │
     │  ON DELETE     │         │        │ FK ship/bill   │──SET NULL──▶ addresses.id
     │  CASCADE       │         │        │  ON DELETE     │
     └────────────────┘         │        │  SET NULL      │
                                │        └───────┬────────┘
                                │                │ CASCADE
                                │                ▼
                                │        ┌────────────────┐
                                │        │  order_items   │
                                │        │ FK order_id    │──CASCADE
                                │        │ FK product_id  │──RESTRICT──▶ products.id
                                │        └────────────────┘
                                │                │
                                │        ┌───────┴────────┐
                                │        │ product_cats   │  PK(product_id,category_id)
                                │        │ FK product_id  │──RESTRICT──▶ products.id
                                │        │ FK category_id │──RESTRICT──▶ categories.id
                                │        └────────────────┘
                                │        ┌────────────────┐
                                │        │   payments     │
                                │        │ FK order_id    │──CASCADE (UNIQUE → 1:1)
                                │        │  ON DELETE     │
                                │        └────────────────┘
```

Tagline: `↑ Database Schema with Foreign Keys added at PR #2` — extends PR #1 skeleton with real tables.

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/db/changelog/db.changelog-master.xml` | `1` | Liquibase master | Includes `v1.0/01_create_tables.sql` |
| `src/main/resources/db/changelog/v1.0/01_create_tables.sql` | `1` | Migration header | `--liquibase formatted sql`, one changeset per table |
| `01_create_tables.sql` | `20` | `customers` | Root, no FK, `uq_customers_email UNIQUE(email)` |
| `01_create_tables.sql` | `34` | `products` | Root, `CHECK price/stock >=0` |
| `01_create_tables.sql` | `50` | `categories` | Root, `uq_categories_name` |
| `01_create_tables.sql` | `62` | `addresses` | FK `customer_id → customers ON DELETE CASCADE:75` |
| `01_create_tables.sql` | `82` | `orders` | Three FKs: `customer CASCADE:95`, `ship SET NULL:97`, `bill SET NULL:99`; `CHECK status:103` |
| `01_create_tables.sql` | `110` | `order_items` | Composite FKs: `order CASCADE:120`, `product RESTRICT:124` |
| `01_create_tables.sql` | `134` | `product_categories` | Join table, composite PK `140`, both FKs RESTRICT `142` |
| `01_create_tables.sql` | `151` | `payments` | `UNIQUE(order_id):162` enforces 1:1, FK `CASCADE:164` |
| `01_create_tables.sql` | `181` | FK indexes | Seven `CREATE INDEX` stmts `182-188`; comments `177` explain omitted duplicates |
| `src/main/resources/application.yml` | `28` | Datasource | `jdbc:postgresql://${DB_HOST}` |
| `src/main/resources/application.yml` | `37` | Liquibase | `change-log: classpath:db/changelog/db.changelog-master.xml` |
| `src/main/resources/application.yml` | `48` | `ddl-auto: validate` | Liquibase owns schema, Hibernate validates |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `50` | `@GeneratedValue(IDENTITY)` | Matches `GENERATED AS IDENTITY` in every table |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Queries `information_schema` / JDBC metadata |
| `docker-compose.yml` | `1` | Postgres 16 | Service `postgres:16`, port `5432`, db `orderdb` |

```sql
-- 01_create_tables.sql:22 — identity matches BaseEntity.java:50 IDENTITY
CREATE TABLE customers (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    CONSTRAINT uq_customers_email UNIQUE (email)
);

-- 01_create_tables.sql:73 — CASCADE: addresses die with customer
CONSTRAINT fk_addresses_customer
    FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE

-- 01_create_tables.sql:94 — the three FK flavours in one table
CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
CONSTRAINT fk_orders_shipping_address FOREIGN KEY (shipping_address_id) REFERENCES addresses (id) ON DELETE SET NULL,
CONSTRAINT fk_orders_billing_address  FOREIGN KEY (billing_address_id)  REFERENCES addresses (id) ON DELETE SET NULL

-- 01_create_tables.sql:123 — RESTRICT protects historical orders
CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run DB + app
docker-compose up -d postgres
./mvnw spring-boot:run &

# Inspect tables + FKs (real DB state, not just migration file)
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d customers"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d addresses"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d order_items"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d product_categories"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d payments"

# Show all FK constraints
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT conname, contype, confdeltype,
         pg_get_constraintdef(oid)
  FROM pg_constraint WHERE contype='f' ORDER BY conname;"

# Show indexes on FK columns
psql "postgresql://order:order@localhost:5432/orderdb" -c "\di idx_*"

# Liquibase history
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT id, author, filename FROM databasechangelog ORDER BY dateexecuted;"

# Hibernate validate proof (no ddl-auto update)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest -Dspring.jpa.hibernate.ddl-auto=validate
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen |
|---|---|---|---|
| FK enforcement | DB-level FKs (every table) | JPA-only `@JoinColumn` | Last line of defence; catches direct SQL + bugs |
| ON DELETE customer→orders | CASCADE | RESTRICT | Orders have no meaning without customer in this model; matches `Customer.java:99` `@PreRemove` guard |
| ON DELETE order→product | RESTRICT | CASCADE | Never delete product still referenced — history must stay intact |
| ON DELETE order→address | SET NULL | CASCADE/RESTRICT | Order is legal even after address deleted; just unlinks |
| product_categories PK | Composite `(product_id, category_id)` | Surrogate `id` | Uniqueness + index in one B-tree, fewer bytes |
| Index every FK | Explicit `CREATE INDEX` 7× | Rely on auto | Postgres does NOT auto-index FKs (unlike MySQL); without index deletes scan |
| Liquibase | Formatted SQL changelogs | Hibernate `ddl-auto` | Reviewable, versioned, rollback per changeset |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — FKs are asserted via JDBC metadata, not by reading the migration.

---

## 7. How to verify

```bash
# Integration test — asserts tables, FKs, indexes exist via JDBC
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Tests: shouldCreateAllTables, shouldEnforceForeignKeys, shouldHaveIndexes

# Manual: FK exists
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  SELECT tc.constraint_name, tc.table_name, kcu.column_name, ccu.table_name AS foreign_table
  FROM information_schema.table_constraints tc
  JOIN information_schema.key_column_usage kcu ON tc.constraint_name=kcu.constraint_name
  JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name=tc.constraint_name
  WHERE tc.constraint_type='FOREIGN KEY' ORDER BY tc.table_name;"

# Manual: RESTRICT actually blocks
psql "postgresql://order:order@localhost:5432/orderdb" -c "
  INSERT INTO products (name, price, stock_quantity) VALUES ('__probe', 9.99, 10) RETURNING id;
  INSERT INTO order_items (order_id, product_id, quantity, unit_price, total_price)
  VALUES (1, <probe_id>, 1, 9.99, 9.99);
  DELETE FROM products WHERE id=<probe_id>; -- should fail: violates fk_order_items_product
"

# Prometheus still healthy
curl -s http://localhost:8080/actuator/prometheus | grep jvm_
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Copy FK delete-rule table (§2.2) for your own schema design review. Always index FK columns — check `\di` before shipping.
- **Operate:** One `psql \d` + `pg_constraint` query proves schema; `databasechangelog` table is deploy audit trail. Use `ddl-auto: validate` in prod — never `update`.
- **Interview:** "PR #2: Liquibase owns 8 tables; FKs use CASCADE (child has no life), RESTRICT (protect history), SET NULL (optional snapshot). Seven explicit indexes because Postgres doesn't auto-index FKs — without them deletes scan. Verified by `DatabaseSchemaIntegrationTest.java:56` against `information_schema`, not migration text."

---

## 9. Interview lens — Q&A

**Q1: Why CASCADE for `orders.customer_id` but RESTRICT for `order_items.product_id`?**
A: Business semantics (§2.2). An order without its customer is meaningless → CASCADE. But an order line referencing a product *is* history — deleting the product must not silently destroy past orders → RESTRICT. The DB refuses with `violates foreign key fk_order_items_product`.

**Q2: How verify without trusting migration?**
A: `DatabaseSchemaIntegrationTest.java:56` introspects `information_schema.table_constraints` + `pg_constraint` + `pg_index` — it checks the *live* catalog, not the SQL file. And `psql \d orders` shows FKs + indexes directly.

**Q3: Why not let Hibernate create the schema (`ddl-auto: update`)?**
A: `ddl-auto: update` is non-versioned, non-reviewable, and can silently drop columns. Liquibase (`01_create_tables.sql:1` changesets with `rollback`) is versioned, code-reviewed, and `ddl-auto: validate` (`application.yml:48`) guarantees entities match DB without migrating.

**Q4: What's the cost of the seven indexes?**
A: Extra disk + slower `INSERT`/`UPDATE` (each index B-tree must be maintained). But without them every parent delete and every FK join is a full scan. Duplicate indexes (e.g. on `product_categories.product_id` already covered by PK) would add cost for zero benefit — deliberately omitted (`01_create_tables.sql:177`).

**Q5: Next step?**
A: PR #3 JPA Entities with Foreign Key Mappings — add `Customer.java:28` `@ManyToOne`, `Order.java:46` mappings that mirror these FKs in Java.

---

## 10. Honest limits & next step → PR #3

Not end-to-end; that is PR #3. Schema exists but no JPA entities map it yet — Hibernate validates but no `@Entity` relationships are navigable. Next PR adds `Address.java:28` owning-side `@ManyToOne`, `Order.java:82` `mappedBy` inverse sides, and `Product.java:54` `@ManyToMany` join table.

See [`03-jpa-entities-with-foreign-key-mappings.md`](./03-jpa-entities-with-foreign-key-mappings.md) or [`README.md`](./README.md).
