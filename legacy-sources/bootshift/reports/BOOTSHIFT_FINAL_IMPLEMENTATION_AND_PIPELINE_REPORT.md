# Bootshift — Final Implementation and Pipeline Report

**Repository:** `E:\Virtusa Projects\bootshift`
**Date:** 2026-09-10
**Harness build:** 190 tests, 0 failures, 1 skipped, BUILD SUCCESS
**Pipeline run 1:** `RUN-01M267M0HBCW8KAGXQKF85WR67` — stopped at EDGE-2, 1 of 8 edges complete
**Pipeline run 2:** `RUN-01M26B99S7GWBDP1CSDV6Q5QV6` — **BLOCKED** at EDGE-2 differential, 1 of 8 edges complete
**`./src` integrity:** `f48b7888…8687b` before run 1, identical after run 2

---

## Table of contents

1. [Verdict](#1-verdict)
2. [What was implemented](#2-what-was-implemented)
3. [Migration path and target resolution](#3-migration-path-and-target-resolution)
4. [Per-edge planning](#4-per-edge-planning)
5. [OpenRewrite integration and license gating](#5-openrewrite-integration-and-license-gating)
6. [Transformation, build and mutation control](#6-transformation-build-and-mutation-control)
7. [Characterization and differential validation](#7-characterization-and-differential-validation)
8. [Execution security and environment](#8-execution-security-and-environment)
9. [Pipeline run 1](#9-pipeline-run-1)
10. [LLM-as-Judge, pass 1](#10-llm-as-judge-pass-1)
11. [The single repair pass](#11-the-single-repair-pass)
12. [Pipeline run 2](#12-pipeline-run-2)
13. [Did the repairs work](#13-did-the-repairs-work)
14. [Invariant compliance](#14-invariant-compliance)
15. [Evidence and coverage statement](#15-evidence-and-coverage-statement)
16. [Remaining blind spots](#16-remaining-blind-spots)
17. [Not implemented](#17-not-implemented)
18. [Deliverables](#18-deliverables)

---

## 1. Verdict

**The harness works. The migration is incomplete, and it is incomplete for the right reason.**

Run 2 executed the entire analysis half, completed one migration edge, executed a second, and then
refused it: the differential stage found a behavioural difference between the original and migrated
`discovery-service` that no verified migration fact and no human decision explained, and the
production policy blocks on that. Six of eight edges — including the Jakarta boundary, where the real
risk lives — never started.

That refusal is worth being precise about. It is not a crash and not a bug. It is a
`POLICY_BLOCK`, the designed terminal state for an unexplained behavioural difference, and it happened
on a comparison the harness would not even have performed before the repair pass. The stated
invariant — *unexplained behavioural differences remain blocking* — held under a case that cost
something to hold.

Per the governing rule, run 2 was the final verification. No further judging and no further repair
were performed after it, and none of the four things that would have made it green — approving the
difference, weakening the policy, narrowing the comparison, reclassifying the finding — were done.

| Question | Answer |
|---|---|
| Does the harness build and test clean? | **Yes** — 190 tests, 0 failures |
| Was the original `./src` ever modified? | **No** — hash identical before and after |
| Did the migration complete? | **No** — 1 of 8 edges; blocked at edge 2 |
| Did the blocking finding get suppressed to force a pass? | **No** |
| Was a human approval fabricated? | **No** — the decision store is empty |
| Is the outcome reported honestly? | Yes — sections 15–17 |

## 2. What was implemented

Work spanned the whole harness. The substantial items, grouped:

**Path and target.** `JavaTargetSelector` (LTS-aware selection over *installed* JDKs, never from the
harness JVM), `TargetResolverStage` rewritten with an `EdgeClass` model and a boundary self-check that
throws when the constructed path crosses fewer boundaries than there are majors between source and
landing.

**Knowledge and planning.** `MigrationFact` gained `component`, `validFrom`/`validTo`,
`ValidityPrecision` and `appliesToEdges`; `KnowledgeStage` resolves a BOM at each end of every edge and
attributes coordinate movement to specific edges; `PlannerStage.planEdge` rewritten so each edge
computes its own facts, impacts, recipes, toolchain and validation depth.

**Transformation.** `OpenRewriteCoreProvider` — real OpenRewrite 8.90.4 execution against parsed LSTs
via `InMemoryLargeSourceSet`, restricted to the Apache-2.0 core modules. `YamlPropertyModel` —
structural SnakeYAML parsing with line marks, replacing line-oriented text editing.

**Mutation control.** `FileMutationGateway` hardened: budget rejection, path authorization for RENAME
destinations, MERGE source validation, LCS-based unified diffs, scoped checkpoint rollback.

**Build abstraction.** `BuildSystemPort.Kind.MIXED`, `BuildModelCodec` with a versioned encode/decode
contract and fingerprint, `BuildSystemResolver`. The codec exists because every stage previously
rehydrated a partial model by hand and dropped managed versions, plugins, repositories and issues.

**Execution.** `ProcessRunner` with an inherited-environment allowlist, secret redaction before log
write, and process-tree termination. `ContainerProvisioner` with a pinned image catalog (tag + digest)
that probes the daemon rather than the binary.

**Characterization and differential.** `Scenario`/`ScenarioObservation` ports, `ScenarioBuilder`,
`ScenarioHttpExecutor` capturing structural facts, `SpringProcessRuntimeProbe.runScenarios`,
`DifferentialStage` keyed on scenarios rather than module metadata.

**Evidence.** `EdgeIndex` (per-edge phase and artifact-directory record), `StageExecutor` (the single
entry point that verifies preconditions), `EdgeToolchain` (freezes and *verifies* the executed JDK),
`EdgeEvidenceAggregator`, `MigrationDocument`.

**Corpus and tests.** Impact fixture corpus 6 → 16; new test suites for migration paths, build-model
round-tripping, mutation hardening, impact corpus accuracy, and judge-repair regressions. Suite grew
from its pre-existing size to **190 tests**.

**Truthfulness.** Eight README sections corrected where the document described behaviour the code did
not have; `pom.xml` licence corrected to MIT to match the LICENSE file; plugin versions pinned.

## 3. Migration path and target resolution

Landing target **Spring Boot 3.5.16**, selected `AUTO_HIGHEST_SAFE_SUPPORTED`. Boot 4.0 and 4.1 were
eliminated because the application uses Spring Cloud and no GA release train targets those lines.

All Boot 3.x lines are past OSS support, so the run required `production-eol-exception.json`, which
permits an EOL landing target *only* because the ecosystem offers no compliant alternative. Under the
default production policy the run refuses.

Landing Java **21**, selected `LTS_PREFERRED` over installed JDKs `{17, 21}` — read from the machine,
not from the harness JVM.

The path crosses the single 2.x→3.x major boundary with a dedicated `MAJOR_BOUNDARY` edge at 3.0.13.
`majors_between_source_and_landing = 1`, `major_boundaries_crossed = 1`, self-check passed.

## 4. Per-edge planning

Every edge carries its own facts, recipes, toolchain, Cloud train and depth:

| Edge | Class | From → to | Java | Cloud | Recipes | Facts | Coverage |
|---|---|---|---|---|---|---|---|
| EDGE-1-PREP-TEST | PREPARATORY | 2.7.12 → 2.7.12 | 17 | 2021.0.9 | 1 | 692 | 0.9697 |
| EDGE-2-PATCH | PATCH | 2.7.12 → 2.7.18 | 17 | 2021.0.9 | 4 | 1388 | 0.8220 |
| EDGE-3-MAJOR-3 | MAJOR_BOUNDARY | 2.7.18 → 3.0.13 | 17 | 2022.0.5 | **33** | 1682 | 0.6795 |
| EDGE-4-MINOR | MINOR | 3.0.13 → 3.1.12 | **21** | 2022.0.5 | 4 | 1551 | 0.7357 |
| EDGE-5-MINOR | MINOR | 3.1.12 → 3.2.12 | 21 | 2023.0.5 | 4 | 1420 | 0.8035 |
| EDGE-6-MINOR | MINOR | 3.2.12 → 3.3.13 | 21 | 2023.0.6 | 4 | 1459 | 0.7820 |
| EDGE-7-MINOR | MINOR | 3.3.13 → 3.4.13 | 21 | 2024.0.3 | 4 | 1578 | 0.7231 |
| EDGE-8-LANDING | LANDING | 3.4.13 → 3.5.16 | 21 | 2025.0.3 | 4 | 1576 | 0.7240 |

Java steps from 17 to 21 exactly at EDGE-4, the first edge whose Boot line supports 21. The boundary
edge carries 33 recipes and the lowest coverage — the Jakarta migration surfacing where it should.

Facts reach an edge through two channels, recorded per edge in `fact_scoping`: declared attribution
from per-edge BOM diffs (0–990 per edge), and validity-interval intersection for unattributed facts
(692–698 per edge).

## 5. OpenRewrite integration and license gating

Real execution, not a probe. `OpenRewriteCoreProvider` parses sources into an LST and runs recipes
through `InMemoryLargeSourceSet`: `ChangePackage`, `RemoveAnnotation`, `ChangeParentPom`,
`ChangePropertyValue`.

Strictly the Apache-2.0 modules of OpenRewrite 8.90.4: `rewrite-core`, `rewrite-java`,
`rewrite-java-21`, `rewrite-maven`, `rewrite-yaml`, `rewrite-properties`. The Moderne
source-available Spring recipe estates are forbidden.

`LicensePolicy` holds the single source of truth — forbidden packages (`org.openrewrite.java.spring`,
`org.openrewrite.recipe.spring`, `io.moderne`) and marker classes. An ArchUnit rule
(`forbiddenRecipePolicyHasOneSourceOfTruth`) fails the build if that list is duplicated anywhere else.
Run 2's OSS gate: **PASSED**, `forbidden_estates_loadable_at_runtime: []`.

## 6. Transformation, build and mutation control

`FileMutationGateway` is the only authorized writer of migrated application source, enforced by
ArchUnit (`gatewayIsTheOnlyMutationPort`). Every write produces a ledger entry carrying `change_id`,
`edge_id`, `file_id`, `before_sha256`, `after_sha256`, `provider`, `recipe_id`, `operation`,
`patch_ref`, `knowledge_refs`, `impact_refs` and `symbols_changed`.

The ledger is hash-chained — each entry carries the previous entry's hash, so editing or removing one
breaks every entry after it. Run 2: **19 entries, all `APPLIED`, all `BOOTSHIFT_DETERMINISTIC`**.

`EdgeToolchain` freezes the JDK per edge and then **verifies** it by executing `java -version` and
parsing the result. Both edges in run 2: frozen 17, executed `17.0.20.1`, `executed_version_verified:
true`. A mismatch is a validation failure, because an observation on an unselected toolchain describes
a system nobody chose.

Scope assertion after every edge: **0 violations** on both edges — nothing changed outside the
authorized file set.

## 7. Characterization and differential validation

A scenario becomes an oracle by being **executed** against the original application, never by being
written. Run 2 built 82 scenarios and froze **36** by executing them against the original before any
change existed; the other 46 are `UNOBSERVABLE_WITH_EXPLICIT_GAP` with a stated reason. Zero remain
pending.

Differential comparison is keyed on the scenario: same request, both sides, identical capture, same
versioned normalization policy. Captured facts are structural — status, content type, header names,
security header values, body shape, sorted body field paths — so a moved timestamp does not register
and a lost field does.

| Edge | Comparisons | IDENTICAL | NOT_COMPARED | UNEXPLAINED |
|---|---|---|---|---|
| EDGE-1-PREP-TEST | 82 | 36 | 46 | 0 |
| EDGE-2-PATCH | 82 | 35 | 46 | **1** |

Dimensions compared: `HTTP_API`, `SECURITY_AUTHORIZATION`, `SERIALIZATION`, `CONFIGURATION_BINDING`,
`CONTEXT_CAPABILITY`.

## 8. Execution security and environment

`ProcessRunner` is the only place a process is started, enforced by ArchUnit
(`processExecutionIsCentralised`, `runtimeExecIsNeverCalled`). It passes an allowlisted environment,
redacts secret-shaped values before writing logs, and terminates process trees rather than leaking
children.

`ContainerProvisioner` holds a catalog pinned by tag and digest (postgres 16.4-alpine, mysql 8.4,
mariadb 11.4, mongo 7.0, redis 7.4-alpine, rabbitmq 3.13-alpine, apache/kafka 3.8.0) and detects a
working runtime by asking the **daemon**, not by finding the binary. In both runs it correctly
reported `docker is installed but its daemon did not answer` and recorded `NO_OCI_RUNTIME`.

The environment artifact records `network.egress.harness = allowlist` and
`network.egress.child.processes = UNRESTRICTED` — the honest statement, since a spawned Maven build
reaches the network on its own terms.

## 9. Pipeline run 1

Stages 00–11 all SUCCESS. EDGE-1-PREP-TEST completed all six edge stages. **EDGE-2-PATCH was refused**
with `STRUCTURED_REFUSAL: Required state PLAN_FROZEN has not been reached (current: EDGE_COMPLETE)`.

Stages 18, 19 and 20 never ran, so run 1 produced no approval record, no evidence manifest, no
migration document and no provenance graph — and nothing was invented in their place.

## 10. LLM-as-Judge, pass 1

One judge pass, written before any code changed:
[`llm-judge-pass-1.md`](llm-judge-pass-1.md) / [`.json`](llm-judge-pass-1.json). Decision:
**`REPAIR_ONCE`**.

| ID | Class | Severity | Finding |
|---|---|---|---|
| J1-001 | HARNESS_IMPLEMENTATION_DEFECT | **BLOCKING** | `StageExecutor.hasReached` answered analysis-half preconditions by state ordinal and refused every edge after the first |
| J1-002 | PLANNER_OR_KNOWLEDGE_DEFECT | MAJOR | `EdgeFact.appliesTo` returned `true` for any attributed fact without testing membership — all 8 edges got identical facts and coverage |
| J1-003 | DIFFERENTIAL_VALIDATION_DEFECT | MAJOR | Scenarios executed on both sides were discarded when the plan had not named their dimension — 36 executed, 18 compared |
| J1-004 | HARNESS_IMPLEMENTATION_DEFECT | MODERATE | Ordinary OSS libraries classified `internal:` by group-name prefix — 37 spurious entries |
| J1-005 | PLANNER_OR_KNOWLEDGE_DEFECT | MODERATE | Every edge planned at `FULL_DIFFERENTIAL` |
| J1-006 | EVIDENCE_OR_PROVENANCE_DEFECT | MODERATE | No final evidence, document or provenance graph exists for run 1 |
| J1-007 | MISSING_EXTERNAL_INFRASTRUCTURE | ENVIRONMENTAL | No OCI daemon |
| J1-008 | SOURCE_APPLICATION_PREEXISTING_DEBT | INFO | 2 baseline test failures, 2 modules that cannot start |
| J1-009 | EXPECTED_LIMITATION | INFO | Coverage unmeasurable for `employee-service` |
| J1-010 | TEST_OR_CHARACTERIZATION_DEFECT | MINOR | 12 scenarios left pending rather than declared as gaps |

The judge found **no over-claim**: run 1 published no claim stronger than its evidence, largely
because stage 19 never ran to publish one.

## 11. The single repair pass

One consolidated pass, recorded in [`judge-repair-pass.md`](judge-repair-pass.md).

| Finding | Change |
|---|---|
| J1-001 | `PROOF_ARTIFACT` maps each analysis-half state to the published artifact that proves it; `analysisStateSatisfied` answers from the artifact plane, cursor as fallback only |
| J1-002 | `appliesTo(edgeId, from, to)` tests declared-edge membership; attribution treated as evidence of absence as well as presence; `fact_scoping` published per edge |
| J1-003 | Every scenario with a successful observation on both sides is compared; the plan's dimension list governs what must be accounted for, not what may be looked at |
| J1-004 | Components classified by whether the resolver fetched them from a public repository; catalogue gaps separated from reachability gaps |
| J1-010 | An attempted-and-failed OLD execution becomes `UNOBSERVABLE_WITH_EXPLICIT_GAP` carrying the failure |
| — | 13 regression tests (`JudgeRepairRegressionTest`), one group per defect |
| — | README sections 20, 23, 33 updated to match |

**Change 3 makes validation strictly stronger and can turn a passing run into a blocking one. It did.**

Nothing prohibited was done: no `./src` edit, no forged approval, no disabled validation, no deleted or
weakened test, no suppressed error, no `NOT_COMPARED` → `PASS`, no `UNEXPLAINED` → `EXPECTED`, no
removed security check, no post-mutation baseline change, no edited evidence.

## 12. Pipeline run 2

Fresh run ID `RUN-01M26B99S7GWBDP1CSDV6Q5QV6` against unchanged `./src`. Run 1's artifact plane was
archived to `output-run-1/` — preserved, not deleted.

> **Two launch attempts preceded it, both invalidated by my own operator error, neither involving any
> code change.** The first resumed run 1's state instead of creating a fresh run. The second was
> launched from a shell where I had stripped `JAVA_HOME`, so the project's `mvnw.cmd` could not find
> a JVM, build resolution degraded to non-authoritative, and stage 04 failed. Its artifacts are kept
> at `output-run-2-aborted-launch-env/`. The third launch, with the correct ambient environment, is
> the run reported here.

| Stage | Result |
|---|---|
| 00 Bootstrap | SUCCESS — OSS gate PASSED |
| 01 Inventory | SUCCESS — 99 files, 6 modules |
| 02 Build | SUCCESS — **authoritative**, 962 dependencies, 0 unresolved |
| 03 Graph | SUCCESS — 839 nodes / 1852 edges, content hash `5b764c29af1e` (identical to run 1) |
| 04 Baseline | SUCCESS — sealed, old workspace matches original, 12 tests / 2 pre-existing failures, 4/6 modules |
| 05 Compatibility | SUCCESS |
| 06 Target | SUCCESS — 3.5.16 / Java 21, 8 edges, 1 boundary |
| 07 Documentation | SUCCESS — 21 documents pinned |
| 08 Knowledge | SUCCESS — 1691 facts, 1683 verified |
| 09 Impact | SUCCESS — 2072 findings, 27 affected files |
| 10 Characterization | SUCCESS — 82 scenarios, 36 frozen, 46 declared gaps, 0 pending |
| 11 Plan | SUCCESS — 8 edges, genuinely distinct |
| 12–17 EDGE-1 | SUCCESS — complete |
| 12–16 EDGE-2 | SUCCESS |
| 17 EDGE-2 | **POLICY_BLOCK** — 1 unexplained behavioural difference |

### The blocking finding

```
Scenario   SCN-00047        Dimension  CONFIGURATION_BINDING
Module     discovery-service           Target  /actuator/configprops
Verdict    UNEXPLAINED                 plan_required_dimension: false
```

`/actuator/configprops` exposed 236 property paths before and 237 after. One appeared, none
disappeared:

```
+ spring.cloud.loadbalancer.callGetWithRequestOnDelegates
    (org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties)
```

Real: moving Spring Cloud 2021.0.7 → 2021.0.9 alongside the Boot patch made a load-balancer property
bindable that was not bindable before. Classified `UNEXPLAINED` because no verified migration fact
names it and no human decision covers it.

`plan_required_dimension: false` means this comparison happened **only because of repair 3**. Before
the repair the harness executed this scenario on both sides, held both observations, and compared
neither — the patch edge would have passed clean and the change would have travelled silently into the
Jakarta boundary.

## 13. Did the repairs work

Measured against run 2's artifacts:

| Finding | Run 1 | Run 2 | Verdict |
|---|---|---|---|
| J1-001 | Refused at EDGE-2 with `PLAN_FROZEN not reached` | EDGE-2 transformed, built, tested, ran, compared | **FIXED** |
| J1-002 | All 8 edges: 1683 facts, coverage 0.6791 | 692–1682 facts, coverage 0.6795–0.9697, boundary lowest | **FIXED** |
| J1-003 | 36 executed, 18 compared, 1 dimension | 36 executed, 82 compared, 5 dimensions | **FIXED** |
| J1-004 | 37 spurious `internal:` entries | 0 possibly-internal; 8 catalogue gaps, 191 public-without-document | **FIXED** |
| J1-005 | All edges `FULL_DIFFERENTIAL` | All edges still `FULL_DIFFERENTIAL` | **NOT FIXED** |
| J1-006 | Stages 18–20 never ran | Stages 18–20 still never ran | **NOT FIXED** |
| J1-010 | 12 scenarios pending | 0 pending; 46 declared gaps | **FIXED** |

**J1-005 was not fixed, and the judge's diagnosis of it was wrong.** The judge predicted that scoping
facts per edge would let depth vary. It did not: even the smallest edge (692 facts) still contains a
HIGH-risk impact finding, so every edge escalates to maximum depth. Uniform maximum depth is safe but
uninformative, and the real cause is that the risk model does not discriminate between edge classes.
Under the no-further-repair rule this was left alone and is reported as an open defect.

**J1-006 was not fixed** because the finalization stages are gated behind edge completion, and the run
blocked at edge 2. The gate is correct; the cause moved from a harness bug to a genuine
behavioural finding.

## 14. Invariant compliance

| Invariant | Status | Evidence |
|---|---|---|
| `./src` is input only, never modified | **HOLDS** | `f48b7888…8687b` before run 1 and after run 2, byte-identical, 111 files walked |
| Cryptographic OLD baseline before any mutation | **HOLDS** | Baseline sealed, old workspace matches original, captured before edge 1 |
| Gateway is the only writer | **HOLDS** | 19 ledger entries; ArchUnit `gatewayIsTheOnlyMutationPort` enforces at build time |
| Every change fully attributed | **HOLDS** | Each entry: edge, FILE_ID, before/after hash, provider, recipe, operation, patch ref, fact refs, impact refs |
| AI proposes, never authorizes | **HOLDS** | Zero AI-proposed changes applied; all 19 deterministic |
| AI changes validated equally or more strongly | **HOLDS (vacuously)** | No AI change existed to validate |
| No test disabled, deleted, weakened; no coverage narrowed | **HOLDS** | 12 tests at baseline, 12 after both edges; harness suite grew to 190 |
| Documentation alone does not authorize mutation | **HOLDS** | All 19 changes carry artifact-channel fact references |
| Unknown means UNKNOWN | **HOLDS** | 46 scenarios `UNOBSERVABLE_WITH_EXPLICIT_GAP`; 0 pending |
| Unobserved means NOT_COMPARED | **HOLDS** | 92 NOT_COMPARED across two edges, each with a stated reason |
| Unexplained differences block | **HOLDS** | Run 2 terminated `POLICY_BLOCK` on exactly one such difference |
| Human decisions come from the decision mechanism | **HOLDS** | Decision store empty; no approval fabricated |
| Every claim has evidence and a coverage statement | **HOLDS** | Sections 15–16 |
| Blind spots described honestly | **HOLDS** | Section 16 |

## 15. Evidence and coverage statement

**Every claim in this report is scoped to two of eight edges: EDGE-1-PREP-TEST and EDGE-2-PATCH.**
Nothing here says anything about Spring Boot 3.x behaviour, because no edge reaching a 3.x version was
ever executed.

| Claim | Evidence | Level | Coverage |
|---|---|---|---|
| `./src` unmodified | Recomputed tree hash, 111 files, identical | Cryptographic | Complete |
| 19 changes applied and attributed | Hash-chained ledger, all APPLIED | Cryptographic | Complete for executed edges |
| Nothing changed outside authorized scope | Scope assertion, 0 violations, both edges | Mechanical | Complete for executed edges |
| Tests neither lost nor weakened | 12 before, 12 after, same classification | Executed | 4 of 6 modules produce tests |
| Toolchain executed as frozen | `java -version` parsed and matched | Executed | Both edges |
| Behaviour unchanged on edge 1 | 36 OLD/NEW scenario pairs, all IDENTICAL | Executed comparison | 4 of 6 modules; 5 of 12 dimensions |
| Behaviour changed on edge 2 | 1 UNEXPLAINED in CONFIGURATION_BINDING | Executed comparison | 4 of 6 modules; 5 of 12 dimensions |
| Repairs effective | Run-1 vs run-2 artifact comparison | Mechanical | 5 of 7 repairable findings fixed |

**What no evidence supports:** that the migration to 3.5.16 is achievable, safe, or complete. Six
edges were never attempted.

**Deterministic coverage (0.6795–0.9697) is not migration completeness.** It measures what fraction of
*known* facts have a transformation rule available, and says nothing about facts never learned.
`residual-report.json` carries this scope statement in the artifact itself.

**Impact accuracy 1.0/1.0** is measured on 11 held-out fixtures with an explicit scope warning; the 5
fixtures whose failures drove analyzer changes are excluded as tuning. It is not a general accuracy
claim.

## 16. Remaining blind spots

**No container runtime.** Docker installed, engine not running. `PERSISTENCE_STATE`, `QUERY_RESULT`
and `TRANSACTION_EFFECT` were never compared; 46 of 82 scenarios are declared gaps; `employee-service`
could not start.

**Runtime evidence covers 4 of 6 modules.** `configuaration-server` fails to start (pre-existing,
captured at baseline); `employee-service` needs MongoDB.

**7 of 12 characterization dimensions were never compared** on either edge.

**Two tests fail before any migration change**, so those two behaviours are unverified throughout.

**Coverage unmeasurable for `employee-service`** — reported as not evaluated, not as passed.

**Symbol attribution 0.6858** — above the 0.6 floor, well below complete. Roughly a third of symbol
relations are unresolved, and findings from them are capped at `POSSIBLY_AFFECTED`.

**No sealed evidence, no provenance graph, no export** — stages 18–20 never ran in either run.

**J1-005 remains open**: every edge is planned at maximum validation depth, so depth carries no
information.

**The Jakarta boundary is entirely unexercised.** EDGE-3-MAJOR-3 carries 33 recipes against 4 for any
other edge. It has never been run. The most likely place for this migration to fail is the place with
the least evidence.

## 17. Not implemented

Stated plainly rather than left to be discovered:

- **Gradle build-script transformation.** `BuildSystemResolver` detects Gradle and `Kind.MIXED`, and
  the model round-trips, but no Gradle transformation is implemented. A Gradle project would be
  analyzed and not migrated.
- **Full Gradle parity** — multi-project discovery, coverage extraction, toolchain handling.
- **Security and persistence graph enrichment**, and explicit ambiguity modelling in the symbol graph.
- **Extended artifact verification** beyond checksum and bytecode diff (no signature verification, no
  SBOM cross-check).
- **AI repair request content verification.** The AI boundary is enforced structurally — AI may
  propose, never authorize — but the contents of a repair request are not independently verified.
  Moot in these runs: no AI provider was enabled and no AI change was applied.
- **A single sealed tool/environment provenance record.** Tool and environment facts are recorded
  per stage; they are not consolidated into one sealed record.
- **Stages 18–20 end to end.** Written and wired, never executed by a run, therefore unverified in
  practice.

## 18. Deliverables

| File | What |
|---|---|
| [`MIGRATION_DOCUMENT.md`](MIGRATION_DOCUMENT.md) | End-to-end migration document — TOC, before/after architecture diagrams, path and control-flow diagrams, stage record, per-edge detail, full change inventory, validation, blind spots |
| [`llm-judge-pass-1.md`](llm-judge-pass-1.md) / [`.json`](llm-judge-pass-1.json) | The single judge pass over run 1 |
| [`judge-repair-pass.md`](judge-repair-pass.md) | The single consolidated repair pass |
| [`pipeline-run-1.log`](pipeline-run-1.log) | Run 1 console output |
| [`pipeline-run-2.log`](pipeline-run-2.log) | Run 2 console output |
| [`src-before-pipeline.sha256`](src-before-pipeline.sha256) / [`src-after-pipeline.sha256`](src-after-pipeline.sha256) | `./src` integrity, identical |
| [`impact-accuracy.json`](impact-accuracy.json) | Held-out fixture accuracy with scope warning |
| `BOOTSHIFT_FINAL_IMPLEMENTATION_AND_PIPELINE_REPORT.md` / `.json` | This report |
| `output/` | Run 2 artifact plane, stages 00–17 |
| `output-run-1/` | Run 1 artifact plane, preserved |
| `output-run-2-aborted-launch-env/` | Aborted launch, preserved |

---

*Run 2 was the final verification. No judging or repair followed it. The migration is blocked at edge
2 of 8 pending a human decision on SCN-00047, and that is the honest end state.*
