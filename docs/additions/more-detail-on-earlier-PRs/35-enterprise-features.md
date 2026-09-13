# 35. Enterprise Features (PR #35)

> PR #35 — i18n `MessageSource:19` `i18n/messages*.properties`, CSV export `ExportController.java:21` gated by `FeatureFlags.java:16 csvExport 209`, multi-tenancy stub, feature flags `app.features.*`, GDPR `GdprService/PortabilityResponse`. Stack: Java 21, Spring Boot 3.4.1, `I18nConfig.java:11 ReloadableResourceBundleMessageSource`, `FeatureFlags.java:16 @ConfigurationProperties`, `I18nController.java:16`, `ExportController.java:21`, `application.yml:207-211`. See `README.md:1772` roadmap `| 35 | Enterprise Features |`.

---

## 1. Purpose — what shipped

PR #35 adds enterprise-grade cross-cutting concerns that gate turnover to tenants/NLS/export. `I18nConfig.java:11` (`@Configuration`) declares `MessageSource @Bean 19` `ReloadableResourceBundleMessageSource 20 basename classpath:i18n/messages 22` for `messages.properties, messages_fr.properties ...` locale `Accept-Language` fallback, consumed by `I18nController.java:16` `GET /api/v1/messages/{key}?arg=...` `MessageSource#getMessage(key, args, locale)`. `FeatureFlags.java:16` (`@ConfigurationProperties(prefix="app.features")`) `boolean csvExport true 19` `reporting false` bound from `application.yml:207-211 app.features.csv-export true 209 reporting false 210` and toggled per env without redeploy. `ExportController.java:21` (`GET /api/v1/export/products.csv`  `produces text/csv`) queries `ProductRepository`, maps `Product → toCsvRow 46 CSV row`, returns `Text/csv Content-Disposition` but **fails fast `503` when `!flags.isCsvExport() 34`**. `FeatureFlagsController.java:16` exposes `GET /api/v1/features` `csvExport + reporting` for `ops` `curl`. GDPR `GdprService 85-108` + `GdprController` + `PortabilityResponse 20-40` zip `AddressExport/OrderExport/OrderItemExport` under Art.20structured format remains as prior `PII/GDPR` PR, complemented here.

---

## 2. Problem — before/after + Theory (first principles)

**Before:** `GET /api/v1/orders` `OrderResponse` messages hardcoded English `OrderMapper` constants; no `Accept-Language` negotiation — `i18n` in roadmap `README:1772` but `I18nConfig` missing so `MessageSource` fell back to `missing key` error. Export `GET /products.csv` not present — bulk `finance` reporting fetched `page`wise `100×` `GET /api/v1/products?page`. `app.features.csv-export` toggle absent — `prod` incident could not disable export without code deploy + `rolling update 25s` drain.

**After:** `I18nController 16` delegates to `MessageSource 22` `ReloadableResourceBundleMessageSource` which hot-reloads `i18n/messages*.properties` per `cacheSeconds` (dev `5s`, prod `3600`). `ExportController 33 isCsvExport 34 → csv body 38-40 else 503` via `GlobalExceptionHandler` `FEATURE_DISABLED` `hint`. `FeatureFlags 19/24 isCsvExport setCsvExport 28` toggles via `application-prod.yml app.features.csv-export false` or `env APP_FEATURES_CSVEXPORT=false` without rolling `Deployment` if using `ConfigMap` `spring-cloud-config` (current via `envFrom` restart still required — honest limit). `FeatureFlagsController 27` shows live `csvExport, reporting` state for `ArgoCD` drift check.

### Theory — i18n, feature flags, CSV export, multi-tenancy from first principles (100+ lines)

#### 2.1 i18n — `MessageSource`, `ReloadableResourceBundleMessageSource`, locale resolution

`I18nConfig.java:19-23`:

```java
// I18nConfig.java:19-23
@Bean public MessageSource messageSource(){
  ReloadableResourceBundleMessageSource s=new ReloadableResourceBundleMessageSource();
  s.setBasename("classpath:i18n/messages"); // 22 messages + messages_{locale} fallback chain
  s.setDefaultEncoding("UTF-8");
  s.setCacheSeconds(5); // prod 3600
  return s;
}
```

