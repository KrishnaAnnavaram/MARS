# MARS: Current System Analysis (Phase A)

Date: 2026-09-24. Scope: the three source repositories named in
`docs/UNIFIED_HARNESS_IMPLEMENTATION_MASTER_PROMPT.md` §1, inspected at the exact branches it names,
before any integration code was written.

## 1. Sources verified

| Repo | Remote | Branch (verified) | Commit | Tracked files |
|---|---|---|---|---|
| A — Bootshift | github.com/KrishnaAnnavaram/bootshift | `main` | `eacdca1997c3` | 447 |
| B — Vulnerability Remediation Harness (VRH) | github.com/kaajalkrish/Vulnerability-Remediation-Harness | `main` | `767bf5a8ff60` | 368 |
| C — Spring migration reference | github.com/Udaradg/sample-java-project | `feature/springboot-3-to-4` | `9616523198599` | 262 |

The originals stay untouched in `merged_repos/`, each with its own `.git`. Exact tracked-file exports
are under `legacy-sources/`; see `legacy-sources/SOURCES.json`. One gap is faithful to the source:
repo C tracks a gitlink with no submodule entry, so it exports as an empty directory.

## 2. Toolchain on the analysis machine

| Tool | Present | Notes |
|---|---|---|
| JDK | 21.0.11 (Microsoft build) only | No JDK 17 is installed, so round 0 of the migration reference workflow cannot run on its declared from-JDK. The skill treats a missing from-JDK as a warning, not an error. |
| Maven | 3.8.7, from the wrapper cache `~/.m2/wrapper/dists` | Not on PATH. The harness locates it through `MAVEN_HOME` / `BOOTSHIFT_MAVEN_HOME`. |
| Node | 22.14 | Runs the VRH and migration-skill scripts for baseline and parity. |
| Docker | CLI installed, engine not running | Testcontainers tests self-skip. |
| Network | Maven Central reachable | Spring Boot 4.0.x and 4.1.x are published. |

## 3. Baseline build and test state (before any change)

| What | Command | Result |
|---|---|---|
| Bootshift (original clone) | `mvn -o test` | **190 tests, 0 failures, 0 errors, 3 skipped**, exit 0 |
| Bootshift (from `legacy-sources/`) | `mvn -o install` | Identical: 190 / 0 / 0 / 3 |
| VRH 04c self-test | `node .../04c-remediation-intelligence/scripts/test-sample.js` | PASS |
| VRH 04d self-test | `node .../04d-remediation-research/scripts/test-sample.js` | PASS |
| VRH pipeline lint | `node .github/scripts/pipeline-lint.js` | passed |
| Repo C pipeline lint | `node .github/scripts/pipeline-lint.js` | passed |
| Repo C sample app (Spring Boot 3.5.0) | `mvn -B package` on JDK 21 | BUILD SUCCESS: 19 tests, 0 failures, 5 skipped. The skipped tests are Testcontainers tests running without Docker. |
| VRH `employee-service` (Spring Boot 2.7.12) | `mvn -B test` on JDK 21 | **BUILD FAILURE (pre-existing, environmental).** See below. |

The VRH `employee-service` failure is `NoSuchFieldError: JCTree$JCImport ... qualid`. That is the
Lombok 1.18.28 (managed by Boot 2.7.12) versus JDK 21 incompatibility. VRH's own docs and golden
build reports already record a "JDK/Lombok toolchain mismatch", and repo C's commit notes describe
the same failure on JDK 25. It is kept separate from any failure this work introduces.

### Pre-existing skips and side effects

- The 3 Bootshift skips are environmental. `SchemaConformanceTest` and
  `PublishedArtifactSecretScanTest` skip because no pipeline run output exists.
  `MutationBoundaryTest.symlinkEscapeIsRefused` skips because Windows symlink creation needs a
  privilege the session does not have.
- Every Bootshift test run rewrites `reports/impact-accuracy.json` (`ImpactAccuracyTest` records
  `measured_at`). This is a pre-existing side effect on a tracked file. It was restored after the
  baseline runs.

