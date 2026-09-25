# Bootshift skills

A **skill** here is a reusable capability the harness discovers at runtime and records in the
transformation capability registry. It is not a plugin system and not an extension point for
arbitrary code: a skill is a named, licence-checked, version-pinned capability that a stage may use,
and every use of one is recorded.

Three rules govern every entry in this catalog.

1. **A skill is discovered, never assumed** (R31). Agent 11 probes for each one and records it
   `AVAILABLE` or `UNAVAILABLE` with the reason. A plan never depends on a capability the run did not
   observe.
2. **A skill never writes** (R11). It computes intended content and returns it. `FileMutationGateway`
   is the only writer in the process.
3. **A skill is licence-checked before use** (R7). An unknown licence is blocked, not permitted
   pending investigation.

---

## Catalog

### `bootshift.maven-pom`

| | |
|---|---|
| **Provider** | `MavenPomTransformer` |
| **Licence** | MIT (harness code) |
| **Determinism** | Pure — same input and parameters, same output |
| **Recipes** | `maven.parent-version`, `maven.property`, `maven.managed-version`, `maven.add-dependency`, `maven.remove-dependency` |

Edits Maven descriptors by targeted tag replacement rather than by reserializing the document, so
comments, formatting and ordering survive. A migration diff a reviewer cannot read is a migration
nobody reviews.

### `bootshift.jakarta-namespace`

| | |
|---|---|
| **Provider** | `JakartaNamespaceTransformer` |
| **Licence** | MIT (harness code) |
| **Determinism** | Pure |
| **Recipes** | `jakarta.namespace` |

Relocates the **28** `javax.*` prefixes that moved to `jakarta.*` and refuses to touch the **26**
that did not. `javax.sql`, `javax.crypto`, `javax.net`, `javax.naming`, `javax.management`,
`javax.security.auth` and `javax.xml.parsers` are JDK packages that never moved; rewriting them is a
classic silent migration defect, and `TransformerTest` fails the build if the transformer does.

### `bootshift.config-property`

| | |
|---|---|
| **Provider** | `ConfigurationPropertyTransformer` |
| **Licence** | MIT (harness code) |
| **Determinism** | Pure |
| **Recipes** | `config.property-migration` |
| **Rules from** | `migration-rules/generated-properties/property-migration-rules.json` |

Applies property renames and removals across `.properties` and `.yml`. The rules are **generated**
from `spring-configuration-metadata.json` in the published artifacts, not hand-maintained — 542 of
them on the reference corpus. Hand-writing hundreds of these is how they end up wrong.

### `bootshift.test-framework`

| | |
|---|---|
| **Provider** | `TestFrameworkTransformer` |
| **Licence** | MIT (harness code) |
| **Determinism** | Pure |
| **Recipes** | `test.junit4-to-junit5` |

Rewrites JUnit 4 constructs to JUnit 5. Runs on a `PREPARATORY` edge at the *current* version, so a
test-framework change and a framework upgrade are never entangled in one diff.

### `bootshift.removed-annotation`

| | |
|---|---|
| **Provider** | `RemovedAnnotationTransformer` |
| **Licence** | MIT (harness code) |
| **Determinism** | Pure |
| **Recipes** | `java.remove-annotation` |

Deletes an annotation *and its import* when the target version removed it and its entire effect was
to opt into behaviour that is now unconditional. Three entries, each carrying the evidence for why
removal changes nothing.

The list is curated deliberately. Driving this from every `API_REMOVED` fact would be easy and wrong:
for most removed types the reference is load-bearing, and deleting it changes behaviour silently.
Anything not on the list stays residual.

### `openrewrite.core`

| | |
|---|---|
| **Provider** | `OpenRewriteCoreProbe` |
| **Licence** | Apache-2.0 — **core modules only** |
| **Determinism** | Pure |
| **Status** | Discovered at runtime; `UNAVAILABLE` when absent |

The probe verifies at runtime that only Apache-2.0 core modules are on the classpath. If a recipe
estate under a source-available licence is found — `rewrite-spring` and the wider Moderne estates —
the probe raises a `LICENSE_BLOCK` and the capability is recorded `UNAVAILABLE` rather than used.
This is R7 as a runtime check rather than a README promise.

### `bootshift.javap-api-diff`

| | |
|---|---|
| **Provider** | `JavapApiDiffAdapter` |
| **Licence** | JDK tool, invoked as a process |
| **Determinism** | Pure with respect to the two jars |
| **Used by** | Agent 08, artifact channel |

Extracts public and protected signatures from two published jars and diffs them. This is the only
channel that can say a type was *removed* rather than *documented as deprecated*, which is why
`API_REMOVED` requires evidence level `E3` and this skill supplies it.

### `bootshift.local-oss-ai` *(optional, default off)*

| | |
|---|---|
| **Provider** | `LocalOssAIProvider` |
| **Licence** | Runtime **and model** checked separately |
| **Determinism** | **Not deterministic** |
| **Endpoint** | Loopback only — a non-loopback endpoint is refused |

The only non-deterministic skill in the catalog, and the only one whose output is gated before use.
An AI-proposed patch passes scope, parse, compile, test and budget gates before it reaches the
gateway; every attempt, accepted or rejected, is recorded. It cannot authorize anything (R9), and the
harness runs end-to-end without it (R10).

---

## Adding a skill

1. Define the capability as an interface in `ports/`.
2. Implement it in `adapters/`. It returns content; it does not write.
3. Register a runtime probe so Agent 11 can record it `AVAILABLE` or `UNAVAILABLE` with a reason.
4. Add its licence to `policies/license/license-policy.json`. Unknown blocks.
5. Add fixture pairs under `fixtures/transform/` and cases to `TransformerTest`.
6. Document it here, including what it deliberately does **not** do.

A skill with no runtime probe cannot be planned against, because a plan that assumes a capability the
run never observed is exactly the failure mode R31 exists to prevent.
