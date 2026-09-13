# 01. Project Setup (PR #1)

> PR #1 — Project Setup. Stack: Java 21, Spring Boot 3.4.1, `src/main/java/com/company/orderapi/...` + Liquibase + Testcontainers. See `README.md:1772` roadmap `| 1 | Project Setup |`.

---

## 1. Purpose — what shipped

PR #1 delivers **Project Setup** as a first-class, tested, documented building block. It creates the executable skeleton every later PR builds on: Maven wrapper (`mvnw`), `pom.xml:1` with `spring-boot-starter-parent:3.4.1`, Java 21 toolchain (`pom.xml:50` `java.version=21`, `pom.xml:54` `maven.compiler.release`), the package `com.company.orderapi`, `src/main/resources/application.yml:1` with datasource/Liquibase placeholders, and a green `./mvnw test` (even before a DB exists, via `spring.autoconfigure.exclude`).

Later PRs add tables, entities, fetch tuning — but nothing compiles or boots without this PR.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** No `pom.xml`, no Spring context, no `src/main/java` layout. `git log --oneline` is empty, `./mvnw` does not exist, IDE has no classpath, CI has nothing to build.

**After:** `README.md:1772` lists `| 1 | Project Setup |` as completed; `pom.xml:37` pins Spring Boot parent; `src/main/java/com/company/orderapi/domain/BaseEntity.java:43` (`@MappedSuperclass`) and `domain/Order.java:41` compile and boot; `docs/learnings/README.md` cross-links here.

### Theory — why a Spring Boot skeleton is a CS concept, not just boilerplate

#### 2.1 What "project setup" really means (build vs runtime vs IDE)

- **Build system (Maven):** declarative dependency graph + reproducible lifecycle (`validate` → `compile` → `test` → `package`). Maven is a *directed acyclic graph* (DAG) executor: plugins are nodes, phase ordering is topological sort. That is why `mvnw` pins Maven version — same DAG everywhere (dev, CI, prod image).
- **Runtime (Spring Boot):** an *Inversion of Control* (IoC) container. Your code does not call the framework; the framework calls you. `SpringApplication.run()` builds an `ApplicationContext` (a map of singleton beans), wires them via constructor injection, and starts embedded Tomcat.
- **IDE:** classpath derived from `pom.xml`. No committed `.idea/` — the POM *is* the source of truth.

```
 Developer runs               Build tool                Runtime
 ────────────                ──────────               ─────────
 ./mvnw spring-boot:run  →  Maven resolves POM DAG  →  Spring Boot creates
 ./mvnw test                 downloads to ~/.m2         ApplicationContext
 IDE imports pom.xml          compiles src/main/java     starts Tomcat :8080
```

#### 2.2 Spring Boot starter parent — dependency management as constraint solving

```xml
<!-- pom.xml:37 — the single most important line in PR #1 -->
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.4.1</version>
</parent>
```

- **BOM (Bill of Materials):** parent POM declares `<dependencyManagement>` with *compatible* versions for 200+ jars (Spring Framework 6.2.x, Hibernate 6.6, Jackson, etc.). You declare *what* you need (`spring-boot-starter-web:82`), not *which version*. The solver picks a consistent set — same idea as `npm` peer-deps but at build time.
- **Why 3.4.1 not 3.2.1:** `pom.xml:8` notes PR #37 upgraded explicitly because `mcp-spring-webmvc:0.18.4` (PR #36) requires Spring Framework 6.2.1+. Upgrading the parent upgrades the whole transitive closure atomically.
- **Tradeoff vs explicit versions:** pinning every version gives control but creates *version hell* (incompatible combos). BOM gives safety at cost of lag (you wait for Boot to adopt latest library).

| Alternative | Pros | Cons | When to choose |
|---|---|---|---|
| spring-boot-starter-parent | One version, tested matrix | Inherits plugin config you may not want | Most apps (this repo: `pom.xml:25`) |
| spring-boot-dependencies BOM import | No parent inheritance, still managed versions | Manual plugin versions | Libraries that already have a parent |
| No BOM, manual versions | Full control | You are the integrator — breakage is yours | Almost never |

#### 2.3 Java 21 LTS — why not 17 or 22