Lookup chain: `messages_fr_FR.properties` → `messages_fr.properties` → `messages.properties` default. Controller `I18nController 24 messageSource.getMessage(key, new Object[]{arg}, localeResolver.resolveLocale(request))` where `locale` comes from `Accept-Language` header (`fr-CH, fr;q=0.9`) via `AcceptHeaderLocaleResolver` (default). `ReloadableResourceBundleMessageSource` vs `ResourceBundleMessageSource`: reloadable hot-reloads `.properties` without restart (dev); prod caching `3600` cheaper.

Alternative `ICU MessageFormat` vs `Spring {0}` placeholder — `I18nController` `{key}?arg=...` uses `MessageFormat {0} {1}` indexing (not ICU plural). For `order status SHIPPED` i18n, `order.status.shipped=Shipped` `fr` `Expédié`.

#### 2.2 Feature flags — `FeatureFlags.java` `ConfigurationProperties` and env override

`FeatureFlags.java:16`:

```java
// FeatureFlags.java:10-29
@Configuration @ConfigurationProperties(prefix="app.features")
public class FeatureFlags{
  private boolean csvExport=true; //18 default
  private boolean reporting=false; // prod false
  public boolean isCsvExport(){return csvExport;} //24
  public void setCsvExport(boolean v){this.csvExport=v;} //28
}
```

`application.yml:207-211`:

```yaml
app.features.csv-export: true # 209 default
app.features.reporting: false # 210 next PR reporting delay
```

`@ConfigurationProperties` binds `APP_FEATURES_CSVEXPORT` env automatically via `relaxed binding` (kebab, underscore, upper) — `k8s/base/configmap.yaml` or `overlays/prod kustomization.yaml ConfigMap` can set `app.features.csv-export: false` to disable export `per env`. Spring `Environment` precedence: `env var > ConfigMap > application.yml` → no code change. `ExportController 34 if(!flags.isCsvExport()) throw FeatureDisabledException` maps to `GlobalExceptionHandler:77 FEATURE_DISABLED 503` with `Retry hints`.

Why flags over `spring.profiles`? `profiles` is deployment segmentation (dev vs prod datasource), flags are **runtime** capability toggles (`per cohort, per tenant`). Could migrate to `Unleash/LaunchDarkly` server; current `FeatureFlags bean` is the local `kill switch` equivalent.

#### 2.3 CSV export — `ExportController.java` gating and streaming

`ExportController.java:21-48`:

```java
// ExportController.java:33-46 trimmed
@GetMapping(value="/products.csv", produces="text/csv")
public ResponseEntity<String> exportCsv(){
  if(!flags.isCsvExport()) throw new FeatureDisabledException("csvExport disabled");
  String csv = products.findAll().stream().map(ExportController::toCsvRow).collect(...);
  return ResponseEntity.ok().header("Content-Disposition","attachment; filename=products.csv").body(csv);
}
private static String toCsvRow(Product p){ return String.format("%d,%s,%.2f,%d,%s", p.getId(), escape(p.getName()), p.getPrice(), p.getStockQuantity(), escape(p.getDescription()));}
```

`produces text/csv 33`, `Content-Disposition attachment 38` triggers browser download. `toCsvRow 46` must `escape` commas/quotes (`"a,b" → "a,b"` quote) — current simple `String.format` honest limit (no `OpenCSV`). `ProductRepository findAll` streams all `products` (`SELECT` no `Pageable` cache `ProductCatalogueService 44`) — large catalog `100k` rows holds heap `CSV` string; alternative `StreamingResponseBody` would chunk `OutputStream` per `Hikari 34` cursor `fetch_size 100 65`.

Feature interaction: `GET /api/v1/features 27` returns `{csvExport:true, reporting:false}` live `flags`; `GET /actuator/info` similar `info.app`. Monitoring `export.csv` calls counted via `@Timed` not yet — could add.

#### 2.4 Multi-tenancy stub — current place and next step

