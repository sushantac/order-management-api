# 15. SQL Logging and Debugging (PR #15)

> PR #15 — SQL Logging and Debugging: `show_sql`, `format_sql`, `use_sql_comments`, `generate_statistics`, `org.hibernate.SQL` / `orm.jdbc.bind` / `stat`, slow-query threshold, and prod silencing. Stack: Java 21, Spring Boot 3.x, Hibernate 6.6, PostgreSQL 16, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 15 | SQL Logging and Debugging |`.

---

## 1. Purpose — what shipped

PR #15 makes every SQL statement **visible, attributable, and measurable**. One YAML block (`application.yml:43-56,168-177`) plus three Hibernate properties gives: (1) the SQL text on `DEBUG` (`show_sql` + `org.hibernate.SQL: DEBUG`), (2) pretty-printed multi-line SQL (`format_sql: true`), (3) the originating JPQL / call-site as an inline `/* comment */` (`use_sql_comments: true`), (4) bind-parameter values (`org.hibernate.orm.jdbc.bind: TRACE`), (5) session/query statistics (counts, cache hits, flush time) via `generate_statistics: true` + `org.hibernate.stat: DEBUG`. `application-prod.yml:14-24` disables all of it for production. The feature is purely observability — zero schema or API change — but it turns N+1, missing `JOIN FETCH`, and slow queries from invisible to grep-able.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** A service returned slowly. Logs showed only `INFO` controller timings. No SQL was emitted, no bind values, no statistics. Whether the method executed 1 query or 101 (N+1) was unanswerable without a debugger or `pg_stat_statements`. Slow queries hid behind the ORM.

**After:** Same request with `application.yml:43` (`show_sql: true`) + `logging.level.org.hibernate.SQL: DEBUG` (`application.yml:175`) emits each SQL. `format_sql: true` (`application.yml:52`) makes it readable. `use_sql_comments: true` (`application.yml:54`) prepends `/* load com.company.orderapi.domain.Product */`. `org.hibernate.orm.jdbc.bind: TRACE` (`application.yml:176`) logs every `binding parameter [1] as [BIGINT] - [42]`. `generate_statistics: true` (`application.yml:56`) + `org.hibernate.stat: DEBUG` (`application.yml:177`) emits `Session Metrics { 25600 nanoseconds spent acquiring 1 JDBC connections; 12300 ns acquiring ...; 7 JDBC statements; ... }` and `Statistics: second level cache hits 0, misses 1`. Slow-query detection is added on top (threshold-based logging). Production silences it (`application-prod.yml:15,18,22-24` all `false/WARN`).

### Theory — SQL observability from first principles (100+ lines)

#### 2.1 What Hibernate actually logs — and where

Hibernate has three independent logging channels that PR #15 wires together:

```
JDBC execution path:
  Session → QueryTranslator → SQL string ─┬─→ org.hibernate.SQL  (the SQL text)
                                         ├─→ use_sql_comments → /* original HQL + caller */ prefix
                                         ├─→ org.hibernate.orm.jdbc.bind (each ? value)
                                         └─→ org.hibernate.stat (counts / timings / cache)
                                                            ↓
                                                     application log (Logback)
```

| Knob | File:line | What it does | Logger/category |
|---|---|---|---|
| `spring.jpa.show-sql: true` | `application.yml:44` | Prints SQL to stdout via `System.out` fallback (legacy). Real log line comes from `org.hibernate.SQL` logger | `org.hibernate.SQL` |
| `hibernate.format_sql: true` | `application.yml:52` | Pretty-prints SQL with newlines/indentation before logging | same `org.hibernate.SQL` line |
| `hibernate.use_sql_comments: true` | `application.yml:54` | Prepends `/* criteria query */` or `/* load com.company.orderapi.domain.Product */` comment so `pg_stat_statements` and log grep show the call site | Inline in SQL string |
| `hibernate.generate_statistics: true` | `application.yml:56` | Collects `SessionStatistics` + `Statistics` (statement count, entity/collection fetch count, L2 hit/miss, flush time, connection acquisition time) | `org.hibernate.stat: DEBUG` |
| `logging.level.org.hibernate.SQL: DEBUG` | `application.yml:175` | Enables the SQL logger (without it `show-sql` alone goes to console, not the log file) | Logback |
| `logging.level.org.hibernate.orm.jdbc.bind: TRACE` | `application.yml:176` | Logs every bind: `binding parameter [1] as [VARCHAR] - [ORD-ABC]` | Logback |
| `logging.level.org.hibernate.stat: DEBUG` | `application.yml:177` | Logs `Session Metrics` and `Statistics` per transaction | Logback |

