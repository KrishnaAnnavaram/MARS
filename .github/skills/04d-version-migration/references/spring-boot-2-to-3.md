---
id: spring-boot-2-to-3
title: Spring Boot 2.x to 3.x (Java 8/11/17 to 17+, javax to jakarta)
stack: Spring Boot
from: "2"
to: "3"
language_from: "8"
language_to: "17"
detect:
  - org.springframework.boot:spring-boot-starter-parent:2
  - org.springframework.boot:spring-boot-dependencies:2
# --- Optional metadata (see references/README.md). The exact target always comes from the request.
# --- In a ladder session this pack supplies the RULES for the 2.x -> 3.0 MAJOR_BOUNDARY edge; the
# --- deterministic transformation is the ladder's upstream UpgradeSpringBoot_3_0 (Apache-2.0 stack,
# --- rewrite-spring 5.24.1) — see references/openrewrite/spring-boot-ladder.json.
provenance:
  sources:
    - https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide
    - https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Release-Notes
    - https://docs.spring.io/spring-security/reference/6.0/migration/index.html
    - https://github.com/spring-projects/spring-data-commons/wiki/Spring-Data-2022.0-%28Turing%29-Release-Notes
    - https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2022.0-Release-Notes
  last_reviewed: "2026-10-01"
target_constraints:
  platform_major: "3"
  language_min: "17"
  note: Boot 3.0 requires Java 17 and Jakarta EE 9+ (Servlet 6.0, JPA 3.1 in 3.0). Confirm the exact line's requirements from its release notes and resolved BOM (section 11).
path:
  preparation_line: "2.7"
  note: Spring's guidance is to upgrade to the latest 2.7.x and clear its deprecations before crossing to 3.0. The ladder plans that PATCH edge first.
surfaces:
  - id: jakarta-namespace
    label: javax.* APIs that moved to jakarta.* (servlet, persistence, validation, annotation, transaction, ws.rs, xml.bind, inject, mail, websocket)
    rule: section 2
    patterns:
      - 'import javax\.(servlet|persistence|validation|annotation\.(PostConstruct|PreDestroy|Resource|Generated|security)|transaction|ws\.rs|xml\.bind|inject|mail|websocket|activation)'
  - id: security
    label: Spring Security configuration (WebSecurityConfigurerAdapter, antMatchers, global method security)
    rule: section 4
    patterns:
      - 'WebSecurityConfigurerAdapter'
      - 'antMatchers|mvcMatchers|authorizeRequests\('
      - 'EnableGlobalMethodSecurity'
  - id: web-api
    label: Controllers whose paths may rely on trailing-slash matching (now off by default)
    rule: section 5.1
    patterns:
      - '@(Get|Post|Put|Delete|Patch|Request)Mapping'
  - id: data-repositories
    label: Spring Data repositories built on PagingAndSortingRepository alone
    rule: section 6.2
    patterns:
      - 'extends\s+PagingAndSortingRepository'
  - id: autoconfig-registration
    label: Auto-configuration registered in spring.factories
    rule: section 7.3
    scope: all
    patterns:
      - 'EnableAutoConfiguration'
  - id: cloud
    label: Spring Cloud annotations removed in 2022.0
    rule: section 8
    patterns:
      - '@EnableEurekaClient'
      - 'spring-cloud-starter-sleuth|org\.springframework\.cloud\.sleuth'
  - id: http-client
    label: Apache HttpClient 4 behind RestTemplate
    rule: section 7.2
    patterns:
      - 'org\.apache\.http\.'
ecosystem_boms:
  - coordinate: org.springframework.cloud:spring-cloud-dependencies
    property: spring-cloud.version
    note: Boot 3.0/3.1 need Spring Cloud 2022.0.x; 3.2/3.3 need 2023.0.x. The ladder pins the train per edge and verifies it against the train's spring-cloud-starter-parent POM.
  - coordinate: org.springdoc:springdoc-openapi-ui
    note: springdoc 1.x does not support Boot 3; move to org.springdoc:springdoc-openapi-starter-webmvc-ui 2.x (section 1). Springfox has no Boot 3 release.
verification:
  - mvn -q dependency:tree -Dincludes=jakarta.servlet,javax.servlet
  - mvn help:effective-pom | grep -A2 "<artifactId>spring-boot-dependencies"
---

# Spring Boot 2.x → 3.x

The boundary that carries the **Jakarta EE namespace move**, the **Java 17 baseline**, **Spring
Framework 6**, **Spring Security 6**, **Hibernate 6** and **Spring Cloud 2022.0**. Each is its own
failure mode, which is why the ladder gives this boundary its own edge (2.7.x → 3.0.x) instead of
folding it into a longer jump.

In a ladder session the deterministic part of this boundary is OpenRewrite's
`org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0` from the Apache-2.0 stack (it chains the
2.x recipes, `UpgradeToJava17`, the javax→jakarta migration, Spring Framework 6, Security 6,
Hibernate 6.1 and Spring Cloud 2022). This pack is what residual repair reads when the build still
fails after it.

