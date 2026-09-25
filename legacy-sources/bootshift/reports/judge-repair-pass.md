# Consolidated Repair Pass — one pass, after judge pass 1

**Input:** [`reports/llm-judge-pass-1.md`](llm-judge-pass-1.md), decision `REPAIR_ONCE`
**Applied:** 2026-09-10
**Scope:** the seven repairable findings from judge pass 1. Nothing else.

This is the only repair pass. After the pipeline is re-run there is no further judging and no further
repair, whatever the outcome.

---

## Changes

### 1. J1-001 — preconditions answered from the artifact plane

[`StageExecutor.java`](../stages/src/main/java/com/bootshift/stages/StageExecutor.java)

`hasReached` decided analysis-half preconditions by comparing state ordinals, and refused whenever the
cursor was in a mutating state. Once EDGE-1 completed, the cursor sat at `EDGE_COMPLETE` and
`PLAN_FROZEN` — satisfied hours earlier, its artifact on disk — evaluated false. Every remaining edge
was refused.

A `PROOF_ARTIFACT` map now names the published artifact that proves each of the seventeen
analysis-half states, and `analysisStateSatisfied` answers from it, falling back to the cursor only
when the artifact is absent. This makes the check monotonic, which is what a precondition must be, and
lets a resumed run reconstruct the same answer with no cursor at all — which is what R23 says the
artifact plane is for.

The fallback still refuses: a state with no artifact and a cursor behind the requirement is
unsatisfied, so this does not become a way to skip a stage.

### 2. J1-002 — facts scoped to the edge they were attributed to

[`PlannerStage.java`](../stages/src/main/java/com/bootshift/stages/stage11/PlannerStage.java)

`EdgeFact.appliesTo` returned `true` whenever a fact carried edge attribution, without comparing the
attribution to anything — the edge id was not even a parameter. All eight edges therefore received all
1683 verified facts and reported identical coverage.

`appliesTo(edgeId, from, to)` now tests membership. Attribution is treated as evidence of absence as
well as of presence: a fact attributed to `EDGE-3-MAJOR-3` and not to `EDGE-2-PATCH` is absent from the
patch edge. Facts with no attribution still fall back to intersecting their validity window with the
edge span. Each edge plan now carries a `fact_scoping` block recording how many facts arrived by each
channel, so a genuinely small edge is distinguishable from a scoping failure.

Effect on this repository, from the run-1 knowledge artifact: 991 facts carry attribution, distributed
696 / 990 / 859 / 728 / 767 / 886 / 884 across edges 2–8, and `EDGE-1-PREP-TEST` receives none of them.

### 3. J1-003 — every scenario observed on both sides is compared

[`DifferentialStage.java`](../stages/src/main/java/com/bootshift/stages/stage17/DifferentialStage.java)

`compareScenarios` skipped any scenario whose dimension was absent from the edge plan's
`differential_dimensions`. That list is derived from the impact set — a statement about what the
harness *expects* to change — so it discarded observations that had been frozen against OLD and
executed against NEW. Run 1 executed 36 oracles on both sides and compared 18.

Every scenario holding a successful observation on both sides is now compared. The frozen dimension
list still decides which dimensions the edge must account for, and still drives the module-level
fallback and the coverage statement; it no longer decides which measurements are looked at.
Comparisons outside the plan's dimensions are flagged `plan_required_dimension: false` and reported
under `dimensions_compared_beyond_plan`.

**A difference stays blocking wherever it is found.** An unexplained behavioural change in a dimension
the plan did not anticipate is precisely the change least likely to have been anticipated, so it is
not downgraded for being unplanned. This repair can therefore make a run block that would previously
have passed — that is the intended direction.

### 4. J1-004 — components classified by resolution evidence

[`ComponentDocumentationCatalog.java`](../stages/src/main/java/com/bootshift/stages/stage07/ComponentDocumentationCatalog.java),
[`DocumentationStage.java`](../stages/src/main/java/com/bootshift/stages/stage07/DocumentationStage.java)

Any coordinate whose group did not begin with one of seven prefixes was labelled `internal:`. Guava,
Gson, `commons-*`, Joda-Time, XStream, ANTLR and Jersey — all resolved from Maven Central by the build
itself — appeared as organization-internal components with no authoritative source, 37 entries burying
the four that mattered.

`detect(model)` now asks the resolver's own answer: a coordinate fetched from a public repository is
public, whatever its group is named. Only an unresolved coordinate, or one from a repository not
demonstrably public, is reported as *possibly* internal. The registry keeps the two gaps apart —
`public_components_without_catalogued_document` (a gap in the catalogue) and
`possibly_internal_components` (a gap in reachability).

### 5. J1-005 and J1-006 — consequences, not separate changes

Validation depth varies once facts are scoped (2), and the finalization stages run once the edge loop
proceeds (1). Both are verified by observing run 2 rather than by further code changes.

### 6. J1-010 — a failed observation is a declared gap

[`CharacterizationStage.java`](../stages/src/main/java/com/bootshift/stages/stage10/CharacterizationStage.java)

A scenario attempted against OLD whose execution failed stayed in `AWAITING_OLD_OBSERVATION`. That
state means "not executed yet" — something a later stage could still resolve. Nothing would resolve
these, so they protected nothing and were not counted as declared blind spots either: the one
combination the evidence rules do not allow.

An attempted-and-failed scenario now becomes `UNOBSERVABLE_WITH_EXPLICIT_GAP` carrying the failure
reason. A scenario that was never attempted still stays pending.

### 7. Regression tests

[`JudgeRepairRegressionTest.java`](../tests/src/test/java/com/bootshift/tests/pipeline/JudgeRepairRegressionTest.java)
— 13 tests across four nested groups, one per repaired defect. Each of these failures was silent
rather than loud: the run kept producing well-formed artifacts that said less than they appeared to,
and none would be caught again by reading a green build.

`StageExecutor.analysisStateSatisfied` and `PlannerStage.EdgeFact` were widened to public to give the
rules a testable seam.

### 8. README

Sections 20, 23 and 33 state the three changed rules. The README describes what the harness does; it
had to change with it.

## What this pass did not do

Checked against the constraints recorded in the judge report:

| Prohibited | Done? |
|---|---|
| Edit original `./src` to make the run pass | No — recomputed hash `f48b7888…8687b`, identical to the pre-run-1 baseline |
| Forge or synthesize a human approval | No — no decision was written; the store is still empty |
| Disable, skip or weaken a validation, gate or policy | No — change 3 makes validation strictly stronger |
| Delete, disable or weaken a test; narrow coverage | No — 177 tests before, 190 after, none removed or relaxed |
| Suppress an error to avoid a refusal | No — change 1 fixes a wrong refusal; the refusal path is intact |
| Turn `NOT_COMPARED` into `PASS` | No — change 3 turns *uncompared* into *compared*, and the result is whatever it is |
| Reclassify `UNEXPLAINED` as `EXPECTED` | No |
| Remove a security or license check | No |
| Change the baseline after mutation | No |
| Edit evidence artifacts to manufacture success | No — no artifact from run 1 was modified |

## Verification before re-running

```
mvn -o clean install -DskipTests   BUILD SUCCESS
mvn -o test                        Tests run: 190, Failures: 0, Errors: 0, Skipped: 1
SourceTreeHash src                 f48b7888b0016061a2d693686412a2d04822aa14c81c506114f9392c6bc8687b
```

The single skip is the pre-existing platform-conditional test in `MutationBoundaryTest`.
