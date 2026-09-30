---
id: spring-boot-3-to-4
title: Spring Boot 3.x to 4.x (Java 17 to 21)
stack: Spring Boot
from: "3"
to: "4"
language_from: "17"
language_to: "21"
detect:
  - org.springframework.boot:spring-boot-starter-parent:3
  - org.springframework.boot:spring-boot-dependencies:3
  - org.springframework.boot:spring-boot-starter-web
---

# Spring Boot 3.x → 4.x

Spring Boot 4 is a major-generation jump, not a patch. It carries Spring Framework 7,
Spring Security 7, Hibernate ORM 7 and **Jackson 3** with it, renames several starters, and moves
a number of types to new packages. The observable consequence: **the source code has to change**,
not just the version numbers. Business logic and REST contracts normally stay exactly as they are —
what breaks is every place the application touches the framework directly.

The five changes that show up in almost every real Boot 3 → 4 migration, in the order the compiler
usually surfaces them:

| # | Area | Nature of the change |
|---|---|---|
| 1 | JSON handling | Jackson 2 → Jackson 3: new package root, new mapper type, new configuration API |
| 2 | Actuator health | `HealthIndicator` and `Health` moved to a new package |
| 3 | Test layer | Removed test annotations, new mocking annotations, new mapper type in tests |
| 4 | Starters | `spring-boot-starter-web` split by stack; test starters modularised |
| 5 | Language level | Java 17 → 21, in the build, the toolchain and the container image |

---

## 0. Before changing anything

1. **Pin the exact target version.** Decide the concrete `spring-boot-starter-parent` version
   (e.g. `4.1.1`), not "latest". Every rule below is written against Boot 4.x generally; the exact
   patch release decides the managed versions underneath it.
