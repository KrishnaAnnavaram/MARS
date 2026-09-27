# MARS: Protected Business Logic and Parity Map

"Protected" means the behaviour must stay functionally unchanged. MARS either calls
this logic unchanged or reproduces its business rules exactly behind an adapter. A parity or guard
test pins each row.

This map was first written in Phase A with planned test names. It is now updated to the classes
and tests that actually exist; every test named here runs in the reactor build. Where a row has no
dedicated test, the row says so.

Legend for **How preserved**:

- **CALLED**: the original Java code is executed unmodified. Bootshift is built from
  `legacy-sources/bootshift` in the same reactor, and its own 190 tests keep running.
- **PORTED**: the original is Node.js or a Markdown rule, so its deterministic rules were
  re-implemented in Java. A parity test runs the legacy script (unchanged, via Node) and the port on
  the same input and compares the results.
- **INPUT**: the original is an LLM judgement step. The harness keeps it as a structured input
  artifact with the same schema and never fabricates it.

Every legacy file is checked against the export manifest by `ProtectedSourcesUnchangedTest`
(1,076 files, SHA-256).

## A. Bootshift (CALLED unless stated)

| # | Protected behaviour | Where | How preserved | Guard tests |
|---|---|---|---|---|
| A1 | Allocated FILE_ID; reattach order path → provider rename → hash → similarity → new; split/merge/delete lineage | `core/identity/FileRegistry` | CALLED; the kernel's sub-file identity sits below it and never re-implements it | Bootshift `FileIdentityTest`; `RenameDuringMigrationE2ETest` (FILE_ID survives a rename through the gateway) |
| A2 | Single writer: seal check, budgets, scope, staleness, containment, operations, patch, ledger, checkpoint, bypass detection | `adapters/mutation/FileMutationGateway` | CALLED; the kernel `MutationGateway` adds its checks and then delegates | Bootshift `MutationBoundaryTest`, `MutationHardeningTest`; `GatewayAuthorizationTest`, `MutationBypassIT`, `ArchitectureTest` |
| A3 | Tamper-evident change ledger (hash chain, verify, reopen) | `core/ledger/ChangeLedger` | CALLED; same ledger file, unchanged schema | Bootshift `ChangeLedgerTamperTest`; `ResumeRecoveryIT`, `CompositeSequencingE2ETest` |
| A4 | Pointer-after-write artifact publication | `core/domain/OutputLayout` | CALLED for Bootshift stages; kernel `FilesystemArtifactStore` uses the same temp-then-move rule and refuses source areas | `ArchitectureTest` (named writers only), `CapabilityContractTest.reportsAreViewsNotState` |
| A5 | Inventory, build resolution, application graph | stages 00–03 | CALLED through `BootshiftBridge` (`StageExecutor`) | `AnalyzeOnlyE2ETest` (registry, identity and graph produced by the bridge). No separate direct-run comparison test: the bridge executes the same stage classes. |
| A6 | Baseline before mutation: mutating states refused before the seal | `core/state/StateMachine`, gateway seal check | CALLED, plus kernel `RunStateMachine` enforcing the same invariant | `CoreContractsTest.theStateMachineRefusesSkippedGatesAndUnsealedMutation` |
| A7 | Curated lifecycle facts with evidence quality and staleness | `stages/stage05/LifecycleSource` | CALLED through `BootshiftLifecycleAdapter` | `MigrationAdvisorTest` (GREEN / YELLOW horizon / YELLOW ended, each citing the lifecycle fact) |
| A8 | Compiler diagnostics clustering for Bootshift's own pipeline | `stages/stage13/CompilerDiagnostics` | Not used by the unified flow. It stays callable in Bootshift's pipeline (A11). The unified round loop uses the reference workflow's classifier (C5). | Bootshift `CompilerDiagnosticsTest` |
| A9 | Process execution allowlist, timeout, redaction | `adapters/exec/ProcessRunner` | CALLED for every build and runtime process | `ArchitectureTest.processesAreLaunchedOnlyByAdapters` |
| A10 | A decision without actor or rationale is refused; the integrity hash is not a signature | `ports/approval/DecisionStore` | Semantics PORTED into kernel `DecisionValidator` and `FilesystemApprovalStore` (HMAC); the Bootshift store is untouched | `CoreContractsTest`, `HumanApprovalsE2ETest`, `CliWorkflowIT` |
| A11 | Full Bootshift migration pipeline (stages 05–20) | stages | CALLED as-is through the Bootshift CLI. Not rewritten; the unified 3→4 migration uses the reference-pack engine (ADR-U003). | Bootshift's own suite |

## B. Vulnerability Remediation Harness (PORTED / INPUT)

