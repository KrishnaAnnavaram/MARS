# Agent 04D — Spring Boot Any Version to Any Version

**Scope.** Extend the 04D skill (and only 04D) from a single Boot 3 → 4 pack to a planner and executor
that can take a Spring Boot application from any published line to any later one. The goals:
- OpenRewrite at the centre, using open-source recipes only by default;
- prove that endpoints are preserved;
- land exactly on the requested version.

The design borrows bootshift's path discipline. No new script was added; the existing scripts and
libraries were extended.

> Approval was programmatically granted for controlled pipeline validation; this does not replace
> the production human approval requirement. (`APPROVAL_MODE=TEST_AUTO_APPROVED` was used for the
> new migration plans; the human gate in the harness is unchanged.)

---

## Verdict

> **04D now plans every Spring Boot upgrade from 1.5 up to 4.1 as an ordered ladder of edges, and
> executes each edge with Apache-2.0 OpenRewrite recipes.**
> - Real runs proved two paths end to end:
>   - **2.7.12 + Spring Cloud → 3.5.16** (the Jakarta boundary);
>   - **3.5.0 → 4.1.1** (the Boot 4 boundary).
> - Both reached the exact requested version, with no new test failures and no endpoint lost.
> - Other paths are planned with the same machinery but have not been executed (see *Limits*).

| Run | Project | Path | Edges (recipe) | Tests before → after | Endpoints | Summary |
|---|---|---|---|---|---|---|
| V1 | MARS `src/employee-service` | 2.7.12 → 3.5.16, Java 17 → 21, Cloud 2021.0.7 → 2025.0.3 | 4: pin, `UpgradeSpringBoot_3_0`, `UpgradeSpringBoot_3_3`, pin | 7/7 → 7/7 | static 5/5, runtime 10/10 | **PARTIAL PASS** — one trailing-slash 200 → 404, classified as an expected Boot 3 change; it needs human acceptance |
| V2 | golden demo (`sample-java-project@6978c4a`) | 3.5.0 → 4.1.1, Java 17 → 21 | 3: pin, open-source composite, pin | 17/19 → 17/19 (same 2 pre-existing failures) | static 7/7, 20/20 probes same status | **PASS** |
| V2b | golden demo | 3.5.0 → 4.1.1 | 3 | 17/19 → 17/19 | 4 probes same status | targeted check of fixes A and B; found 4 more defects |
| V2c | golden demo | 3.5.0 → 4.1.1 | 3 | 17/19 → 17/19 (Testcontainers 5/5 on postgres:16) | static 11/11, 9/9 probes same status | **PASS** — clean re-run with every fix in place |

Every recipe that ran in every run is Apache-2.0: `rewrite-spring` 5.24.1 and its dependencies for
V1, and the built-in recipes of `rewrite-maven-plugin` 6.46.1 for V2/V2b/V2c. Each run is under
[`evidence/<run>/`](./evidence/):
- the per-run `MIGRATION_SUMMARY.md`;
- the migration report and diff;
- the session records: baseline, plan, narrative, probes, rounds, every generated edge recipe and
  preview patch, and the runtime records.

---

## How it works

**1. Plan — `detect-baseline.js`.** The detector reads the declared Boot version, then reads the
published lines and latest patches from Maven Central. It plans a path with
`references/openrewrite/spring-boot-ladder.json`:

| Edge class | Meaning | Recipe |
|---|---|---|
| `PATCH` | latest patch of the current line | pin only |
| `MINOR` | a later line in the same major | `UpgradeSpringBoot_X_Y` if the licence policy allows it, else pin only + compiler-driven repair |
| `MAJOR_BOUNDARY` | first line of the next major — never skipped | upstream recipe, or 04D's open-source composite |

The path reaches the last line of the source major before crossing it, and stops at the licence
frontier (the highest line an allowed recipe covers). Each edge also records:
- its Java level and JDK;
- its Spring Cloud train, checked against the train's own POMs;
- the rules pack for its boundary.

A target that cannot be reached is **BLOCKED**, never jumped:
- a missing rung, or a boundary no allowed recipe covers → `UNSUPPORTED_MIGRATION_PATH`;
- a Cloud line with no GA train → `BLOCKED_ECOSYSTEM`, with the closest supportable target named.

**2. Execute — `run-migration-build.js --edge En`.** Each edge runs a generated declarative recipe,
`mars.migration.edge.En`. It contains the rung's recipe or composite, then the pins:
- the parent `<version>`, by a plain XML edit;
- every explicitly versioned `org.springframework.boot` artifact;
- the Java property;
- the Cloud train property.

The edge is previewed (`dryRunNoFork`), inspected, applied (`runNoFork`) and built. Edges run in
order. An edge is complete only when a build tagged with it compiles and the sandbox declares the
edge's version. Residual compiler errors are repaired against the rules packs
(`spring-boot-2-to-3.md`, `spring-boot-3-to-4.md`).