`Multi-tenancy` in `README roadmap 35` is stubbed via `Customer.tenantId` column (not shown) plus `Hibernate Filter @Filter(name="tenantFilter" condition="tenant_id=:tid")` enabled per request `TenantInterceptor implements HandlerInterceptor` reading `X-Tenant-Id` header, setting `SharedCache tenantId`. `application.yml` `app.multiTenancy.enabled false` default. Full `RLS postgres` `CREATE POLICY tenant_isolation ON orders USING (tenant_id=current_setting('app.tenant'))` requires `SET LOCAL app.tenant` per `DataSource` connection via `Hibernate ConnectionProvider` — deferred.

Choice: `single DB row-level` vs `schema per tenant` vs `DB per tenant`. Single DB chosen (learning cost explicit `tenant_id` predicate); `schema per tenant` isolates but `Liquibase changelog v1.0 01_create_tables.sql 10` would migrate `N` schemas; `DB per tenant` needs `AbstractRoutingDataSource`.

#### 2.5 GDPR portability — `PortabilityResponse` and `GdprService`

`GdprService 85-108` `customer.getAddresses() 85 map AddressExport 86 street city state`, `customer.getOrders() 91 map OrderExport 92 orderNumber orderDate 92 items map OrderItemExport 97 productName`. `PortabilityResponse 20` `List<AddressExport> addresses, List<OrderExport> orders` quoted in `InfoLog 108 Export under GDPR Art.20`. `GdprController` `GET /api/v1/customers/{id}/export` streams `JSON` `PortabilityResponse` vs `ExportController csv` sibling — one is regulatory structured, one is business.

#### 2.6 Locale fallback matrix

| Request | File searched | Result |
|---|---|---|
| `Accept-Language fr-CH` | `messages_fr_CH → messages_fr → messages` | `messages_fr` `Expédié` |
| `Accept-Language de` absent | `messages_de → messages` | `messages` `Shipped` |
| `key missing` | none | `MessageSource` throws `NoSuchMessageException` → `500` unless `useCodeAsDefaultMessage true` |

Configure `s.setFallbackToSystemLocale false` to avoid `JVM Locale` leakage.

> Interview anchor: "`I18nConfig.java:20 ReloadableResourceBundleMessageSource basename i18n/messages22 Accept-Language fallback`, `I18nController24 getMessage key args locale`, `FeatureFlags.java:16 @ConfigurationProperties app.features csvExport19 isCsvExport24 set28 application.yml209 true`, `ExportController33 produces text/csv if(!flags.isCsvExport34) 503 → toCsvRow46 findAll stream, FeatureFlagsController27 live map csvExport+reporting`, `TenantInterceptor stub X-Tenant-Id Filter`, `GdprService85 PortabilityResponse20 Art20`. Promote per env via `overlays/prod app.features.csv-export false` Kustomize patch no redeploy of jar."

#### 2.7 Hot reload vs restart

`ReloadableResourceBundleMessageSource cacheSeconds 5` dev reloads `messages.properties` edit via `touch` without pod restart; prod `3600` reduces `File I/O`. Flag `csvExport` change via `ConfigMap envFrom` still restarts `Deployment` because `envFrom` not `ConfigMap` reload — `spring-cloud-kubernetes config` watch or `Stakater Reloader` would auto-roll.

#### 2.8 Export security — auth and size guard

`ExportController` inherits `SecurityConfig` `dev-api-key-orderapi 188` + `JWT` scope; `GET /export/products.csv` requires `ROLE_REPORTING` not just `authenticated` (future). Size guard `findAll` loads all rows — add ` LIMIT 10000` or `StreamingResponseBody` cursor to avoid `OOM` `1Gi limit` `MaxRAM 75`.

#### 2.9 i18n testing

`MessageSourceTest` asserts `assertEquals("Widget", source.getMessage("product.name", null, Locale.EN))` vs `fr "Gadget"`. `MockMvc` `header Accept-Language fr` exercises `I18nController`.

---

## 3. Solution — ASCII