| # | Protected behaviour | Legacy source | Unified location | Parity / guard tests |
|---|---|---|---|---|
| B1 | Register column contract; skip blank ids; numeric sort; list split on newline/comma with bullet strip; synthesized body; JS whitespace trimming | `00-issue-register/scripts/lib/{register,xlsx}.js` | `kernel.adapters.excel.{Xlsx,IssueRegister,IssueRegisterNormalizer}` | `IssueRegisterParityTest` (composite fixture, both legacy registers, edge-case workbook written by the legacy writer) |
| B2 | CWE detection `CWE-\d+`, uppercase, dedupe, first-appearance order | `detectCweMentions` | `CweRouter.detectCweMentions` | `RoutingParityTest`, `SecurityUnitTest` |
| B3 | Catalog wins if any detected CWE is catalogued; one CWE per plan; a gap is stated, never invented | 04a SKILL, `classifyGap`, `detect-gap.js` | `security.routing.CweRouter` | `RoutingParityTest` (includes all legacy register bodies). The mixed-gap case is a documented deviation: the port follows `run-fallback.js --cwe`. |
| B4 | Hybrid ranking (keyword/synonym, TF-IDF, renormalised weights, round4, tie-breaks); derived strategy Low; provenance; KB gap refused | `04c/scripts/lib/fallback.js`, `run-fallback.js` | `security.kb.{HybridRanker,KbStrategyDeriver}` | `KbRankingParityTest` (exact scores and strings; KB-gap and catalogued-CWE exits) |
| B5 | Research: double-gap trigger; Low always; ≥2 candidates; no fabricated citations; evidence-gap plan; promotion candidate never auto-written | `04d/scripts/generate-strategy.js` | `security.research.ResearchAssembler` (analysis is INPUT) | `ResearchParityTest`, `VulnerabilityRoutesE2ETest` |
| B6 | Plan is Proposed; only an explicit human approval authorizes the fix | 04a plan, 04b `verify-patch.js` | Machine `Decision` bound to the proposal hash; `fix_plan_<ISSUE>.md` rendered from it | `HumanApprovalsE2ETest`, `GatewayAuthorizationTest`, `VulnerabilityRoutesE2ETest` |
| B7 | Fix statuses `Compiled` / `Compile Failed` / `Refused` | 04b | `security.verify.FixVerifier` | `VulnerabilityRoutesE2ETest`, `ArbiterParityTest` (both fix statuses) |
| B8 | 05 verdict vocabularies (rescan, redteam, behaviour); "absence of the old signature is a data point, not proof" | 05-verify | `FixVerifier` (identity-anchored rescan) | `VulnerabilityRoutesE2ETest`, `ResumeRecoveryIT` |
| B9 | 06 QA and build gates (`Passed` / `Failed` / `Refused`) | 06a / 06b | `FixVerifier` over one VERIFY build | `VulnerabilityRoutesE2ETest`, `SecurityUnitTest` |
| B10 | Arbiter scoring, thresholds, hard gates, override may only downgrade | 07a `scoring.json`, `compute-score.js` | `security.verify.MergeArbiter` reading the **same** `scoring.json` | `ArbiterParityTest` (1,152 combinations), `SecurityUnitTest` |
| B11 | PR only for Cleared plus an explicit request | 07b | Not implemented: the harness makes no git/gh calls. The final report and `cumulative.patch` are its output, and applying to the project is a separate decision-bound command. | — (limitation, see the implementation report) |

## C. Spring migration reference (PORTED / INPUT)

| # | Protected behaviour | Legacy | Unified | Tests |
|---|---|---|---|---|
| C1 | No matching reference pack is a stop (no improvising); a drifted pack is a stop | `detect-baseline.js`, SKILL | `ReferencePack.loadAll` + `ReferencePackEngine.plan` stop conditions | `MigrationAdvisorTest.unknownWhenNoPlatformCanBeObserved` (no platform → UNKNOWN). No dedicated test for a drifted pack. |
| C2 | Sandbox only; the project is never edited; apply is explicit and refused unless green | `prepare-workspace.js`, `apply-migration.js` | Workspace written only through the gateway; `exec/` copies for builds; `ProjectApplier` | `ApplyToProjectIT`; every E2E asserts the repository tree hash is unchanged |
| C3 | Round 0 before any version change; pre-existing test failures recorded, not fixed | SKILL §Step 4 | Baseline seal = round 0 | `FullMigrationE2ETest` |
| C4 | Build-file rules up front; source changes only when a round fails with the rule's symptom | SKILL §Step 5/6 | `ReferencePackEngine` with `spring-boot-3-to-4.rules.json` (pinned to the pack SHA-256) | `FullMigrationE2ETest` (round 1 touches no Java; every later rule follows a failing round) |
| C5 | Error parsing and categories | `parseBuildErrors`, `ERROR_CATEGORIES`, `summariseErrors` | `kernel.adapters.maven.BuildErrorClassifier` | `BuildErrorClassifierParityTest` (all 8 recorded round logs + synthetic logs) |
| C6 | Outcome vocabulary `passed` / `compile-failed` / `dependency-failed` / `tests-failed` / `timed-out` | `buildOutcome` | `BuildPort.Outcome` legacy ids | `BuildErrorClassifierParityTest` |
| C7 | Probe comparison identical / body-differs / status-differs; test comparison vs round 0 | `compareProbes`, `testComparison` | `kernel.ports.runtime.ProbeComparator` | `ProbeComparatorParityTest` (recorded runtime files). Documented deviation: a run that never started is "not compared", never a zero-probe match. |
| C8 | Every round kept; failed rounds are evidence | SKILL | Round records persisted in `migration-execution.json`; report generated from them | `FullMigrationE2ETest` |
| C9 | Behaviour, not compilation, is the acceptance criterion; §13 differences must be explained | SKILL, pack §13 | `BEHAVIOR_PROBES` dimension; unexplained differences fail | `FullMigrationE2ETest`, `RealToolchainAcceptanceIT` |

## D. Things that must not be merged prematurely (spec §31)

These stay in separate packages with separate data files:

- the reference pack
- compiler-guided repair rules
- the CWE catalog
- the KB
- the research assembler
- root cause
- blast radius
- migration compatibility
- target resolution
- probes
- red-team

`ArchitectureTest` enforces that the two capability packs do not depend on each other. The shared
kernel owns only infrastructure: identity, graph, evidence, ledger, decisions, the mutation
gateway, validation orchestration and the run state.