2. **Read that release's own baseline.** Boot 4's documented minimum Java level and its managed
   library versions come from the release notes and the resolved BOM, not from this file. Confirm
   before you assume — see [§11](#11-verify-a-coordinate-or-class-before-using-it).
3. **Java 21 is the target here — by intent, not because the compiler forces it.** Java 21 is the
   LTS the Boot 4 generation is built and tested against. Do **not** assume the build will refuse to
   run on the old JDK: a Boot 4 BOM can resolve and compile under Java 17, and when it does the first
   failures are package relocations (§2, §3), not a release-level error. Confirm the target release's
   documented minimum, then move the language level deliberately — a migration that leaves the
   project on the older runtime is only half done.
4. **Record the pre-migration behaviour first.** Baseline build plus a runtime probe of the real
   endpoints, on the *old* JDK, before any file is touched. Without it there is nothing to compare
   the migrated application against.

---

## 1. Build file — dependencies and coordinates

### 1.1 Parent / BOM

```diff
- <version>3.5.0</version>   <!-- spring-boot-starter-parent -->
+ <version>4.1.1</version>
```

Nothing else can move until this does: the parent manages the version of every
`org.springframework.boot` artifact, plus Jackson, Hibernate, Micrometer and the test stack.

### 1.2 Language level

```diff
- <java.version>17</java.version>
+ <java.version>21</java.version>
```

If the project also pins `maven-compiler-plugin` `<source>`/`<target>` explicitly, change those too —
a stale `<source>17</source>` overrides the property and the build silently keeps compiling at 17:

```diff
- <source>17</source>
- <target>17</target>
+ <release>21</release>
```

**Symptom if missed:** `invalid target release: 21`, `release version 21 not supported`, or
`class file has wrong version 65.0, should be 61.0` / `unsupported class file major version`.

### 1.3 Starter renames

| Before (Boot 3) | After (Boot 4) | Note |
|---|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Servlet MVC stack; the starter is now named for its stack |
| `spring-boot-starter-webflux` | `spring-boot-starter-webflux` | Unchanged |
| `spring-boot-starter-test` | modularised — see §4.3 | Boot 4 splits the test starter into per-slice modules |

**Symptom if missed:** `Could not resolve dependencies … spring-boot-starter-web:jar:4.x` — a
dependency-resolution failure, not a compile failure, so it appears in the very first round.

### 1.4 Ecosystem libraries that move with Boot 4

| Library | Boot 3 line | Boot 4 line | Note |
|---|---|---|---|
| Jackson | 2.x (`com.fasterxml.jackson`) | 3.x (`tools.jackson`) | Managed by the parent — see §2 |
| Spring Framework | 6.x | 7.x | Managed |
| Spring Security | 6.x | 7.x | Managed — see §5 |
| Hibernate ORM | 6.x | 7.x | Managed — see §6 |
| springdoc-openapi | 2.x (e.g. `2.8.14`) | 3.x (e.g. `3.1.0`) | **Explicitly versioned in the pom — must be bumped by hand** |

springdoc is the common trap: it is not managed by the Boot BOM, so it keeps its Boot 3-era version
and fails at runtime or on resolution. The artifact id for the servlet stack stays
`springdoc-openapi-starter-webmvc-ui`; only the version moves. *Verify the exact 3.x version is
published before pinning it.*

Any other third-party library that integrates with Spring internals (mapping frameworks, cache
providers, tracing bridges) needs the same treatment: an explicitly pinned version that predates
Boot 4 is a resolution or `NoSuchMethodError` waiting to happen.

---

## 2. Jackson 2 → Jackson 3 (the largest source change)

Jackson 3 changes its package root, its entry-point type and its configuration API. Boot 4's
Jackson auto-configuration changes with it. This is normally where most of the compile errors are.

### 2.1 Package and type moves

| Boot 3 / Jackson 2 | Boot 4 / Jackson 3 |
|---|---|
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.json.JsonMapper` |
| `com.fasterxml.jackson.databind.SerializationFeature` | `tools.jackson.databind.SerializationFeature` |
| date/time serialization features on `SerializationFeature` | `tools.jackson.databind.cfg.DateTimeFeature` |
| `org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer` | `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` |
| `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule` (registered manually) | built in — `java.time` support is on by default, drop the manual registration |
| `com.fasterxml.jackson.annotation.*` (`@JsonInclude`, `@JsonProperty`, …) | **unchanged** — the annotations package keeps its name |

That last row matters: annotations on DTOs and entities do **not** need touching. Only the
databind/configuration side moves.

### 2.2 Configuration API

The Boot 3 shape — build an `ObjectMapper` bean directly, or customise the Jackson 2 builder:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

@Bean
public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    mapper.disable(SerializationFeature.INDENT_OUTPUT);
    return mapper;
}
```

becomes, in Boot 4:

```java
import com.fasterxml.jackson.annotation.JsonInclude;          // unchanged
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

@Bean
public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
    return builder -> builder
            .changeDefaultPropertyInclusion(
                    inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.INDENT_OUTPUT);
}
```

Three distinct changes are folded into that rewrite, and each has its own compiler symptom:

| Change | Symptom |
|---|---|
| `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` | `package org.springframework.boot.autoconfigure.jackson does not exist` |
| `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` → `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` | `cannot find symbol: variable WRITE_DATES_AS_TIMESTAMPS` |
| `setDefaultPropertyInclusion(...)` → `changeDefaultPropertyInclusion(inclusion -> ...)` | `cannot find symbol: method setDefaultPropertyInclusion` |

**Prefer customising the builder over defining your own mapper bean.** A hand-built mapper bypasses
Boot's auto-configuration; the customizer keeps every Boot default and applies only your deltas.

### 2.3 Injected mappers

Anywhere an `ObjectMapper` is injected — controllers, services, tests, serializers — becomes
`JsonMapper`:

```diff
- import com.fasterxml.jackson.databind.ObjectMapper;
+ import tools.jackson.databind.json.JsonMapper;

- private final ObjectMapper objectMapper;
+ private final JsonMapper jsonMapper;
```

`writeValueAsString` / `readValue` keep their names, so call sites usually compile unchanged once
the type and import are swapped. Checked-exception behaviour differs — Jackson 3 uses unchecked
exceptions — so a `try/catch (JsonProcessingException)` around a mapper call may become
*unreachable*, which the compiler reports as an error, not a warning.

---

## 3. Actuator — health contributor package

```diff
- import org.springframework.boot.actuate.health.Health;
- import org.springframework.boot.actuate.health.HealthIndicator;
+ import org.springframework.boot.health.contributor.Health;
+ import org.springframework.boot.health.contributor.HealthIndicator;
```

The types, their builder API (`Health.up()`, `.withDetail(...)`, `.build()`) and the
`@Component("name")` registration pattern are unchanged — only the package moves. A genuine
compile-time relocation, and one of the cleanest examples of "the upgrade changed our source".

**Symptom:** `package org.springframework.boot.actuate.health does not exist`.

Check every actuator extension point the application implements, not just health: custom
endpoints (`@Endpoint`, `@ReadOperation`), `InfoContributor`, and metrics bindings can move in the
same way. *Verify each against the resolved jar before rewriting the import.*

---

## 4. Test layer

Tests break separately from main code and usually a round later, because a `test-compile` only runs
once `compile` is clean. Expect a second wave of errors.

### 4.1 Mocking annotations

`@MockBean` and `@SpyBean` were deprecated in Boot 3.4 and are **removed** in Boot 4:

```diff
- import org.springframework.boot.test.mock.mockito.MockBean;
+ import org.springframework.test.context.bean.override.mockito.MockitoBean;

- @MockBean
+ @MockitoBean
  private EmployeeService employeeService;
```

`@SpyBean` → `@MockitoSpyBean`, from the same `org.springframework.test.context.bean.override.mockito`
package. Note the new home is in Spring Framework's test module, not Boot's.

**Symptom:** `package org.springframework.boot.test.mock.mockito does not exist`.

### 4.2 Slice tests and injected mappers

A `@WebMvcTest` slice that relied on an auto-configured `ObjectMapper` needs the Jackson 3 type, and
in practice a slice that pulled in a hand-built mapper bean is simplest to move to a full context:

```diff
- @WebMvcTest(EmployeeController.class)
- @Import(SecurityConfig.class)
+ @SpringBootTest
+ @AutoConfigureMockMvc

- @Autowired private ObjectMapper objectMapper;
+ @Autowired private JsonMapper jsonMapper;
```

Widening a slice to `@SpringBootTest` is a real behavioural change to the test — it now loads the
whole context. Prefer keeping the slice if it still works; when you do widen one, say so in the
report rather than letting it pass as a mechanical import change.

Testing against real HTTP Basic credentials (`.with(httpBasic("demo", "demo123"))`) rather than
`@WithMockUser` is closer to runtime behaviour and survives security-configuration changes better,
but it is an improvement, not a migration requirement — see §10.

### 4.3 Test starters — and the slice annotations that moved with them

Boot 4 modularises `spring-boot-starter-test` into per-concern modules. A project that used the
single starter has to add the module for each slice it actually tests — **and repoint the import,
because the annotations moved package too**. Adding the starter alone changes nothing; that is the
most common wasted round in this migration.

| Slice | Add (test scope) | New import |
|---|---|---|
| `@WebMvcTest`, `@AutoConfigureMockMvc` | `org.springframework.boot:spring-boot-starter-webmvc-test` | `org.springframework.boot.webmvc.test.autoconfigure.*` |
| `@DataJpaTest` | `org.springframework.boot:spring-boot-starter-data-jpa-test` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| Security test support (see below) | `org.springframework.boot:spring-boot-starter-security-test` | — |

**Symptoms:** `package org.springframework.boot.test.autoconfigure.web.servlet does not exist`,
`package org.springframework.boot.test.autoconfigure.orm.jpa does not exist`.

For any slice not in this table, resolve the artifact from the BOM and the package from the jar
(§11) — the pattern is consistent (`spring-boot-starter-<area>-test`, package
`org.springframework.boot.<area>.test.autoconfigure`), but confirm rather than extrapolate.

### 4.4 `@WithMockUser` silently stops authenticating

The nastiest failure in this migration compiles cleanly and only shows up when the suite runs: every
test relying on `@WithMockUser` returns **401** instead of the expected status, while tests that send
real credentials with `.with(httpBasic(...))` keep passing.

The cause is the same modularisation: the MockMvc security test auto-configuration that applies the
test `SecurityContext` now lives in Boot's own security-test starter. A project that declared the
raw artifact directly is left without it.

```diff
- <dependency>
-     <groupId>org.springframework.security</groupId>
-     <artifactId>spring-security-test</artifactId>
-     <scope>test</scope>
- </dependency>
+ <dependency>
+     <groupId>org.springframework.boot</groupId>
+     <artifactId>spring-boot-starter-security-test</artifactId>
+     <scope>test</scope>
+ </dependency>
```

The split between what passes and what fails is the diagnostic: filter-chain behaviour intact,
test-side security context missing.

---

## 5. Spring Security 6 → 7

Security 7 arrives with Boot 4 and removes what Security 6 deprecated. Code already written in the
**lambda DSL** — `http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> ...)` — normally
compiles unchanged. Code still using the removed chained/`and()` style does not.

```java
// This shape is Security 7-compatible and needs no change:
http
    .csrf(csrf -> csrf.disable())
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/api/**").authenticated()
        .anyRequest().permitAll())
    .httpBasic(Customizer.withDefaults());
```

**Symptom of a real Security 7 break:** `cannot find symbol: method and()`, or
`method authorizeRequests() … cannot be applied`.

**Do not attribute unrelated security edits to the upgrade.** Adding an H2-console permit rule or
frame-options handling is configuration revalidation, not a Security 7 breaking change. It belongs
in the report's "not caused by the upgrade" section.

---

## 6. Hibernate 6 → 7 and Spring Data JPA

Entity mappings using `jakarta.persistence` annotations carry over — Boot 3 already made the
`javax` → `jakarta` move, so there is no namespace work left.

What to watch, symptom-first:

| Symptom | Likely cause |
|---|---|
| Startup fails on dialect or driver resolution | Dialect auto-detection and removed dialect classes in Hibernate 7 |
| Schema differs / new sequence tables appear | Identifier-generation defaults changed for `@GeneratedValue` |
| `cannot find symbol` on a Hibernate `Session`/`Criteria` API | Removed legacy API surface in ORM 7 |
| A `@Query` with a native SQL fragment fails | Stricter HQL/JPQL parsing |

Repository interfaces (`JpaRepository`, derived query methods, `@Query` JPQL) normally need no
change. Verify with the integration tests and, where a database is involved, against the real
database rather than an in-memory substitute.

---

## 7. Configuration properties and runtime

Boot 4 removes properties deprecated across the 3.x line. These do **not** fail the compiler —
they fail, or silently do nothing, at startup.

- Add `org.springframework.boot:spring-boot-properties-migrator` at `runtime` scope temporarily.
  It reports renamed and removed properties on startup with the replacement name. Remove it once
  the report is clean — it is a migration aid, not a dependency.
- Re-check anything under `management.*`, `spring.jpa.*`, `spring.mvc.*` and logging configuration.
- A property that no longer exists is inert, so the symptom is behavioural: an endpoint that is
  suddenly exposed or hidden, a format that changed, a log level that stopped applying.

---

## 8. Container image and CI

```diff
- FROM eclipse-temurin:17-jre
+ FROM eclipse-temurin:21-jre
```

Also update, wherever they exist: `docker-compose.yml` build args, CI workflow
`setup-java` versions, `.sdkmanrc` / `.tool-versions`, and any deployment descriptor pinning a JRE.

**Symptom if missed:** the build is green and the container fails at start with
`has been compiled by a more recent version of the Java Runtime`.

---

## 9. Symptom → rule lookup

Read a failing round's errors and come here first.

| Compiler / build error | Rule |
|---|---|
| `Could not resolve dependencies … spring-boot-starter-web:jar:4.x` | §1.3 starter rename |
| `invalid target release: 21` / `release version 21 not supported` | §1.2 — the build is running on an older JDK |
| `class file has wrong version 65.0` / `unsupported class file major version` | §1.2 / §8 — JDK mismatch between build and runtime |
| `package com.fasterxml.jackson.databind does not exist` | §2.1 Jackson package move |
| `package org.springframework.boot.autoconfigure.jackson does not exist` | §2.2 customizer rename |
| `cannot find symbol: variable WRITE_DATES_AS_TIMESTAMPS` | §2.2 `DateTimeFeature` |
| `cannot find symbol: method setDefaultPropertyInclusion` | §2.2 `changeDefaultPropertyInclusion` |
| `exception JsonProcessingException is never thrown` | §2.3 unchecked exceptions in Jackson 3 |
| `package org.springframework.boot.actuate.health does not exist` | §3 actuator relocation |
| `package org.springframework.boot.test.mock.mockito does not exist` | §4.1 `@MockitoBean` |
| `package org.springframework.boot.test.autoconfigure.web.servlet does not exist` | §4.3 `@WebMvcTest` moved module and package |
| `package org.springframework.boot.test.autoconfigure.orm.jpa does not exist` | §4.3 `@DataJpaTest` moved module and package |
| `cannot find symbol: class ObjectMapper` in a test | §2.3 / §4.2 `JsonMapper` |
| Tests using `@WithMockUser` return 401, `httpBasic` tests pass | §4.4 missing `spring-boot-starter-security-test` |
| `cannot find symbol: method and()` in security config | §5 removed DSL |
| Startup failure, dialect or schema related | §6 Hibernate 7 |
| Property has no effect at runtime | §7 properties migrator |
| Health JSON or the default error body changed shape at runtime | §13 observed runtime differences |

---

## 10. What this jump does *not* require

Migrations attract unrelated work. Anything in this category must be reported separately from the
upgrade — a migration report that claims a bug fix was "required by Boot 4" is wrong, and it
undermines every other claim in the report.

- **Business logic corrections.** A repository method that queried the wrong field was wrong before
  the upgrade too. Fix it, but as its own change.
- **New endpoints, new fields, renamed DTO properties.** The REST contract should come through a
  migration unchanged; that is the main evidence the migration worked.
- **Security rules for developer tooling** (H2 console access, frame options, permissive CORS in
  dev). Configuration revalidation, not an API break.
- **Test-quality improvements** — replacing mock users with real credentials, adding assertions.
  Worth doing, not caused by Boot 4.
- **Formatting, import ordering and refactors.** They inflate the diff and hide the real changes.

---

## 11. Verify a coordinate or class before using it

This pack was written against a specific point in Boot 4's history. Package names and artifact ids
that have drifted since must be caught by verification, not propagated. Before writing a coordinate
or import you have not seen resolve:

```powershell
# What the target BOM actually manages, and at what version
mvn -B dependency:tree
mvn -B dependency:tree -Dincludes=org.springframework.boot

# Does the artifact exist at that version? (resolution failure = it does not)
mvn -B dependency:get -Dartifact=org.springframework.boot:spring-boot-starter-webmvc:4.1.1

# Which package a class really lives in, from the jar that is on the classpath
jar tf "<path-to-jar>" | findstr /i "HealthIndicator"
```

The resolved dependency tree and the jar contents outrank this document. If they disagree with a
rule here, follow them, and note the discrepancy in the migration report so the pack can be
corrected.

---

## 12. What the rounds usually look like

This is the sequence an actual run of this pack took on a small Boot 3.5.0 service (17 main + 5 test
sources), recorded round by round:

| Round | Goal | Result | What it taught |
|---|---|---|---|
| 0 | `package` on JDK 17 | 15 tests, 2 failures + 1 error | The reference. Both failures were pre-existing, and the error was a missing Docker daemon — none of it caused by the migration, all of it needed as the comparison |
| 1 | `test-compile` on JDK 21 | 27 errors in 2 files | Parent, `java.version`, compiler `release`, `web`→`webmvc`, Dockerfile. Boot 4.1.1 resolved *and compiled under JDK 17*, so the language move was a decision, not a compiler demand. All errors were §2 and §3 |
| 2 | `test-compile` | 12 errors in 2 test files | Main code fixed (§2, §3); the failure moved to the test layer, which only compiles once main does |
| 3 | `test-compile` | same 12 errors | Adding the two test starters changed nothing — the slice annotations moved *package*, not just artifact (§4.3) |
| 4 | `test-compile` | green | Imports repointed, `@MockBean`→`@MockitoBean`, `ObjectMapper`→`JsonMapper` |
| 5 | `package` on JDK 21 | 7 failures (was 2) | A real regression, invisible to the compiler: `@WithMockUser` tests returning 401 (§4.4) |
| 6 | `package` | back to 2 failures + 1 error | `spring-boot-starter-security-test` added. Identical to round 0 — nothing introduced |
| 7 | `package -DskipTests` | green | Runnable jar on JDK 21, then the final runtime probe |

Two lessons worth carrying into any run: the test layer breaks a round *after* main code, and the
worst breakage of the whole migration (round 5) produced no compiler error at all. A migration
verified only by `compile` would have shipped it.

Rounds merge or multiply depending on the build goal and how much framework surface a project uses.
The count is not the point — every round is recorded, and the report shows what each one taught.

---

## 13. Runtime differences to expect even when nothing broke

Observed by probing the same nine endpoints before and after, with all four business endpoints
returning byte-identical responses:

| What | Before | After |
|---|---|---|
| `/actuator/health` payload | `{"status":"UP","components":{...}}` | keys reordered (`details` before `status`), larger, same information |
| Default error body timestamp | `2026-09-06T14:35:21.651+00:00` | `2026-09-06T14:35:50.560Z` |

Neither is an application change and neither breaks a well-behaved client, but both break a client
that asserts on exact JSON text or field order. Check any consumer that parses the health document
or matches the error timestamp format before calling a migration behaviour-neutral.