All six must align for full visibility. Missing any one leaves a blind spot (SQL without binds, or `format_sql` without logger).

#### 2.2 `show_sql` vs `org.hibernate.SQL: DEBUG` — why both

`show-sql: true` is a Spring Boot convenience: Hibernate writes to `System.out` via `StandardOutLogger`. It bypasses Logback levels, file appenders, JSON layout, and sampling — useful in tests run via `./mvnw test` console but noisy. `org.hibernate.SQL: DEBUG` via Logback is the production-grade channel: it respects levels, can be toggled per profile (`application-prod.yml:22` → `WARN`), and is captured by `logging pattern` / OTEL log export. PR #15 sets **both** so tests show SQL on console and the logger controls what reaches files. The important mental model: `show-sql` is a *writer*, `org.hibernate.SQL` is a *logger*.

#### 2.3 `format_sql: true` — why pretty-print matters

Raw SQL: `select o1_0.id,o1_0.created_at,o1_0.customer_id from orders o1_0 where o1_0.customer_id=?`. With `format_sql` Hibernate inserts `\n` and indentation via `org.hibernate.engine.jdbc.internal.FormatStyle.BASIC`. When grepping logs or pasting into `psql EXPLAIN`, formatted SQL is diffable and explainable. Cost: trivial CPU (formatting happens only when the logger is enabled). Disabled in prod (`application-prod.yml` does not enable it) because prod should not log SQL at all.

#### 2.4 `use_sql_comments: true` — attributing SQL to code

With `use_sql_comments`, Hibernate prefixes the SQL with the HQL/JPQL source:

```sql
/* load com.company.orderapi.domain.Product */ select p1_0.id, ... from products p1_0 where p1_0.id=?
/* criteria query */ select distinct c1_0.id from customers c1_0 join addresses ...
```

This comment survives into PostgreSQL `pg_stat_statements.query` and `pg_stat_activity.query`, so `SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC` immediately shows which repository method is slow without correlating logs. Combined with `orderRepository.findRecentOrdersByCustomer` (`OrderRepository.java:55`) style JPQL, the comment reveals call-site without stack-trace overhead.

#### 2.5 `org.hibernate.orm.jdbc.bind: TRACE` — the bind values

SQL without binds is `where customer_id=?` — not reproducible in `psql`. With `bind: TRACE`:

```
2026-09-12 10:01:23 TRACE org.hibernate.orm.jdbc.bind - binding parameter [1] as [BIGINT] - [1]
2026-09-12 10:01:23 TRACE org.hibernate.orm.jdbc.bind - binding parameter [2] as [VARCHAR] - [PLACED]
```

Now `psql` reproduction is `SELECT ... WHERE customer_id=1 AND status='PLACED' ...`. Security note: binds may contain PII (email `customers.email`, phone). TRACE level is disabled in prod (`application-prod.yml:23` `WARN`) to avoid logging PII. In dev/test it is invaluable for reproducing constraint violations (`value too long for type character varying(40)` at `Order.java:64`) with the actual value.

#### 2.6 `generate_statistics` + `org.hibernate.stat` — quantifying N+1

Statistics output per session/transaction:

```
Session Metrics {
  42000 nanoseconds spent acquiring 1 JDBC connections;
  15000 ns spent releasing 1 JDBC connections;
  2300000 ns spent preparing 7 JDBC statements;
  1800000 ns spent executing 7 JDBC statements;
  310000 ns spent flushing
}
Statistics [
  second level cache puts 0, hits 0, misses 0,
  entities fetched 7, collections fetched 6, queries executed 7
]
```

Reading it: `queries executed 7` for a `findAll()` that should be 1 → N+1 is 6 extra queries. `collections fetched 6` confirms lazy collections fired per parent. PR #7 batch fetching (`application.yml:61` `default_batch_fetch_size: 20`) reduces that number; PR #6 `JOIN FETCH` (`CustomerRepository.java:61`) reduces to 1. Statistics make the fix provable: before/after numbers in logs. Cost: small `System.nanoTime()` overhead per operation; disabled in prod (`application-prod.yml:18` `false`) for that reason.