## 0. Before changing anything

- Be on the latest **2.7.x** first and clear its deprecation warnings (the ladder's PATCH edge).
- Install **JDK 17** (the edge builds on it). Lombok must be **≥ 1.18.22** on Java 17; Boot 3 manages
  a newer one — an explicit older Lombok pin shows up as `cannot find symbol` on getters/setters.

## 1. Build file

| Before (2.x) | After (3.x) | Symptom if missed |
|---|---|---|
| `spring-boot-starter-parent` 2.x | 3.0.x (then onward per the path) | — |
| `java.version` 8/11 | **17** | `invalid target release: 17` on an old JDK, or `class file has wrong version 61.0` |
| `javax.*` API artifacts (e.g. `javax.servlet:javax.servlet-api`, `javax.ws.rs:javax.ws.rs-api`) | `jakarta.*` artifacts (`jakarta.servlet:jakarta.servlet-api`, `jakarta.ws.rs:jakarta.ws.rs-api`) | `package jakarta.servlet does not exist` after the import move |
| `org.springdoc:springdoc-openapi-ui` 1.x | `org.springdoc:springdoc-openapi-starter-webmvc-ui` 2.x | startup failure / missing classes |
| `org.apache.httpcomponents:httpclient` (4.x) used by `RestTemplate` | `org.apache.httpcomponents.client5:httpclient5` | `package org.apache.http.impl.client does not exist` (section 7.2) |
| Spring Cloud train 2021.0.x | **2022.0.x** for Boot 3.0/3.1 | `ClassNotFoundException` / incompatible auto-configuration at startup |

## 2. javax → jakarta

**Only these packages moved** (Jakarta EE 9 namespace change). Moving anything else breaks the build:

| Moved (`javax.X` → `jakarta.X`) | Did **not** move (JDK or unrelated) |
|---|---|
| `servlet`, `persistence`, `validation`, `transaction`, `annotation.PostConstruct`/`PreDestroy`/`Resource`/`Generated`/`security.*`, `ws.rs`, `xml.bind`, `xml.soap`, `xml.ws`, `inject`, `mail`, `websocket`, `activation`, `json`, `jws`, `ejb`, `faces`, `el`, `enterprise` | `javax.sql`, `javax.crypto`, `javax.net`, `javax.naming`, `javax.management`, `javax.security.auth`, `javax.xml.parsers`, `javax.xml.transform`, `javax.xml.stream`, `javax.xml.xpath`, `javax.annotation.processing`, `javax.lang.model`, `javax.imageio`, `javax.swing`, `javax.script`, `javax.cache` (JSR-107) |

Symptoms: `package javax.servlet does not exist`, `package javax.persistence does not exist`,
`package javax.validation.constraints does not exist`, `cannot find symbol: class Entity`.

A third-party library compiled against `javax.*` (an old Jersey 2, a JAX-RS client, a SOAP stack)
needs its Jakarta release — the import move alone leaves a `NoClassDefFoundError: javax/...` at
runtime. Verify each with `mvn dependency:tree` (section 11).

## 3. Java 17

`javax.annotation.Generated` / `javax.xml.bind` are no longer in the JDK; illegal reflective access
to JDK internals now fails (`InaccessibleObjectException`) — an old library doing it needs upgrading,
or an explicit `--add-opens` recorded as a residual edit with its evidence.

## 4. Spring Security 6

| Before | After | Symptom |
|---|---|---|
| `extends WebSecurityConfigurerAdapter` + `configure(HttpSecurity)` | a `@Bean SecurityFilterChain filterChain(HttpSecurity http)` | `cannot find symbol: class WebSecurityConfigurerAdapter` |
| `authorizeRequests()` | `authorizeHttpRequests()` | deprecation, then behaviour differences in 6.x |
| `antMatchers(..)` / `mvcMatchers(..)` / `regexMatchers(..)` | `requestMatchers(..)` | `cannot find symbol: method antMatchers(String)` |
| `@EnableGlobalMethodSecurity(prePostEnabled = true)` | `@EnableMethodSecurity` | deprecation; `prePostEnabled` is the default |
| `configure(AuthenticationManagerBuilder)` | a `UserDetailsService` / `AuthenticationManager` bean | `cannot find symbol` on the overridden method |

The **security boundary** probes (authenticated / unauthenticated / bad credentials) must keep the
same status codes across this edge.

## 5. Spring Framework 6 / Spring MVC

### 5.1 Trailing-slash matching is off by default

`GET /api/v1/items/` no longer matches `@GetMapping("/api/v1/items")` — it returns **404**. This
compiles and starts; only a probe or a test sees it. Do not re-enable it silently: if a client relies
on it, record it as a behaviour change (or add an explicit `configurePathMatch` with the evidence).

### 5.2 Status types

`ResponseEntity.getStatusCode()` returns `HttpStatusCode` (not `HttpStatus`); `getStatusCodeValue()`
is deprecated. Code comparing with `HttpStatus` constants still compiles via `HttpStatus implements
HttpStatusCode`. **Recompile everything**: classes compiled against Framework 5 fail at runtime with
`NoSuchMethodError: ...ResponseEntity.<init>(Object, HttpStatus)`.

## 6. Persistence — Hibernate 6.1, Spring Data 2022.0

- `javax.persistence` → `jakarta.persistence` (section 2).
- **6.2 `PagingAndSortingRepository` no longer extends `CrudRepository`.** A repository declared only as
  `extends PagingAndSortingRepository<T, ID>` loses `save`, `findById`, `deleteById`:
  `cannot find symbol: method findById(...)`. Add `CrudRepository<T, ID>` (or `ListCrudRepository`)
  to its `extends` list.
- Hibernate 6 ID generation: the default sequence for `@GeneratedValue(strategy = AUTO)` is now
  `<entity>_seq` per entity instead of one `hibernate_sequence` — a schema concern for an existing
  database; record it in `manual_follow_ups`, do not change the mapping without evidence.
- `@Type(type = "...")` string form is removed (`cannot find symbol: method type()`); Hibernate 6 uses
  `@JdbcTypeCode` / `@Type(SomeType.class)`.

## 7. Configuration and runtime

### 7.1 Renamed properties (startup warns; values silently ignored if missed)

| Before | After |
|---|---|
| `spring.redis.*` | `spring.data.redis.*` |
| `server.max-http-header-size` | `server.max-http-request-header-size` |
| `management.metrics.export.<system>.*` | `management.<system>.metrics.export.*` |
| `spring.jpa.hibernate.use-new-id-generator-mappings` | removed |
| `management.trace.http.*` | `management.httpexchanges.*` |

Temporarily adding `org.springframework.boot:spring-boot-properties-migrator` (runtime scope) prints
every renamed key at startup — use it as evidence, then remove it.

### 7.2 RestTemplate and Apache HttpClient

`HttpComponentsClientHttpRequestFactory` needs **HttpClient 5**: `package org.apache.http.impl.client
does not exist` → switch the dependency and imports to `org.apache.hc.client5.*`.

### 7.3 Auto-configuration registration

`META-INF/spring.factories` `org.springframework.boot.autoconfigure.EnableAutoConfiguration=...` is no
longer read for auto-configuration → move the entries to
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Symptom: a bean
from your own auto-configuration is missing at startup (`NoSuchBeanDefinitionException`).

### 7.4 Actuator

`/actuator/env` and `/actuator/configprops` values are masked by default
(`management.endpoint.env.show-values=never`) — a probe body difference that is **expected** for this
jump. `httptrace` is now `httpexchanges` (`HttpTraceRepository` → `HttpExchangeRepository`).

## 8. Spring Cloud 2022.0

- `@EnableEurekaClient` was removed (`cannot find symbol: class EnableEurekaClient`) — delete it;
  having the Eureka client starter on the classpath registers the service.
- Spring Cloud Sleuth is replaced by Micrometer Tracing (`io.micrometer:micrometer-tracing-bridge-brave`).
- `bootstrap.properties` is still read only with `spring-cloud-starter-bootstrap`; otherwise use
  `spring.config.import=optional:configserver:` in `application.properties`.

## 9. Symptom → rule lookup

| Compiler / runtime message | Rule |
|---|---|
| `package javax.servlet does not exist` / `javax.persistence` / `javax.validation` | §2 |
| `NoClassDefFoundError: javax/...` at startup | §2 third-party library still on javax |
| `invalid target release: 17` / `release version 17 not supported` | §1, §0 (JDK) |
| `cannot find symbol: class WebSecurityConfigurerAdapter` | §4 |
| `cannot find symbol: method antMatchers(...)` | §4 |
| `cannot find symbol: method findById(...)` on a paging repository | §6.2 |
| `cannot find symbol: class EnableEurekaClient` | §8 |
| `package org.apache.http.impl.client does not exist` | §7.2 |
| `NoSuchMethodError: ...ResponseEntity.<init>(Object, HttpStatus)` | §5.2 (stale classes) |
| 404 on a URL ending in `/` that used to answer | §5.1 |
| `cannot find symbol` on Lombok getters/setters | §0 Lombok on Java 17 |
| `InaccessibleObjectException` at startup | §3 |

## 10. What this jump does *not* require

Rewriting business logic, changing the persistence model, moving to Spring Security's lambda DSL
everywhere, reformatting, upgrading unrelated libraries, or adopting virtual threads, observability
or native images. Record any such proposal from a recipe as out of scope.

## 11. Verify a coordinate or class before using it

```bash
mvn -q dependency:tree -Dincludes=jakarta.servlet           # the Jakarta API actually resolved
mvn -q dependency:tree -Dincludes=javax.servlet,javax.ws.rs  # nothing javax left on the classpath
unzip -l ~/.m2/repository/<path>/<artifact>-<version>.jar | grep <Class>
```