## 4. Repository A — Bootshift

Java 21, Maven reactor: `core`, `ports`, `adapters`, `stages`, `apps/migration-cli`, `tests`. About
40.5k lines of Java. `./src` holds the **input** application (six Spring Boot 2.7 microservices) and
is deliberately not a build module.

### 4.1 Architecture

- **core** (`com.bootshift.core`): the domain.
  - Identity: `FileRegistry`, `FileRecord`, `FileRole`, `FileStatus`, `RenameSource`.
  - Graph: `ApplicationGraph`, `GraphNode`, `GraphEdge`, `NodeType`, `EdgeType`, `GraphDiff`.
  - Ledger: `ChangeLedger` (SHA-256 hash chain, `verify`, `reopen`) and `ChangeEvent`.
  - State: `RunState` and `StateMachine`, whose mutating states are guarded by the baseline seal (R7).
  - Evidence: `Claim`, `CoverageStatement`, `EvidenceLevel`, `EvidenceManifest`.
  - Policy: `HarnessPolicy`, `LicensePolicy`, `ValidationDepth`.
  - Other: `ProvenanceGraph`, `SensitiveValues`, and utilities (`Ids` ULIDs, `Hashing`, `Json`
    canonical, `SchemaValidator`, `Similarity`).
- **ports**: 20 interfaces. The key ones are `MutationPort` (the single writer, R13),
  `TransformationPort` (proposals only), `ApprovalPort` / `DecisionStore`, `CodeModelPort`,
  `ScmPort`, `BuildSystemPort`, `RuntimeProbePort`, `DifferentialPort`, `EvidenceObjectStore`,
  `RunStateStore` and `AIProvider`.
- **adapters**:
  - `FileMutationGateway`, the only `MutationPort` implementation.
  - `JavaParserCodeModelAdapter`, `MavenBuildAdapter` / `GradleBuildAdapter`, `GitScmAdapter` (JGit).
  - `ProcessRunner`, which enforces an executable allowlist, timeouts, output caps and a sanitized
    environment.
  - `HttpFetcher` (egress allowlist, content-addressed cache), OpenRewrite core transformers,
    `FilesystemDecisionStore`, `SpringProcessRuntimeProbe`, `ScenarioHttpExecutor` and others.
- **stages**: 20 stages plus bootstrap, sequenced by a thin `PipelineOrchestrator` (R24).
  - Analysis half: 01 inventory, 02 build, 03 graph, 04 baseline, 05 compatibility, 06 target,
    07 documentation, 08 knowledge, 09 impact, 10 characterization, 11 plan.
  - Per-edge mutating loop: 12 transformation, 13 build repair, 14 graph diff, 15 tests,
    16 runtime, 17 differential.
  - Finalization: 18 approval, 19 evidence, 20 provenance QA.
  - `StageExecutor` checks preconditions and input artifacts from the artifact plane (ADR-002).
- **Artifact plane**: `OutputLayout` writes `output/<stage>/<timestamp>/` and publishes
  `latest.json` atomically, pointer-after-write.

### 4.2 Key contracts observed

- **FILE_ID is allocated**, never derived (ADR-001). `FileRegistry.reattach` follows a fixed order:
  1. exact path
  2. provider or Git rename
  3. exact content hash
  4. similarity at or above 0.72 (token-shingle Jaccard)
  5. otherwise a new identity

  Split, merge and delete keep lineage. Delete and merge are statuses, never erasure.
- **SYMBOL_ID is not persistent.** `GraphBuilder.addType` calls `Ids.symbolId()` fresh on every
  graph build, so the same method gets a different `SYMBOL_ID` after each rebuild. This is the main
  identity gap the unified kernel fills.