#### 2.7 Slow-query detection — threshold logging

Beyond per-statement logging, a slow-query logger flags statements exceeding a threshold (e.g., `hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS` or a `DataSource` proxy like `datasource-proxy`/`p6spy` or Hikari `slowQueryThreshold`). Pattern: wrap `DataSource` with `ProxyDataSourceBuilder` that logs `executionTime > 50ms` as `WARN`. In this repo the statistics Nash timing (`session.events.log`) plus `HikariCP` `leakDetectionThreshold` and pool metrics serve. For PostgreSQL, `log_min_duration_statement = 100ms` on the server correlates. Interview point: threshold logging converts "is this query slow?" from post-mortem `EXPLAIN` to automatic `WARN` with SQL + binds + stack.

#### 2.8 Profile split — dev/test vs prod

```
application.yml:43-56,175-177  show_sql true, format_sql true, use_sql_comments true,
                               generate_statistics true, SQL DEBUG, bind TRACE, stat DEBUG
                               → default (tests, dev)

application-prod.yml:15,18,22-24  show_sql false, generate_statistics false, SQL WARN, bind WARN, stat WARN
                                  → production: no PII in logs, no stats overhead
```

Profile activation: `SPRING_PROFILES_ACTIVE=prod` (K8s) or `application-prod.yml` included via `spring.profiles.include`. Tests run on default `application.yml` so CI sees SQL and statistics, catching N+1 regressions.

#### 2.9 Alternatives — p6spy, datasource-proxy, pg_stat_statements

- `p6spy` intercepts JDBC and logs with execution time + stack — heavier but adds timing per SQL. Alternative to Hibernate logging.
- `datasource-proxy` (Spring) wraps `DataSource`, logs `query + time + success` without Hibernate internals.
- `pg_stat_statements` extension on Postgres normalizes queries, counts calls, mean time — server-side, survives app restart. Complement, not replacement, because it loses Hibernate's entity-level statistics and bind values.
PR #15 chooses Hibernate-native logging because it is zero-dependency, shows HQL comments, and statistics integrate with batch-fetch verification.

#### 2.10 Interview-ready mental model

> "PR #15 is `application.yml:43-56,175-177`: `show_sql: true` + `org.hibernate.SQL: DEBUG` emits SQL; `format_sql: true` pretty-prints; `use_sql_comments: true` tags each SQL with the HQL/entity that generated it so `pg_stat_statements` attributes slow queries; `orm.jdbc.bind: TRACE` logs bind values so `psql` reproduction is copy-paste; `generate_statistics: true` + `org.hibernate.stat: DEBUG` emits per-session statement counts confirming N+1 (7 queries instead of 1) and L2 hit/miss. Prod (`application-prod.yml:15,18,22-24`) silences all to avoid PII and overhead."

---

## 3. Solution — ASCII