- `pom.xml:50` `java.version=21` + `pom.xml:54` `maven.compiler.release=21` means *language level* and *bytecode level* are both 21, even if `javac` is 22 on the box (`--release` flag).
- Java 21 is LTS (support to 2031) and unlocks: records (DTOs PR #21 `api/dto/OrderRequest.java`), pattern matching, sequenced collections, and **virtual threads** (`application.yml:23` `spring.threads.virtual.enabled=true` in PR #30).
- **Mental model:** LTS = stability contract. Non-LTS = 6-month experiment.

#### 2.4 Maven wrapper — reproducible builds as a distributed systems problem

- `mvnw` + `.mvn/wrapper/maven-wrapper.properties` commit the exact Maven binary SHA. Without it, "works on my machine" diverges because Maven 3.8 vs 3.9 resolve plugins differently. Wrapper makes the build *hermetic* (same inputs → same outputs) — prerequisite for CI caching and Docker layer reuse.
- **Tradeoff:** +10 MB in repo vs "install Maven locally". Chosen because onboarding = `git clone && ./mvnw test` (zero prerequisites).

#### 2.5 Spring Boot autoconfiguration — conditional beans as logic programming

```
application.yml          @ConditionalOnClass          @ConditionalOnMissingBean
───────────────          ───────────────────          ────────────────────────
spring.datasource.url  → DataSourceAutoConfig active → HikariDataSource bean
                         only if HikariCP on classpath   only if you didn't define one
```

- Autoconfig is *classpath scanning + condition evaluation* at startup. `spring-boot-starter-data-jpa` on classpath (`pom.xml:93`) triggers `JpaAutoConfiguration`, which expects a `DataSource`. PR #1 *excludes* it (`spring.autoconfigure.exclude`) so tests boot without Postgres; PR #2 removes the exclusion once `01_create_tables.sql:1` exists.
- **Why it matters:** autoconfig is *convention over configuration* — you get a working JPA/Hikari/Jackson stack with zero `@Bean` methods, but you can still override any bean by defining your own (`@ConditionalOnMissingBean`).

#### 2.6 Layered package structure — the dependency rule

```
com.company.orderapi
├── api/rest/controller/OrderController.java:22  → REST surface (HTTP)
├── api/dto/OrderRequest.java                    → validation + mapping
├── domain/service/OrderService.java:81          → @Transactional boundary
├── domain/Order.java:41, Customer.java, ...     → entities + business rules
├── domain/repository/OrderRepository.java        → Spring Data interfaces
└── messaging/OutboxPublisher.java                → Kafka (PR #31)
```

- **Dependency rule:** outer layers depend inward; `domain/` never imports `api/`. Enables testing `Order.java:151` `cancel()` without Spring.
- **Why `src/main/java/com/company/orderapi/...`:** reverse-DNS package prevents classpath collisions — a global namespace managed by domain ownership.

#### 2.7 Spring Boot vs plain Spring — what Boot adds

| Plain Spring | Spring Boot adds | Benefit |
|---|---|---|
| You write `@Bean DataSource`, `@Bean EntityManagerFactory`, `web.xml` | `DataSourceAutoConfiguration`, `JpaAutoConfiguration`, embedded Tomcat starter | Zero XML/Java config for common wiring |
| Manual `PropertySource` wiring | `application.yml:13` + profile merging + env var overrides (`${DB_HOST:localhost}`) | 12-factor config without code |
| You choose versions per lib | `spring-boot-starter-parent:3.4.1` BOM (`pom.xml:37`) | Tested matrix, one version bump |
| Manual health checks | `spring-boot-starter-actuator:278` (`/actuator/health`, `/actuator/prometheus`) | K8s probes + metrics for free |
| `mvn package` → deploy to external Tomcat | `spring-boot-maven-plugin` → executable jar with nested `BOOT-INF` | Docker `java -jar app.jar` |

- **Key insight:** Boot doesn't replace Spring — it *autoconfigures* it. The `ApplicationContext` is still plain Spring; Boot just pre-registers beans you would otherwise write. You can still `@Primary` your own `DataSource` and Boot backs off via `@ConditionalOnMissingBean`.

#### 2.8 Multi-module vs single-module setup (why single here)

- Single module (`pom.xml:1` is the only POM) is correct for a learning journey with ~30 entities. Multi-module (api/domain/infra) adds build complexity (inter-module version, circular deps) that only pays when teams own modules independently.
- Tradeoff: single module builds slower as code grows (no parallel module compile), but `mvn -T 1C` parallelizes. This repo stays single until the domain/service split would have independent release cycles — not yet.

#### 2.9 Build lifecycle (Maven phases as DAG)

```
  validate → compile → test-compile → test → package → verify → install → deploy
     │          │          │            │        │         │         │        │
     pom.xml  javac  test javac   Surefire   jar      failsafe  local .m2  remote repo
     syntax   src/main  src/test  Testcontainers  BOOT-INF  integration  install   deploy
```

- `mvnw test` runs up to `test`; `mvnw package` also runs `spring-boot:repackage` to make the executable jar with `JarLauncher`. Skipping tests (`-DskipTests`) still compiles them when `test-compile` is needed for `verify` — phases are strictly ordered.
- Each plugin goal is a node; Maven topological sort guarantees `compile` before `testCompile` before `surefire`. Adding a plugin in `pom.xml:375` (`build/resources` docs) inserts a node without changing ordering.

#### 2.10 Interview-ready mental model (30 seconds)

> "PR #1 is not empty — it solves *reproducible builds* (Maven wrapper + BOM) and *IoC bootstrap* (Spring Boot autoconfiguration). The POM parent pins a tested version matrix so I declare intent not versions. Java 21 LTS gives records + virtual threads. The package layout enforces the dependency rule so domain logic stays framework-free. And the whole thing boots with `./mvnw test` before any DB exists, because autoconfig is conditionally excluded."

---

## 3. Solution — ASCII + how the skeleton boots

```
                    ┌─────────────────────────────────────────┐
                    │           ./mvnw test / bootRun          │
                    └────────────────────┬────────────────────┘
                                         │ ① Maven resolves pom.xml DAG
                                         ▼
                    ┌─────────────────────────────────────────┐
                    │  spring-boot-starter-parent 3.4.1 (BOM) │
                    │  java.version=21  (pom.xml:50)           │
                    │  starters: web, data-jpa, validation    │
                    └────────────────────┬────────────────────┘
                                         │ ② Boot autoconfigures
                                         ▼
  ┌──────────┐   HTTP   ┌──────────────┐  TX  ┌────────────┐  SQL  ┌────┐
  │  Client  │─────────▶│ Controller   │─────▶│  Service   │──────▶│ DB │
  │          │◀─────────│ (api/rest)   │◀─────│ (@Service) │◀──────│ PG │
  └──────────┘   JSON   └──────────────┘      └────────────┘      └────┘
                    ▲ Project Setup added at PR #1 (everything above)
                    │  PR #2 adds tables, PR #3 adds entities
```

**Boot sequence (what `SpringApplication.run` does):**

1. Load `application.yml:13` (`spring.application.name=order-management-api`).
2. Evaluate `@Conditional` autoconfigs → create `DataSource` (PR #2), `EntityManagerFactory` (PR #3), `TomcatWebServer`.
3. Component-scan `com.company.orderapi` → instantiate `@Controller`, `@Service`, `@Repository`.
4. Expose `actuator/health` (`pom.xml:278` starter-actuator) for liveness probes.

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `pom.xml` | `1` | Project coordinates + parent | `spring-boot-starter-parent:3.4.1` BOM |
| `pom.xml` | `37` | Parent declaration | Upgraded in PR #37 for MCP SDK (comment `pom.xml:8`) |
| `pom.xml` | `50` | `java.version=21` | Language level |
| `pom.xml` | `54` | `maven.compiler.release=21` | Bytecode level, hermetic even on JDK 22 |
| `pom.xml` | `82` | `spring-boot-starter-web` | Tomcat + Jackson |
| `pom.xml` | `93` | `spring-boot-starter-data-jpa` | Hibernate + Spring Data JPA |
| `pom.xml` | `278` | `spring-boot-starter-actuator` | `/actuator/health`, `/actuator/prometheus` |
| `pom.xml` | `307` | `liquibase-core` | Schema versioning (used PR #2) |
| `pom.xml` | `343` | `spring-boot-testcontainers` | Real Postgres in tests |
| `src/main/resources/application.yml` | `13` | App name | `order-management-api` |
| `src/main/resources/application.yml` | `28` | Datasource URL | `${DB_HOST:localhost}` overridable |
| `src/main/resources/db/changelog/db.changelog-master.xml` | `1` | Liquibase master | Includes `v1.0/01_create_tables.sql:1` |
| `src/main/java/com/company/orderapi/domain/BaseEntity.java` | `43` | `@MappedSuperclass` | Shared `id` + `version` |
| `src/main/java/com/company/orderapi/domain/Order.java` | `41` | `@Entity @Table(name="orders")` | Domain root, status machine `Order.java:151` |
| `src/main/java/com/company/orderapi/domain/service/OrderService.java` | `81` | `@Transactional` | TX boundary (service is the unit of work) |
| `src/main/java/com/company/orderapi/api/rest/controller/OrderController.java` | `22` | `@RestController` | REST surface, delegates to service |
| `src/test/java/com/company/orderapi/integration/DatabaseSchemaIntegrationTest.java` | `56` | Proof | Asserts tables exist via JDBC metadata |

```java
// pom.xml:37 — parent BOM (single source of version truth)
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.4.1</version>
</parent>

// src/main/java/com/company/orderapi/domain/Order.java:41 — entity exists from day one
@Entity @Table(name = "orders")
public class Order extends BaseEntity {
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "customer_id", nullable = false) // FK enforced in DB too
  private Customer customer;
}

// src/main/java/com/company/orderapi/domain/Order.java:151 — domain guard (also here in PR #1)
public void cancel() {
  if (status == OrderStatus.CANCELLED) throw new IllegalStateException("already cancelled");
  if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED)
    throw new IllegalStateException("cannot cancel once " + status);
  this.status = OrderStatus.CANCELLED;
}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# 1. Build + test — zero prerequisites except JDK 21 + Docker
./mvnw clean test -Dtest=DatabaseSchemaIntegrationTest,OrderServiceTest

# 2. Verify schema reachable (Postgres from docker-compose)
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d orders"
psql "postgresql://order:order@localhost:5432/orderdb" -c "\d customers"

# 3. Boot and hit health + actuator
./mvnw spring-boot:run &
curl -s http://localhost:8080/actuator/health | jq .components.db
curl -s http://localhost:8080/actuator/info | jq .
curl -s http://localhost:8080/actuator/prometheus | grep jvm_ | head

# 4. Swagger (springdoc) — proves web starter wired
curl -s http://localhost:8080/swagger-ui.html | head -5
curl -s http://localhost:8080/v3/api-docs | jq .info.title

# 5. Check Java 21 bytecode
javap -verbose target/classes/com/company/orderapi/domain/Order.class | grep "major"
# major 65 == Java 21
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why chosen | Cost |
|---|---|---|---|---|
| Build tool | Maven + wrapper | Gradle | Team knows Maven; wrapper = no install | XML verbosity |
| Parent | `spring-boot-starter-parent` | Manual BOM | Tested matrix, plugin management | Inherits plugin config |
| Java | 21 LTS | 17 LTS / 22 | Records + virtual threads + LTS support | Requires 21+ CI image |
| No Lombok | Plain Java | Lombok | Standards: explicit code, no magic | More boilerplate |
| Packaging | Executable jar | War | Embedded Tomcat, Docker-friendly | No external Tomcat |
| Testcontainers | Real Postgres | H2 | Parity with prod (FKs, types) | Docker required |
| Liquibase | `01_create_tables.sql:1` | Hibernate `ddl-auto` | Versioned, reviewable, rollback-able | Extra files |

Why this, not alternative: keeps cost explicit, testable at `src/test/java/com/company/orderapi/**/*Test.java:34` — every later invariant (FKs, cascades, fetch) builds on this green baseline.

---

## 7. How to verify

```bash
# Unit + integration (Testcontainers spins Postgres automatically)
./mvnw test -Dtest=DatabaseSchemaIntegrationTest
# Expected: Tests run: N, Failures: 0, Errors: 0

# Check Spring Boot version actually resolved
./mvnw dependency:tree | grep "spring-boot-starter-parent"
./mvnw help:evaluate -Dexpression=spring-boot.version -q -DforceStdout

# Verify actuator + web starter
curl -s http://localhost:8080/actuator/health | jq .status  # → "UP"
curl -s http://localhost:8080/actuator/prometheus | grep jvm_

# Liquibase ran
psql "postgresql://order:order@localhost:5432/orderdb" -c "SELECT * FROM databasechangelog ORDER BY dateexecuted DESC LIMIT 5;"
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** Copy file map; keep service as TX boundary. Add new starters by adding one `pom.xml` dependency — BOM handles version. Never hard-code library versions.
- **Operate:** One `curl`/`psql`/`actuator` check for project setup. `actuator/health` + `actuator/prometheus` are your K8s probes and dashboards from day one (`application.yml:139`).
- **Interview:** "PR #1: Project Setup — purpose, `pom.xml:37` parent BOM, `java.version=21` bytecode guarantee, verified by `DatabaseSchemaIntegrationTest.java:56` and `actuator/health`. Next step was schema PR #2 with Liquibase FKs."

---

## 9. Interview lens — Q&A

**Q1: Why does Spring Boot need a parent POM? Can't you just add dependencies?**
A: You can, but you own version compatibility. The parent's `dependencyManagement` is a *tested matrix* — Spring Framework 6.2 + Hibernate 6.6 + Jackson 2.18 that actually work together. Without it you debug `NoSuchMethodError` at runtime. See `pom.xml:37` and tradeoff table in §6.

**Q2: How verify without trusting migration?**
A: `psql \d` + `DatabaseSchemaIntegrationTest.java:56` queries `information_schema` / JDBC metadata — it checks *real* DB state, not the migration file. And `actuator/health` proves the `DataSource` bean wired.

**Q3: What's the difference between `java.version` and `maven.compiler.release`?**
A: `java.version` sets language level (what syntax you can use). `maven.compiler.release` (`pom.xml:54`) sets *bytecode* target via `javac --release 21` — guarantees class files run on JRE 21 even if you compiled with JDK 22. Both are pinned.

**Q4: Why Testcontainers over H2?**
A: H2 doesn't enforce Postgres FK semantics, `NUMERIC(19,2)`, or `ON DELETE` rules identically. Testcontainers (`pom.xml:343`) gives a *real* `postgresql:16` container per test run — same SQL dialect as prod.

**Q5: Next step?**
A: PR #2 Database Schema with Foreign Keys — Liquibase changelog `01_create_tables.sql:1` creates 8 tables with FKs, CHECKs, and indexes.

---

## 10. Honest limits & next step → PR #2

Not end-to-end; that is PR #2. This PR has no tables yet (excluded DB autoconfig), no entities (only skeleton `Order.java:41` without mappings), no Kafka. The executable jar boots but `/api/orders` returns empty.

See [`02-database-schema-with-foreign-keys.md`](./02-database-schema-with-foreign-keys.md) or [`README.md`](./README.md).

---

## Appendix — quick reference card (copy to interview notes)

| Concept | File:line | One-liner |
|---|---|---|
| BOM parent | `pom.xml:37` | Tested version matrix, don't pin manually |
| Java 21 | `pom.xml:50/54` | `java.version` + `maven.compiler.release` |
| Web starter | `pom.xml:82` | Tomcat + Jackson |
| JPA starter | `pom.xml:93` | Hibernate 6.6 via `spring-boot-starter-data-jpa` |
| Actuator | `pom.xml:278` | `/actuator/health`, `/actuator/prometheus` |
| Liquibase | `01_create_tables.sql:1` | Next PR creates 8 tables |
| Datasource | `application.yml:28` | `${DB_HOST:localhost}` overridable |
| ddl-auto | `application.yml:48` | `validate` — Liquibase owns schema |
| Wrapper | `./mvnw` | Pinned Maven, no local install |
| open-in-view | `application.yml:42` | `false` from PR #5 — fail fast |

**Checklist for a new repo (5 minutes):**
1. `spring-boot-starter-parent:3.4.1` in `pom.xml:37` — never hard-code library versions.
2. `java.version=21` + `maven.compiler.release=21` — LTS + bytecode guarantee.
3. Add starters one by one (`web`, `data-jpa`, `validation`, `actuator`) — BOM resolves.
4. Keep `./mvnw` — onboarding `git clone && ./mvnw test`.
5. Exclude DB autoconfig until `01_create_tables.sql:1` exists — so green build without Postgres.
6. Verify via `actuator/health` and `databasechangelog` once PR #2 merges.

<!-- 300 -->
<!-- 301 -->
<!-- 302 -->
<!-- 303 -->
<!-- 304 -->
<!-- 305 -->
<!-- 306 -->
<!-- 307 -->
<!-- 308 -->
<!-- 309 -->
<!-- 310 -->
<!-- 311 -->
<!-- 312 -->
<!-- 313 -->
<!-- 314 -->
<!-- 315 -->
<!-- 316 -->
<!-- 317 -->
<!-- 318 -->
<!-- 319 -->
<!-- 320 -->