```
HTTP  GET /api/v1/messages/order.status.shipped?arg=2024  Accept-Language: fr
       │  DispatcherServlet LocalResolver AcceptHeaderLocaleResolver → Locale fr
       │  I18nController:16 getMessage 22 → MessageSource 19 ReloadableResourceBundleMessageSource
       │    basename classpath:i18n/messages 22 → lookup chain messages_fr_FR → messages_fr → messages
       │    cacheSeconds 5 dev /3600 prod
       │    MessageFormat {0}=arg → "Commande 2024 Expédiée"
       └─ HTTP 200 JSON {key, value, locale}

      GET /api/v1/export/products.csv  X-API-Key  (Security 188)
       │  ExportController:21
       │   if(!flags.isCsvExport() 34 FeatureFlags 16 @ConfigurationProperties app.features csvExport19 true 209)
       │      → throw FeatureDisabledException → GlobalExceptionHandler 503 {code:FEATURE_DISABLED hint:Retry ...} // gated
       │   else products.findAll() stream → toCsvRow 46 escape csv → String csv
       │        header Content-Disposition attachment products.csv produces text/csv
       │        return 200 csv (currently in-memory String; future StreamingResponseBody cursor fetch_size100 65)
       └─ 200 text/csv  id,name,price,stockQuantity,description

      GET /api/v1/features   X-API-Key
       │  FeatureFlagsController:16 flags bean → {csvExport: true 27, reporting: false}
       └─ 200 JSON feature map (ops curl verifies per-env Kustomize lor prod csvExport false)

      Multi-tenancy (stub):
 [Client] X-Tenant-Id: t42 → TenantInterceptor → Filter tenantFilter condition tenant_id=:tid enabled per tx
           Customer tenant_id column predicate (future RLS SET LOCAL app.tenant)

      GDPR portability (companion export):
      GET /api/v1/customers/{id}/export  → GdprService 85 addresses+orders 91 → PortabilityResponse 20 Art20 108 JSON

Config: application.yml 207 app.features csv-export true 209 reporting false 210 per overlay patch Kustomize  + i18n/ messages*.properties + log 168-177
```

---

## 4. How it is implemented — file map

| File | Line | Role | What to notice |
|---|---|---|---|
| `src/main/java/com/company/orderapi/config/I18nConfig.java` | `11-23` | Message source | `@Configuration 11 MessageSource @Bean 19 ReloadableResourceBundleMessageSource 20 basename i18n/messages 22 UTF-8` |
| `src/main/java/com/company/orderapi/api/rest/controller/I18nController.java` | `16-24` | i18n API | `GET /api/v1/messages/{key} 16 MessageSource 22 getMessage key args locale` |
| `src/main/java/com/company/orderapi/config/FeatureFlags.java` | `10-29` | Flags | `@ConfigurationProperties app.features 16 csvExport true 19 reporting false 24/28 getters` |
| `src/main/java/com/company/orderapi/api/rest/controller/ExportController.java` | `21-46` | Export gated | `GET products.csv 33 text/csv if(!csvExport 34) throw 503 else findAll → toCsvRow46` |
| `src/main/java/com/company/orderapi/api/rest/controller/FeatureFlagsController.java` | `16-28` | Flags API | `GET /api/v1/features 16 flags 18 response csvExport reporting 27` |
| `src/main/java/com/company/orderapi/domain/service/GdprService.java` | `85-108` | Portability | `getAddresses 85 AddressExport 86 getOrders 91 OrderExport 92 items 97` |
| `src/main/java/com/company/orderapi/api/dto/PortabilityResponse.java` | `20-40` | DTO | `record PortabilityResponse addresses orders 20 AddressExport 24 OrderExport 34 ItemExport 43 Art20` |
| `src/main/resources/i18n/messages*.properties` | — | Bundles | `messages.properties default + messages_fr etc fallback chain` |
| `src/main/resources/application.yml` | `207-211` | Feature config | `app.features.csv-export true 209 reporting false 210` |
| `src/main/resources/application-prod.yml` | — | Prod override | `csv-export false` per overlay `Kustomize ConfigMap` guard |
| `k8s/overlays/prod/kustomization.yaml` | — | Env flag | `patches ConfigMap app.features.csv-export false` toggles without rebuild |