- **No STATEMENT_ID, PROGRAM_UNIT_ID or MODULE_ID concept exists.** Module is a string attribute.
- **`FileMutationGateway.apply(Authorization, List<ProposedChange>, Provider)`** performs:
  - seal check (refuses before baseline)
  - batch budget
  - per-proposal authorization by FILE_ID or path-segment prefix, including RENAME destinations
    and MERGE sources
  - line budget
  - `base_hash` staleness
  - workspace containment, including symlink escape
  - CREATE / MODIFY / DELETE / RENAME / MERGE / SPLIT with registry updates
  - a unified-diff patch artifact
  - a hash-chained ledger event for every attempt, including rejected ones
  - a Git checkpoint

  `detectBypass()` compares on-disk hashes against the registry. ArchUnit forbids `Files.write*`
  from stage12/stage13/transform packages.
- **Decisions**: `DecisionStore.record` refuses a missing actor or rationale. The keyed hash is
  honestly named `integrityHash`, not a signature. `ActorAuthentication` is `LOCALLY_ASSERTED`.
- **Lifecycle knowledge**: `LifecycleSource` holds a curated Spring Boot lifecycle table (lines 2.7
  to 4.1) with `VERIFIED` quality, `AS_OF` 2026-09-01, and a 6-month staleness rule. A community
  source can only be `ADVISORY`; a missing source is `UNKNOWN`.
- **Compiler diagnostics**: `CompilerDiagnostics.parse/cluster` classifies failures as
  `DEPENDENCY_RESOLUTION`, `PLUGIN_OR_TOOLCHAIN`, `MISSING_TYPE_OR_PACKAGE`,
  `REMOVED_OR_RENAMED_API`, and so on.
- **Build resolution** is authoritative only when Maven or Gradle actually ran. Otherwise the model
  is descriptor-derived, flagged `authoritative=false`, and carries a blind spot.

### 4.3 Entry points

- CLI `bootshift` (picocli), under `apps/migration-cli`:
  - `inventory`, `resolve-build`, `graph`, `baseline`, `compatibility`, `resolve-target`,
    `documentation`, `knowledge`, `impact`, `characterize`, `plan`
  - `migrate [--edge]`, `validate`, `approve`, `report`, `run`
  - inspection commands
- Library entry points: `RunFactory.create(Options)` builds a `StageContext`, and
  `PipelineOrchestrator.runAnalysis/runEdges/runFinalization`.

### 4.4 Stage inputs and outputs (analysis half, which the unified kernel consumes)

| Stage | Consumes | Publishes (under `output/<dir>/`) |
|---|---|---|
| 00-bootstrap | repo | `bootstrap.json`, `source-provenance.json`, `oss-license-gate.json`; creates `original/` (read-only snapshot) and `migration/` workspaces plus checkpoint git |
| 01-inventory | snapshot | `inventory-artifact.json`, `file-registry.json`, `inventory-signals.json`, `inventory-issues.json` |
| 02-build | inventory | `build-model.json` (with `authoritative`), `dependency-model.json`, `bom-model.json`, `plugin-model.json`, … |
| 03-graph | build model, registry | `application-graph.json`, `file-registry.json` (with symbols), `symbol-registry.json`, sub-graphs, `graph-verification-report.json` |
| 04-baseline | graph, build | `baseline-build.json`, `baseline-tests.json`, `baseline-runtime.json`, `baseline-manifest.json` (sealed hash) |
| 05–11 | above | compatibility registry, target and migration path, documentation, migration knowledge, impact report, characterization, frozen edge plan |

## 5. Repository B — Vulnerability Remediation Harness

This is **not** a prompts-only harness. Each stage is a pair:

- deterministic zero-dependency Node.js scripts, which handle discovery, gating, scoring and
  rendering;
- Markdown agent and skill definitions, which are the LLM judgement layer that writes the JSON
  inputs the scripts render.

`.github/agents/00..07` are canonical. `.claude/agents` are shims that point to them.
`.github/pipeline-contract.md` is the authoritative contract.

### 5.1 Chain and contract

```text
issue-register.xlsx (read-only)
  -> 01 architect
  -> 02 root cause
  -> 03 blast radius
  -> 04 plan (04a catalog / 04c KB / 04d research)
  -> Proposed
  -> [human edits Status cell -> Approved | Rejected]
  -> 04b fixer (Compiled | Compile Failed | Refused)
  -> 05 rescan / redteam / behavior
  -> 06 QA / build
  -> 07 arbiter (Cleared | Blocked)
  -> scribe
  -> PR only on Cleared plus an explicit user request
```

