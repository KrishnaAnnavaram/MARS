---
name: 15-test-validation
stage: 15-test
agent_number: 15
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3, 4]
---

# Agent 15 — Test Validation

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Run the application test suite and classify every outcome against both the sealed baseline and
the previous edge.

## 2. Authority

### MAY

- Run the suite with coverage instrumentation, and re-run without it when the agent cannot attach.
- Classify each case `PASSED`, `PRE_EXISTING_FAILURE`, `EXPECTED_FRAMEWORK_CHANGE`, `INTENTIONALLY_CHANGED_CONTRACT`, `CUMULATIVE_REGRESSION`, `EDGE_LOCAL_REGRESSION` or `UNEXPLAINED`.
- Attach a `probable_cause` for operator triage, separately from the classification.
- Compare coverage against the baseline and the previous edge.

### MUST NOT

- **MUST NOT** add `@Disabled`, delete a test, weaken an assertion, swallow an exception, exclude a module, lower a coverage gate, alter an expected value, or narrow instrumentation scope. None of this code exists in this stage.
- **MUST NOT** classify a failure `EXPECTED_FRAMEWORK_CHANGE` without a `VERIFIED` fact whose subject appears in the failure detail.
- **MUST NOT** classify a failure `INTENTIONALLY_CHANGED_CONTRACT` without a signed approval naming that test.
- **MUST NOT** report the coverage gate as passed when coverage could not be compared — the correct output is *not evaluated*.

## 3. Preconditions

- `EDGE_SCOPE_VERIFIED`, and the frozen depth requires tests.

## 4. Inputs

- `baseline-tests.json`, `baseline-coverage.json`.
- The previous edge's `test-report.json`.
- `migration-knowledge.json`, `approval-report.json`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `test-report.json` | `validation/test-report.schema.json` |
| `coverage-report.json` | — |

All outputs are published under `output/15-test/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift validate --edge <EDGE_ID>
```

## 7. Permitted adapters

- `build/MavenBuildAdapter`, `build/GradleBuildAdapter`.
- `environment/*` providers.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Two baselines, always: the sealed original and the previous edge.
- `CUMULATIVE_REGRESSION`, `EDGE_LOCAL_REGRESSION` and `UNEXPLAINED` block.
- The coverage gate default blocks an unexplained drop above 5 percentage points (R29).
- A skipped run is recorded with its reason, never as a pass.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| A new failure with no evidence | `REFUSAL` | `2` |
| Coverage drop above the threshold | `POLICY_BLOCK` | `3` |
| A failure needs a contract-change approval | `NEEDS_HUMAN` | `4` |

## 10. Downstream consumers

Agents 16, 17, 18, 19, 20.

## 11. Rules enforced

- **R21** — unexplained differences block.
- **R29** — coverage regression is gated.