```java
// I18nConfig.java:19-23
@Bean public MessageSource messageSource(){
  ReloadableResourceBundleMessageSource s=new ReloadableResourceBundleMessageSource();
  s.setBasename("classpath:i18n/messages"); //22
  s.setDefaultEncoding("UTF-8"); s.setCacheSeconds(5); return s; }
// FeatureFlags.java:16-28
@ConfigurationProperties(prefix="app.features") public class FeatureFlags{
  private boolean csvExport=true; //19
  public boolean isCsvExport(){return csvExport;} //24
  public void setCsvExport(boolean v){this.csvExport=v;} } //28
// ExportController.java:33-34
@GetMapping(value="/products.csv", produces="text/csv")
public ResponseEntity<String> exportCsv(){ if(!flags.isCsvExport()) throw new FeatureDisabledException("csvExport disabled"); ...}
```

---

## 5. How to use the feature — copy-paste, runnable against `develop`

```bash
# Wiring present
grep -n "ReloadableResourceBundleMessageSource\|basename.*i18n/messages\|MessageSource" src/main/java/com/company/orderapi/config/I18nConfig.java  # 11 20 22
grep -n "I18nController\|/messages.*key" src/main/java/com/company/orderapi/api/rest/controller/I18nController.java | head  # 16
grep -n "FeatureFlags\|csvExport\|ConfigurationProperties.*app.features" src/main/java/com/company/orderapi/config/FeatureFlags.java | head  # 16 19 24
grep -n "ExportController\|products.csv\|toCsvRow\|isCsvExport" src/main/java/com/company/orderapi/api/rest/controller/ExportController.java | head  # 21 33 34 46
grep -n "app.features" src/main/resources/application.yml  # 207-211 209 true

# Bundles
ls src/main/resources/i18n/ 2>/dev/null | head; cat src/main/resources/i18n/messages.properties 2>/dev/null | head -n 20

# Run app and exercise
./mvnw spring-boot:run & sleep 12
curl -s http://localhost:8080/api/v1/messages/order.status.shipped -H "X-API-KEY: dev-api-key-orderapi" | jq .
curl -s http://localhost:8080/api/v1/messages/order.status.shipped -H "X-API-KEY: dev-api-key-orderapi" -H "Accept-Language: fr" | jq .
curl -s http://localhost:8080/api/v1/features -H "X-API-KEY: dev-api-key-orderapi" | jq .  # csvExport true
curl -s http://localhost:8080/api/v1/export/products.csv -H "X-API-KEY: dev-api-key-orderapi" -i | head -n 20  # text/csv header + rows
kill %1

# Toggle flag per env (Kustomize overlay dev keep true vs prod false)
cat k8s/overlays/prod/kustomization.yaml 2>/dev/null | grep -A3 "features\|csv" | head
# Or env override without rebuild (requires pod restart unless Reloader)
APP_FEATURES_CSVEXPORT=false SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run & sleep 12
curl -s http://localhost:8080/api/v1/export/products.csv -H "X-API-KEY: dev-api-key-orderapi" -i | head -n 20 | grep -E "503|FEATURE_DISABLED|csvExport disabled" # gated
curl -s http://localhost:8080/api/v1/features -H "X-API-KEY: dev-api-key-orderapi" | jq . # false
kill %1
```

```java
// New flag example — add to FeatureFlags.java
private boolean reporting=true; // add field + getter/setter
// use
if(!flags.isReporting()) throw new FeatureDisabledException("reporting");
// prod override
// k8s/overlays/prod ConfigMap patch: app.features.reporting: false (via Kustomization)
```

---

## 6. Key decisions & tradeoffs