Decision policy, verbatim:

- "`STILL_VULNERABLE` and build `Failed` are hard gates and compute `Blocked`."
- "The merge arbiter may only override a computed `Cleared` to `Blocked`."
- "A `Cleared` verdict is the only release authorization."

### 5.2 Routing hierarchy (preserved exactly)

- **CWE detection**: regex `CWE-\d+` over the issue body plus the root-cause text, deduplicated in
  first-appearance order.
- **Level 1, 04a catalog**: `catalog/cwe-patterns.json` holds 10 entries: CWE-943, 89, 306, 284,
  532, 200, 770, 400, 798, 79. Repo C's copy adds CWE-1104.
  - "One CWE per plan."
  - A catalogued CWE always wins; 04c is used only when every detected CWE is a gap.
- **Level 2, 04c KB**: `knowledge/remediation-kb.json` covers CWE-22, 918, 359 and 862, each with
  `historical_fixes`, synonyms and provenance.
  - Hybrid rank = weighted mean of available signals: keyword/synonym 0.3, TF-IDF cosine 0.3,
    embedding 0.4. The embedding is optional, and when it is unavailable the weights renormalise.
  - No score threshold; the top-ranked fix is used.
  - Derived strategy is **Low** confidence, `catalog_reference.title=null`, and carries a provenance
    sentence plus `derived_pattern.source_type=expanded-catalog`.
  - A KB gap is refused, never invented.
- **Level 3, 04d research** runs only on a double gap. 13 steps; at least 2 candidates; the smallest
  safe one is selected.
  - Confidence is always **Low** and never self-upgrades.
  - "Never fabricates a citation": sources stay empty when internally derived.
  - `research_status: established | insufficient_evidence`. Insufficient evidence produces a
    **Proposed evidence-gap plan**.
  - A promotion candidate is never written to the KB automatically.
- **Approval**: a human edits `| **Status** | Proposed |` to `Approved`. The regex is exact and
  case-sensitive.
  - Re-rendering preserves `Approved`/`Rejected`.
  - The Fixer refuses anything else ("a strongly-worded request is not approval").
- **Arbiter** (`scoring.json`):
  - Points: redteam `NO_BYPASS_FOUND` 30 / `INCONCLUSIVE` 15; behavior `BEHAVIOR_PRESERVED` 30 /
    `INCONCLUSIVE` 15; QA `Passed` 40.
  - Thresholds by severity: Critical 90, High 85, Medium 75, Low 65; default 85.
  - Hard gates: rescan `STILL_VULNERABLE` and build `Failed`.

### 5.3 Inputs and golden outputs

- **Register** `docs/agent_output/00-issues/issue-register.xlsx`, sheet `issues`, 17 columns, no CWE
  column. Five rows:

  | Issue | Finding | Route | Note |
  |---|---|---|---|
  | ISSUE-001 | CWE-770, unbounded `findAll()` | catalog | real in code |
  | ISSUE-002 | CWE-306, missing auth + PII in logs | catalog | real in code |
  | ISSUE-003 | CWE-943, NoSQL injection via `BasicQuery` concatenation | catalog | real in code |
  | ISSUE-004 | CWE-22 | KB | synthetic fixture |
  | ISSUE-005 | CWE-502 | research | synthetic fixture |

- **Golden outputs** live in `docs/agent_output/0x-*/`. The 004 KB plan ranks `HF-PATH-001` at 0.62
  with the embedding and about 0.66 without it. The 005 research plan is established, with two
  candidates and no citations.
- **Caution**: the goldens are partly inconsistent in time. The 002 and 003 verdicts predate their
  latest build reruns. Parity tests use the scoring *rules*, not those stale verdict files.

### 5.4 Target microservices

