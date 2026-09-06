# Order Management API

A **production-grade Order Management API**, built as a **learning journey**: one
pull request at a time, each PR teaching one concrete aspect of modern Java 21 /
Spring Boot API development (JPA mappings, cascading, fetch strategies, locking,
auditing, security, event-driven, Kubernetes, ...).

> **Status: PR #1 (Project Setup) — in review.**
> See [Learning Roadmap](#learning-roadmap) for the full 35-PR sequence.

---

## Tech Stack

| Category   | Technology                     | Purpose                    |
|------------|--------------------------------|----------------------------|
| Language   | Java 21 LTS                    | Records, pattern matching, virtual threads |
| Framework  | Spring Boot 3.2.1              | Application framework      |
| Build      | Maven 3.9+ (wrapped via mvnw)  | Dependency management      |
| Database   | PostgreSQL 16                  | Primary + event store (from PR #2) |
| Migration  | Liquibase                      | Schema versioning (PR #2)  |
| API        | REST (GraphQL later)           | Dual API approach          |

---

## Project Structure (after PR #1)

```
order-management-api/
├── pom.xml                                   # Spring Boot 3.2.1 + Java 21
├── mvnw / mvnw.cmd                           # Maven wrapper
├── .gitignore
├── README.md
└── src/
    ├── main/
    │   ├── java/com/company/orderapi/
    │   │   └── OrderManagementApiApplication.java
    │   └── resources/
    │       └── application.yml
    └── test/java/com/company/orderapi/
        └── OrderManagementApiApplicationTests.java   # contextLoads smoke test
```

Future PRs extend this into the spec's target package tree:
`domain/` (entities, value objects, events), `api/` (REST/GraphQL controllers,
DTOs), `security/`, `infrastructure/` (persistence, messaging, resilience),
and `observability/`.

---

## Prerequisites

- **JDK 21+** (build targets Java 21 bytecode via `maven.compiler.release`,
  so a newer JDK also works for local development)
- **Docker** (only needed from PR #2 for PostgreSQL/Redis/Kafka containers)

No local Maven install is required — use the checked-in wrapper:

```bash
./mvnw clean test        # compile + run all tests
./mvnw spring-boot:run   # start the API on http://localhost:8080
```

Verify it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## PR #1 — Project Setup

**Aspect learned:** Spring Boot project structure & dependency management.

### Key questions answered

1. **How does a Spring Boot project structure work?**
   - Convention-over-configuration: `src/main/java` for sources,
     `src/main/resources` for configuration, `src/test/java` for tests.
   - `@SpringBootApplication` = component scanning + auto-configuration +
     configuration bootstrap, all from one class.
   - `application.yml` externalizes configuration; auto-configuration can be
     reasoned about and, when needed, explicitly excluded.

2. **What are the key dependencies for a JPA project?**
   - `spring-boot-starter-data-jpa` → Hibernate + Spring Data JPA.
   - PostgreSQL driver (runtime) → the JDBC target database.
   - `liquibase-core` → versioned, reviewable schema changes.
   - Plus the always-needed `web`, `validation`, `actuator` and `test` starters.
   - The Spring Boot **parent POM acts as a BOM** — we never pin third-party
     versions manually.

3. **How does the Maven wrapper work?**
   - `mvnw`/`mvnw.cmd` bootstrap the Maven version declared in
     `.mvn/wrapper/maven-wrapper.properties`, downloading it on first use.
   - Every developer and the CI server get the *exact same* Maven version —
     no "works on my machine".

### Key decisions

- **Java 21 bytecode even on newer JDKs** via `<maven.compiler.release>21</maven.compiler.release>`.
- **DB auto-configuration excluded in PR #1** (and commented in `application.yml`)
  so the app boots and the `contextLoads` smoke test passes *without* a database.
  PR #2 introduces PostgreSQL + Liquibase and removes those exclusions.
- **No Lombok** — per coding standards we prefer plain, readable Java.
- **No speculative dependencies** — starters (security, cache, Kafka, etc.)
  are added in the PR that teaches them.

---

## Learning Roadmap

| # | Aspect | # | Aspect |
|---|--------|---|--------|
| 1 | Project Setup | 19 | Event Sourcing & Event Store |
| 2 | Database Schema with Foreign Keys (Liquibase) | 20 | Service Layer |
| 3 | JPA Entities with Foreign Key Mappings | 21 | DTOs (Java Records) |
| 4 | Cascading Strategies | 22 | REST Controllers |
| 5 | Fetch Types | 23 | Validation |
| 6 | Fetch Joins & Entity Graphs (N+1 Fix) | 24 | Exception Handling & Idempotency |
| 7 | Batch Fetching | 25 | OpenAPI Documentation |
| 8 | Optimistic Locking | 26 | Security (OAuth2 & JWT) |
| 9 | Pessimistic Locking | 27 | PII & GDPR |
| 10 | Auditing | 28 | Caching (Redis) |
| 11 | Custom Queries | 29 | Resilience Patterns |
| 12 | Specifications & QueryDSL | 30 | Virtual Threads & Concurrency |
| 13 | Entity Lifecycle Callbacks | 31 | Kafka (Event-Driven) |
| 14 | Schema Generation & Validation | 32 | Observability |
| 15 | SQL Logging & Debugging | 33 | Docker & Kubernetes |
| 16 | Second Level Cache | 34 | CI/CD & GitOps |
| 17 | DTO Projections | 35 | Enterprise Features (Optional) |
| 18 | JPA Events & Listeners | | |

---

*Built one pull request at a time — each teaching one API development aspect.*