**3. Preserve endpoints — `scanEndpoints`, `probe-runtime.js --discover`.** The baseline records:
- every controller mapping;
- every framework endpoint that configuration switches on (H2 console, exposed actuator endpoints).

Probes record the running application's `/actuator/mappings` when it is exposed. `--discover` drafts
safe GET probes for every endpoint and lists the rest with a reason. The run fails, and the apply gate
refuses, in three cases:
- a lost endpoint;
- an unclassified status change;
- an unfinished edge.

A classified framework change produces PARTIAL PASS and needs human acceptance.

---

## Licence audit (from each artifact's POM on Maven Central, 2026-10-01)

| Artifact | Licence | Used for |
|---|---|---|
| `rewrite-spring` ≤ 5.24.1 (2024-11-28) | Apache-2.0 | `UpgradeSpringBoot_2_0` … `3_3` (default stack) |
| `rewrite-spring` ≥ 5.25.0, 6.x | Moderne Source Available (free internal use; not OSI; no SaaS) | only with `--license-policy source-available` |
| `rewrite-maven-plugin` 5.46.1 / 6.46.1, OpenRewrite core | Apache-2.0 | all runs |
| All 34 `org.openrewrite` jars loaded in V1 | Apache-2.0 | verified at run time |

The 34 jars include the transitive `rewrite-logging-frameworks` 2.17.0, `rewrite-jenkins` 0.18.1,
`rewrite-github-actions` 2.10.1, `rewrite-csharp` 0.16.1, `rewrite-analysis` 2.13.1 and
`rewrite-kotlin` 1.23.1. Their newer lines are Moderne Source Available, and the 5.24.1 pins keep
those newer lines out.

So under the default `open-source-only` policy:
- Spring's own upgrade recipes cover up to **Boot 3.3**;
- the 3.3 → 3.5 edges are pin-only plus compiler-driven repair;
- 3 → 4 uses 04D's composite, `spring-boot-3-to-4.oss.yml`, built only from Apache core recipes
  (`ChangePackage`, `ChangeType`, `ChangeDependencyGroupIdAndArtifactId`, `AddDependency`).

The licence gate in `lib/openrewrite.js` refuses any other recipe (`rejected-license`), including
pack transformations.

---

## Defects found by the real runs, and fixed

| # | Found in | Defect | Fix | Proven by |
|---|---|---|---|---|
| A | V2 | `ChangeParentPom` also deleted explicit versions the new parent manages (Testcontainers 1.20.1 → 2.0.5 for one artifact only) | parent pinned with `xml.ChangeTagValue` | V2b/V2c previews: only the parent line changes |
| B | V2 | composite `AddDependency` for the Boot 4 test starters never fired (keyed on types unresolvable before the starter exists) | runs first, keyed on the Boot 3 types | V2b/V2c: both starters added; test-compile green with no hand edit |
| D | V1 | `--discover` ignored `--port`; drafted bare probes for endpoints with required params | both fixed | test 16i; V2c draft |
| E | V1 | "probe now" advice after a transit edge | edge-aware message | V1/V2c console |
| F | V2 | pack lacked the H2-console module move and the launch-script removal | pack §7.1, §7.2 | V2c restore |
| G | V1 | summary tables: "Files 0", one reason per file, plugin bumps missing | fixed | summaries |
| H | V2 | probes needed basic auth, but credentials may not be written to files | `username_env` / `password_env` | V2b/V2c `probes.json` holds no password |
| I | V1 | the Maven wrapper jar was excluded from the sandbox, so the sandbox looked changed | copied like any file | V1 |
| J | V1 | probe started only `*.jar` | executable `.war` accepted | code |
| K | V1 | a plugin failure after green tests was labelled `compile-failed` | `build-failed` outcome | test 16i |
| L | V2 | report badge and summary status disagreed | report shows the summary's status | V2c report |
| M | V1 | a rejected hunk recurred on every edge | plan candidates can narrow an edge's recipes | test 16e |
| P | V1 | an accepted, classified status change made the run FAIL | FAIL only for unclassified changes or regressions; classified → PARTIAL PASS | V1 re-finalized |
| Q | V2b | a repair round run without `--edge` was untagged, so the edge never completed and "probe now" was printed with E3 still to go | the round is tagged with the open edge the sandbox declares (`openEdgeFor`) | V2c R3 "E2 (inferred) COMPLETE" |
| R | V2b | the composite wrote `<version>4.0.8</version>` on the added starters; E3 would have left them on 4.0.8 beside a 4.1.1 parent | each edge runs `UpgradeDependencyVersion org.springframework.boot:*` | V2c rewrite-04: redundant tags removed |
| S | V2b | framework endpoints switched on by configuration were invisible to the inventory; `/h2-console` could be lost unnoticed | `configEndpoints` (H2 console, exposed actuator endpoints, base path, separate port) | V2c: drafted probe caught 200 → 404; restored |
| T | V2b | the apply gate did not check edges or endpoints (the docs said it did) | `eligibility` refuses an unfinished edge or a lost endpoint, and fails closed | test 16k |
| U | V2b | the console line `policy optional` read like the licence policy | prints the licence policy and the unavailable-recipe policy separately | V2c console |
| V | this report | Spring Cloud 2025.1 is GA for Boot 4.0/4.1, but the offline ladder data stopped at 2025.0; 2025.1 ships no `spring-cloud-starter-parent`, so the check returned 404 | 2025.1.3 recorded; train verified through `spring-cloud-build`'s `spring-boot.version` | live check: 2025.1.3 → Boot 4.0.8; test 16c |

