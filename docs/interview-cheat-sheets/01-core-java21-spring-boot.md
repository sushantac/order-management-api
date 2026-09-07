# 01 — Core Java 21 & Spring Boot

## Java 21 highlights you MUST show
| Feature | Explain | Example in repo |
|---|---|---|
| Records | immutable data carriers, canonical constructor | every `*Request`/`*Response`, `DomainEvent` implementations |
| Pattern matching switch | exhaustiveness, readability | `GlobalExceptionHandler.classify(ex)` maps exception families |
| Pattern matching instanceof | `instanceof Foo f` no cast | `value instanceof String s` |
| Virtual threads | ~KB stacks, park on blocking I/O; `Executors.newVirtualThreadPerTaskExecutor()` | `DashboardService`, concurrency tests |
| Text blocks | readable SQL/JSON | JPQL `@Query("""...""")` |
| `@Serial` | serialization hygiene | DTO stored in Redis cache |
| Sealed/interface defaults | `DomainEvent.aggregateType()` default method | domain events |

## Spring Boot core questions
**DI / IoC**: constructor injection (final fields, testable). Beans are singletons by default - shared state must be thread-safe or scope-aware.
**Starters**: dependency + auto-configuration in one (web, data-jpa, security-oauth2, data-redis, kafka, actuator).
**Profiles**: `application-{profile}.yml`; tests run the DEFAULT profile; `dev`/`prod` override specifics (this project: caching Redis in dev/prod, JSON logs in prod, tracing enabled in prod).
**Auto-configuration**: conditionals (`@ConditionalOnProperty`, `@ConditionalOnClass`) decide what is active - it is why adding a library can "just work".
**Application events & scheduling**: `@EnableScheduling` (outbox poller), `@Scheduled`; `@EnableAsync` when needed.
**Property binding**: `@ConfigurationProperties` classes (`SecurityProperties`, `FeatureFlags`) keep config typed and documented.

## Daily-work traps (from this repo)
- `@Lazy` on a `@Bean` alone is not enough: annotate the **injection point** too, or eager singletons still instantiate it (Redisson connecting to Redis at startup).
- Micrometer `@Timed(percentiles=...)` takes `double[]`, not a string.
- `ExecutorService.submit(methodRef)` is ambiguous for primitive-returning method refs - type locals as `Callable<Long>`.
- Records + Jackson: annotate components (`@MaskedPii`) and it flows to field/accessor.

## Tell me about...
1. **"Your Spring Boot project architecture."** → Layers: controllers (thin) → services (`@Transactional`) → repositories/entities; DTOs never entities; config in typed property classes; cross-cutting concerns (security, caching, metrics, outbox) as annotations/beans.
2. **"A tricky bug you fixed."** → 403s came back as 500s because the catch-all `@ExceptionHandler(Exception)` swallowed `AccessDeniedException`; added explicit 401/403 handlers to the Problem-Detail contract.
3. **"How do you test Spring apps?"** → unit for pure logic; `@SpringBootTest` + Testcontainers (real Postgres/Redis/Kafka) per integration class; schema-truth tests; every class its own DB via unique `integration.database.tag`.

## Rapid Q&A
- Q: Why constructor injection? A: final/immutable, explicit dependencies, easier tests.
- Q: Singleton + request data? A: Don't store request state in singletons; use request-scoped/thread-locals (MDC, SecurityContext) carefully and clean up (finally).
- Q: Restart-free config? A: profiles + env vars + `@ConfigurationProperties`; feature flags for behaviour.
- Q: Java 21 records vs Lombok? A: records are final/canonical; Lombok is mutable-friendly boilerplate - prefer records for DTOs.