```
Request → Controller → Service (@Transactional) → Repository → Hibernate → JDBC → PostgreSQL
                          │                           │
                          │                           ├─ format_sql: true          (application.yml:52)  → pretty SQL
                          │                           ├─ use_sql_comments: true    (application.yml:54)  → /* load Product */ prefix
                          │                           ├─ generate_statistics: true (application.yml:56)  → counts/timings
                          │                           │
                          └─ logging.level ───────────┼─ org.hibernate.SQL: DEBUG       (application.yml:175) → SQL text
                                                      ├─ org.hibernate.orm.jdbc.bind: TRACE (176) → bind values
                                                      └─ org.hibernate.stat: DEBUG      (177) → Session Metrics / Statistics

Profile gate:
  application.yml:43-56,175-177  (default/test/dev) → all DEBUG/TRACE/true  → full visibility
  application-prod.yml:15,18,22-24 (prod)          → all WARN/false        → silence + no overhead

Flow for N+1 proof (Customer findAll without JOIN FETCH):
  logs: 1× select customers + 20× select addresses → Statistics: queries executed 21, collections fetched 20
  fix: CustomerRepository.java:61  JOIN FETCH → logs: 1 query → Statistics: queries executed 1

Slow query path:
  SQL WARN threshold (>50ms) → log: "slow query 120ms: /* criteria query */ select ..." + binds
  correlate: pg_stat_statements mean_exec_time + EXPLAIN ANALYZE in psql
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/resources/application.yml` | `43-44` | `show-sql: true` + `open-in-view: false` | `show-sql` prints SQL; `open-in-view: false` ensures session closes at service boundary so N+1 is visible |
| `application.yml` | `52` | `format_sql: true` | `FormatStyle.BASIC` pretty-print |
| `application.yml` | `54` | `use_sql_comments: true` | Prefix `/* load ... */` attributed to caller |
| `application.yml` | `56` | `generate_statistics: true` | Enables `Statistics` + `Session Metrics` |
| `application.yml` | `61,64` | `default_batch_fetch_size: 20`, `jdbc.fetch_size: 100` | Batch-fetch knob (PR #7) and streaming knob — observable via statistics |
| `application.yml` | `68-78` | L2 cache `use_second_level_cache: true`, `factory_class: jcache` | Statistics `hits/misses` include L2 — PR #15 + PR #16 together |
| `application.yml` | `168-177` | `logging.level` block | `com.company.orderapi: DEBUG`, `org.hibernate.SQL: DEBUG`, `orm.jdbc.bind: TRACE`, `org.hibernate.stat: DEBUG` |
| `src/main/resources/application-prod.yml` | `14-24` | Prod silencing | `show-sql: false`, `generate_statistics: false`, all `WARN` |
| `src/main/resources/application-dev.yml` | — | Dev override (inherits `application.yml` debug) | Keeps SQL logging on for local docker-compose |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof that context loads with validate | Logs show SQL during test bootstrap |
| `src/main/java/com/company/orderapi/domain/Order.java` | `42,64` | Entity mapping validated by PR #14 | SQL columns `orders.order_number VARCHAR(40)` appear in logged INSERTs |

```java
// application.yml:43-56 — the PR #15 block
jpa:
  show-sql: true              // application.yml:44
  hibernate.ddl-auto: validate
  properties:
    hibernate:
      format_sql: true        // application.yml:52
      use_sql_comments: true  // application.yml:54
      generate_statistics: true // application.yml:56

// application.yml:168-177 — logger levels
logging:
  level:
    org.hibernate.SQL: DEBUG          // 175 — SQL text
    org.hibernate.orm.jdbc.bind: TRACE // 176 — bind values
    org.hibernate.stat: DEBUG         // 177 — Session Metrics / Statistics

// application-prod.yml:14-24 — silence + no overhead
jpa.show-sql: false
hibernate.generate_statistics: false
logging.level.org.hibernate.SQL: WARN
logging.level.org.hibernate.orm.jdbc.bind: WARN
logging.level.org.hibernate.stat: WARN
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Run with SQL logging (default profile shows SQL + binds + statistics)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dspring.profiles.active=test 2>&1 | grep -E "org.hibernate.SQL|binding parameter|Session Metrics|Statistics"

# Show only the formatted SQL for a single test
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.SQL=DEBUG 2>&1 | grep -A2 "select.*from.*orders"

# Verify bind values appear (copy-paste into psql)
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 | grep "binding parameter"

# Statistics: prove N+1 vs JOIN FETCH (before/after counts)
./mvnw test -Dtest=CustomerRepositoryTest 2>&1 | grep -E "queries executed|collections fetched"
# Without JOIN FETCH: queries executed 21; with CustomerRepository.java:61 JOIN FETCH: 1

# Show HQL comments attributing SQL to code
./mvnw test 2>&1 | grep "/\*"
# Expect: /* load com.company.orderapi.domain.Product */ select ...

# Verify prod silencing (no SQL on WARN)
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run 2>&1 | grep "org.hibernate.SQL" | head
# Expect: nothing (WARN)

# Slow-query / pool insight via Hikari + statistics
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 5;"

# Full smoke with all SQL channels
./mvnw test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest -Dorg.hibernate.SQL=DEBUG -Dorg.hibernate.orm.jdbc.bind=TRACE -Dorg.hibernate.stat=DEBUG 2>&1 | tail -n 80

# Check config values
grep -A2 "show-sql\|format_sql\|use_sql_comments\|generate_statistics" src/main/resources/application.yml | head -n 20
grep -A1 "org.hibernate" src/main/resources/application.yml | head -n 10
```

```java
// Programmatic access to statistics inside a test (alternative to log grep)
@Autowired SessionFactory sessionFactory;
Statistics stats = sessionFactory.getStatistics();
stats.setStatisticsEnabled(true);
stats.clear();
orderService.placeOrder(1L, lines);
assertThat(stats.getQueryExecutionCount()).isEqualTo(expectedQueries);
assertThat(stats.getEntityFetchCount()).isLessThan(nPlusOneThreshold);
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Hibernate-native logging (6 knobs) | `application.yml:43-56,175-177` | `p6spy` / `datasource-proxy` | Zero extra dep, shows HQL comments + Hibernate statistics + cache hits, no JDBC proxy overhead | Log volume in dev/test; silenced in prod |
| `format_sql: true` | On in default profile | Raw one-line SQL | Pasting into `psql EXPLAIN` is immediate; diffable in code review | Negligible CPU only when logger enabled |
| `use_sql_comments: true` | On | Off | `pg_stat_statements` query attribution without stack trace; correlates server-side slow query to repository method `OrderRepository.java:55` | Few bytes per SQL |
| `generate_statistics: true` + `stat: DEBUG` | On in default | Off except when debugging | Makes `queries executed N` grep-able → N+1 provable in CI log | `System.nanoTime()` per operation; disabled in prod `application-prod.yml:18` |
| `orm.jdbc.bind: TRACE` | On in default | Off / `DEBUG` only | Reproduction in `psql` without guessing binds; diagnoses `value too long` at `Order.java:64` with actual value | PII leaks into logs → disabled in prod `application-prod.yml:23` |
| `show-sql: true` + `SQL: DEBUG` both | Both | Only one | Console for tests (`show-sql`), Logback for files/OTEL (`SQL: DEBUG`); together cover all appenders | Duplicate line on console in tests — acceptable |
| Prod silence | `show-sql: false`, `generate_statistics: false`, `WARN` | Keep DEBUG in prod | Avoid PII (email/phone binds), avoid stats overhead, keep log volume low | Lose SQL visibility in prod — rely on `pg_stat_statements` + sampled tracing |

---

## 7. How to verify

```bash
# Config proof: dev/test enable, prod disables
grep -n "show-sql\|format_sql\|use_sql_comments\|generate_statistics" src/main/resources/application.yml src/main/resources/application-prod.yml

# SQL text appears
./mvnw test -Dtest=OrderServiceTest 2>&1 | grep "org.hibernate.SQL" | head -n 5
# Expect: Hibernate: select ... from orders ... / insert into orders ...

# Binds appear
./mvnw test -Dtest=OrderServiceTest -Dorg.hibernate.orm.jdbc.bind=TRACE 2>&1 | grep "binding parameter" | head -n 5
# Expect: binding parameter [1] as [VARCHAR] - [ORD-...]

# Comments appear
./mvnw test 2>&1 | grep "/\* load" | head -n 3
# Expect: /* load com.company.orderapi.domain.Product */

# Statistics appear
./mvnw test -Dtest=OrderServiceTest 2>&1 | grep -E "Session Metrics|Statistics\[" | head -n 5
# Expect: Session Metrics { ... } and Statistics [ queries executed ... ]

# Prod profile silences
SPRING_PROFILES_ACTIVE=prod ./mvnw test -Dtest=DatabaseSchemaIntegrationTest 2>&1 | grep "org.hibernate.SQL" | wc -l
# Expect: 0

# Hikari pool not leaking (complements SQL timing)
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Keep `application.yml:43-56,175-177` on for every local/test run. Grep `queries executed` to prove each new repository method uses `JOIN FETCH` (`CustomerRepository.java:61`) or `default_batch_fetch_size: 20` (`application.yml:61`) instead of N+1. Use `use_sql_comments: true` (`application.yml:54`) so `pg_stat_statements` points to `OrderRepository.java:55` without asking.
- **Operate:** In prod logs are `WARN` (`application-prod.yml:22-24`) — use `pg_stat_statements` + `EXPLAIN ANALYZE` + sampled trace/OTEL. Set `log_min_duration_statement=50ms` on Postgres and Hikari `slowQueryThreshold` to get slow-query WARN with SQL+binds. Alert on `hikaricp.connections.pending` and `statistics queryExecutionCount` spike.
- **Interview:** "PR #15: `application.yml:44,52,54,56,175-177` — `show_sql: true` + `SQL: DEBUG` emits SQL, `format_sql: true` pretty-prints, `use_sql_comments: true` tags `/* load Product */` so `pg_stat_statements` attributes, `orm.jdbc.bind: TRACE` logs `binding parameter [1]=42` for `psql` reproduction, `generate_statistics: true` + `stat: DEBUG` emits `Session Metrics`/`queries executed N` proving N+1 is fixed (`CustomerRepository.java:61` → 1 query). Prod `application-prod.yml:15,18,22-24` disables all (PII/overhead). Verified by `grep binding parameter` + `queries executed` in CI logs."

---

## 9. Interview lens — Q&A

**Q1: What six knobs does PR #15 set and what does each do?**
A: `show-sql: true` (`application.yml:44`) + `org.hibernate.SQL: DEBUG` (`175`) — SQL text; `format_sql: true` (`52`) — pretty-print; `use_sql_comments: true` (`54`) — `/* HQL */` prefix; `generate_statistics: true` (`56`) + `org.hibernate.stat: DEBUG` (`177`) — `Session Metrics`/`Statistics`; `orm.jdbc.bind: TRACE` (`176`) — bind values. See §2.1 table.

**Q2: Why `use_sql_comments` and where does the comment go?**
A: Hibernate prepends `/* load com.company.orderapi.domain.Product */` or `/* criteria query */` to the SQL (`application.yml:54`). It appears in logs and survives into `pg_stat_statements.query` / `pg_stat_activity`, attributing slow queries to the repository method without a stack trace (§2.4).

**Q3: How do you prove N+1 is fixed with statistics?**
A: `generate_statistics: true` + `stat: DEBUG` emits `queries executed 21` for naive `findAll()` vs `1` after `JOIN FETCH` (`CustomerRepository.java:61`) or batch size `20` (`application.yml:61`). Grep CI log for `queries executed` (§2.6).

**Q4: How do you reproduce a logged query in `psql`?**
A: Copy SQL from `org.hibernate.SQL` + binds from `orm.jdbc.bind: TRACE` (`binding parameter [1] as [BIGINT] - [1]`) and substitute `?`. TRACE is disabled in prod (`application-prod.yml:23`) to avoid PII (§2.5).

**Q5: How are slow queries surfaced?**
A: Threshold logging (`slowQueryThreshold` / `datasource-proxy` / `log_min_duration_statement`) emits `WARN` when `executionTime > 50ms` with SQL+binds; `pg_stat_statements.mean_exec_time` correlates server-side; `EXPLAIN ANALYZE` confirms (§2.7).

**Q6: What is disabled in production and why?**
A: `application-prod.yml:15,18,22-24` — `show-sql: false`, `generate_statistics: false`, all `WARN`. Avoids PII in logs (email/phone binds), `System.nanoTime` overhead, and log volume (§2.8).

---

## 10. Honest limits & next step → PR #16

Logging shows *what* queries ran, not *why* the planner chose a scan — `EXPLAIN ANALYZE` and `pg_stat_statements` are still needed for index/plan analysis. Bind TRACE can leak PII if not gated to `WARN` in prod. Statistics overhead is small but not zero. PR #16 adds the second-level cache (`application.yml:68-78`, `Product.java:33-34`) whose `hits/misses` are visible precisely because this PR's `stat: DEBUG` is on — the cache's value is proven by the statistics this PR emits.

See [`16-second-level-cache.md`](./16-second-level-cache.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Knob | File:line | When |
|---|---|---|---|
| See SQL text | `show-sql: true` + `SQL: DEBUG` | `application.yml:44,175` | Always in dev/test |
| Pretty SQL for EXPLAIN | `format_sql: true` | `application.yml:52` | Dev/test |
| Attribute SQL to code | `use_sql_comments: true` | `application.yml:54` | Dev/test + `pg_stat_statements` |
| Reproduce with binds | `orm.jdbc.bind: TRACE` | `application.yml:176` | Dev/test only — PII |
| Prove N+1 fixed | `generate_statistics: true` + `stat: DEBUG` | `application.yml:56,177` | Dev/test, CI gate |
| Silence prod | `show-sql: false`, `WARN`, `generate_statistics: false` | `application-prod.yml:15,18,22-24` | Prod |
| Inspect slow queries | `slowQueryThreshold` + `pg_stat_statements` | DB / `datasource-proxy` | Prod + dev |