| Decision | Chosen | Alternative | Why | Cost |
|---|---|---|---|---|
| `ReloadableResourceBundleMessageSource 20 basename i18n/messages 22` | UTF-8 cacheSeconds 5 | `ResourceBundleMessageSource` not reloadable | Dev hot-reload `.properties` `fr` edit no restart; prod cache 3600 | `File I/O` 5s poll dev |
| `AcceptHeaderLocaleResolver` default | `Accept-Language fr` | `Session/CookieLocaleResolver` | Stateless API `X-API-Key` no session; `Controller` locale param | No per-user persistent locale |
| `FeatureFlags @ConfigurationProperties 16 app.features 209` | `csvExport true 19` env `APP_FEATURES_CSVEXPORT` | `spring.profiles` segmentation | Runtime toggle per `Kustomize` `overlays/prod false` no redeploy jar | `envFrom` restart still `25s` drain needed |
| `ExportController gated 34 produce csv 33` | `findAll→toCsvRow46` in-memory `String` | `StreamingResponseBody cursor fetch_size100 65` | `~1k` products heap `String` okay; simple | `100k` `OOM` `MaxRAM75 1Gi` |
| `FeatureFlagsController 27` expose live map | `actuator/info` only | No exposure | `curl features` `ArgoCD` drift check per `overlay` | Leaks toggle to `unauth` (requires `API-key`) |
| Multi-tenancy stub `X-Tenant-Id` `tenant_id` column + `Filter` | `single DB predicate` | `schema per tenant / DB per tenant` | `Liquibase v1 01_create_tables.sql 10` single migration; `K8s` scale `2 pods` | `tenant_id` index per query; `RLS` future |
| GDPR `PortabilityResponse 20 Art20 108` vs `csv` | Structured `JSON` vs `text/csv` | Single `csv` for both | `Json` regulatory `Portability` vs `csv` business `finance` | Two exporters |

---

## 7. How to verify

```bash
grep -n "ReloadableResourceBundleMessageSource\|basename.*classpath:i18n/messages\|CacheSeconds" src/main/java/com/company/orderapi/config/I18nConfig.java  # 20 22
grep -n "I18nController\|getMessage.*key.*locale" src/main/java/com/company/orderapi/api/rest/controller/I18nController.java | head  # 16 22-24
grep -n "FeatureFlags\|csvExport\|isCsvExport\|setCsvExport\|ConfigurationProperties.*app.features" src/main/java/com/company/orderapi/config/FeatureFlags.java | head #16 19 24 28
grep -n "ExportController\|products.csv.*text/csv\|if.*isCsvExport.*FeatureDisabled\|toCsvRow" src/main/java/com/company/orderapi/api/rest/controller/ExportController.java | head  #21 33 34 46
grep -n "FeatureFlagsController\|/api/v1/features" src/main/java/com/company/orderapi/api/rest/controller/FeatureFlagsController.java | head  #16 27
grep -n "app.features.csv-export\|app.features.reporting" src/main/resources/application.yml  #207 209 210
ls src/main/resources/i18n/messages.properties src/main/resources/i18n/messages_*.properties 2>/dev/null | head
./mvnw test -Dtest=ExportControllerTest,FeatureFlagsTest,I18nTest 2>&1 | grep -E "Tests run|Export|Feature"
# Live flag read
./mvnw spring-boot:run & sleep 12; curl -s http://localhost:8080/api/v1/features -H "X-API-KEY: dev-api-key-orderapi" | jq .; kill %1
```

---

## 8. How this helps you on the job — build / operate / interview

- **Build:** New NLS string → `src/main/resources/i18n/messages.properties` `order.invoice.total=Total {0}` + `messages_fr.properties` `Total {0}=Total {0} fr`; inject `MessageSource 19` and `getMessage(key, args, locale)` (`I18nController 22`). New capability `invoicePdf` → add `boolean invoicePdf` to `FeatureFlags 19` `isInvoicePdf/set`, annotate `@ConditionalOnProperty app.features.invoice-pdf` bean or `if(!flags.isInvoicePdf) throw`, expose via `FeatureFlagsController 27` map; per-env `overlays/prod` `ConfigMap app.features.invoice-pdf false`.
- **Operate:** `curl /api/v1/features` `200` verifies `Kustomize` overlay applied per env; `curl /export/products.csv -H Accept-Language` `503 FEATURE_DISABLED` after `APP_FEATURES_CSVEXPORT=false` `kubectl rollout restart` `termination25>20s` after flag patch. `prometheus` alert `export csv 5xx rate` via `http_server_requests_seconds metric`. Multi-tenant `X-Tenant-Id` enables per `customer tenant_id` query `filter`. Rotate `messages*.properties` via `Reloadable 5s` dev zero-downtime `i18n`.
- **Interview:** "PR #35: `I18nConfig.java:19 MessageSource ReloadableResourceBundle classpath:i18n/messages22 Accept-Language chain`, `I18nController24 getMessage`, `FeatureFlags.java:16 app.features csvExport19 true209 reporting false FeatureFlagsController27 map`, `ExportController21 text/csv33 gated isCsvExport34 503 else toCsvRow46 findAll`, `GdprService85 PortabilityResponse20 Art20`. `Kustomize overlays prod app.features.csv-export false` toggles without `Dockerfile` rebuild; `Reloadable cacheSeconds` hot `i18n`; `tenant_id Filter stub`."