The test suite went from 48 to **50/50**:
- new tests: 16j (config endpoints, edge inference) and 16k (apply gate);
- extended tests: 16c (Cloud to 4.x) and 16e (platform-artifact pin).

The pipeline lint passes for `.github` and `.claude`.

---

## What a migration still needs from the agent (by design)

Recipes do not cover everything. In both validated paths, the remaining edits were judgement calls,
each backed by build or probe evidence and a pack rule:

| Path | Residual edit | Pack rule |
|---|---|---|
| 2 → 3 (V1) | `@EnableEurekaClient` removed | §8 |
| 2 → 3 (V1) | `HttpStatusCode` | §5.2 |
| 2 → 3 (V1) | `javax.ws.rs` → `jakarta.ws.rs` | §2 |
| 3 → 4 (V2c) | Jackson 3 mapper built with `JsonMapper.builder()` | §2.1/2.2 |
| 3 → 4 (V2c) | `spring-boot-h2console` added | §7.1 |
| 3 → 4 (V2c) | Dockerfile moved to Java 21 | §8 |

---

## Limits (stated plainly)

| Area | Status |
|---|---|
| Paths executed for real | 2.7 → 3.5 (with Cloud), 3.5 → 4.1. Everything else is planned and unit-tested only. |
| 1.5 → 2.x | Planned (Apache `UpgradeSpringBoot_2_0` exists), but there is **no 1 → 2 rules pack** and it was never executed. |
| 2.x → 4.x with Spring Cloud | Planned online and offline (Cloud 2025.1.3 on 4.0/4.1, verified via `spring-cloud-build`). **Not executed.** |
| 3.4, 3.5, 4.0, 4.1 under `open-source-only` | No Apache upstream recipe exists. These edges are pin-only plus the 04D composite plus compiler-driven repair. With `source-available`, the newer Moderne recipes run, but 4.1 has only a properties recipe even there. |
| Behaviour the compiler cannot see | Covered only as far as the tests and probes reach. Jackson 3 default changes (property order, ProblemDetail, timestamps) were observed and classified, not prevented. |
| Gradle | The edge recipe (`UpgradePluginVersion`, Gradle `UpgradeDependencyVersion`) is unit-tested only. |
| Endpoint inventory | Static scan covers annotated controllers and default-profile config. Functional routes and programmatic registration need `/actuator/mappings` exposed at runtime. |
| Apply to the project | `apply-migration.js` still requires the last round to be fully green. A project with pre-existing failing tests (the golden demo has 2) is refused, even when the summary is PASS. This is unchanged and deliberate. |
| Approval | Plans in these runs used `TEST_AUTO_APPROVED`; production keeps the human gate. |

---

## Where things changed (04D skill set only)

**Data:**
- `references/openrewrite/spring-boot-ladder.json` (rungs 1.5 – 4.1, recipe stacks with licence
  evidence, Cloud trains);
- `references/openrewrite/spring-boot-3-to-4.oss.yml`;
- `references/spring-boot-2-to-3.md`;
- updates to `spring-boot-3-to-4.md` and `references/README.md`.

**Scripts (extended, none new):**

| Script | Changes |
|---|---|
| `detect-baseline.js` | ladder planning, `--license-policy`, `--path-granularity`, Cloud evidence, endpoints |
| `run-migration-build.js` | `--edge`, edge order and completion, inferred edge for repair rounds |
| `probe-runtime.js` | `--discover`, runtime endpoint inventory, env-var auth, `.war` |
| `apply-migration.js` | edge and endpoint gate |
| `prepare-workspace.js` | wrapper jar |
| `render-migration-report.js` | path and endpoint section, status badge |

**Libraries:**

| Library | Changes |
|---|---|
| `lib/references.js` | ladder, `planLadderPath`, licence policies |
| `lib/openrewrite.js` | `renderEdgeRecipe`, licence gate |
| `lib/migration.js` | Maven metadata, Cloud train verification, `scanEndpoints`/`configEndpoints`, `build-failed` |
| `lib/summary.js` | edges, endpoints, status rules |

**Docs:** `SKILL.md`, `ARCHITECTURE.md` §12, and the `.claude` pointer `SKILL.md`.
