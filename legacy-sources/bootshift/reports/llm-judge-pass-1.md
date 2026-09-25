# LLM-as-Judge — Pass 1 over Pipeline Run 1

**Run judged:** `RUN-01M267M0HBCW8KAGXQKF85WR67`
**Judged:** 2026-09-10
**Decision:** `REPAIR_ONCE`

This is the only judge pass this work receives. It was written by reading the published Run-1
artifacts, the change ledger, the edge index and the run workspace — not the console output — and it
was written **before any code was changed**.

---

## Table of contents

1. [What Run 1 actually did](#1-what-run-1-actually-did)
2. [Invariants: verified](#2-invariants-verified)
3. [Findings](#3-findings)
4. [Claims stronger than evidence](#4-claims-stronger-than-evidence)
5. [Decision and repair scope](#5-decision-and-repair-scope)
6. [What the repair pass may not do](#6-what-the-repair-pass-may-not-do)

---

## 1. What Run 1 actually did

The analysis half of the pipeline completed. The migration half did not.

| Half | Stages | Result |
|---|---|---|
| Analysis | 00 bootstrap → 11 plan | **All SUCCESS** |
| Edge loop | 12 → 17 for EDGE-1-PREP-TEST | **Completed** |
| Edge loop | EDGE-2-PATCH onwards | **STRUCTURED_REFUSAL** |
| Finalization | 18 approval, 19 evidence, 20 export | **Never executed** |

One of eight planned edges was migrated. The run then refused to continue, so no approval record,
no evidence manifest, no coverage statement, no migration document and no provenance graph exist for
Run 1. Nothing was invented to cover that absence, which is the correct behaviour — but it does mean
Run 1 produced **no publishable migration claim at all**.

What the completed edge demonstrates is worth stating precisely, because it is the part that works:

- 3 source changes applied through the gateway, each with edge id, FILE_ID, before and after hash,
  recipe id and patch reference; ledger chain valid; scope assertion clean.
- Build repaired in a single round on the frozen toolchain; the executed binary reported **17.0.20.1**
  against a frozen requirement of **17**, verified, not assumed.
- 12 tests before, 12 tests after — 10 PASSED, 2 PRE_EXISTING_FAILURE. No test was deleted,
  disabled, excluded or weakened.
- 36 scenarios frozen against OLD, all 36 executed against NEW.
- 18 scenario-level comparisons: 16 IDENTICAL, 2 NOT_COMPARED.

## 2. Invariants: verified

| Invariant | Verdict | Evidence |
|---|---|---|
| `./src` never modified | **HOLDS** | Before-hash `f48b7888…8687b` recomputed identical immediately before the run; all mutation under the external workspace root |
| Cryptographic OLD baseline before mutation | **HOLDS** | `baseline-manifest.json` sealed, hash `132e5db8da64002d`, old workspace matches original |
| Gateway is the only writer | **HOLDS** | 3 ledger events, all APPLIED, all through the gateway; ArchUnit rule `gatewayIsTheOnlyMutationPort` enforces it at build time |
| Every change carries full attribution | **HOLDS** | Each ledger event carries edge, FILE_ID, both hashes, provider, recipe id, fact refs, impact refs, patch ref |
| AI proposes, never authorizes | **HOLDS** | Zero AI-proposed changes in this run; all 3 changes were deterministic OpenRewrite recipes |
| Tests never weakened to go green | **HOLDS** | Test count identical before and after; the 2 failures were classified as pre-existing debt, not suppressed |
| Unknown stays UNKNOWN | **HOLDS** | 34 scenarios `UNOBSERVABLE_WITH_EXPLICIT_GAP`, 12 `AWAITING_OLD_OBSERVATION` — none counted as protection |
| Unobserved stays NOT_COMPARED | **HOLDS** | 2 NOT_COMPARED comparisons reported as such |
| Human approvals not manufactured | **N/A** | The approval stage never ran; no decision exists and none was forged |

Target resolution and path construction both hold up. Boot 3.5.16 was selected
`AUTO_HIGHEST_SAFE_SUPPORTED` with 4.0 and 4.1 eliminated on a real constraint — the application uses
Spring Cloud and no GA train targets those lines. The path crosses the single major boundary between
2.7 and 3.5 with a dedicated `MAJOR_BOUNDARY` edge at 3.0, and the stage's own self-check (which
throws when boundary edges are fewer than majors crossed) passed. Landing Java 21 came from
`LTS_PREFERRED` over the *installed* JDKs `{17, 21}`, not from the JVM running the harness.

## 3. Findings

### J1-001 — `HARNESS_IMPLEMENTATION_DEFECT` — **BLOCKING**
**StageExecutor refuses every edge after the first.**

`StageExecutor.hasReached` evaluates a non-mutating required state with
`!current.isMutating() && current.ordinal() >= required.ordinal()`. After EDGE-1 finishes, `current`
is `EDGE_COMPLETE`, which *is* mutating, so the precondition `PLAN_FROZEN` — long since satisfied —
evaluates false and EDGE-2 is refused with *"Required state PLAN_FROZEN has not been reached
(current: EDGE_COMPLETE)"*.

The precondition mechanism I added to enforce stage ordering is what stopped the run. The plan
genuinely is frozen; the state machine is being asked the wrong question. A precondition about an
analysis-half artifact should be answered from the artifact plane — the published artifact that
proves the plan exists — not from a state ordinal that necessarily moves forward as the edge loop
progresses.

**Repairable.** This is the single defect that turned a would-be eight-edge run into a one-edge run.

### J1-002 — `PLANNER_OR_KNOWLEDGE_DEFECT` — **MAJOR**
**Per-edge fact scoping does not narrow: every edge receives every fact.**

`PlannerStage.EdgeFact.appliesTo` returns `true` unconditionally when the fact carries declared edge
attribution, rather than testing membership — and it is never passed the edge id it would need to
test against.

The knowledge stage did its half correctly. It resolved **8 per-edge BOMs**, attributed 108
coordinates, and classified validity as `EDGE_EXACT` 112 / `ARTIFACT_VERSION_WINDOW` 887 /
`SPAN_ONLY` 692, with 991 facts carrying explicit edge attribution. The planner then discards all of
it. The visible symptom is that all 8 edges report **identical** `verified_facts` = 1683 and
**identical** `deterministic_coverage` = 0.6791.

That is exactly the defect Phase 2 exists to eliminate: a plan that looks per-edge and is not.

**Repairable.**

### J1-003 — `DIFFERENTIAL_VALIDATION_DEFECT` — **MAJOR**
**Scenarios executed against both sides are discarded before comparison.**

36 scenarios were frozen against OLD and all 36 executed successfully against NEW — but only **18**
were compared. `compareScenarios` skips any scenario whose dimension is absent from the edge's
`differential_dimensions`, and that list is derived from the impact set, which for EDGE-1 contained
only `CONTEXT_CAPABILITY`.

So `HTTP_API`, `SECURITY_AUTHORIZATION`, `SERIALIZATION` and `CONFIGURATION_BINDING` observations
exist on disk for *both* sides, and were thrown away. Those dimensions would be reported at E2 "no
comparison executed" while the evidence to reach E4 sits in the artifact directory. This under-claims
rather than over-claims, so it is not an integrity violation — but it wastes the most expensive
evidence the harness collects.

The frozen dimension list should govern which classifications **block** an edge, not which
observations are **looked at**.

**Repairable.**

### J1-004 — `HARNESS_IMPLEMENTATION_DEFECT` — **MODERATE**
**Documentation component detection labels ordinary OSS libraries as internal.**

`ComponentDocumentationCatalog.detectComponents` classifies any coordinate whose group does not start
with a short allowlist of prefixes as `internal:<group>`. Guava, ANTLR, Gson, Joda-Time, XStream,
`commons-*` and Jersey were all labelled internal, producing **37** entries in
`components_without_authoritative_source`.

That list exists to show where the documentation channel is blind. Padded with well-known OSS
libraries, it buries the four genuine gaps — Hibernate Validator, Tomcat, Resilience4j, Cucumber.

**Repairable.**

### J1-005 — `PLANNER_OR_KNOWLEDGE_DEFECT` — **MODERATE**
**Every edge is planned at `FULL_DIFFERENTIAL`, including the patch edge.**

All 8 edges froze `validation_depth = FULL_DIFFERENTIAL`. This is downstream of J1-002: because every
edge receives every impact finding, every edge sees a HIGH-risk finding and escalates to maximum
depth. A patch edge that bumps a parent POM does not warrant the same behavioural depth as the
Jakarta boundary, and uniform maximum depth is indistinguishable from having no depth calculation.

**Repairable as a consequence of J1-002.** Verify in Run 2 that depth actually varies by edge class.

### J1-006 — `EVIDENCE_OR_PROVENANCE_DEFECT` — **MODERATE**
**No final evidence, migration document or provenance graph exists for Run 1.**

Stages 18, 19 and 20 never executed, so the mechanical evidence-level assignment, the coverage
statement, the residual/blind-spot report, `MIGRATION_DOCUMENT.md` and the provenance graph were
never produced — and therefore were never *exercised end to end*. They must be verified in Run 2.

**Repairable as a consequence of J1-001.**

### J1-007 — `MISSING_EXTERNAL_INFRASTRUCTURE` — **ENVIRONMENTAL**
**No OCI runtime daemon, so datastore-dependent dimensions cannot be compared.**

Docker is installed on this machine but its engine is not running. `ContainerProvisioner` probed the
*daemon* rather than the binary, found nothing answering, and recorded `NO_OCI_RUNTIME` plus
`UNPROVISIONED` gaps for mongodb and scheduler. `PERSISTENCE_STATE`, `QUERY_RESULT` and
`TRANSACTION_EFFECT` scenarios were emitted as `UNOBSERVABLE_WITH_EXPLICIT_GAP` and will be
NOT_COMPARED.

This is the harness behaving correctly under an environment limitation. **Not repairable in code** —
it requires a running container runtime on the execution host, and the final report must carry it as
a declared blind spot.

### J1-008 — `SOURCE_APPLICATION_PREEXISTING_DEBT` — **INFORMATIONAL**
**Two baseline test failures; two modules that cannot start without infrastructure.**

The original application has 2 failing tests before any migration change, `configuaration-server`
fails to start outright, and `employee-service` cannot refresh its context without MongoDB. All three
were captured in the sealed baseline and classified as `PRE_EXISTING_FAILURE`, not as migration
regressions. Correct. Not a harness defect.

### J1-009 — `EXPECTED_LIMITATION` — **INFORMATIONAL**
**Coverage is unmeasurable for `employee-service`.**

No coverage report at baseline or at EDGE-1, so the coverage gate is reported as *not evaluated* for
that module rather than as passed, with an explicit gate note. Correct.

### J1-010 — `TEST_OR_CHARACTERIZATION_DEFECT` — **MINOR**
**Twelve scenarios remain `AWAITING_OLD_OBSERVATION`.**

12 of 82 scenarios were neither executed against OLD nor marked unobservable. They protect nothing
and are correctly excluded from the oracle set — but they are also not converted into an explicit
declared gap, so the coverage statement does not count them.

**Repairable.**

## 4. Claims stronger than evidence

| Check | Result |
|---|---|
| Any dimension claiming E4 without executed OLD/NEW comparisons? | **No claim was published at all** in Run 1 — stage 19 never ran. The mechanical level assignment is therefore *unverified end to end* and must be checked in Run 2. |
| Deterministic coverage presented as migration completeness? | **No.** `residual-report.json` carries an explicit `metric_scope` saying it measures rule availability for *known* facts, not fact-set completeness. |
| Impact accuracy presented as general accuracy? | **No.** `reports/impact-accuracy.json` records 11 held-out fixtures with an explicit scope warning; the 5 fixtures whose failures drove analyzer changes are excluded as tuning. |

## 5. Decision and repair scope

**`REPAIR_ONCE`.**

The blocking defect is a harness bug in my own precondition logic, not a migration-safety failure,
not a forged claim, and not an environment wall. Nothing in Run 1 over-claims. The repair is
mechanical and bounded.

The single consolidated repair pass covers exactly seven items:

| # | Finding | Change |
|---|---|---|
| 1 | J1-001 | Answer non-mutating preconditions from the artifact plane instead of state ordinals |
| 2 | J1-002 | Pass the edge id into `EdgeFact.appliesTo`; test declared-edge membership, keep interval intersection as fallback |
| 3 | J1-003 | Compare every scenario holding both an OLD and a NEW observation; dimension list governs blocking, not visibility |
| 4 | J1-004 | Classify components by public-repository resolvability, not group-name prefix |
| 5 | J1-005 | Consequence of #2 — verify depth varies by edge class in Run 2 |
| 6 | J1-006 | Consequence of #1 — finalization stages will run |
| 7 | J1-010 | Convert an OLD-execution failure into `UNOBSERVABLE_WITH_EXPLICIT_GAP` carrying the failure |

Findings J1-007, J1-008 and J1-009 are **not repaired**. They are environment and application facts,
and the final report will state them as declared blind spots.

## 6. What the repair pass may not do

Recorded here so the repair can be audited against it:

- Not edit the original `./src` to make the run pass.
- Not forge, synthesize or impersonate a human approval.
- Not disable, skip or weaken any validation, gate or policy.
- Not delete, disable or weaken any test, or narrow coverage scope.
- Not suppress or swallow an error to avoid a refusal.
- Not turn `NOT_COMPARED` into `PASS`.
- Not reclassify `UNEXPLAINED` as `EXPECTED` without evidence.
- Not remove a security or license check.
- Not change the baseline after mutation.
- Not edit evidence artifacts to manufacture success.

If Run 2 still fails, the run stops there. No second judge pass, no second repair.