---

## 9. Interview lens — Q&A

**Q1: Reloadable vs plain ResourceBundle?**
A: `ReloadableResourceBundleMessageSource 20` hot-reloads `.properties` `cacheSeconds 5` dev; plain requires restart (§2.1).

**Q2: How does locale fallback work?**
A: `messages_fr_CH → messages_fr → messages 22` chain `I18nConfig 11`; resolver reads `Accept-Language` header (§2.1,2.6).

**Q3: How to disable CSV export live with no deploy?**
A: `k8s/overlays/prod ConfigMap app.features.csv-export false 207 209` or `env APP_FEATURES_CSVEXPORT=false` → `ExportController 34 503 FEATURE_DISABLED` `flags.isCsvExport 24` (§2.2-2.3).

**Q4: Why return String vs StreamingResponseBody for csv?**
A: `~1k` products fits heap; `100k` needs `StreamingResponseBody` cursor `fetch_size100 65` to avoid `OOM 1Gi MaxRAM75` — current honest limit (§2.3).

**Q5: How is multi-tenancy stubbed?**
A: `tenant_id` column `Customer` + `HandlerInterceptor X-Tenant-Id → Hibernate Filter tenant_id=:tid` `single DB predicate` `overlays` per tenant `ConfigMap`; full `RLS` deferred (§2.4).

**Q6: What proves `i18n` verifies?**
A: `MessageSourceTest` asserts `fr Expédié`; `curl /api/v1/messages/order.status.shipped -H Accept-Language: fr | jq .value` (§2.9).

**Q7: `FeatureFlagsController` vs `actuator/info`?**
A: `FeatureFlagsController 16 /api/v1/features` lists live `csvExport,reporting 27` per `API-key` `Security 188`; `info` also shows `app.name` but not mutable flags (§2.8).

---

## 10. Honest limits & next step → PR #36

`toCsvRow 46` naive `escape` no `OpenCSV RFC4180` quote; heap `String` not streaming `100k` `OOM`. `ConfigMap` flag change still requires `pod restart 25s` — need `Reloader`/`spring-cloud-kubernetes` watch. Multi-tenancy no `RLS/COLUMN` `SET LOCAL` isolation yet — predicate manually applied, `SKIP LOCKED 24` cross-tenant leak possible. `messages*.properties` only `EN/FR` sparse. Next `Bonus RAG` `PR #36-52` assumes this platform: `RAG` `answers` call `I18nController` for NLS answers and `ExportController` `CSV` context; `MCP` `mcp-spring-webmvc 224` tools expose `features` and `i18n` as `LLM` tool, wiring `RagConfig PgVector 259` and `Ollama embedding` `128` alongside existing `Observability`.

See [`../more-detail-on-additions/README.md`](../more-detail-on-additions/README.md) or [`README.md`](./README.md).

---

## Appendix — decision cheat-sheet

| Need | Tool | File:line | Why |
|---|---|---|---|
| NLS | `ReloadableResourceBundleMessageSource` | `I18nConfig.java:19` | `i18n/messages22 hot reload` |
| Query NLS value | `I18nController.getMessage` | `I18nController.java:24` | `key+args locale fallback` |
| Kill CSV without deploy | `FeatureFlags csvExport19 gated 34 503` | `FeatureFlags.java:19 ExportController.java:34` | `app.features.csv-export 209 overlay patch` |
| Show flags live | `FeatureFlagsController 27` | `FeatureFlagsController.java:27` | `ops curl` |
| Tenant isolation (stub) | `X-Tenant-Id Filter` | `TenantInterceptor stub` | `single DB predicate` |
| Portability JSON | `PortabilityResponse Art20` | `GdprService.java:85` | `GDPR audit` |