The same six Spring Boot 2.7.12 / Java 17 services as Bootshift's `./src`, using Mongo and Lombok.
The real vulnerabilities (ISSUE-001/002/003) are confirmed at the cited lines, for example
`EmployeeSearchRepository.java:20-31` for the `BasicQuery` concatenation. They do not compile on
JDK 21 (§3).

## 6. Repository C — Spring migration reference (`feature/springboot-3-to-4`)

Two parts:

- **The migration capability**: skill `.github/skills/04d-version-migration` (Node scripts plus
  `SKILL.md`) and reference pack `references/spring-boot-3-to-4.md`.
- **A copy of the VRH pipeline** that adds `04c-dependency-upgrader` (CWE-1104, version-bump
  worktree verification) and a CWE-1104 catalog entry.

Sample app: `com.example:spring-boot-migration-demo`, Spring Boot 3.5.0 / Java 17. It uses H2,
security, actuator and JPA, and ships 6 test classes.

### 6.1 Migration workflow (protected semantics)

1. **Detect baseline** (`detect-baseline.js`). POM inventory, JDKs and pack matching through
   `detect:`. "No matching reference pack is a stop condition, not a licence to improvise."
2. **Understand the app and write `probes.json`.** "Cover the real endpoints … happy path, an error
   path, and an authentication boundary."
3. **Sandbox** (`prepare-workspace.js`). Copy plus `git init`. "The project directory is never
   edited."
4. **Round 0** build and baseline probes run **before any version is changed**.
   - Does not compile: stop.
   - Pre-existing test failures are recorded, never fixed.
5. **Change declared versions** using the pack's build-file section: parent, Java level,
   renamed/split starters, pinned third-party libraries, container image.
   - "Do not pre-emptively rewrite source code in this step."
6. **Round loop**: build, group errors by category, look up the pack's symptom table, apply the named
   rule, re-run.
   - "The compiler is the authority; the reference pack is the map."
   - "Never guess an import path."
   - Every round is kept; there is no round cap.
   - Intents run cheapest first (test-compile, then package). At least one round must use round 0's
     goal.
7. **Final probes**, then **compare**.
   - Probe verdicts: identical / body-differs / status-differs.
   - Test verdict compares against round 0.
   - "Behaviour is the acceptance criterion, not compilation."
8. **Judgement record plus report** (`migration_<slug>.md`) and a **cumulative patch** (`.diff`).
   Outcomes are rendered exactly as recorded.
9. **Apply** is a separate explicit step, refused unless the last round is `passed`.

Round outcome vocabulary: `passed`, `compile-failed`, `dependency-failed`, `tests-failed`,
`timed-out`. Behaviour verdict: `unchanged` / `changed` / `not-compared`.

The recorded run took 8 rounds (0–7) and touched 6 files. The main changes were parent 4.1.1,
Java 21, `webmvc`, Jackson 3 `JsonMapperBuilderCustomizer`, the health-contributor package,
`@MockitoBean`, the test-slice starters and `spring-boot-starter-security-test`.

## 7. Cross-repository observations

- VRH's target app and Bootshift's `./src` are the same application, so a composite
  migrate-plus-remediate scenario is natural.
- There are two catalogs and two dependency-upgrade notions:
  - VRH has no dependency remediation stage.
  - Repo C adds `04c-dependency-upgrader` and a CWE-1104 catalog entry.
  - The unified catalog is VRH's catalog plus repo C's CWE-1104 entry, with provenance per entry
    (ADR-U004).
- **Three "04" namespaces collide**:
  - VRH `04c-remediation-intelligence` versus repo C `04c-dependency-upgrader`;
  - VRH `04d-remediation-research` versus repo C `04d-version-migration`.

  MARS names capabilities by function, not by skill number.
- **Every approval mechanism is text-based**: a VRH Markdown status cell, repo C's explicit "apply"
  request, and Bootshift's local decision store. Only Bootshift's is machine-structured. The unified
  kernel adopts Bootshift's `DecisionStore` semantics, extended for scope and staleness.

## 8. Protected business logic

See `docs/protected-business-logic.md` for the full protection and parity map, and
`docs/implementation-plan.md` for the phased plan.
