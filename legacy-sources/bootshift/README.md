<div align="center">

# Bootshift

**`bsh`**

### A Spring Boot migration harness that proves what it did

![Java](https://img.shields.io/badge/Java-21-blue) ![Build](https://img.shields.io/badge/build-Maven-C71A36) ![License](https://img.shields.io/badge/license-MIT-green) ![OSS](https://img.shields.io/badge/dependencies-strict%20OSS-success) ![AI](https://img.shields.io/badge/AI-optional%2C%20off%20by%20default-lightgrey)

**Bootshift does not merely change Spring Boot versions.**
It understands the repository, plans the migration from evidence, tracks every source change,
validates the result structurally and behaviourally, and produces a defensible evidence trail
explaining what changed, why it changed, what it affected, and how it was validated.

</div>

---

## Table of contents

1. [Project overview](#1-project-overview)
2. [Why Bootshift exists](#2-why-bootshift-exists)
3. [Core principles](#3-core-principles)
4. [What the harness can and cannot claim](#4-what-the-harness-can-and-cannot-claim)
5. [Source input model](#5-source-input-model)
6. [Source snapshot and workspace bootstrap](#6-source-snapshot-and-workspace-bootstrap)
7. [Architecture overview](#7-architecture-overview)
8. [Architecture decision records](#8-architecture-decision-records)
9. [Three-plane architecture](#9-three-plane-architecture)
10. [Environment provider contract](#10-environment-provider-contract)
11. [Complete end-to-end flow](#11-complete-end-to-end-flow)
12. [Agent catalog](#12-agent-catalog)
13. [Agent 01 — Inventory](#13-agent-01--inventory)
14. [Agent 02 — Build Resolver](#14-agent-02--build-resolver)
15. [Agent 03 — Application Graph](#15-agent-03--application-graph)
16. [Agent 04 — Baseline](#16-agent-04--baseline)
17. [Agent 05 — Compatibility Registry](#17-agent-05--compatibility-registry)
18. [Internal and company component onboarding](#18-internal-and-company-component-onboarding)
19. [Agent 06 — Target Resolver](#19-agent-06--target-resolver)
20. [Agent 07 — Documentation Registry](#20-agent-07--documentation-registry)
21. [Agent 08 — Migration Knowledge](#21-agent-08--migration-knowledge)
22. [Agent 09 — Impact Analyzer](#22-agent-09--impact-analyzer)
23. [Agent 10 — Characterization](#23-agent-10--characterization)
24. [Agent 11 — Migration Planner](#24-agent-11--migration-planner)
25. [Agent 12 — Transformation](#25-agent-12--transformation)
26. [FileMutationGateway](#26-filemutationgateway)
27. [Change Ledger](#27-change-ledger)
28. [Agent 13 — Build and Repair](#28-agent-13--build-and-repair)
29. [Agent 14 — Graph Rebuild and Graph Diff](#29-agent-14--graph-rebuild-and-graph-diff)
30. [Agent 15 — Test Validation](#30-agent-15--test-validation)
31. [Agent 16 — Runtime Validation](#31-agent-16--runtime-validation)
32. [Runtime graph enrichment](#32-runtime-graph-enrichment)
33. [Agent 17 — Differential Validation](#33-agent-17--differential-validation)
34. [Agent 18 — Approval](#34-agent-18--approval)
35. [Agent 19 — Evidence and Report](#35-agent-19--evidence-and-report)
36. [Agent 20 — Provenance Graph and Q&A](#36-agent-20--provenance-graph-and-qa)
37. [Application graph model](#37-application-graph-model)
38. [File identity and lineage](#38-file-identity-and-lineage)
39. [Migration edge model](#39-migration-edge-model)
40. [Composite transformation and checkpoint reconciliation](#40-composite-transformation-and-checkpoint-reconciliation)
41. [Adaptive validation](#41-adaptive-validation)
42. [Evidence levels](#42-evidence-levels)
43. [OSS tooling](#43-oss-tooling)
44. [GenAI role and boundaries](#44-genai-role-and-boundaries)
45. [Security and threat model](#45-security-and-threat-model)
46. [Sensitive data handling](#46-sensitive-data-handling)
47. [Retention](#47-retention)
48. [Observability](#48-observability)
49. [Repository structure](#49-repository-structure)
50. [State machine](#50-state-machine)
51. [Artifact schemas](#51-artifact-schemas)
52. [CLI usage](#52-cli-usage)
53. [Example end-to-end run](#53-example-end-to-end-run)
54. [Failure behavior](#54-failure-behavior)
55. [Testing strategy](#55-testing-strategy)
56. [Fixture corpus](#56-fixture-corpus)
57. [Installation](#57-installation)
58. [Configuration](#58-configuration)
59. [Development guide](#59-development-guide)
60. [Troubleshooting](#60-troubleshooting)
61. [Limitations and blind spots](#61-limitations-and-blind-spots)
62. [Future work](#62-future-work)
63. [License](#63-license)

---

## 1. Project overview

Bootshift is a reusable harness that takes an existing Spring Boot repository and migrates it to a
supported target state, producing evidence for every step.

It is a **pipeline of twenty deterministic stages**, not twenty autonomous agents. The word *agent*
in this project means a controlled stage with declared inputs, outputs, preconditions,
postconditions and authority boundaries. Most stages contain no AI at all; the whole pipeline runs
correctly with `AI_ENABLED=false`, which is the default.

The harness answers, with evidence:

<table>
<tr><td width="50%" valign="top">

**Understand**
1. What application did we receive?
2. What files, modules, dependencies, beans, endpoints, repositories, entities and tests exist?
3. How are they connected?
4. What depends on a given file, symbol, bean, endpoint or property?
5. What is the blast radius of a change?
6. What is the baseline behaviour before migration?

**Decide**
7. What target version is safe and supportable?
8. What migration path should be used?
9. Which official documents and artifact-level facts apply?
10. Which files and symbols does each fact affect?
11. Which behaviours are already protected by tests?
12. Which need characterizing first?
13. What exact changes, in what order, by which tool?

</td><td width="50%" valign="top">

**Prove**
14. Which files were modified?
15. Why was each one modified?
16. Which tool modified it?
17. What was the before and after hash?
18. Was it renamed, split, merged, created or deleted?
19. Which symbols and graph relationships changed?
20. Did it compile?
21. Did existing tests regress?
22. Did runtime behaviour change?
23. Did API, security, serialization, configuration binding, persistence, messaging or business
    behaviour change?
24. Which changes are expected and backed by verified evidence?
25. Which differences are unexplained, and therefore blocking?
26. What evidence level was achieved per dimension?
27. What blind spots remain?
28. What evidence proves the final result?

</td></tr>
</table>

### Diagram 1 — Product high-level flow

```mermaid
flowchart LR
    S["Source<br/><i>Git or plain directory</i>"] --> U["Understand<br/><i>inventory, build, graph</i>"]
    U --> B["Baseline<br/><i>observe and seal</i>"]
    B --> D["Decide<br/><i>compatibility, target</i>"]
    D --> P["Plan<br/><i>knowledge, impact, edges</i>"]
    P --> M["Migrate<br/><i>gateway-mediated change</i>"]
    M --> V["Validate<br/><i>build, test, runtime, differential</i>"]
    V --> R["Prove<br/><i>claims and evidence levels</i>"]
    R --> Rep["Report<br/><i>sealed manifest and bundle</i>"]

    style B fill:#fff3cd,stroke:#856404
    style M fill:#f8d7da,stroke:#721c24
    style R fill:#d4edda,stroke:#155724
```

The yellow stage is the point of no return for evidence: nothing may mutate before it. The red stage
is the only one that changes source. The green stage is where claims become checkable.

---

## 2. Why Bootshift exists

A Spring Boot major upgrade is not a version bump. It is a simultaneous change to the namespace
(`javax` to `jakarta`), the security configuration model, the Java baseline, the auto-configuration
registration mechanism, hundreds of configuration property names, and the transitive dependency
graph. Any of those can change behaviour without changing a line of application code.

The usual tooling answers a narrower question than the one that matters:

| Tool answers | Question that actually matters |
|---|---|
| "It compiles." | Does it still do the same thing? |
| "Tests pass." | Do the tests cover the behaviour that changed? |
| "It starts." | Are the same beans active, and are the same properties bound? |
| "The recipe ran." | Which of my files did it touch, and why was it allowed to? |
| "Migration complete." | Complete by what standard, with what evidence, and what could it not see? |

Bootshift is built around the position that **the second column is the only column worth
answering**, and that answering it requires the harness to be honest about what it did not observe.

That honesty is mechanical, not aspirational. A claim without a coverage statement is refused before
publication. A behavioural difference with no verified explanation blocks the run. A dimension the
environment could not support is reported as `NOT_COMPARED` rather than quietly passed.

### What this looks like in practice

Running the harness against the reference corpus in this repository produced these findings without
anyone looking for them:

| Finding | How it surfaced |
|---|---|
| Lombok 1.18.24 cannot compile on JDK 21 | Toolchain hazard probe during baseline capture |
| MongoDB Atlas credentials committed in four `application.properties` files | Inventory secret scan, recorded by location and redacted sample only |
| No GA Spring Cloud release train exists for Spring Boot 4.x | Artifact-channel probe of published `spring-cloud-starter-parent` POMs |
| The only Boot line with Spring Cloud support has passed its OSS support date | Lifecycle registry crossed with the Spring Cloud mapping |
| `xml-apis:xml-apis-ext` has a malformed POM that breaks `dependency:list` | Build resolution, which then fell back to `dependency:tree` |

None of those is a bug in the harness. All of them are facts about the repository and its ecosystem
that a version bump would have hit at a much worse moment.
---

## 3. Core principles

Thirty-one rules govern the harness. They are enforced in code, not in documentation. These are the
ones that shape everything else.

| # | Rule | Enforced by |
|---|---|---|
| R1 | Inventory runs first | Stage preconditions and the state machine |
| R2 | Inventory owns FILE_ID creation | Only `InventoryStage` calls `FileRegistry.allocate` on a fresh scan |
| R3–R4 | Identity survives edits and renames, and is neither path nor hash | `FileRegistry` reattachment order; `FileIdentityTest` |
| R5 | Build tools are authoritative | `BuildModel.authoritative` is false unless Maven or Gradle answered |
| R6 | The graph precedes migration decisions | `GRAPH_VERIFIED` is a precondition of baseline capture |
| **R7** | **No mutation before the baseline seal** | `StateMachine` refuses mutating transitions; `FileMutationGateway` refuses to write |
| R8–R9 | Strict OSS; OpenRewrite core is a tool, not the product | `LicensePolicy` gate, forbidden coordinates, ArchUnit rule |
| R10 | Documentation is necessary but insufficient | A fact is VERIFIED only with artifact-channel evidence |
| **R11** | **AI cannot authorize changes** | Every AI proposal passes deterministic verification before the gateway sees it |
| **R12** | **AI must be optional** | Default `AI_ENABLED=false`; the reference run used zero AI |
| **R13** | **No component writes source directly** | `FileMutationGateway` is the sole writer; ArchUnit forbids the bypass |
| R14 | Every attempted change is recorded | Rejected, failed and reverted attempts are ledger entries |
| R15–R16 | Migration runs edge by edge with frozen validation depth | `edge-plan.json` freezes depth; execution reads it |
| R17–R19 | Compilation, tests and startup are each one dimension, not success | Evidence levels are per-dimension |
| **R20–R21** | **Differential is the strongest gate; unexplained blocks** | `UNEXPLAINED > 0 => BLOCKED` |
| R22 | Logs and evidence are separate | `TelemetryPort` versus `EvidenceObjectStore` |
| R23 | The artifact plane is the source of truth | Pointer-after-write; state restored from artifacts |
| R24 | Every stage is independently runnable | One CLI command per stage; orchestrator holds no semantics |
| R25 | Auto-target means highest **safe supported** stable GA | Ranked by support horizon and evidence, not version number |
| R26 | Mandatory checkpoints cannot be silently collapsed | Planner decomposes, escalates, or blocks |
| R27 | Static and runtime graphs are distinct layers | `EdgeType.isRuntimeObserved()`; ADR-003 |
| R28 | Unknown internal components are never assumed compatible | Existence probe, then `UNKNOWN`, then policy action |
| R29 | Coverage regression is a gated signal | Default 5 percentage-point block, configurable |
| **R30** | **The sealed baseline is immutable** | Re-sealing with a different hash throws; a new baseline needs a new run |
| R31 | Transformation capability is discovered dynamically | `transformation-capability-registry.json` is probed per run |

### The three that do the most work

> **R7 — nothing changes before the baseline is sealed.**
> Without a sealed baseline there is nothing to compare against, so every later claim would be
> unfalsifiable. The gateway physically refuses to write, and the state machine refuses to enter a
> mutating state.

> **R13 — one writer.**
> Transformation and repair produce *proposals*. Only `FileMutationGateway` writes. That single
> chokepoint is what makes it possible to say, for every byte that changed, which fact authorized it
> and which tool produced it.

> **R21 — unexplained differences block.**
> Not "warn". Not "log". A behavioural difference with no verified migration fact and no signed
> approval stops the run with exit code 3.

---

## 4. What the harness can and cannot claim

This section is deliberately placed before the architecture, because it is the most important thing
to understand about the tool.

<table>
<tr><th width="50%">It can claim</th><th width="50%">It never claims</th></tr>
<tr><td valign="top">

- These files exist, and this is their persistent identity
- This is the resolved dependency graph, per the build tool itself
- These are the beans, endpoints, repositories and properties, and how they connect
- This is what the original application did, sealed and hashed
- These migration facts are verified against published artifacts
- These repository locations are affected by those facts, and here is the graph path proving it
- These changes were applied, by this tool, authorized by that fact
- These dimensions were compared between old and new, for these scenarios
- This is the evidence level reached **per dimension**, with a coverage statement
- **This is what the run could not see**

</td><td valign="top">

- That all business behaviour is equivalent
- That untested code paths are safe
- That reflection and dynamic configuration were fully analysed
- That a dimension it could not observe is unaffected
- That a green test suite means correctness
- That a successful startup means migration success
- That an AI proposal is correct because a model produced it
- That documentation alone justifies a code change
- That a `DELEGATED` environment guarantees the same equivalence a `MANAGED` one does

</td></tr>
</table>

### The coverage statement rule

A dimension assertion without a coverage statement is not a legal assertion in this harness, and
`Claim.isPublishable()` refuses it before the report is written.

```text
ILLEGAL:  Security = E4

LEGAL:    Security = E4
          Coverage: 41/44 protected endpoints observed
                    3 unobservable
                    GAP-021
```

---

## 5. Source input model

Two intake shapes are supported, and neither requires write access to the input.

```mermaid
flowchart TD
    IN{"Input path"}
    IN -->|"contains .git"| G["Git-backed intake"]
    IN -->|"plain directory"| P["Plain-directory intake"]

    G --> GP["Capture remote, branch,<br/>commit SHA, tree SHA"]
    P --> PP["Compute deterministic<br/>content-manifest hash"]

    GP --> SNAP["Immutable original snapshot"]
    PP --> SNAP
    SNAP --> RO["original/ marked read-only<br/>held for the whole run"]
    SNAP --> MIG["migration/ mutable worktree"]
    MIG --> CKPT["internal checkpoint Git repository<br/><i>inside the external workspace</i>"]

    style RO fill:#e7f3ff,stroke:#0366d6
    style CKPT fill:#fff3cd,stroke:#856404
```

**The user input path is never written to.** The internal checkpoint history lives in the external
run workspace, so a plain directory such as `./src` gets full migration history without ever
acquiring a `.git` folder.

For the reference corpus, provenance is recorded as:

```json
{
  "kind": "PLAIN_DIRECTORY",
  "contentManifestHash": "743b1d181dcb80e00ef3f89d8e7a7f9037147c952595a17780b4b6229b619695",
  "capturedAt": "2026-09-10T05:39:41Z"
}
```

---

## 6. Source snapshot and workspace bootstrap

Bootstrap is **infrastructure preflight, not an agent**. It may create workspaces and capture
provenance. It may not make a single migration decision.

### Diagram 17 — Source snapshot and workspace lifecycle

```mermaid
flowchart TD
    subgraph USER["User input (read-only, never written)"]
        SRC["./src"]
    end

    subgraph WS["External workspace root — BOOTSHIFT_WORKSPACE_ROOT/&lt;run_id&gt;/"]
        ORIG["original/<br/><i>immutable, read-only,<br/>held for the entire run</i>"]
        MIGR["migration/<br/><i>the mutable worktree</i>"]
        ROLD["runtime-old/<br/><i>writable OLD build and run</i>"]
        RNEW["runtime-new/<br/><i>NEW side runtime</i>"]
        CG["internal-checkpoint-git/"]
        EV["evidence/<br/><i>content-addressed</i>"]
        ST["state/"]
    end

    subgraph OUT["Artifact plane — ./output/"]
        STAGES["&lt;stage&gt;/&lt;timestamp&gt;/<br/>&lt;stage&gt;/latest.json"]
    end

    SRC -->|snapshot| ORIG
    SRC -->|snapshot| MIGR
    ORIG -->|seeded copy| ROLD
    MIGR --> CG
    MIGR -->|packaged| RNEW
    ORIG -.->|differential OLD side| ROLD

    style ORIG fill:#e7f3ff,stroke:#0366d6
    style MIGR fill:#f8d7da,stroke:#721c24
```

Two design points that came out of running this for real:

1. **The baseline builds in `runtime-old/`, not in `original/`.** Building inside the pristine
   snapshot would pollute it with `target/` output, and because its files are read-only, Maven would
   propagate that attribute into the copied resources and fail on the second build. `original/` stays
   untouched; `runtime-old/` is the writable copy that gets built, tested and run.

2. **Wrapper jars are not excluded from snapshots.** The Maven wrapper jar is part of the repository
   and the harness needs it to reproduce the build. Excluding it made the wrapper unusable and
   silently downgraded the build model to non-authoritative.

By default `BOOTSHIFT_WORKSPACE_ROOT` points at a temporary directory outside the harness repository, so
customer worktrees are never committed by accident.

---

## 7. Architecture overview

Ports and adapters, enforced by ArchUnit rather than convention.

```mermaid
flowchart TD
    subgraph APPS["apps/"]
        CLI["migration-cli<br/><i>picocli, no migration semantics</i>"]
    end
    subgraph STAGES["stages/"]
        ORCH["PipelineOrchestrator<br/><i>sequencing only</i>"]
        AG["Agents 01-20"]
    end
    subgraph PORTS["ports/"]
        P["16 port interfaces"]
    end
    subgraph ADAPTERS["adapters/"]
        A["Git, Maven, Gradle, JavaParser,<br/>JDK tools, HTTP, filesystem stores,<br/>transformers, environment, AI"]
    end
    subgraph CORE["core/"]
        C["domain, identity, graph, ledger,<br/>policy, state, evidence, provenance, security"]
    end

    CLI --> ORCH
    ORCH --> AG
    AG --> P
    AG --> C
    A -.implements.-> P
    P --> C
    A --> C

    style CORE fill:#d4edda,stroke:#155724
    style ADAPTERS fill:#fff3cd,stroke:#856404
```

| Rule | Test |
|---|---|
| `core` depends on nothing above it | `coreIsIndependent` |
| `core` imports no concrete tooling (JGit, JavaParser, Maven, picocli) | `coreImportsNoTooling` |
| `ports` never reference adapters or stages | `portsAreInterfacesOverCore` |
| `adapters` never reference stages or the CLI | `adaptersDoNotDependOnStages` |
| The CLI holds no migration semantics | `cliDelegates` |
| Only the gateway writes application source | `onlyGatewayWritesSource` |
| No source-available Spring recipe estate on the classpath | `noForbiddenRecipeEstate` |
| Only the composition root builds the state adapter | `stagesUseContextForSharedPorts` |
| The harness never depends on the application under analysis | `noApplicationDependency` |

The last rule matters more than it looks: the harness core must not couple to the Spring Boot version
being migrated, or it could not migrate across the boundary it lives on.

---

## 8. Architecture decision records

Seven decisions carry consequences worth writing down. Full text in [`docs/adr/`](docs/adr/).

| ADR | Decision | The cost we accepted |
|---|---|---|
| [**001**](docs/adr/ADR-001-persistent-file-identity.md) | File identity is **allocated**, not derived from path or content | Identity recovery depends on File Registry integrity. Mitigated by sealing, pointer-after-write, per-stage persistence, and content-addressed storage. |
| [**002**](docs/adr/ADR-002-artifact-plane-as-source-of-truth.md) | Artifacts are truth; the state machine only says where the run is | A stage cannot cheaply signal "in progress". An invisible partial result is safer than a visible one. |
| [**003**](docs/adr/ADR-003-static-vs-runtime-graph.md) | Static and runtime graphs are separate layers, never merged | Consumers must say which layer they mean. That is a feature. |
| [**004**](docs/adr/ADR-004-strict-oss-transformation-boundary.md) | Automate where verification is cheap; measure the rest as residual | Complex framework API migrations surface as diagnosed compile failures rather than automated fixes. |
| [**005**](docs/adr/ADR-005-full-graph-rebuild-v1.md) | v1 always rebuilds the graph fully | A full parse per edge. In exchange, the scope gate is trustworthy. |
| [**006**](docs/adr/ADR-006-old-vs-new-differential-contract.md) | Environment equivalence is a contract; normalization is hashed; unexplained blocks | The harness blocks on differences that turn out benign. Intended asymmetry. |
| [**007**](docs/adr/ADR-007-sequential-recipe-application.md) | Recipes apply one batch at a time, and every proposal carries its base hash | One commit per recipe instead of one per edge, and a file touched by three recipes is written three times. In exchange the ledger and the tree cannot disagree. |

---

## 9. Three-plane architecture

### Diagram 3 — Control, execution and evidence planes

```mermaid
flowchart TB
    subgraph CONTROL["CONTROL PLANE — decides, never executes repository code"]
        ORCH["Thin orchestrator"]
        SM["State machine"]
        POL["Policy engine"]
        LIC["OSS license gate"]
        IDR["Identity registry coordination"]
        APR["Approval policy"]
    end

    subgraph EXEC["EXECUTION PLANE — runs untrusted repository-controlled code, isolated"]
        MVN["Maven / Gradle"]
        AP["Annotation processors"]
        TST["Test suites"]
        RUN["Application startup"]
        ANA["Java static analysis"]
        TRF["Transformation tools"]
        AI["Local OSS inference (optional)"]
    end

    subgraph EVID["ARTIFACT / EVIDENCE PLANE — canonical machine-readable truth"]
        ART["Stage artifacts + latest.json"]
        REG["File and symbol registries"]
        GRAPH["Application graphs"]
        LED["Change ledger (hash chain)"]
        OBS["Observations"]
        MAN["Evidence manifest"]
    end

    CONTROL -->|"allowlisted commands,<br/>timeouts, resource limits"| EXEC
    EXEC -->|"results and raw captures"| EVID
    EVID -->|"state reconstruction (R23)"| CONTROL

    style CONTROL fill:#e7f3ff,stroke:#0366d6
    style EXEC fill:#f8d7da,stroke:#721c24
    style EVID fill:#d4edda,stroke:#155724
```

The control plane **never executes arbitrary repository code in-process**. Every Maven invocation,
test run and application start happens through `ProcessRunner`, which enforces a command allowlist, a
timeout, an output cap, an explicit working directory, and a deterministic locale and timezone. It
never goes through a shell, so argument injection cannot escape the argument vector.

---

## 10. Environment provider contract

### Diagram 19 — MANAGED versus DELEGATED

```mermaid
flowchart TD
    REQ["Environment requirement"] --> MODE{"Provider mode"}

    MODE -->|MANAGED| M["Harness owns creation"]
    MODE -->|DELEGATED| D["CI or infrastructure supplies it"]

    M --> MC["Controls locale, timezone, clock,<br/>encoding, egress policy"]
    M --> MP["Probes for a rootless OCI runtime"]
    MP -->|absent| MG["Records NO_OCI_RUNTIME as a gap;<br/>infrastructure dimensions are unobservable"]

    D --> DD["Reads declared attributes"]
    DD --> DA{"Attestation supplied<br/>for MUST_MATCH?"}
    DA -->|no| DG["UNATTESTED_MUST_MATCH gap:<br/>a delegated environment cannot<br/>self-certify equivalence"]
    DA -->|yes| DOK["Equivalence recorded as enforceable"]

    MC --> EV["Every runtime and differential result records:<br/>mode, implementation, version,<br/>fingerprint, checks, gaps"]
    MG --> EV
    DG --> EV
    DOK --> EV

    style MG fill:#fff3cd,stroke:#856404
    style DG fill:#fff3cd,stroke:#856404
```

Attributes are classified before anything is measured:

| Classification | Examples | Effect on comparison |
|---|---|---|
| `MUST_MATCH` | locale, timezone, clock strategy, file encoding, seed data, broker version, egress policy | A mismatch makes affected dimensions `NOT_COMPARED` |
| `EXPECTED_TO_DIFFER` | JDK, Spring Boot, Spring Framework, Hibernate, Jackson, servlet container | Difference is expected and does not invalidate |
| `UNCONSTRAINED` | OS name, CPU count | Not part of the contract |

A `DELEGATED` environment does not earn the same evidence strength as a `MANAGED` one for the same
observations, unless equivalent attested evidence is supplied. That asymmetry is recorded per result,
not asserted globally.
---

## 11. Complete end-to-end flow

### Diagram 2 — Full twenty-agent flow with the migration edge loop

```mermaid
flowchart TD
    SRC["Input repository"] --> BOOT["Bootstrap<br/><i>preflight, not an agent</i>"]
    BOOT --> A01["01 Inventory"]
    A01 --> A02["02 Build Resolver"]
    A02 --> A03["03 Application Graph"]
    A03 --> A04["04 Baseline Capture + Seal"]

    A04 --> A05["05 Compatibility &amp; Lifecycle"]
    A05 --> A06["06 Target Resolver"]
    A06 --> A07["07 Documentation Registry"]
    A07 --> A08["08 Migration Knowledge"]
    A08 --> A09["09 Impact Analyzer"]
    A09 --> A10["10 Characterization"]
    A10 --> A11["11 Migration Planner"]

    A11 --> A12["12 Transformation"]
    A12 --> A13["13 Build + Repair"]
    A13 --> A14["14 Graph Rebuild + Diff"]
    A14 --> A15["15 Test Validation"]
    A15 --> A16["16 Runtime Validation"]
    A16 --> A17["17 Differential Validation"]

    A17 --> EDGE{"More migration edges?"}
    EDGE -->|Yes| A12
    EDGE -->|No| A18["18 Approval"]
    A18 --> A19["19 Evidence + Report"]
    A19 --> A20["20 Provenance Graph + Q&amp;A"]
    A20 --> DONE["MIGRATION_COMPLETE<br/>BLOCKED<br/>NEEDS_HUMAN"]

    style A04 fill:#fff3cd,stroke:#856404
    style A12 fill:#f8d7da,stroke:#721c24
    style A13 fill:#f8d7da,stroke:#721c24
    style A19 fill:#d4edda,stroke:#155724
```

### Complete execution flowchart with phase subgraphs

```mermaid
flowchart TD
    subgraph INIT["Phase 0 — Initialization"]
        I1["Create run id"] --> I2["Validate input path"]
        I2 --> I3["OSS policy gate<br/>on harness components"]
        I3 --> I4{"Gate passes?"}
        I4 -->|no| IBLOCK["exit 3 POLICY_BLOCK"]
        I4 -->|yes| I5["Create external workspaces"]
        I5 --> I6["Immutable original snapshot<br/>+ mutable migration worktree"]
        I6 --> I7["Init internal checkpoint git"]
    end

    subgraph UNDERSTAND["Phase 1 — Understand (read only)"]
        U1["01 Inventory<br/>allocate FILE_IDs"] --> U2["Seal file registry"]
        U2 --> U3["02 Build Resolver<br/>ask Maven or Gradle"]
        U3 --> U4["03 Build application graph"]
        U4 --> U5["Verify graph<br/>against inventory and build model"]
        U5 --> U6{"Integrity floors met?"}
        U6 -->|no| UBLOCK["exit 3 POLICY_BLOCK"]
    end

    subgraph BASE["Phase 2 — Baseline"]
        B1["Select a compatible toolchain"] --> B2["Build, test, measure coverage"]
        B2 --> B3["Start each module, observe runtime"]
        B3 --> B4["Enrich baseline runtime graph"]
        B4 --> B5["Establish environment<br/>equivalence contract"]
        B5 --> B6["SEAL baseline manifest"]
    end

    subgraph DECIDE["Phase 3 — Decide"]
        D1["05 Compatibility and lifecycle<br/>from artifact metadata"] --> D2["Probe internal components"]
        D2 --> D3["06 Rank candidate targets"]
        D3 --> D4{"Any viable<br/>landing target?"}
        D4 -->|no| DBLOCK["exit 3 with the trade-off explained"]
        D4 -->|yes| D5["FREEZE target and path"]
    end

    subgraph PLAN["Phase 4 — Plan"]
        P1["07 Pin authoritative documents"] --> P2["08 Documentation channel<br/>+ artifact channel"]
        P2 --> P3["Generate property rules<br/>from configuration metadata"]
        P3 --> P4["09 Locate impacts,<br/>measure analyzer accuracy"]
        P4 --> P5["10 Characterize behaviour"]
        P5 --> P6["11 Discover capabilities,<br/>compute residual"]
        P6 --> P7["Reconcile checkpoints"]
        P7 --> P8["FREEZE validation depth per edge"]
    end

    subgraph LOOP["Phase 5 — Migration edge loop"]
        L1["Checkpoint: edge start"] --> L2["12 Propose changes"]
        L2 --> L3["FileMutationGateway<br/><i>the only writer</i>"]
        L3 --> L4["13 Compile, cluster root causes, repair"]
        L4 --> L5["14 Full graph rebuild + diff"]
        L5 --> L6{"Scope authorized?"}
        L6 -->|no| LBLOCK["exit 3 SCOPE VIOLATION"]
        L6 -->|yes| L7["Read frozen validation depth"]
        L7 --> L8{"Tests required?"}
        L8 -->|yes| L9["15 Test + coverage gate"]
        L8 -->|no| L10
        L9 --> L10{"Runtime required?"}
        L10 -->|yes| L11["16 Runtime + binding provenance"]
        L10 -->|no| L12
        L11 --> L12{"Differential required?"}
        L12 -->|yes| L13["17 OLD vs NEW"]
        L12 -->|no| L14
        L13 --> L14["Checkpoint: edge complete"]
    end

    subgraph PROVE["Phase 6 — Prove"]
        V1["18 Raise approval gates"] --> V2{"Outstanding?"}
        V2 -->|yes| VHUMAN["exit 4 NEEDS_HUMAN"]
        V2 -->|no| V3["19 Build claims with coverage"]
        V3 --> V4["Verify ledger and index evidence"]
        V4 --> V5["SEAL evidence manifest"]
        V5 --> V6["20 Provenance graph + Q&amp;A"]
        V6 --> V7["Export validated bundle"]
    end

    INIT --> UNDERSTAND --> BASE --> DECIDE --> PLAN --> LOOP
    LOOP -->|more edges| LOOP
    LOOP -->|done| PROVE

    style BASE fill:#fff3cd,stroke:#856404
    style LOOP fill:#f8d7da,stroke:#721c24
    style PROVE fill:#d4edda,stroke:#155724
```

---

## 12. Agent catalog

| # | Agent | Mutating | AI | Purpose | Postcondition |
|---|---|:--:|:--:|---|---|
| 01 | Inventory | no | no | Discover the repository, allocate permanent identities | `FILE_REGISTRY_SEALED` |
| 02 | Build Resolver | no | no | Obtain the authoritative effective build model | `BUILD_RESOLVED` |
| 03 | Application Graph | no | no | Build and verify the typed multi-view graph | `GRAPH_VERIFIED` |
| 04 | Baseline | no | no | Observe and cryptographically seal original behaviour | `BASELINE_SEALED` |
| 05 | Compatibility | no | no | Tier-1 version-space knowledge with evidence quality | `COMPATIBILITY_REGISTRY_READY` |
| 06 | Target Resolver | no | no | Choose the landing target and transit checkpoints | `TARGET_FROZEN` |
| 07 | Documentation | no | no | Fetch and content-address authoritative documents | `DOCUMENTATION_RETRIEVED` |
| 08 | Knowledge | no | opt | Documentation plus artifact reality into verified facts | `KNOWLEDGE_VERIFIED` |
| 09 | Impact | no | no | Which repository locations the facts affect | `IMPACT_ANALYZED` |
| 10 | Characterization | no | opt | Behavioural contracts before change | `CHARACTERIZATION_COMPLETE` |
| 11 | Planner | no | no | Freeze how the path will be executed | `PLAN_FROZEN` |
| **12** | **Transformation** | **yes** | no | Apply authorized deterministic transformations | `EDGE_TRANSFORMED` |
| **13** | **Build + Repair** | **yes** | **opt** | Compile and repair bounded residual failures | `EDGE_COMPILED` |
| 14 | Graph Diff | no | no | Rebuild the graph and assert scope | `EDGE_SCOPE_VERIFIED` |
| 15 | Test Validation | no | no | Run tests, classify against two baselines | `EDGE_TESTED` |
| 16 | Runtime Validation | no | no | Start the app, observe, enrich the runtime graph | `EDGE_RUNTIME_GRAPH_ENRICHED` |
| 17 | Differential | no | opt | Compare OLD and NEW for required dimensions | `EDGE_DIFFERENTIAL_VALIDATED` |
| 18 | Approval | no | no | Raise and record human decisions | `FINAL_APPROVAL` |
| 19 | Evidence | no | no | Seal the manifest, produce the report | `EVIDENCE_SEALED` |
| 20 | Provenance | no | no | Queryable provenance and the question catalog | `MIGRATION_COMPLETE` |

Exactly two stages may mutate application source, and both do so only through the
`FileMutationGateway`. An ArchUnit rule fails the build if either touches the filesystem write API
directly.

---

## 13. Agent 01 — Inventory

```text
READ ONLY | DETERMINISTIC | ZERO LLM | FIRST STAGE
```

### Purpose

Discover exactly what repository arrived, and allocate the permanent file identities everything
downstream refers to.

### Why the stage exists

Two responsibilities no later stage may take over. First, classification: nothing else scans the raw
tree. Second, and more importantly, **identity allocation (R2)**. If identity were assigned later,
after a transformation had already moved a file, the harness could never honestly say that the file
before and after are the same file.

### Inputs and preconditions

| Input | Source |
|---|---|
| Repository root | Bootstrap snapshot (`original/`) |
| Run id | Run context |
| Exclude policy | `GitScmAdapter.DEFAULT_EXCLUDES` |
| Existing File Registry | Optional; present on a re-scan |

**Precondition:** `OSS_POLICY_VERIFIED`.

### Internal flow

```mermaid
flowchart TD
    START["Enumerate the original snapshot"] --> EX["Apply exclude policy<br/><i>target, build, .git, node_modules</i>"]
    EX --> SL{"Symbolic link?"}
    SL -->|yes| SKIP["Skip: symlinks are an<br/>escape hatch out of the workspace"]
    SL -->|no| CL["Classify role by extension and location"]
    CL --> HASH["SHA-256 the content"]
    HASH --> SCAN["Declare all observed paths<br/>before reattaching any of them"]
    SCAN --> RE{"Existing registry?"}
    RE -->|no| ALLOC["Allocate FILE-&lt;ULID&gt;"]
    RE -->|yes| ORDER["Reattachment order:<br/>1 exact path<br/>2 provider or Git rename<br/>3 exact content hash<br/>4 similarity above threshold<br/>5 allocate new"]
    ALLOC --> SIG
    ORDER --> SIG["Record migration SIGNALS<br/><i>never conclusions</i>"]
    SIG --> SEC["Scan for embedded credentials"]
    SEC --> SEAL["Seal the registry:<br/>hash over identity-to-baseline bindings"]
    SEAL --> PUB["Validate against schema, then publish"]

    style SEAL fill:#fff3cd,stroke:#856404
```

### Signals, not conclusions

Twenty-seven signal definitions are recorded with `"classification": "SIGNAL_NOT_CONCLUSION"`. A
signal says *this file mentions `javax.persistence`*. It never says *this file must be migrated* —
that decision belongs to Agent 09, after verified knowledge exists.

Reference corpus result:

| Signal | Count | Signal | Count |
|---|--:|---|--:|
| `LOMBOK` | 24 | `MONGODB` | 9 |
| `SPRING_CLOUD` | 13 | `WAR_PACKAGING` | 6 |
| `WEBFLUX` | 11 | `CONFIG_SERVER` | 6 |
| `EUREKA` | 11 | `INTERNAL_STARTER` | 6 |
| `BOOTSTRAP_PROPERTIES` | 4 | `CUCUMBER` | 4 |
| `JUNIT4` | 3 | `JAVAX_NAMESPACE` | 2 |
| `MOCK_BEAN` | 2 | `SCHEDULING` | 2 |
| `RESILIENCE4J` | 1 | | |

### Outputs

```text
output/01-inventory/<timestamp>/
├── inventory-artifact.json
├── file-registry.json
├── inventory-signals.json
├── inventory-issues.json
└── manifest.json
```

### Invariants

- `FILE_ID != PATH` and `FILE_ID != CONTENT_HASH`
- Reattachment order is fixed and the deciding rule is recorded per file
- Secret values are never copied into an artifact; only presence, location and a redacted sample

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| Input path is not a directory | Structured refusal | 2 |
| A file cannot be read | Recorded as `UNREADABLE`, gap `GAP-INV-001` raised | 0 |
| An artifact fails schema validation | `latest.json` is not advanced | 1 |

### Example artifact

```json
{
  "pipeline_stage": "01-inventory",
  "run_id": "RUN-01M24X67XDPR9YQXDK3QRS7FNB",
  "file_count": 99,
  "role_counts": {
    "JAVA_MAIN": 51, "JAVA_TEST": 12, "MAVEN_BUILD": 6,
    "CONFIG_PROPERTIES": 10, "SETTINGS": 12, "RESOURCE": 8
  },
  "stats": {
    "file_registry_seal": "743b1d181dcb80e0...",
    "signals_recorded": 104
  }
}
```

An inventory issue, showing the credential-handling policy in action:

```json
{
  "kind": "CREDENTIAL_IN_SOURCE",
  "path": "employee-service/src/main/resources/application.properties",
  "line": 3,
  "redacted_sample": "#spring.data.mongodb.uri=mongodb+srv://REDACTED:REDACTED@cluster0...",
  "evidence_policy": "NEVER_STORE_PLAINTEXT"
}
```

### Downstream consumers

Agents 02, 03, 04, 09, 12, 13, 14, 19, 20 — every one of them addresses files by `FILE_ID`.

---

## 14. Agent 02 — Build Resolver

### Purpose

Ask the build tool what the build actually is. Never let XML parsing be the authority (R5).

### Why the stage exists

A `pom.xml` describes intent. The effective model — after parent resolution, BOM imports, property
interpolation and conflict mediation — is something only Maven knows. On the reference corpus the
declared dependencies number 49; the resolved graph has **962 records across 6 modules** and
**9,186 managed versions**. Planning against the first number would miss most of the migration.

### Inputs and preconditions

Inventory artifact and file registry. **Precondition:** `FILE_REGISTRY_SEALED`.

### Internal flow

```mermaid
flowchart TD
    D["Discover reactor roots"] --> C["Build executable candidate chain:<br/>1 repository wrapper<br/>2 mvn on PATH<br/>3 MAVEN_HOME / M2_HOME"]
    C --> PROBE["Probe each candidate with -version<br/><i>a wrapper is probed from its own module,<br/>because it resolves its base directory from cwd</i>"]
    PROBE --> OK{"Any candidate<br/>responded?"}
    OK -->|no| DEG["authoritative = false<br/>+ BLOCKING issue<br/>+ descriptor hints only"]
    OK -->|yes| EP["help:effective-pom<br/>-> managed versions"]
    EP --> DL["dependency:list"]
    DL --> DLOK{"Resolved<br/>artifacts?"}
    DLOK -->|no| DT["Fall back to dependency:tree<br/><i>tolerates a malformed upstream POM</i>"]
    DLOK -->|yes| PL
    DT --> DTOK{"Resolved?"}
    DTOK -->|no| BLOCK["BLOCKING issue;<br/>never substitute a version"]
    DTOK -->|yes| PL["resolve-plugins"]
    PL --> CP["build-classpath<br/><i>feeds the type solver in Agent 03</i>"]
    CP --> FW["Detect framework anchors:<br/>Boot, Cloud, Framework, Java, Jackson"]
    FW --> PUB["Publish"]

    style DEG fill:#f8d7da,stroke:#721c24
    style DT fill:#fff3cd,stroke:#856404
```

The `dependency:tree` fallback is not a nicety. On this corpus `dependency:list` fails outright
because `xml-apis:xml-apis-ext:1.3.04`, reached transitively through Apache POI, has a POM that
declares `distributionManagement.status` and cannot be model-built. `dependency:tree` walks the
resolved graph instead and tolerates it.

### Outputs

```text
output/02-build/<timestamp>/
├── build-model.json          modules, toolchains, frameworks, authoritative flag
├── dependency-model.json     962 resolved records with scope and provenance
├── bom-model.json            9,186 managed versions, imported BOMs
├── plugin-model.json
├── repository-model.json
└── resolution-issues.json
```

### Invariants

- `authoritative=false` whenever the tool did not answer, with a `degraded_reason`
- An unresolved required artifact is a blocking issue or an explicit blind spot; **never substituted**
- The classpath is captured so type attribution downstream is real rather than aspirational

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| No Maven or Gradle build found | Structured refusal | 2 |
| Tool unavailable | Publishes non-authoritative model + blind spot `BS-BUILD-001` | 0 |
| Some coordinates unresolved | Gap `GAP-BUILD-001` | 0 |

### Example artifact

```json
{
  "kind": "MAVEN",
  "tool_version": "Apache Maven 3.8.7",
  "wrapper_used": true,
  "authoritative": true,
  "frameworks": {
    "spring-boot": "2.7.12",
    "spring-cloud": "2021.0.7",
    "spring-framework": "5.3.27",
    "jackson": "2.13.5",
    "java": "17"
  }
}
```

### Downstream consumers

Agents 03, 04, 05, 08, 13, 14, 15, 16.
---

## 15. Agent 03 — Application Graph

### Purpose

Build a type-aware, multi-view representation of the application, then verify it independently.

### Why the stage exists

Every later question — what depends on this, what is the blast radius, which validation dimensions
does this impact require — is a graph traversal. Without the graph, impact analysis degenerates into
text search, and a text match cannot explain *why* something is affected.

### Inputs and preconditions

File registry, build model, dependency model with resolved classpath.
**Precondition:** `BUILD_RESOLVED`.

### Internal flow

```mermaid
flowchart TD
    MOD["Module nodes from the<br/>authoritative build model"] --> LIB["Library nodes<br/>+ DEPENDS_ON_LIBRARY edges"]
    LIB --> FILES["File nodes from the registry"]
    FILES --> PARSE["JavaParser with symbol solver<br/><i>real dependency jars on the classpath</i>"]
    PARSE --> TYPES["Type nodes with Spring stereotype<br/>classification and attribution status"]
    TYPES --> MEM["Member nodes: methods,<br/>constructors, fields"]
    MEM --> AP["Synthesize annotation-processor members<br/><i>Lombok accessors, builder, log</i>"]
    AP --> LINK["Link relationships:<br/>EXTENDS, IMPLEMENTS, IMPORTS, USES_TYPE,<br/>CALLS, INJECTS, CALLS_SERVICE, CALLS_REPOSITORY"]
    LINK --> UNRES["Recover unresolved calls<br/>only when exactly one visible type matches;<br/>confidence 0.5, basis recorded"]
    UNRES --> EP["Endpoint nodes from<br/>request mapping annotations"]
    EP --> CFG["Configuration view:<br/>properties, profiles, placeholders"]
    CFG --> INT["Integration view: Eureka, Config Server,<br/>MongoDB, brokers, external HTTP"]
    INT --> VIEWS["Project ten view artifacts"]
    VIEWS --> VER["Independent verification"]

    style AP fill:#e7f3ff,stroke:#0366d6
    style VER fill:#d4edda,stroke:#155724
```

### Two things that make the graph real rather than decorative

**1. The type solver gets the actual classpath.** Agent 02 captures the resolved compile-plus-test
classpath and Agent 03 hands it to the symbol solver. On the reference corpus this moved type
attribution from **0.19 to 0.69** — the difference between a graph of guesses and a graph of
resolved facts.

**2. Annotation-processor members are modelled explicitly.** Lombok generates accessors, builders and
the `log` field at compile time, so they do not exist in source and a source-level parser cannot
resolve a call to `employee.getName()` or `log.trace(...)`. Leaving them out would delete most
service-to-model relationships from the graph. They are synthesized and marked
`synthetic=true, generated_by=lombok`. Nothing pretends they were parsed. This took `CALLS` edges
from 31 to 81 and `DECLARES` from 239 to 370.

### Graph verification (spec section 14)

Verification never reads the graph's own output to check the graph. Every check crosses to an
independent source.

| # | Check | Cross-checked against | Reference result |
|---|---|---|---|
| 1 | Java file coverage | Inventory registry | 63/63 = 1.0 |
| 2 | Module coverage | Build model | 6/6 |
| 3 | Type coverage | Parser output | 63/63 |
| 4 | Endpoint coverage | Controller nodes | 5 controllers → 10 endpoints |
| 5 | Spring component coverage | Annotation extraction | 25 components |
| 6 | Resolved dependency coverage | Build model | 300/300 distinct libraries |
| 7 | Representative edge spot checks | Source file and line | 12 sampled with evidence |
| 8 | No harness-source contamination | Package prefix | 0 |
| 9 | Attribution ratio | Parser | 0.6858 |
| 10 | Graph query integrity | Live traversal | blast radius and transitive dependencies both non-empty, every result carries a path |

Failing check 1, 2, 4, 6, 8 or 10 blocks the run. Check 9 below the policy floor raises
`GAP-GRAPH-001` and caps impact classification.

### Outputs

Thirteen artifacts: `application-graph.json`, `file-registry.json`, `symbol-registry.json`, ten view
projections (`module-graph`, `file-graph`, `symbol-graph`, `dependency-graph`, `spring-graph`,
`configuration-graph`, `persistence-graph`, `endpoint-graph`, `test-graph`, `integration-graph`),
plus `graph-summary.json`, `graph-issues.json` and the verification report in both JSON and Markdown.

### Reference corpus graph

**839 nodes, 1,852 edges, 239 symbols**, structural hash `4bf6f2986e6d…`

| Node type | n | Node type | n | Edge type | n |
|---|--:|---|--:|---|--:|
| LIBRARY | 300 | METHOD | 84 | DEPENDS_ON_LIBRARY | 962 |
| FILE | 99 | CONTROLLER | 5 | DECLARES | 370 |
| FIELD | 87 | SERVICE | 5 | CONTAINS | 172 |
| PACKAGE | 38 | REPOSITORY | 5 | IMPORTS | 90 |
| CLASS | 12 | MONGODB_DOCUMENT | 4 | CALLS | 81 |
| TEST | 12 | INTERFACE | 4 | USES_TYPE | 80 |
| ENDPOINT | 10 | ENUM | 6 | CONFIGURES | 21 |
| CONFIG_PROPERTY | 10 | MODULE | 6 | COVERED_BY_TEST | 18 |
| CONFIGURATION_CLASS | 8 | SPRING_BEAN | 2 | READS_FROM_CONFIG_SERVER | 10 |

### Invariants

- The portable JSON is the source of truth; a graph database is never required for correctness
- Runtime facts are absent by construction — blind spot `BS-GRAPH-RUNTIME` states this explicitly
- Unresolved relations are labelled, never presented as high confidence

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| Verification floor breached | Publishes artifacts, blocks baseline | 3 |
| Attribution below floor | Gap `GAP-GRAPH-001`, impact capped | 0 |
| Schema validation failure | Pointer not advanced | 1 |

### Downstream consumers

Agents 04, 09, 10, 14, 16, 17, 19, 20 and the `graph` CLI queries.

---

## 16. Agent 04 — Baseline

### Purpose

Observe the original application, then seal what was observed.

### Why the stage exists

This is the stage that makes every later claim falsifiable. After it, R7 permits mutation and R30
forbids ever rewriting what it recorded.

### Internal flow

```mermaid
flowchart TD
    ENV["Establish the environment<br/>equivalence contract<br/><i>before anything is measured</i>"] --> TC["Discover installed JDKs"]
    TC --> SEL{"A JDK matching the<br/>declared language level?"}
    SEL -->|no| TCB["Blind spot BS-TOOLCHAIN-001:<br/>every observation below is unreliable"]
    SEL -->|yes| HAZ["Check known toolchain hazards<br/><i>e.g. Lombok before 1.18.30 on JDK 21</i>"]
    HAZ --> COPY["Seed runtime-old/ from original/<br/><i>never build inside the pristine snapshot</i>"]
    COPY --> BUILD["Compile every module"]
    BUILD --> TEST["Attach JaCoCo as a CLI goal<br/><i>editing the POM would violate R7</i>"]
    TEST --> AGENT{"Did the agent attach?"}
    AGENT -->|no| RETRY["Re-run tests without coverage;<br/>record coverage unavailable with the reason"]
    AGENT -->|yes| COV["Parse Surefire and JaCoCo"]
    RETRY --> COV
    COV --> CFG["Capture configuration with<br/>sensitive values as metadata only"]
    CFG --> PKG["Package, then start each module<br/>with derived isolation settings"]
    PKG --> OBS["Observe context, health, beans, conditions,<br/>mappings, bound properties"]
    OBS --> RG["Enrich the baseline runtime graph"]
    RG --> SEAL["Hash every observation,<br/>the tree, the registry seal and the fingerprint"]
    SEAL --> IMMUT["BASELINE SEALED — immutable for this run"]

    style RETRY fill:#fff3cd,stroke:#856404
    style IMMUT fill:#fff3cd,stroke:#856404
```

### Three details that came from running this against a real corpus

**Toolchain selection is environment provisioning, not mutation.** Spring Boot 2.7.12 pins Lombok
1.18.24, which cannot run on JDK 21 — it fails with `NoSuchFieldError` on
`JCTree$JCImport.qualid`. Compiling the baseline on JDK 21 would produce a failure that says nothing
about the migration. The probe finds a JDK matching the declared level, selects it, and records the
choice in the sealed manifest. When none exists, it says so instead of producing a meaningless result.

**Coverage instrumentation must never destroy the observation.** `employee-service` pins Surefire
2.19.1, which does not late-evaluate the `argLine` property that `jacoco:prepare-agent` sets. The
fork dies with `processing of -javaagent failed` and produces **zero** test reports — so the harness
would report "0 tests" for a module that has tests. The fallback detects the agent failure from the
Surefire dump stream, re-runs without instrumentation, and reports coverage as unavailable **with the
reason**. Twelve tests are observed instead of five.

**Runtime isolation settings are derived, not blanket.** Disabling the Eureka client is right for a
Eureka *client* and fatal for the Eureka *server*, whose own auto-configuration needs those beans.
The first version of this code broke `discovery-service` for a reason that had nothing to do with the
application.

### Baseline dimensions

| Dimension | What is captured | Reference result |
|---|---|---|
| Build | success, diagnostics, duration, toolchain | 6/6 modules compile on JDK 17.0.20.1 |
| Tests | pass, fail, error, skip, per case | 12 tests, 2 pre-existing MongoDB failures |
| Coverage | instruction, branch, line, per module | JaCoCo for 5 modules; 1 unavailable with reason |
| Configuration | effective properties with provenance | 10 files, sensitive values as metadata |
| Runtime | context, beans, conditions, mappings, health | 5/6 modules started |
| Binding | canonical key, source, target, bound, defaulted | 368 bound properties |
| Integrations | Config Server, Eureka, MongoDB, schedulers | recorded per module |

### The seal

```json
{
  "original_tree_hash": "743b1d181dcb80e0...",
  "old_workspace_hash": "743b1d181dcb80e0...",
  "old_workspace_matches_original": true,
  "file_registry_seal": "743b1d181dcb80e0...",
  "sealed_environment_fingerprint": "8f2c…",
  "environment_mode": "MANAGED",
  "observation_hashes": {
    "build": "…", "tests": "…", "coverage": "…", "configuration": "…",
    "runtime": "…", "runtime_graph": "…", "static_graph": "…", "toolchain": "…"
  },
  "normalization_policy_hash": "…",
  "sealed": true,
  "baseline_manifest_hash": "217d0c7f7d8e8a1a…"
}
```

### Invariants

- Re-sealing with a different hash throws. A new baseline requires a new run identity (R30).
- A module that does not start produces a blind spot, never an assumed pass (R19).
- Sensitive values never appear in plaintext in any artifact.

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| Original snapshot missing | Structured refusal | 2 |
| Module fails to build | Recorded as pre-existing baseline debt | 0 |
| Module fails to start | Blind spot `BS-RUNTIME-<MODULE>` | 0 |
| Attempted re-seal with a different hash | Policy block | 3 |

### Downstream consumers

Agents 05, 09, 10, 15, 16, 17, 19 — and the gateway, which refuses to write without the seal.

---

## 17. Agent 05 — Compatibility Registry

### Purpose

Repository-independent Tier-1 version-space knowledge, built **before** target resolution so the two
do not depend on each other circularly.

### Why the stage exists

Target resolution needs to know which lines exist, which are supported, which Java versions they
accept and which Spring Cloud train goes with them. Deriving that from the repository would be
circular. Hardcoding it goes stale.

### Internal flow

```mermaid
flowchart TD
    META["maven-metadata.xml for spring-boot<br/><i>authoritative: which lines exist</i>"] --> LINES["Derive lines and newest stable patch"]
    LINES --> LC{"Curated lifecycle table<br/>still within its as-of window?"}
    LC -->|yes| VER["quality = VERIFIED"]
    LC -->|no| ADV["Consult the community aggregator<br/>quality = ADVISORY"]
    ADV --> NONE{"Still nothing?"}
    NONE -->|yes| UNK["quality = UNKNOWN"]
    VER --> CLOUD
    ADV --> CLOUD
    UNK --> CLOUD["For each Spring Cloud train line,<br/>read spring-cloud-starter-parent POM<br/>and take its Boot parent version"]
    CLOUD --> AVAIL["Probe artifact existence<br/>for each candidate"]
    AVAIL --> INT["Classify internal components<br/>by public-repository existence"]
    INT --> PUB["Publish registry"]

    style VER fill:#d4edda,stroke:#155724
    style ADV fill:#fff3cd,stroke:#856404
    style UNK fill:#f8d7da,stroke:#721c24
```

### Evidence quality is load-bearing

A curated lifecycle table is only trustworthy while someone refreshes it, so the table carries an
`as_of` date and goes **stale** on its own. Quality then decides what the fact is allowed to do:

| Quality | Source | May eliminate a target? |
|---|---|---|
| `VERIFIED` | Curated Tier-1 table, within its as-of window | **Yes** |
| `ADVISORY` | Community aggregator, or a stale curated entry | No — raises an approval gate instead |
| `ESTIMATED` | Derived from release cadence | No |
| `UNKNOWN` | Nothing reachable | No |

This is the difference between "we know this line is end of life" and "we believe it is". Only the
first may reject a target on its own.

### The Spring Cloud mapping is verified from artifacts

The train-to-Boot mapping is not remembered; it is read out of the published
`spring-cloud-starter-parent` POM, which literally declares `spring-boot-starter-parent` as its
parent. One probe per train line covers the whole mapping.

| Boot line | Spring Cloud train | Evidence |
|---|---|---|
| 3.2 | 2023.0.5 | starter-parent POM parent version |
| 3.3 | 2023.0.6 | starter-parent POM parent version |
| 3.4 | 2024.0.3 | starter-parent POM parent version |
| 3.5 | 2025.0.3 | starter-parent POM parent version |
| 4.0 | *none* | no GA train targets this line |
| 4.1 | *none* | no GA train targets this line |

### Internal components are classified by probe, not by name

An earlier version used a group-id allowlist and produced 31 false positives — `joda-time`,
`junit:junit`, `xalan`, the whole Sonatype Aether stack. A naming heuristic cannot distinguish an
organization starter from a public library with an unusual coordinate.

The current test is existence: if the configured public repository resolves the coordinate, it is a
public component. If it does not, it is internal and its compatibility is `UNKNOWN` until a profile
supplies evidence. On the reference corpus this correctly yields **zero** internal components.

### Outputs

`compatibility-registry.json`, `lifecycle-registry.json`, `artifact-availability.json`,
`version-space-evidence.json`, `internal-components.json`.

### Invariants

- Absence of compatibility evidence is `UNKNOWN`, never "compatible" (R28)
- Community sources are advisory only and never become hard assertions
- Every externally retrieved fact is cached with provenance and a content hash

### Downstream consumers

Agent 06 primarily; Agents 08 and 18.

---

## 18. Internal and company component onboarding

A private starter that nobody can prove is compatible is a migration risk that hides until runtime.

```mermaid
flowchart TD
    DEP["Resolved dependency"] --> MOD{"One of the<br/>repository modules?"}
    MOD -->|yes| SKIP["Not a third-party component"]
    MOD -->|no| PROF{"InternalComponentProfile<br/>on disk?"}
    PROF -->|yes| USE["Use the declared compatibility<br/>and its evidence references"]
    PROF -->|no| PROBE["Probe the public artifact repository"]
    PROBE --> EX{"Coordinate resolves?"}
    EX -->|yes| PUBLIC["Public ecosystem component"]
    EX -->|no| ONLINE{"Repository reachable?"}
    ONLINE -->|no| UNDET["UNDETERMINED<br/><i>cannot tell public from internal offline</i>"]
    ONLINE -->|yes| INTERNAL["INTERNAL_COMPONENT<br/>compatibility = UNKNOWN"]
    INTERNAL --> ACT{"Policy action"}
    ACT -->|BLOCK| B["Eliminates target candidates"]
    ACT -->|WARN| W["Caution + approval gate"]

    style INTERNAL fill:#fff3cd,stroke:#856404
    style B fill:#f8d7da,stroke:#721c24
```

An `InternalComponentProfile` lives at
`policies/default/internal-components/<groupId>_<artifactId>.json`:

```json
{
  "group_id": "com.acme",
  "artifact_id": "acme-spring-starter",
  "component_version": "4.2.1",
  "compatibility": "SUPPORTED",
  "java_range": "17-21",
  "spring_boot_range": "3.2.0-3.5.999",
  "spring_framework_range": "6.1.0-6.2.999",
  "transitive_managed_dependencies": ["com.acme:acme-core:4.2.1"],
  "migration_notes": "4.2.x drops the deprecated AcmeAutoConfiguration entry point.",
  "evidence_references": ["https://internal.acme/…", "sha256:…"],
  "owner_contact": "platform-team@acme.example",
  "confidence": "HIGH",
  "status": "VERIFIED"
}
```

Anything other than `SUPPORTED` constrains target resolution. A private starter never silently blocks
graph resolution without being surfaced as a named constraint.
---

## 19. Agent 06 — Target Resolver

### Purpose

Decide **where** to go. Not how to get there.

### Why the stage exists

`--target auto` is where a migration tool is most tempted to be wrong in a way nobody notices.
"Newest" is not "safe". R25 defines auto as the **highest safe supported stable GA** state, ranked by
supportability and compatibility evidence, with version number as a tiebreak only.

### Diagram 9 — Target resolution

```mermaid
flowchart TD
    LC["Lifecycle registry<br/><i>with evidence quality</i>"] --> CAND["Enumerate stable GA candidates"]
    COMPAT["Compatibility registry"] --> CAND
    AVAIL["Artifact availability"] --> CAND
    INT["Internal components"] --> CAND
    JDK["Installed JDKs"] --> CAND

    CAND --> E1{"Stable GA?"}
    E1 -->|no| X1["eliminate"]
    E1 -->|yes| E2{"Newer than<br/>the current state?"}
    E2 -->|no| X2["eliminate"]
    E2 -->|yes| E3{"Artifacts exist?"}
    E3 -->|no| X3["eliminate"]
    E3 -->|yes| E4{"App uses Spring Cloud<br/>and a GA train exists?"}
    E4 -->|no train| X4["eliminate:<br/>required artifacts do not exist"]
    E4 -->|ok| E5{"An installed JDK satisfies<br/>the range and the project level?"}
    E5 -->|no| X5["eliminate"]
    E5 -->|yes| E6{"Lifecycle evidence<br/>quality?"}
    E6 -->|VERIFIED + EOL| X6["eliminate"]
    E6 -->|ADVISORY + EOL| C1["caution -> approval gate"]
    E6 -->|ok| RANK
    C1 --> RANK["Rank by support horizon,<br/>GA status, availability,<br/>evidence quality, train presence"]
    RANK --> PICK{"Any viable?"}
    PICK -->|no| BLOCK["exit 3, naming the closest<br/>candidate and the exact policy flag<br/>that would admit it"]
    PICK -->|yes| LAND["Landing target"]
    LAND --> PATH["Compute transit checkpoints"]
    PATH --> FREEZE["FREEZE target and path"]

    style X4 fill:#f8d7da,stroke:#721c24
    style C1 fill:#fff3cd,stroke:#856404
    style FREEZE fill:#d4edda,stroke:#155724
```

### Transit checkpoint is not landing target

A version may be a legal step without being an acceptable place to stop. Spring Boot 3.0 is EOL and
would never be selected as a landing target, but it is a **mandatory** transit checkpoint because it
carries the Jakarta namespace relocation, the Spring Security 6 configuration model and the Java 17
baseline. Collapsing it would hide three distinct failure modes behind one diff.

### What the reference corpus actually produced

Under strict production policy, **every candidate was eliminated**, and the harness said exactly why:

```text
POLICY BLOCK: No Spring Boot line satisfies the active policy as a landing target.
  2.7 -> the application uses Spring Cloud but no GA release train targets this Boot line…; open-source support has ended
  3.0 -> open-source support has ended
  3.1 -> …no GA release train…; open-source support has ended
  3.2 -> …no GA release train…
  3.3 -> open-source support has ended
  3.4 -> open-source support has ended
  3.5 -> open-source support has ended
  4.0 -> the application uses Spring Cloud but no GA release train targets this Boot line…
  4.1 -> the application uses Spring Cloud but no GA release train targets this Boot line…

Closest supportable candidate: 3.5.16 with Spring Cloud 2025.0.3.
It was rejected because: open-source support has ended.
If that is an accepted business risk, re-run with a policy that sets
allow_eol_landing_target=true, which records the exception explicitly rather than hiding it.
```

That is the correct answer for this application in September 2026. The ecosystem is in a squeeze: the
newest Boot lines have no GA Spring Cloud train yet, and the newest line that does have one has just
passed its open-source support date. A tool that silently picked 4.1 would have produced a repository
that cannot resolve `spring-cloud-starter-netflix-eureka-client` at all.

With the documented exception policy, resolution proceeds:

```text
Landing target Spring Boot 3.5.16 (Java 21, Spring Cloud 2025.0.3),
8 migration edge(s), -2 month support horizon
```

and Agent 18 raises a `SHORT_HORIZON_TARGET` gate for it.

### The frozen path

| Edge | From → To | Class | Mandatory | Landing |
|---|---|---|:--:|:--:|
| `EDGE-1-PREP-TEST` | 2.7.12 → 2.7.12 | PREPARATORY | ✅ | |
| `EDGE-2-PATCH` | 2.7.12 → 2.7.18 | PATCH | | |
| `EDGE-3-MAJOR` | 2.7.18 → 3.0.13 | **MAJOR_BOUNDARY** | ✅ | |
| `EDGE-4-MINOR` | 3.0.13 → 3.1.12 | MINOR | | |
| `EDGE-5-MINOR` | 3.1.12 → 3.2.12 | MINOR | | |
| `EDGE-6-MINOR` | 3.2.12 → 3.3.13 | MINOR | | |
| `EDGE-7-MINOR` | 3.3.13 → 3.4.13 | MINOR | | |
| `EDGE-8-MINOR` | 3.4.13 → 3.5.16 | MINOR | | ✅ |

The preparatory test-infrastructure edge runs **first**, before any framework change, so that
pass/fail/skip semantics are proven to survive independently. Otherwise a later regression cannot be
distinguished from a test-runner artefact.

The final edge is classed `LANDING` rather than `MINOR`: a version can be a legal transit checkpoint
while being an illegal place to stop, and the two need different validation depth.

### The boundary rule

**Every** major version crossed gets its own mandatory `MAJOR_BOUNDARY` edge, and the last supported
line of a major is reached before the next major is entered.

The path builder used to jump straight to the lowest line of the *landing* major. For the 2.7 → 3.5
migration above that happens to produce the right answer, because only one major is crossed. For a
2.7 → 4.x migration it did not: it emitted a single boundary edge into 4.0 and produced **no Boot 3
checkpoint at all** — skipping precisely the transition that carries the Jakarta EE relocation, the
Spring Security 6 configuration rewrite and the Java 17 baseline.

A 2.7 → 4.1 path is now:

```text
PREPARATORY → PATCH(2.7.18) → MAJOR_BOUNDARY(3.0) → MINOR(3.1…3.5)
            → MAJOR_BOUNDARY(4.0) → LANDING(4.1)
```

The stage asserts this before it freezes anything: if the number of `MAJOR_BOUNDARY` edges is less
than the number of majors between source and landing, the stage fails rather than publishing a path
that skips a boundary. That check exists because the path builder is the only thing standing between
a two-major migration and a one-hop rewrite.

### Java target selection

The application's Java level is **not** derived from the JDK running Bootshift. That was a fact about
the harness process, not about the application: it made the target depend on how the operator
launched the tool, and on a machine with a newer JDK it silently raised the compiler target on an
edge that did not carry that change.

Selection is: the JDKs actually installed, intersected with the majors the edge's target Boot line
supports, discarding anything below the project's current level, then the policy preference — by
default the **highest LTS**, because a non-LTS release is not a defensible production landing target
and picking one as a side effect of what happened to be installed is how a migration acquires a
second problem.

Each edge records `edge_java`, `java_vendor`, `java_version`, `java_home`, the selection reason and
the supporting evidence. When no installed JDK satisfies an edge, that is reported as a blind spot —
never substituted with the harness JVM.

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| Source version undeterminable | Structured refusal | 2 |
| Explicit target is not a known line | Structured refusal | 2 |
| Explicit target is not viable | Policy block naming the eliminations | 3 |
| No viable candidate | Policy block with the trade-off explained | 3 |

---

## 20. Agent 07 — Documentation Registry

> **A component is classified by resolution evidence, not by the shape of its group id.** A coordinate
> the build resolver fetched from a public repository is a public open-source component, whatever its
> group is called; only a coordinate that did not resolve, or that came from a repository not
> demonstrably public, is reported as *possibly* organization-internal — and only as possibly, because
> a private mirror of a public library is indistinguishable from here. The registry keeps the two
> gaps apart: `public_components_without_catalogued_document` is a gap in this harness's catalogue,
> `possibly_internal_components` is a gap in what it can reach at all. Collapsing them made the
> "no authoritative source" list unusable — three dozen ordinary libraries buried the handful of
> entries that deserved a reader's attention.
>

### Purpose

Fetch and pin the authoritative documents for the frozen path, by content hash.

### Why the stage exists

It runs **after** the path is frozen because only then is it known which edge-specific documents
matter. Fetching everything first would pin documents for edges that were never planned.

### Internal flow

```mermaid
flowchart TD
    PATH["Frozen migration path"] --> PER["For each edge that changes version"]
    PER --> URL["Locate release notes<br/>and migration guide for the target line"]
    URL --> ALLOW{"Host on the<br/>egress allowlist?"}
    ALLOW -->|no| REFUSE["Egress refused"]
    ALLOW -->|yes| CACHE{"Already in the<br/>content-addressed cache?"}
    CACHE -->|yes| HIT["Serve from cache;<br/>the run stays reproducible offline"]
    CACHE -->|no| FETCH["Fetch and store"]
    FETCH --> RAW["Store the raw snapshot<br/><i>this is the authority</i>"]
    RAW --> TEXT["Store a readable rendition<br/><i>balanced-tag container extraction</i>"]
    HIT --> REG
    TEXT --> REG["Record id, publisher, component,<br/>versions, URL, timestamp, content hash,<br/>trust level, ETag, sizes"]
    REG --> COV["Report coverage and<br/>extraction usability"]

    style RAW fill:#d4edda,stroke:#155724
```

### Trust levels

| Level | Admissible as |
|---|---|
| `OFFICIAL_MIGRATION_GUIDE` | Candidate facts, highest documentation precedence |
| `OFFICIAL_RELEASE_NOTES` | Candidate facts |
| `OFFICIAL_METADATA` | Artifact-channel evidence |
| `OFFICIAL_API_DOC` / `OFFICIAL_GENERAL_DOC` | Candidate facts |
| `UPSTREAM_PROJECT_DOC` | Candidate facts |
| `COMMUNITY_ADVISORY` | **Advisory only** — confidence 0.2, never a sole basis |

### Extraction is a rendition, never a replacement

Official Spring documentation is served as HTML wiki pages. Asking a prose pattern to read page
chrome produces noise, so a readable rendition is stored beside the raw snapshot and both are
retained. The raw snapshot remains the authority; no summary — LLM-produced or otherwise — may
replace it.

The container extraction counts opening and closing tags rather than using a lazy regex. A lazy match
stops at the first inner close, which silently truncated a one-megabyte page to **430 characters** of
navigation chrome. With depth counting the same page yields **20,396 characters** of real prose, and
`extracted_text_length` is reported per document so a reader can see which is which.

Reference result: **15 documents pinned across 7 edges, including 7 official migration guides.**

### Failure behavior

| Situation | Behavior | Exit |
|---|---|---|
| Host not on the allowlist | Refused, recorded as an unretrieved attempt | 0 |
| Offline | Cached documents still resolve; blind spot `BS-DOC-001` | 0 |
| Edge with no guide | Gap `GAP-DOC-001`; artifact channel covers it alone | 0 |
| Retrieved but unreadable | Gap `GAP-DOC-002` | 0 |

---

## 21. Agent 08 — Migration Knowledge

### Purpose

Turn official documentation plus artifact reality into **verified** migration facts.

### Diagram 10 — The two-channel flow

```mermaid
flowchart TD
    subgraph DOC["DOCUMENTATION CHANNEL — what the maintainers intended"]
        D1["Pinned document renditions"] --> D2["Sentence-level prose patterns:<br/>removed, deprecated, renamed to,<br/>replaced by, replacement introduced,<br/>no longer supported, default changed"]
        D2 --> D3["Subject filter:<br/>annotation, dotted name or CamelCase"]
        D3 --> D4["CANDIDATE facts<br/><i>never authorize a change</i>"]
    end

    subgraph ART["ARTIFACT CHANNEL — what actually changed in the bytes"]
        A1["Source and target BOM diff"] --> A2["Managed version changes<br/>restricted to what this app uses"]
        A1 --> A3["Artifacts removed from management"]
        A4["Artifact existence probes"] --> A5["Coordinates that no longer resolve"]
        A6["spring-configuration-metadata.json diff"] --> A7["Official deprecation entries<br/>with replacement keys"]
        A8["Structural edge facts"] --> A9["Jakarta relocation, Java baseline,<br/>Security 6 model, autoconfig imports"]
    end

    D4 --> MERGE{"Merge by subject and type"}
    A2 --> MERGE
    A3 --> MERGE
    A5 --> MERGE
    A7 --> MERGE
    A9 --> MERGE

    MERGE -->|"artifact evidence agrees"| V["VERIFIED<br/>may authorize a transformation"]
    MERGE -->|"channels disagree<br/>about the replacement"| C["CONFLICTING<br/>requires human resolution"]
    MERGE -->|"documentation only"| CAND["CANDIDATE<br/>cannot authorize a change"]

    A7 --> GEN["Generate property migration rules<br/>with evidence attached"]

    style V fill:#d4edda,stroke:#155724
    style C fill:#f8d7da,stroke:#721c24
    style CAND fill:#fff3cd,stroke:#856404
    style GEN fill:#e7f3ff,stroke:#0366d6
```

### Only reality authorizes a change

`MigrationFact.verifyWithArtifactEvidence` is the single path to `VERIFIED`, and it requires
artifact-channel evidence. Documentation describes intent; artifacts describe what shipped. A code
change is authorized by the second (R10).

### Reference corpus knowledge

**1690 facts: 1683 VERIFIED, 7 CANDIDATE, 0 CONFLICTING**

| Fact type | n | Where it came from |
|---|--:|---|
| `API_REMOVED` | 1034 | **published-bytecode diff** — types present in the source jar and absent in the target jar |
| `PROPERTY_RENAMED` | 389 | configuration metadata deprecation entries with replacements |
| `PROPERTY_REMOVED` | 154 | configuration metadata deprecations without replacements |
| `MANAGED_VERSION_CHANGED` | 105 | BOM diff, restricted to coordinates this application uses |
| `ARTIFACT_REMOVED` | 3 | BOM diff plus existence probes |
| `API_RENAMED` | 2 | structural boundary fact plus prose |
| `BASELINE_REQUIREMENT` | 1 | Java 17 baseline at the 3.x boundary |
| `COMPATIBILITY_REQUIREMENT` | 1 | Spring Cloud train lock |
| `BEHAVIOR_CHANGED_NO_API_CHANGE` | 1 | `spring.factories` to `AutoConfiguration.imports` |

Artifact channel measurements: source BOM **1151** entries, target BOM **1471** (both after following
imports), three configuration-metadata artifacts read, **542 deprecated properties** discovered, and
**60 jar pairs** diffed at the budget ceiling yielding **1028 removed types**.

The seven CANDIDATE facts — `RestHighLevelClient`, `YamlJsonParser`, `WebMvcMetricsFilter`,
`banner.png` and others — are real breaking changes that the documentation states plainly. They stay
CANDIDATE because no artifact observation in this run corroborated them, and so they may not
authorize a transformation. That is the rule working, not a gap.

### The channel that opens the jar

BOM entries, existence probes and configuration metadata all describe the *packaging* around a
dependency. Only `javap` over two published jars can say that a **type** was removed — as opposed to
documented as deprecated, which is a different and weaker claim.

For every coordinate the build actually resolved that both the source and target BOMs manage at
different versions, Agent 08 fetches both jars and diffs their public and protected signatures. Both
BOM families are consulted: the Spring Boot BOM manages no Spring Cloud artifact at all, so a diff
that read only the Boot BOM would be blind to the half of a Spring migration that usually breaks
first.

This channel is the reason `API_REMOVED` went from **6** facts, all documentation candidates, to
hundreds of artifact-verified ones. It is also the reason `API_REMOVED` can require evidence level
`E3`: without a channel that inspects published bytes, no such fact could ever reach that level, and
every one of them would have been blocked from authorizing a change.

Starter and BOM-aggregator artifacts are skipped. A starter ships an empty jar — pulling in a
dependency set is its whole purpose — so diffing one is guaranteed to find nothing while consuming a
slot the budget could have spent on an artifact that declares types.

### BOMs are read transitively, because Spring Cloud is nothing but imports

A BOM snapshot follows `<scope>import</scope>` entries to a bounded depth, carrying
`${project.version}` down so a sub-BOM's own modules resolve correctly.

This is not a refinement. `spring-boot-dependencies` manages most of its estate directly, so a
first-level read works for it. `spring-cloud-dependencies` is the opposite — **all seventeen** of its
dependency blocks are imports:

| | managed entries | usable Spring Cloud artifacts |
|---|---:|---:|
| First level only | 17 | **0** |
| Following imports | 358 | **131** |

Reading only the first level recorded seventeen *aggregators* as if they were dependencies and never
opened one, so no real Spring Cloud coordinate existed in the snapshot at all — which meant the
managed-version diff could not see a Spring Cloud version change, and the bytecode diff skipped every
Spring Cloud artifact as "not managed by both BOMs".

The diff is bounded at 60 jar pairs per run. Coordinates beyond the budget, and coordinates only one
BOM manages, are listed in `api_diff.not_diffed` and raise `GAP-KNOW-002` — the limit shows up as
reduced coverage rather than as facts that quietly do not exist.

### Generated property rules

Rules are **generated, never hand-written** (spec section 23), from the target release's own
deprecation metadata:

```json
{
  "generated_by": "bootshift 08-knowledge",
  "source_version": "2.7.12",
  "target_version": "3.5.16",
  "provenance": "spring-configuration-metadata.json deprecation entries published in the target release artifacts",
  "hand_written": false,
  "rule_count": 542,
  "rules": [
    { "from": "logging.file", "to": "logging.file.name", "action": "RENAME",
      "deprecation_level": "error",
      "evidence_ref": "META-INF/spring-configuration-metadata.json in spring-boot 3.5.16" }
  ]
}
```

Generating them is what makes the 542 property facts deterministically covered at all — hand-maintaining that many rules is how they end up wrong. They are the reason `PROPERTY_RENAMED` and `PROPERTY_REMOVED` show as fully covered in the residual report.

### Optional AI role

When enabled, AI may summarize, extract candidates, cluster and draft explanations. Its output is
recorded as evidence with model identity, prompt hash, context hash and response hash — and remains a
hypothesis until deterministic verification succeeds. The reference run used **zero** AI.

---

## 22. Agent 09 — Impact Analyzer

### Purpose

Answer: which parts of **this** repository are affected by **these** verified facts?

### Diagram 11 — Impact analysis flow

```mermaid
flowchart TD
    F["VERIFIED migration facts only"] --> LOC{"Fact type"}
    LOC -->|API| L1["Graph nodes referencing the type<br/>+ textual scan as a capped fallback"]
    LOC -->|PROPERTY| L2["CONFIG_PROPERTY nodes,<br/>exact then prefix"]
    LOC -->|ARTIFACT| L3["LIBRARY nodes and the module<br/>descriptors that resolve them"]
    LOC -->|BASELINE| L4["Build descriptors"]
    LOC -->|BEHAVIOR| L5["Named resource, else type reference"]

    L1 --> ATTR{"Was the relationship<br/>type-resolved?"}
    L2 --> CLASS
    L3 --> CLASS
    L4 --> CLASS
    L5 --> ATTR
    ATTR -->|no| CAP["CAP at POSSIBLY_AFFECTED<br/><i>a textual match is not a fact</i>"]
    ATTR -->|yes| CLASS["Classify"]
    CAP --> CLASS

    CLASS --> BR["Blast radius traversal<br/>with the explaining path"]
    BR --> DIM["Derive required validation dimensions<br/>from fact type and node kind"]
    DIM --> TESTS["Find covering tests"]
    TESTS --> BS["Attach inherited blind spots"]
    BS --> ACC["Measure precision and recall<br/>on held-out fixtures"]
    ACC --> FLOOR{"Recall below<br/>the policy floor?"}
    FLOOR -->|yes| ESC["Gap GAP-IMPACT-001;<br/>validation breadth escalates"]

    style CAP fill:#fff3cd,stroke:#856404
    style ESC fill:#fff3cd,stroke:#856404
```

### Classification and the attribution cap

| Classification | Meaning |
|---|---|
| `DEFINITELY_AFFECTED` | Type-resolved reference, or an exact configuration key match |
| `LIKELY_AFFECTED` | Strong structural evidence, prefix match |
| `POSSIBLY_AFFECTED` | Located textually, or through an unresolved relation |
| `UNAFFECTED_WITHIN_OBSERVED_COVERAGE` | Nothing in the observed graph matched |

**Universal `UNAFFECTED` is never emitted.** The strongest negative statement available names its own
limit, and the artifact says so in the rationale:

> "No node, symbol or configuration key in the observed graph matched this fact. This is not a claim
> of universal safety: reflection, dynamic configuration and unresolved types are outside observed
> coverage."

The attribution cap is mechanical: `match.typeResolved ? classification : classification.capAt(POSSIBLY_AFFECTED)`.

### Reference corpus impact

**2072 findings across 27 files**: 502 `DEFINITELY_AFFECTED`, 5 `POSSIBLY_AFFECTED`,
1565 `UNAFFECTED_WITHIN_OBSERVED_COVERAGE`.

The third class is named the way it is on purpose. `UNAFFECTED_WITHIN_OBSERVED_COVERAGE` is a claim
about what the analysis could see, not about the file. Calling it `UNAFFECTED` would assert something
the harness has no basis for.

Every affected finding carries a graph path:

```json
{
  "impact_id": "IMPACT-00042",
  "knowledge_id": "MK-00218",
  "classification": "DEFINITELY_AFFECTED",
  "path": "employee-service/src/main/resources/application.properties",
  "rationale": "Configuration key spring.data.mongodb.uri is exactly the affected property",
  "confidence": 0.98,
  "attribution_capped": false,
  "blast_radius_size": 7,
  "graph_path": [
    { "node": "MODULE:employee-service", "distance": 1,
      "explanation": "FILE:FILE-01M2… -[CONFIGURES]-> PROPERTY:spring.data.mongodb.uri" }
  ],
  "required_validation_dimensions": ["CONFIGURATION_BINDING"]
}
```

### Measured accuracy, not assumed

`bootshift evaluate-impact` scores the analyzer against held-out fixtures in
`fixtures/impact-evaluation/`. Fixtures marked `tuning` are excluded from reported numbers, so the
harness never reports evaluation on data it was tuned against. **When no held-out fixtures exist the
result is UNMEASURED, not perfect** — and `GAP-IMPACT-002` says so.

Recall below `impact_recall_floor` (default 0.80) escalates validation breadth for every edge.

---

## 23. Agent 10 — Characterization

> **Scenarios are executable, and they are executed here.** Agent 10 produces
> `characterization-scenarios.json` — concrete requests with a method, a path with its variables
> substituted, the headers that make the observation meaningful, and the exact facts to capture — and
> then runs them against the **original** application, before anything is migrated, writing
> `characterization-old-observations.json`.
>
> That execution is what makes an observation an oracle. Previously the stage emitted a *description*
> of a probe and nothing ever executed it, so every contract sat in `AWAITING_OLD_OBSERVATION` for the
> life of the run while the report counted it as protection, and differential validation — having no
> scenario to run — fell back to comparing Actuator metadata between the two sides.
>
> `AWAITING_OLD_OBSERVATION` is explicitly **not** a protected state. A scenario is accounted for only
> when it is `FROZEN`, `MAPPED_TO_EXISTING_VERIFIED_TEST`, `UNOBSERVABLE_WITH_EXPLICIT_GAP` or
> `HUMAN_EXCEPTION_REQUIRED`. Expected values are never derived by reading migrated code.
>
> **A scenario that was attempted and failed becomes a declared gap, not a pending one.**
> `AWAITING_OLD_OBSERVATION` means "not executed yet" — a state something later could still resolve.
> A scenario whose module was running and whose request came back unusable will never be resolved by
> anything in the run, and leaving it pending kept it out of the declared-gap count: it protected
> nothing, and it was not admitted as a blind spot either. It becomes
> `UNOBSERVABLE_WITH_EXPLICIT_GAP` carrying the execution failure as its reason. Only a scenario that
> was never attempted stays pending.
>
> Scenario families emitted from the endpoint graph, per endpoint: the endpoint's own contract
> (`HTTP_API`), the same endpoint called with no credential and with an invalid one
> (`SECURITY_AUTHORIZATION`), and its response field paths (`SERIALIZATION`). Per module that starts:
> `CONFIGURATION_BINDING` over `/actuator/configprops` and `/actuator/env`, and `SPRING_CONTEXT` over
> `/actuator/beans`, `/actuator/conditions`, `/actuator/mappings` and `/actuator/health`. Dimensions
> needing provisioned datastores or brokers are emitted as
> `UNOBSERVABLE_WITH_EXPLICIT_GAP` when the environment provider cannot supply them, so the coverage
> statement describes the migration actually being performed rather than a smaller one.

### Purpose

Protect migration-sensitive behaviour **before** it changes.

### Diagram 12 — Characterization flow

```mermaid
flowchart TD
    I["Impact finding<br/><i>not UNAFFECTED</i>"] --> P{"Does adequate protection<br/>already exist?"}
    P -->|"a passing baseline test covers it"| MAP["MAPPED_TO_EXISTING_TEST"]
    P -->|no| OBS{"Can the behaviour be<br/>observed in this environment?"}
    OBS -->|no| UNOBS["UNOBSERVABLE<br/>+ characterization gap"]
    OBS -->|yes| GEN["Scaffold a harness-owned probe"]
    GEN --> WAIT["AWAITING_OLD_OBSERVATION<br/><i>cannot act as an oracle yet</i>"]
    WAIT --> RUN["Execute against the ORIGINAL application"]
    RUN --> OK{"Valid against OLD?"}
    OK -->|no| REJ["REJECTED"]
    OK -->|yes| FREEZE["FROZEN — observed behaviour<br/>becomes the oracle"]
    MAP --> FREEZE

    style WAIT fill:#fff3cd,stroke:#856404
    style FREEZE fill:#d4edda,stroke:#155724
    style UNOBS fill:#f8d7da,stroke:#721c24
```

### The rule that matters

> Expected behaviour comes from **observed original behaviour**, an **authoritative specification**,
> or an **explicit human decision**. Never from invention.

A probe whose expectation cannot be filled from the sealed baseline is created in
`AWAITING_OLD_OBSERVATION` and is structurally unable to act as an oracle until it has run against
OLD. AI, when enabled, may scaffold the probe; it may never supply the expected value.

### Contracts are framework-neutral

A contract describes a **scenario and its observed outcome**, not a JUnit test:

```json
{
  "scenario_id": "SCN-00031",
  "dimension": "HTTP_API",
  "node_id": "ENDPOINT:GET /api/v1/employee/search",
  "state": "AWAITING_OLD_OBSERVATION",
  "oracle_source": "OBSERVED_ORIGINAL_BEHAVIOUR_PENDING",
  "probe": {
    "kind": "HTTP_API", "harness_owned": true, "transport": "HTTP",
    "method": "GET", "path": "/api/v1/employee/search",
    "capture": ["status_code", "content_type", "body_shape", "error_payload_shape"]
  }
}
```

This is what lets the test-infrastructure edge migrate JUnit 4 to Jupiter without destroying the
behavioural oracle: the oracle was never a JUnit test in the first place.

Reference corpus: **517 contracts**, all `AWAITING_OLD_OBSERVATION`, covering every impacted dimension
plus an HTTP contract for each of the 10 observed endpoints.
---

## 24. Agent 11 — Migration Planner

> **Everything in an edge plan is computed for that edge.** Facts, impact findings, affected
> `FILE_ID`s, affected symbols, required validation dimensions, risk, residual, deterministic
> coverage, the Spring Cloud train, the Java target, the characterization scenarios and the approval
> obligations are all derived per edge and never inherited from the migration as a whole.
>
> This used to be otherwise: the whole run's fact set, impact set and file set were copied into every
> edge, so a patch edge claimed authority over every impacted file in the repository and carried a
> coverage figure that described a different edge entirely.
>
> The mechanism is a validity interval on each migration fact. Every fact carries `component`,
> `valid_from`, `valid_to` and a `validity_precision`, and a fact reaches an edge only when its
> interval intersects that edge's `(from, to]` span. Precision is published rather than smoothed
> over: `EDGE_EXACT` means the evidence names the exact version pair, `ARTIFACT_VERSION_WINDOW` means
> the change is somewhere inside the edges on which the owning artifact's managed version actually
> moved, and `SPAN_ONLY` means the evidence covers the whole migration and the fact cannot be
> attributed to one edge. A fact presented as edge-exact when it is span-only is an overstated claim.
>
> Capability matching is per recipe. A plan entry naming `capability_id` names the capability that
> actually implements that recipe, resolved by asking the owning provider. The planner previously
> attached "the first AVAILABLE capability whose declared fact types overlap anything in the run",
> which paired a Maven POM recipe with a JUnit capability — a claim a reviewer checking the plan
> would find is simply untrue, and a coverage number computed from it means nothing. A recipe no
> capability claims is recorded as `NO_CAPABILITY_CLAIMS_THIS_RECIPE` rather than given a substitute.

### Purpose

The Target Resolver said where to go. The Planner says exactly **how**, and freezes the answer.

### Why the stage exists

Three things happen here that nothing else may do later.

### Internal flow

```mermaid
flowchart TD
    CAP["Probe every transformation provider<br/>for what it can actually do right now"] --> REG["TransformationCapabilityRegistry<br/><i>with license evidence per capability</i>"]
    REG --> COV["Deterministic coverage =<br/>facts handled / verified facts"]
    COV --> RES["Residual: fact types with<br/>no AVAILABLE capability"]
    RES --> EDGE["For each edge in the frozen path"]

    EDGE --> TRAIN["Resolve the Spring Cloud train<br/>for THIS edge target line"]
    TRAIN --> JAVA["Resolve the Java level for THIS edge:<br/>highest installed JDK the line supports"]
    JAVA --> RECIPES["Select ordered transformations;<br/>omit managed-version when no train exists"]
    RECIPES --> SCOPE["Scope: impacted files + build descriptors,<br/>plus every test source on a PREPARATORY edge"]
    SCOPE --> RECON{"Does a composite capability<br/>span multiple checkpoints?"}
    RECON -->|no| DEC["DECOMPOSED"]
    RECON -->|"yes, policy allows"| COL["COLLAPSE_WITH_ESCALATED_VALIDATION"]
    RECON -->|"yes, policy forbids"| BLK["BLOCK"]
    DEC --> DEPTH
    COL --> DEPTH["depth = MAX(class, residual, impact, policy)"]
    DEPTH --> FREEZE["FREEZE the depth into the edge plan"]
    BLK --> STOP["exit 3"]

    style FREEZE fill:#d4edda,stroke:#155724
    style BLK fill:#f8d7da,stroke:#721c24
```

### Per-edge resolution, not per-run

Two bugs found by running this end to end, both from carrying a landing-target value backwards onto a
transit checkpoint:

**The Spring Cloud train.** The first version installed the *landing* train (2025.0.3) on the
2.7.18 patch edge. Spring Cloud removed `@EnableEurekaClient` in 2022.0, so the application stopped
compiling with `cannot find symbol: class EnableEurekaClient` — a failure caused entirely by the
harness. Each edge now resolves the train published for **its own** Boot line, and when no train
targets that line the managed-version transformation is omitted rather than guessing.

**The Java level.** Setting the landing Java level (21) on the 3.0 edge would set a compiler target
that Spring Boot 3.0 does not support. Each edge now takes the highest installed JDK that **its**
Boot line accepts and that is not below the project level.

### Why one transformer's list is curated on purpose

`java.remove-annotation` exists because the reference corpus proved the gap: Spring Cloud 2022.0
deleted `@EnableEurekaClient`, nothing in the deterministic estate could remove it, and the major
edge stopped with sixteen `cannot find symbol` errors that a human would have fixed by deleting one
line per module.

On the reference corpus it now removes the annotation and its import from four classes — and it does
so on the **patch** edge, not the major one, because once the fact existed the impact analysis put
those classes in that edge's scope. Nothing scheduled that by hand; it followed from the fact
reaching the analyzer at all.

It would be easy to drive that transformer from every `API_REMOVED` fact the bytecode diff produces.
It would also be wrong. **"This type no longer exists" does not imply "deleting the reference is
safe."** For most removed types the reference is load-bearing, and deleting it changes behaviour
silently — the single worst outcome this harness exists to prevent.

Removal is only safe for an annotation whose *entire* effect was to opt into behaviour the target
version now performs unconditionally. That is a claim about semantics, and no diff can establish it.
So each entry carries the evidence for why its removal is a no-op, and an annotation not on the list
stays residual — reported, not guessed at. `TransformerTest` asserts that every entry justifies
itself, and that the capability does not claim `API_REMOVED` facts outside its list.

### Capability discovery (R31)

Nothing is assumed to exist for an edge:

| Capability | Provider | License | Status on this run |
|---|---|---|---|
| `CAP-MAVEN-PARENT-VERSION` | `BOOTSHIFT_MAVEN_POM` | MIT (harness code) | AVAILABLE |
| `CAP-MAVEN-DEPENDENCY` | `BOOTSHIFT_MAVEN_POM` | MIT (harness code) | AVAILABLE |
| `CAP-JAKARTA-NAMESPACE` | `BOOTSHIFT_JAKARTA` | MIT (harness code) | AVAILABLE at the 2.x→3.x boundary |
| `CAP-JUNIT4-JUPITER` | `BOOTSHIFT_TEST_FRAMEWORK` | MIT (harness code) | AVAILABLE |
| `CAP-MOCKBEAN-MOCKITOBEAN` | `BOOTSHIFT_TEST_FRAMEWORK` | MIT (harness code) | AVAILABLE |
| `CAP-CONFIG-PROPERTY` | `BOOTSHIFT_CONFIG_PROPERTY` | MIT (harness code) | AVAILABLE, 542 generated rules |
| `CAP-REMOVE-NOOP-ANNOTATION` | `BOOTSHIFT_REMOVED_ANNOTATION` | MIT (harness code) | AVAILABLE, 3 annotations |
| `CAP-OPENREWRITE-CORE` | `OPENREWRITE_CORE` | Apache-2.0 | **UNAVAILABLE** — not on the classpath; counted as residual |

The OpenRewrite row is the point of R31. The registry states the truth, and the missing coverage
flows into the residual calculation rather than becoming a silent hole.

### Validation depth is computed once and frozen (R16)

```text
depth(edge) = MAX(
    class_depth,              PATCH -> tests, MINOR -> runtime,
                              PREPARATORY -> impacted differential,
                              MAJOR_BOUNDARY -> full differential
    residual_depth,           escalated by deterministic coverage
    impact_required_depth,    escalated when any impact is HIGH risk
    policy_required_depth     escalated when impact recall is below floor,
                              or a checkpoint was collapsed
)
```

Execution **reads** this. It never decides for itself how much validation an edge deserves.

### Reference corpus plan

**8 edges frozen, deterministic coverage 0.3886** — 654 of 1683 verified facts have a transformer
that claims their subject.

Read the breakdown, not the number:

| Fact type | facts | covered | uncovered |
|---|---:|---:|---:|
| `API_REMOVED` | 1029 | 1 | 1028 |
| `PROPERTY_RENAMED` | 389 | 389 | 0 |
| `PROPERTY_REMOVED` | 153 | 153 | 0 |
| `MANAGED_VERSION_CHANGED` | 105 | 105 | 0 |
| `ARTIFACT_REMOVED` | 3 | 3 | 0 |
| `API_RENAMED` | 1 | 1 | 0 |
| `BASELINE_REQUIREMENT` | 1 | 1 | 0 |
| `BEHAVIOR_CHANGED_NO_API_CHANGE` | 1 | 0 | 1 |
| `COMPATIBILITY_REQUIREMENT` | 1 | 1 | 0 |

The single covered `API_REMOVED` fact is `@EnableEurekaClient` — the one annotation on the
transformer's justified list. The other **1028** are the honest residual: types the published-bytecode
diff proved are gone, for which no transformer claims a safe rewrite, because "the type is gone" does
not imply "deleting the reference is safe". The residual report names them with example
subjects, so an operator can see exactly what a human still has to do. `spring.factories` remains
uncovered for the same reason it always did — no safe mechanical equivalent exists.

An earlier version of this harness reported **0.9983** for the same corpus. Nothing about the
transformers changed. That number was the product of a fact set that had never inspected a jar, and
of a capability model in which a JUnit rewriter's claim on the `API_REMOVED` *type* absorbed every
removed API in the ecosystem. The migration did not get harder; the number stopped lying about it.

---

## 25. Agent 12 — Transformation

### Purpose

Apply only the deterministic transformations the frozen plan authorized for this edge.

### Why the stage exists — and what it deliberately does not do

**Agent 12 never writes a file.** It computes proposals and hands them to the gateway. An ArchUnit
rule fails the build if anything in `stages.stage12`, `stages.stage13` or `adapters.transform` calls
the filesystem write API.

### Internal flow

```mermaid
flowchart TD
    SEAL{"Baseline sealed?"} -->|no| REFUSE["POLICY BLOCK (R7)"]
    SEAL -->|yes| CP["Checkpoint: edge start"]
    CP --> LOAD["Load the frozen edge plan"]
    LOAD --> REG["Register the transformers<br/>that handle its recipes"]
    REG --> EACH["For each ordered transformation"]
    EACH --> NARROW["Narrow target paths by role:<br/>descriptors, Java sources, tests, config"]
    NARROW --> PROPOSE["Provider computes ProposedChange objects"]
    PROPOSE --> COLLECT["Collect proposals across recipes"]
    COLLECT --> GATE["FileMutationGateway.apply<br/><i>the only writer</i>"]
    GATE --> PERSIST["Persist the live registry"]
    PERSIST --> REPORT["Report applied, rejected, failed,<br/>residual recipes, ledger head"]

    style REFUSE fill:#f8d7da,stroke:#721c24
    style GATE fill:#f8d7da,stroke:#721c24
```

### Transformers

| Recipe | What it does | Restraint |
|---|---|---|
| `maven.parent-version` | Sets the Boot parent version | Verifies the artifact id first; surgical text edit |
| `maven.property` | Sets a property, inserting it if absent | Only the named property |
| `maven.managed-version` | Sets a managed BOM version | Only within `dependencyManagement` |
| `maven.dependency-coordinate` | Relocates a coordinate | Only an exact group and artifact match |
| `java.remove-annotation` | Deletes annotations that became no-ops and were then removed | **3 curated entries only**, each with the evidence that removal changes nothing |
| `jakarta.namespace` | Rewrites relocated Jakarta EE packages | **28 relocated prefixes only**; 26 preserved packages never touched |
| `test.junit4-to-jupiter` | Mechanical JUnit 4 constructs | Rules, runners and `ExpectedException` are residual |
| `test.mockbean-to-mockitobean` | Bean-override annotations for Boot 3.4 | Import and annotation only |
| `config.property-migration` | Applies generated property rules | Comments preserved; removals commented with the reason |

### The Jakarta restraint is the whole design in miniature

A blanket `javax` to `jakarta` rewrite is the fastest way to turn a working application into one that
does not compile for reasons unrelated to the migration. `javax.sql`, `javax.net`, `javax.crypto`,
`javax.naming`, `javax.management`, `javax.xml.parsers` and nineteen others **did not move**.

```java
// rewritten — these relocated in Jakarta EE 9
import javax.persistence.Entity;   ->  jakarta.persistence.Entity
import javax.servlet.Filter;       ->  jakarta.servlet.Filter
import javax.xml.bind.JAXBContext; ->  jakarta.xml.bind.JAXBContext

// untouched — these are still JDK or unrelated specs
import javax.sql.DataSource;
import javax.crypto.Cipher;
import javax.xml.parsers.SAXParser;
```

`TransformerTest` asserts both halves, including that `javax.xml.bind` moves while
`javax.xml.parsers` stays, and that the rewrite is idempotent.

### Removals are commented, not deleted

```properties
# [bootshift] removed property server.max-http-header-size: no replacement in the target version
#server.max-http-header-size=16KB
```

A reviewer can see what was there. A deleted line is invisible in review.

### Forbidden by construction

Source-available Spring recipe packs · proprietary engines · direct source writes · broad
reformatting of unrelated files · unverified dependency substitution.

### Failure behavior and exit codes

| Situation | Behavior | Exit |
|---|---|---|
| Baseline not sealed | Policy block | 3 |
| Change outside authorized scope | Rejected by the gateway, recorded in the ledger | 0 |
| No provider for a recipe | Recorded as residual | 0 |
| Path traversal attempt | Structured refusal | 2 |

---

## 26. FileMutationGateway

The central architectural enforcement point. **Everything that changes application source goes
through here.**

### Diagram 6 — Gateway internal flow

```mermaid
flowchart TD
    IN["ProposedChange from Agent 12 or 13"] --> S1["1 Verify the baseline seal"]
    S1 -->|not sealed| BLOCK["POLICY BLOCK (R7)"]
    S1 -->|sealed| S2["2 Resolve FILE_ID from the path"]
    S2 --> S3["3 Verify authorization:<br/>file in scope, operation permitted,<br/>line budget respected"]
    S3 -->|denied| REJ["ChangeEvent status REJECTED<br/><i>appended to the ledger anyway</i>"]
    S3 -->|allowed| S4["4 Capture before path, hash and content"]
    S4 --> S4B{"4b Does the proposal's base_hash<br/>still match the file on disk?"}
    S4B -->|no| STALE["REJECTED — STALE_BASE_CONTENT<br/><i>applying it would discard an<br/>already-applied change</i>"]
    S4B -->|yes| S5["5 Resolve inside the workspace<br/><i>traversal and symlink escape refused</i>"]
    S5 --> S6["6 Apply by operation:<br/>MODIFY, CREATE, DELETE, RENAME, MERGE"]
    S6 --> S7["7 Handle identity:<br/>append version, record split,<br/>merge or delete"]
    S7 --> S8["8 Capture after path and hash"]
    S8 --> S9["9 Identify changed SYMBOL_IDs"]
    S9 --> S10["10 Write the patch artifact"]
    S10 --> S11["11 Build the ChangeEvent"]
    S11 --> S12["12 Append to the hash chain"]
    S12 --> S13["13 Checkpoint the repository state"]
    S13 -->|checkpoint failed| CPF["Record a FAILED_VALIDATION event:<br/>rollback is unavailable for this batch"]
    S13 -->|ok| DONE["BatchOutcome"]
    CPF --> DONE

    style BLOCK fill:#f8d7da,stroke:#721c24
    style REJ fill:#fff3cd,stroke:#856404
    style STALE fill:#f8d7da,stroke:#721c24
    style S12 fill:#d4edda,stroke:#155724
```

### Step 4b: why a stale proposal is refused

A transformer computes its replacement content from the file as it stood when the transformer ran.
Two recipes routinely target the same `pom.xml` — a parent-version bump and a managed-version bump,
for instance. If both are computed against the pre-edge tree and handed to the gateway together, the
second write silently discards the first, and **the ledger still records both as APPLIED**.

That happened. On the reference corpus, an edge reported `12 applied` and the commit contained six
file changes: every parent-version bump had been overwritten by a managed-version bump to the same
file, so the project never left Spring Boot 2.7.12 while the ledger said otherwise. The next edge
then rewrote `javax.servlet` to `jakarta.servlet` on a project still on Boot 2.7, and the compiler
reported that `jakarta.servlet.http` does not exist.

A lost change that the ledger reports as applied is the one failure the ledger cannot survive, so
there are now two defences. Agent 12 applies **one recipe per batch**, so each transformer sees the
previous recipe's output — this is why the git history carries one commit per recipe, each naming
the recipe that produced it. And every proposal carries the hash of the content it was derived from;
the gateway rejects it as `STALE_BASE_CONTENT` if the file has moved on. `MutationBoundaryTest`
covers both directions.

### Why a checkpoint failure does not throw

If checkpointing fails, the changes are already applied and already in the ledger. Throwing would
leave a mutated workspace with no record of why. Instead the failure becomes its own ledger event
stating that deterministic rollback is unavailable for that batch — a fact the operator must see,
rather than an exception that erases the work. `MutationBoundaryTest.checkpointFailureIsRecorded`
asserts this.

### Two independent bypass defences

**Static.** An ArchUnit rule forbids mutation-capable packages from calling `Files.write*`,
`Files.delete*`, `Files.move` or `Files.copy`.

**Dynamic.** `detectBypass()` compares on-disk content against the hashes the registry believes are
current, and reports three classes of violation:

```text
BYPASS:    <path> content hash <actual> does not match the gateway-recorded hash <expected>
UNTRACKED: <path> exists in the migration workspace but has no registered identity
MISSING:   <path> is registered as active but absent from the migration workspace
```

This catches a write that skipped the gateway **even if it came from outside the JVM**.

Agent 12 calls `detectBypass()` after every edge. A `BYPASS` — content that differs from what the
gateway recorded — fails the stage; `UNTRACKED` and `MISSING` are reported as gaps, because build
output legitimately appears in the workspace and failing on it would teach operators to ignore the
check. That call site is itself asserted by `ControlsAreWiredTest`: for a while `detectBypass()` had
three passing unit tests and no caller, so the dynamic defence described in this section did not
actually run.

---

## 27. Change Ledger

### Diagram 7 — Tamper-evident hash chain

```mermaid
flowchart LR
    G["GENESIS<br/>0000…0000"] --> E1["EVENT 1<br/>CHANGE-000001"]
    E1 --> H1["hash₁ = SHA256(GENESIS ‖ canonical₁)"]
    H1 --> E2["EVENT 2<br/>CHANGE-000002"]
    E2 --> H2["hash₂ = SHA256(hash₁ ‖ canonical₂)"]
    H2 --> E3["EVENT 3<br/>CHANGE-000003"]
    E3 --> H3["hash₃ = SHA256(hash₂ ‖ canonical₃)"]
    H3 --> HEAD["HEAD<br/><i>sealed into the evidence manifest</i>"]

    style HEAD fill:#d4edda,stroke:#155724
```

Each link depends on the exact canonical bytes of every earlier event, so insertion, deletion,
reordering and in-place mutation are all detectable. Verification recomputes the chain from the
persisted file and never trusts in-memory state, which is what makes offline tampering detectable.

| Attack | Detected by |
|---|---|
| Delete an event | Sequence discontinuity and a broken chain link |
| Reorder events | Previous-hash mismatch |
| Edit an event in place | Recomputed event hash differs — "content was modified" |
| Insert a forged event | Chain break at the insertion point |
| Truncate **and** forge the head | The head declares more events than the ledger holds |

All five have failure-injection tests in `ChangeLedgerTamperTest`.

### Every attempt is history (R14)

```json
{ "change_id": "CHANGE-000004", "status": "APPLIED",  "file_id": "FILE-01M2…" }
{ "change_id": "CHANGE-000005", "status": "REJECTED", "rejection_reason": "Path … is outside the authorized scope of edge EDGE-1-PREP-TEST" }
{ "change_id": "CHANGE-000006", "status": "REVERTED", "rejection_reason": "rolled back to checkpoint" }
```

A rejected change is not a no-op. It is a recorded decision, and `bootshift explain change` will
show it with its full provenance.

---

## 28. Agent 13 — Build and Repair

### Purpose

Compile the migrated state and repair **bounded** residual compile failures.

### Diagram 13 — Compiler repair loop

```mermaid
flowchart TD
    START["Select a toolchain for the edge target"] --> COMPILE["Compile every module"]
    COMPILE --> OK{"All modules<br/>compile?"}
    OK -->|yes| CP["Checkpoint: compiled"]
    OK -->|no| PARSE["Parse diagnostics"]
    PARSE --> CLUSTER["Cluster by root cause, in diagnosis order:<br/>1 dependency resolution<br/>2 plugin or toolchain<br/>3 missing type or package<br/>4 removed or renamed API<br/>5 generic or type mismatch<br/>6 namespace migration<br/>7 application specific"]
    CLUSTER --> PROG{"Did the error count fall?"}
    PROG -->|no| STOP["NO_PROGRESS -> NEEDS_HUMAN"]
    PROG -->|yes| ENV{"Environmental cause?"}
    ENV -->|yes| SKIP["NOT_REPAIRABLE_BY_SOURCE_EDIT<br/><i>no source edit can fix a broken toolchain</i>"]
    ENV -->|no| BUDGET{"Attempts left<br/>for this root cause?"}
    BUDGET -->|no| EXH["BUDGET_EXHAUSTED"]
    BUDGET -->|yes| R1["1 Verified deterministic rule"]
    R1 -->|none| R2["2 Knowledge-grounded template"]
    R2 -->|none| R3{"AI enabled and<br/>within budget?"}
    R3 -->|no| NONE["NO_REPAIR_AVAILABLE"]
    R3 -->|yes| AI["Bounded AI proposal"]
    AI --> VERIFY["Deterministic verification"]
    VERIFY -->|fails| REJECT["REJECTED_BEFORE_APPLY<br/><i>recorded with model provenance</i>"]
    VERIFY -->|passes| ACCEPT["ACCEPTED_FOR_GATEWAY"]
    R1 --> DUP
    R2 --> DUP
    ACCEPT --> DUP{"Same patch bytes<br/>proposed before?"}
    DUP -->|yes| LOOP["REPEATED_PATCH_DETECTED -> stop"]
    DUP -->|no| GATE["FileMutationGateway"]
    GATE --> COMPILE

    style STOP fill:#f8d7da,stroke:#721c24
    style REJECT fill:#fff3cd,stroke:#856404
    style GATE fill:#f8d7da,stroke:#721c24
```

### Root-cause ordering is the point

A single unresolved dependency produces dozens of "cannot find symbol" errors. Repairing those
individually is how a repair loop burns its entire budget on symptoms. Diagnostics are attributed to
the **first** cause that explains them, so a wave of missing-symbol errors caused by an unresolved
dependency is attributed to the dependency.

Environmental causes — dependency resolution and plugin or toolchain failures — are marked
`NOT_REPAIRABLE_BY_SOURCE_EDIT` and never consume repair budget. No source edit fixes a JDK mismatch.

### AI verification gates (R11)

Every check runs **before** the proposal reaches the gateway, and each one is decidable without
asking the model anything:

| Gate | Rejects |
|---|---|
| Non-empty and different | A no-op or empty response |
| Line budget | A patch larger than `ai_max_changed_lines_per_patch` |
| Half-file heuristic | A response that removed most of the file |
| Test weakening | Newly introduced `@Disabled`, `@Ignore`, `assumeTrue(false)` |
| Assertion count | Any net loss of `assert` |
| Security and transactions | Any net loss of `@PreAuthorize`, `@Secured`, `@RolesAllowed`, `@Transactional` |
| Forbidden dependency | Any reference to a source-available recipe estate |
| Shape sanity | A Maven descriptor that no longer looks like one |

Rejections are recorded with model identity, prompt hash, context hash and response hash, so
`bootshift explain change` can show what was proposed and why it was refused.

### Budgets

| Budget | Default |
|---|---|
| Attempts per root cause | 4 |
| Total repair rounds | 10 |
| Total AI attempts | 12 |
| AI attempts per root cause | 3 |
| AI files per patch | 3 |
| AI changed lines per patch | 80 |

No progress means `NEEDS_HUMAN` (exit 4), not another attempt.

### Failure behavior

What a diagnosis looks like matters more than that one exists. The major edge on the reference corpus
once reported this:

```text
PLUGIN_OR_TOOLCHAIN     |  4 diagnostics | a build plugin or the toolchain itself failed;
                        |                | no source edit can repair it
MISSING_TYPE_OR_PACKAGE |  2 diagnostics | jakarta.servlet.http is not on the compile classpath
REMOVED_OR_RENAMED_API  | 18 diagnostics | Symbol unknown does not exist at the target version
APPLICATION_SPECIFIC    | 26 diagnostics | no framework-level cause explains this
```

Four clusters, fifty diagnostics, and almost all of it wrong. The first cluster is Maven's summary
line, not a toolchain fault, and the claim that no source edit can repair it ended the repair loop
after a single round. `Symbol unknown` is what happens when javac's continuation lines are parsed as
separate diagnostics, so the symbol name never reaches the cluster and every removed API collapses
into one useless bucket. Twenty-six of the "application specific" entries were banners and help
pointers.

The same edge now reports:

```text
REMOVED_OR_RENAMED_API  | 16 diagnostics | removed-api:EnableEurekaClient
                        |                | Symbol EnableEurekaClient does not exist at the
                        |                | target version
```

One cluster, correctly named, pointing at exactly the annotation Spring Cloud 2022.0 deleted. The
residual halved because the framing was never a diagnostic, and the repair budget is sized against
that number.

---

## 29. Agent 14 — Graph Rebuild and Graph Diff

### Purpose

Rebuild the static graph and assert that every change stayed inside the authorized scope — **before**
expensive validation runs.

### Why the position in the pipeline matters

Running this after tests would mean discovering an out-of-scope structural change an hour later. It
runs immediately after compile, so a scope violation blocks in seconds.

### Internal flow

```mermaid
flowchart TD
    COMP{"Did the edge compile?"} -->|no| PART["GRAPH_STATUS = PARTIAL<br/><i>full type attribution is impossible</i>"]
    COMP -->|yes| FULL["Full rebuild from the migration workspace"]
    PART --> FULL
    FULL --> DIFF["Diff against LAST_GOOD_GRAPH"]
    DIFF --> CHANGED["Changed file ids from the diff"]
    CHANGED --> AUTH{"Authorized by the plan,<br/>or changed via the ledger?"}
    AUTH -->|yes| OK["expected"]
    AUTH -->|no| CONSEQ{"Does this file depend on<br/>a file the ledger changed?"}
    CONSEQ -->|yes| EXP["Expected consequence<br/><i>a referenced type changed</i>"]
    CONSEQ -->|no| VIOL["SCOPE VIOLATION"]
    VIOL --> BLOCK["exit 3"]
    OK --> ADV
    EXP --> ADV["Advance LAST_GOOD_GRAPH"]
    ADV --> CP["Checkpoint: graph-verified"]

    style VIOL fill:#f8d7da,stroke:#721c24
    style PART fill:#fff3cd,stroke:#856404
```

### Full rebuild, always (ADR-005)

Incremental graph mutation is faster and is the classic source of silent drift: a missed invalidation
produces a subtly wrong graph, the diff then looks clean, and the scope gate passes something it
should have blocked. v1 always rebuilds. Incremental may be added later, behind a graph-equivalence
test proving `INCREMENTAL_GRAPH == FULL_REBUILD_GRAPH` by structural hash.

### Expected consequence versus scope violation

Not every graph change in an unmutated file is a violation. If `EmployeeService` references
`Employee`, and `Employee` was legitimately changed, then facts about `EmployeeService` change too.
The stage distinguishes the two by asking whether the file depends on something the ledger actually
changed, and reports expected consequences separately from violations.

### Degraded mode

When the edge does not compile, complete type attribution cannot be rebuilt. The graph is marked
`PARTIAL` and blind spot `BS-GRAPH-PARTIAL` is raised. **The harness does not claim a full graph
exists.**

### Outputs

`application-graph-current.json` · `graph-diff.json` · `scope-assertion.json` · `last-good-graph.json`

---

## 30. Agent 15 — Test Validation

### Purpose

Run the application test suite and classify every outcome against **two** baselines.

### Why two baselines

Against the sealed original alone, you cannot tell a regression introduced by this edge from one
introduced three edges ago. Against the previous edge alone, you cannot tell a migration regression
from pre-existing debt. Both are required.

### Internal flow

```mermaid
flowchart TD
    DEPTH{"Frozen depth<br/>requires tests?"} -->|no| SKIP["Skip, recorded with the reason"]
    DEPTH -->|yes| RUN["Run tests with coverage instrumentation"]
    RUN --> AGENT{"Did the agent attach?"}
    AGENT -->|no| RETRY["Re-run without it;<br/>coverage unavailable with the reason"]
    AGENT -->|yes| PARSE
    RETRY --> PARSE["Parse Surefire reports"]
    PARSE --> EACH["For each test case"]
    EACH --> FAIL{"Failing now?"}
    FAIL -->|no| PASSED["PASSED"]
    FAIL -->|yes| BASE{"Failing at the<br/>sealed baseline?"}
    BASE -->|yes| PRE["PRE_EXISTING_FAILURE"]
    BASE -->|"did not exist"| UNEX1["UNEXPLAINED"]
    BASE -->|no| APPR{"Signed approval<br/>for this expectation?"}
    APPR -->|yes| INT["INTENTIONALLY_CHANGED_CONTRACT"]
    APPR -->|no| FACT{"Explained by a<br/>VERIFIED migration fact?"}
    FACT -->|yes| EXPF["EXPECTED_FRAMEWORK_CHANGE"]
    FACT -->|no| PREV{"Failing at the<br/>previous edge?"}
    PREV -->|yes| CUM["CUMULATIVE_REGRESSION"]
    PREV -->|no| LOC["EDGE_LOCAL_REGRESSION"]

    CUM --> BLOCKG["BLOCKS"]
    LOC --> BLOCKG
    UNEX1 --> BLOCKG

    PASSED --> COV["Coverage comparison"]
    PRE --> COV
    COV --> DROP{"Unexplained drop above<br/>the policy threshold?"}
    DROP -->|yes| BLOCKG
    DROP -->|no| CP["Checkpoint: tested"]

    style BLOCKG fill:#f8d7da,stroke:#721c24
```

### Two classifications require evidence, not a story

- `EXPECTED_FRAMEWORK_CHANGE` requires a **VERIFIED** migration fact whose subject appears in the
  failure detail.
- `INTENTIONALLY_CHANGED_CONTRACT` requires a **signed approval** naming that test.

Without one of those, a new failure is a regression or `UNEXPLAINED`. Both block.

### The probable-cause field

Classification is about evidence; `probable_cause` is about what an operator reads first:

```json
{
  "test": "…EmployeeRepositoryTest#employeeSaveMethodTest",
  "outcome": "ERROR",
  "baseline_outcome": "ERROR",
  "classification": "PRE_EXISTING_FAILURE",
  "probable_cause": "INFRASTRUCTURE_UNAVAILABLE: MongoDB"
}
```

This does not soften the classification. It is the difference between reading "the migration broke
persistence" and "MongoDB is not running".

### Coverage gate (R29)

Compared against the sealed original **and** the previous successful edge. Default policy blocks an
unexplained drop above **5 percentage points**, configurable. When coverage cannot be compared, the
gate is reported as **not evaluated** — never as passed — and a gap is raised.

### Forbidden autonomous actions

The harness never adds `@Disabled`, deletes tests, weakens assertions, swallows exceptions, excludes
failing modules, lowers coverage gates, alters expected values, or modifies instrumentation scope to
hide a regression. None of that code exists in this stage, by construction.

### Reference corpus result

`EDGE-1-PREP-TEST: 12 test(s), {PASSED=10, PRE_EXISTING_FAILURE=2}` — the two failures being the
MongoDB-dependent repository tests, correctly attributed to absent infrastructure rather than to the
migration.

---

## 31. Agent 16 — Runtime Validation

### Purpose

Start the migrated application and observe it. Startup is **one observation**, not migration success
(R19).

### Internal flow

```mermaid
flowchart TD
    DEPTH{"Frozen depth<br/>requires runtime?"} -->|no| SKIP["Skip with the reason"]
    DEPTH -->|yes| ENV["Provision the environment;<br/>record mode and fingerprint"]
    ENV --> PKG["Package each module"]
    PKG --> SET["Derive isolation settings from<br/>what the module IS<br/><i>a discovery server keeps its client beans</i>"]
    SET --> START["Start the process"]
    START --> READY{"Reached readiness<br/>within the timeout?"}
    READY -->|no| FAIL["started=false<br/>+ blind spot with the failure reason<br/>+ every dimension unobservable"]
    READY -->|yes| PROBE["Probe: health, beans, conditions,<br/>env, mappings, configprops"]
    PROBE --> BIND["Capture bound-property provenance:<br/>canonical key, source, target field,<br/>bound, defaulted, deprecated, sensitive"]
    BIND --> SIL["Cross the static configuration graph<br/>with the runtime bound set"]
    SIL --> IGN{"Configured and bound at baseline,<br/>but not bound now?"}
    IGN -->|yes| PSI["PROPERTY_SILENTLY_IGNORED"]
    IGN -->|no| ENRICH
    PSI --> ENRICH["Enrich the runtime graph;<br/>every edge references its observation"]

    style FAIL fill:#f8d7da,stroke:#721c24
    style PSI fill:#fff3cd,stroke:#856404
```

### Binding provenance is the point

Recording values alone would miss the failure mode that matters. The harness records, per property:
canonical key, source file and source type, target type and field, whether it was **bound**, whether
it was **defaulted**, whether it is deprecated, its replacement, and whether it is sensitive.

That is what makes `PROPERTY_SILENTLY_IGNORED` detectable at all: the static layer has a `CONFIGURES`
edge, the runtime layer has no `ACTUALLY_BINDS_PROPERTY` edge, nothing failed, and the value simply
stopped taking effect.

Reference corpus: **426 bound properties** captured across 5 started modules.

### Failure is an observation

A module that does not start produces `started=false`, a failure reason, every dimension listed as
unobservable, and blind spot `BS-RUNTIME-<MODULE>`. It never produces an assumed pass.

---

## 32. Runtime graph enrichment

### Diagram 18 — Static and runtime graph lifecycle

```mermaid
flowchart TD
    subgraph BASE["Baseline"]
        G0["G0_BASELINE_STATIC<br/><i>Agent 03</i>"] --> O1["Agent 04 runtime observations"]
        O1 --> G0E["G0_BASELINE_ENRICHED"]
    end

    subgraph EDGE["Per migration edge"]
        GE["G_EDGE_STATIC<br/><i>Agent 14 full rebuild</i>"] --> DIFF["Graph Diff<br/><i>static only</i>"]
        DIFF --> SCOPE["Scope gate"]
        SCOPE --> O2["Agent 16 runtime observations"]
        O2 --> GEE["G_EDGE_ENRICHED"]
    end

    G0E -->|"OLD side"| CMP["Agent 17 differential"]
    GEE -->|"NEW side"| CMP
    CMP --> FINAL["FINAL_GRAPH + provenance"]

    style DIFF fill:#e7f3ff,stroke:#0366d6
    style GEE fill:#d4edda,stroke:#155724
```

Runtime edge types, each carrying an `evidenceRef`:

| Runtime edge | Static counterpart | What the difference means |
|---|---|---|
| `ACTUALLY_INJECTED` | `INJECTS` | The bean was actually created and wired |
| `ACTIVE_UNDER_PROFILE` | `ACTIVATED_BY_PROFILE` | The profile was actually active |
| `ACTUALLY_HANDLES_ENDPOINT` | `HANDLES_ENDPOINT` | The mapping was actually registered |
| `ACTUALLY_BINDS_PROPERTY` | `USES_CONFIG_PROPERTY` | The value actually took effect |
| `ACTUALLY_CALLS_EXTERNAL` | `CALLS_EXTERNAL_SERVICE` | The call actually happened |
| `ACTUALLY_PUBLISHES_TO` | `PUBLISHES_TO` | The message was actually published |
| `ACTUALLY_CONSUMES_FROM` | `CONSUMES_FROM` | The subscription actually existed |

**A runtime edge never overwrites a static edge.** Both layers coexist, so a report can say whether a
relationship was inferred, observed, or both.

Reference corpus: **441 runtime graph edges** added at edge 1.

---

## 33. Agent 17 — Differential Validation

> **The unit of comparison is the characterization scenario.** The same scenario object is executed
> against the original application and the migrated one, captured identically, normalized by the same
> versioned policy, and compared.
>
> Comparing "module plus Actuator dimension" — which is what this stage did while no scenario was ever
> executed — compares the shape of two contexts. It cannot see that an endpoint changed status, that
> an unauthenticated caller is now let through, or that a response lost a field. Module-level
> comparison remains only as a fallback for dimensions no scenario covered, and is labelled as the
> weaker evidence it is.
>
> **Every scenario observed on both sides is compared.** The edge plan's required dimensions say which
> dimensions this edge must *account for*; they do not say which measurements may be *looked at*. The
> plan's list is derived from the impact set, which is a statement about what the harness expects to
> change — filtering the comparison by it discards observations that were already frozen against OLD
> and already executed against NEW. A difference is blocking wherever it is found, and a comparison in
> a dimension the plan did not anticipate is flagged `plan_required_dimension: false` and reported
> under `dimensions_compared_beyond_plan`.
>
> What is captured is structural rather than literal: status, content type, header names, security
> header values, body shape and the sorted set of body field paths. A body that genuinely changed
> shape shows up; a body whose `timestamp` moved does not.
>
> A `SECURITY_AUTHORIZATION` scenario that differs without an explanation is classified `UNEXPECTED`
> rather than `UNEXPLAINED`, because the difference **is** the authorization decision changing.
>
> Every comparison carries `scenario_id`, `dimension`, `old_evidence_ref`, `new_evidence_ref`, the
> normalization policy hash, the differences, the classification, the explanation references and the
> approval reference when one applies.

### Purpose

Run identical scenarios against the original and migrated applications, and classify every
difference.

### Diagram 14 — OLD versus NEW

```mermaid
flowchart TD
    SC["Characterized scenario"] --> EQ{"Environment equivalence<br/>satisfied for this dimension?"}
    EQ -->|no| NC["NOT_COMPARED<br/><i>a number is not evidence</i>"]
    EQ -->|yes| SPLIT

    SPLIT --> OLD["Original application<br/><i>runtime-old</i>"]
    SPLIT --> NEW["Migrated application<br/><i>runtime-new</i>"]
    OLD --> O1["Observation A"]
    NEW --> O2["Observation B"]

    O1 --> N["Versioned normalizer<br/><i>explicit, hashed, reviewable</i>"]
    O2 --> N
    N --> CMP["Structural comparator"]
    CMP --> DIFFS{"Any differences?"}
    DIFFS -->|no| IDENT["IDENTICAL"]
    DIFFS -->|yes| EXPL{"Explained by a VERIFIED fact<br/>or a signed approval?"}
    EXPL -->|"all of them"| EXP["EXPECTED"]
    EXPL -->|"some of them"| UNEXP1["UNEXPLAINED"]
    EXPL -->|none| UNEXP2["UNEXPLAINED"]
    EXPL -->|"known defect class"| UNEXPECTED["UNEXPECTED<br/><i>migration defect</i>"]

    UNEXP1 --> BLOCK["BLOCKS (R21)"]
    UNEXP2 --> BLOCK
    UNEXPECTED --> BLOCK

    style NC fill:#fff3cd,stroke:#856404
    style IDENT fill:#d4edda,stroke:#155724
    style EXP fill:#d4edda,stroke:#155724
    style BLOCK fill:#f8d7da,stroke:#721c24
```

### Dimensions

`HTTP_API` · `SECURITY_AUTHORIZATION` · `SERIALIZATION` · `CONFIGURATION_BINDING` ·
`PERSISTENCE_STATE` · `QUERY_RESULT` · `TRANSACTION_EFFECT` · `CONTEXT_CAPABILITY` · `EVENT_MESSAGE` ·
`EXTERNAL_INTEGRATION` · `BATCH_RESULT` · `BUSINESS_RULE_OUTCOME`

### Normalization is explicit, versioned and hashed

Eleven rules, each with an id, a scope, an action and a rationale. The policy hash goes into the
evidence manifest.

| Rule | Applies to | Action | Why |
|---|---|---|---|
| NORM-001/002 | any `timestamp`, `date` | drop | differ by construction between two runs |
| NORM-003/004 | HTTP `Date`, `Server` | drop | generated per response; container identity is EXPECTED_TO_DIFFER |
| NORM-005/006 | `traceId`, `spanId` | drop | generated per request |
| NORM-007 | ports in URLs | normalize | the harness allocates a free port per side |
| NORM-008 | `uptime` | drop | not behaviour |
| NORM-009 | `springBootVersion` | drop | the thing being changed |
| NORM-010 | 32 to 64 hex strings | normalize | content hashes of payloads containing timestamps |
| NORM-011 | `generatedSql` | **demote to diagnostic** | literal SQL equality is not the contract |

**Nothing is dropped implicitly.** Rules that fire are listed on every comparison result.

### Persistence compares semantics, not SQL

The contract is query outputs, database and document state, transaction outcomes, lock behaviour and
schema behaviour. SQL text is retained as diagnostic evidence, because two statements can differ in
text and be identical in effect — and identical in text while behaving differently under a new
dialect.

### Reference corpus result

`EDGE-1-PREP-TEST: 6 comparison(s) across 1 dimension(s); {IDENTICAL=5, NOT_COMPARED=1}`

Five modules compared identically. The sixth is `NOT_COMPARED` because `discovery-service` did not
start on the OLD side, so there is nothing to compare against — reported as a gap, not as a pass.

---

## 34. Agent 18 — Approval

> **Decisions live in a store, not in this stage.** Validation and approval used to be mutually
> dependent: the differential stage needed to know whether an intentional-change decision existed, and
> the only thing that could tell it was the approval stage — which runs *after* validation and derives
> its gates from validation's own output. A legitimately recorded decision therefore could not
> influence the validation it was recorded for.
>
> `DecisionStore` breaks that cycle. Decisions are filed from outside — by an operator, a CI step, or
> an enterprise approval system through another adapter — into a directory outside the run workspace
> (`BOOTSHIFT_DECISIONS_DIR`, default `~/.bootshift/decisions`). Any stage may read them. Agent 18
> keeps its real job: discovering which gates exist, checking the decisions on file against them, and
> reporting what is still outstanding. It never writes a decision on a human's behalf, and a decision
> with no actor or no rationale is refused rather than loaded.
>
> **`integrity_hash`, not `signature`.** The field was previously called `signature`. It is an HMAC
> over the decision's fields computed with a key held alongside the store, so it detects modification
> and does **not** authenticate anybody — anyone who can write the file can recompute it. Calling it a
> signature claimed a property the harness cannot provide, and a reader trusting that name would have
> believed an approval was cryptographically attested when the actor was whatever string the caller
> typed. Each stored decision records its `actor_authentication` as `LOCALLY_ASSERTED`,
> `EXTERNAL_IDENTITY_PROVIDER` or `CRYPTOGRAPHICALLY_SIGNED`; an enterprise identity integration
> replaces the store implementation, not the port, and every caller keeps working.

### Purpose

Handle the judgments a machine must not self-authorize.

### Internal flow

```mermaid
flowchart TD
    IN["Findings from Agents 13–17<br/><i>unexplained differences, AI patches,<br/>broad repairs, evidence shortfalls</i>"] --> MAP["Map each finding to its gate"]
    MAP --> OPEN["Open an approval request<br/><i>request_id, gate, evidence refs</i>"]
    OPEN --> WAIT{"A signed decision<br/>exists for this request?"}
    WAIT -->|no| HUMAN["NEEDS_HUMAN — exit 4<br/><i>the run stops here</i>"]
    WAIT -->|yes| VAL{"Actor named AND<br/>rationale non-empty?"}
    VAL -->|no| THROW["ApprovalPort.record throws<br/><i>an unsigned decision is not a decision</i>"]
    VAL -->|yes| VERDICT{"Verdict"}
    VERDICT -->|APPROVED| REC["Record the decision;<br/>bind it to the findings it covers"]
    VERDICT -->|REJECTED| BLOCK["BLOCKED — exit 3"]
    REC --> LEDGER["Append to the ledger<br/>and the provenance graph"]
    LEDGER --> NEXT["The gate is closed for<br/>THESE findings only"]

    style HUMAN fill:#fff3cd,stroke:#856404
    style THROW fill:#f8d7da,stroke:#721c24
    style BLOCK fill:#f8d7da,stroke:#721c24
```

An approval is scoped to the exact findings it was raised for. A decision recorded for one edge's
`HIGH_RISK_AI_PATCH` does not close the same gate on the next edge — R9 would be meaningless if a
single sign-off could pre-authorize everything that followed it.

### Gates

| Gate | Raised when |
|---|---|
| `INTENTIONAL_SECURITY_CHANGE` | An unexpected or unexplained `SECURITY_AUTHORIZATION` difference |
| `PERSISTENCE_SCHEMA_CHANGE` | Unexplained persistence, query or transaction differences |
| `BUSINESS_OUTCOME_CHANGE` | Unexplained business-rule differences |
| `UNSUPPORTED_INTERNAL_STARTER` | Internal components with `UNKNOWN` compatibility |
| `BROAD_RESIDUAL_PATCH` | A repair patch beyond the normal budget |
| `HIGH_RISK_AI_PATCH` | Any accepted AI-authored repair |
| `DOCUMENTATION_CONFLICT` | Channels disagree — a `CONFLICTING` fact |
| `SHORT_HORIZON_TARGET` | The landing target has little support remaining |
| `NORMALIZATION_POLICY_CHANGE` | The normalization policy hash changed |
| `TEST_EXPECTATION_CHANGE` | A test expectation is intentionally changed |
| `EVIDENCE_SHORTFALL` | A dimension did not reach its required level |
| `CHECKPOINT_COLLAPSE` | A mandatory checkpoint was collapsed |
| `COVERAGE_REGRESSION` | The coverage gate triggered |

### A decision is a signed artifact

```json
{
  "decisionId": "DEC-00001",
  "request_id": "REQ-SHORT_HORIZON_TARGET-A3F91C2E",
  "gate": "SHORT_HORIZON_TARGET",
  "actor": "j.okafor",
  "role": "Principal Engineer, Platform",
  "verdict": "APPROVED",
  "rationale": "3.5.16 is the newest line with a GA Spring Cloud train. Landing here and revisiting when 2025.1 reaches GA is a smaller risk than landing on 4.x with no ecosystem support.",
  "policy_version": "1.0.0-eol-exception",
  "timestamp": "2026-09-10T06:12:44Z",
  "signature": "d41f8a…"
}
```

`ApprovalPort.record` **throws** on an empty rationale or a missing actor. The harness never approves
itself: decisions arrive from outside through `bootshift approve`, and outstanding gates produce
exit code 4.
---

## 35. Agent 19 — Evidence and Report

> **Final evidence aggregates every planned edge.** It used to read
> `output/13-build-repair/latest.json`, `output/15-test/latest.json` and so on. Each of those pointers
> names one directory: the one the stage published most recently. In a run with eight planned edges
> that is the eighth edge, and the report described it as though it described the migration — an edge
> that failed to compile halfway through was simply absent from the evidence.
>
> An explicit **edge index** (`edge-index.json` in the run workspace) records which directory each
> stage published into for each edge. `edge-evidence.json` then proves, per edge, that it was planned,
> transformed, compiled, graph verified, scope verified, tested where required, run where required,
> compared where required, that its residuals are accounted for and that a checkpoint exists.
>
> **Evidence levels are mechanical.** A dimension reaches `E4` when executed OLD/NEW scenario
> comparisons exist for it across the planned edges, every comparison that ran came out `IDENTICAL` or
> `EXPECTED`, and nothing required was left `NOT_COMPARED`. `E4` is never inferred from the
> application starting, from the test suite passing, or from the two graphs being equal. Each of those
> is evidence about something else.
>
> **Absence is never a pass.** A missing approval report means the approval stage never ran, which is
> not the same as no gate being open; it is recorded as an evidence shortfall. A required comparison
> that was `NOT_COMPARED` is a shortfall. A missing `final_source_tree_hash` is a shortfall, and
> export refuses it outright.

### The migration document

Alongside the report, Agent 19 writes **`MIGRATION_DOCUMENT.md`** — the end-to-end account of what
happened, generated from the artifacts on every run. The report answers "is this defensible?"; the
document answers the question a reviewer asks first: *what actually happened?*

It contains a table of contents and fifteen sections: the executive summary, how to read it, the
architecture **before** the migration with a Mermaid topology diagram, the architecture **after** it
with the corresponding diagram, what changed in the architecture and why, the migration path with the
reason each checkpoint exists and the toolchain chosen for it, a stage-by-stage account of all twenty
stages, per-edge detail, every change that was made with its authorizing facts and impacts, the
behavioural validation outcomes, evidence levels and coverage, residuals and gaps, human decisions,
provenance and integrity seals, and the honest limitations of that specific run.

It travels with the exported bundle, so a reviewer receiving only the export can still see what was
done rather than only whether it was defensible.

### Purpose

Assemble the evidence manifest, state coverage honestly, and produce the reports a reviewer, an
auditor and an operator each need.

### Internal flow

```mermaid
flowchart TD
    COLLECT["Collect every artifact from every stage"] --> HASH["Content-address each one<br/>SHA-256"]
    HASH --> MAN["Evidence manifest<br/><i>id, hash, producer, stage, run</i>"]
    MAN --> CLAIMS["Assemble claims"]
    CLAIMS --> LEVEL["Assign an evidence level per claim,<br/>from its supporting artifacts"]
    LEVEL --> REQ{"Level reached >=<br/>level required?"}
    REQ -->|yes| PUB["Publishable claim"]
    REQ -->|no| SHORT["EVIDENCE_SHORTFALL<br/><i>stated, not hidden</i>"]
    PUB --> COV["Coverage statement per dimension"]
    SHORT --> COV
    COV --> BS["Merge blind spots and gaps from every stage"]
    BS --> REP["migration-report.md<br/>evidence-manifest.json<br/>coverage-statement.json<br/>blind-spots.json<br/>claims.json"]
    REP --> VER["Self-verification: re-hash every artifact"]

    style SHORT fill:#fff3cd,stroke:#856404
```

### A claim is publishable only if its evidence reaches the required level

```java
public boolean isPublishable() {
    return levelReached.atLeast(levelRequired);
}
```

`EvidenceManifest.verify()` re-hashes every referenced artifact and returns one of:

| Verdict | Meaning |
|---|---|
| `VERIFIED` | Every artifact is present and its hash matches |
| `ARCHIVED` | Artifacts were pruned by the retention policy; the manifest itself is intact |
| `TAMPERED` | An artifact is present but its content no longer matches its recorded hash |

### The report says what was NOT covered

Every report carries a mandatory coverage statement per dimension. A dimension with no observation is
reported as `UNOBSERVED` with the reason — never omitted, and never implied to have passed. This is
R8 (documentation alone is insufficient) and R21 (unexplained differences block) expressed as a
document.

### Outputs

`evidence-manifest.json` · `claims.json` · `coverage-statement.json` · `migration-result.json` ·
`file-lineage.json` · `symbol-lineage.json` · `migration-report.md`

`blind-spots.json` and `gaps.json` are published by Agent 20, which is where the run-wide catalogs of
what could not be observed and what could not be explained are assembled. Residual coverage lives in
Agent 11's `residual-report.json`, next to the plan it constrains.

---

## 36. Agent 20 — Provenance Graph and Q&A

### Purpose

Make every artifact answerable: *why does this file look like this?*

### Internal flow

```mermaid
flowchart TD
    LED["Change Ledger"] --> N1["Change nodes"]
    PLAN["Edge plans"] --> N2["Plan nodes"]
    FACTS["Migration facts"] --> N3["Knowledge nodes"]
    IMP["Impact records"] --> N4["Impact nodes"]
    DEC["Decisions"] --> N5["Decision nodes"]
    OBS["Validation observations"] --> N6["Observation nodes"]

    N1 --> G["Provenance graph"]
    N2 --> G
    N3 --> G
    N4 --> G
    N5 --> G
    N6 --> G

    G --> Q["Query interface"]
    Q --> Q1["explain change CHG-…"]
    Q --> Q2["explain impact IMP-…"]
    Q --> Q3["lineage FILE-…"]
    Q --> Q4["gaps · blind-spots"]

    style G fill:#e7f3ff,stroke:#0366d6
```

### Answers are traversals, not prose

Every field below is read from a recorded artifact. Nothing is summarized or inferred.

```
$ bootshift explain change CHG-00007

  CHG-00007  [APPLIED]
  sequence       7
  event hash     9a3c…
  previous hash  4f1e…
  edge           EDGE-2-PATCH
  file           FILE-01M251AT6T287A1WRY6NWQTP81
  operation      MODIFY
  path before    report-service/pom.xml
  path after     report-service/pom.xml
  sha before     3160a4d…
  sha after      2afca57…
  agent          12-transformation
  provider       BOOTSHIFT_DETERMINISTIC bootshift-transformers
  recipe         maven.parent-version
  knowledge refs [MK-00031]
  impact refs    [IMP-00014]
  patch          patches/CHG-00007.patch
```

The reverse direction starts from a finding:

```
$ bootshift explain impact IMP-00014

  IMP-00014  [DEFINITELY_AFFECTED]
  subject        org.springframework.cloud.netflix.eureka.EnableEurekaClient
  fact           MK-00218 (API_REMOVED)
  path           configuaration-server/src/main/java/…/ConfiguarationServerApplication.java
  risk           HIGH
  confidence     0.95
  capped         false
  rationale      Graph node … references the subject with attribution RESOLVED
  blast radius   6
  dimensions     ["CONTEXT_CAPABILITY"]
```

A change whose `knowledge refs` are empty has no justifying fact, and a fact with no supporting
artifact could never have reached `E3` to authorize it in the first place. `gaps` and `blind-spots`
publish the run-wide catalogs of what could not be explained and what could not be observed.

---

## 37. Application graph model

### Diagram 4 — Typed multi-view application graph

```mermaid
flowchart TD
    subgraph SRC["Sources"]
        FS["Filesystem<br/><i>inventory</i>"]
        BLD["Build tools<br/><i>effective POM, dependency tree</i>"]
        AST["JavaParser + symbol solver<br/><i>real dependency classpath</i>"]
        RT["Runtime probes<br/><i>actuator</i>"]
    end

    FS --> CORE["Single typed graph<br/><i>nodes + edges, every edge evidenced</i>"]
    BLD --> CORE
    AST --> CORE
    RT --> CORE

    CORE --> V1["module view"]
    CORE --> V2["file view"]
    CORE --> V3["symbol view"]
    CORE --> V4["dependency view"]
    CORE --> V5["spring view"]
    CORE --> V6["configuration view"]
    CORE --> V7["persistence view"]
    CORE --> V8["endpoint view"]
    CORE --> V9["test view"]
    CORE --> V10["integration view"]

    style CORE fill:#e7f3ff,stroke:#0366d6
```

**Ten views, one graph.** A view is a projection, never a separate store — that is what keeps a
question asked in the endpoint view answerable in the persistence view.

### Node types

| Category | Node types |
|---|---|
| Structure | `MODULE`, `PACKAGE`, `FILE`, `RESOURCE` |
| Type | `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `ANNOTATION_TYPE` |
| Member | `METHOD`, `CONSTRUCTOR`, `FIELD` |
| Spring | `SPRING_BEAN`, `CONFIGURATION_CLASS`, `COMPONENT`, `CONTROLLER`, `SERVICE`, `REPOSITORY`, `CONFIGURATION_PROPERTIES` |
| Web | `ENDPOINT`, `FILTER`, `INTERCEPTOR`, `EXCEPTION_HANDLER` |
| Data | `ENTITY`, `DOCUMENT`, `REPOSITORY_INTERFACE`, `QUERY`, `TRANSACTION_BOUNDARY` |
| Config | `PROPERTY_KEY`, `PROFILE`, `CONFIG_SOURCE` |
| Integration | `EXTERNAL_SERVICE`, `MESSAGE_TOPIC`, `MESSAGE_LISTENER`, `SCHEDULED_TASK` |
| Test | `TEST_CLASS`, `TEST_METHOD`, `TEST_FIXTURE` |
| Build | `DEPENDENCY`, `PLUGIN`, `BOM`, `PROPERTY_DEFINITION` |
| Security | `SECURITY_RULE`, `AUTHENTICATION_PROVIDER`, `SECRET_REFERENCE` |

### Edge types

Static: `CONTAINS`, `DECLARES`, `EXTENDS`, `IMPLEMENTS`, `CALLS`, `REFERENCES_TYPE`, `ANNOTATED_WITH`,
`INJECTS`, `HANDLES_ENDPOINT`, `USES_CONFIG_PROPERTY`, `CONFIGURES`, `ACTIVATED_BY_PROFILE`,
`PERSISTS_TO`, `QUERIES`, `PARTICIPATES_IN_TRANSACTION`, `TESTS`, `DEPENDS_ON`, `MANAGED_BY_BOM`,
`CALLS_EXTERNAL_SERVICE`, `PUBLISHES_TO`, `CONSUMES_FROM`, `SECURED_BY`, `REFERENCES_SECRET`.

Runtime-observed: the seven `ACTUALLY_*` / `ACTIVE_UNDER_PROFILE` edges of §32.
`EdgeType.isRuntimeObserved()` is what keeps the two layers separable in every query — R18.

### Every edge carries evidence

```json
{
  "edgeId": "EDGE-…",
  "type": "INJECTS",
  "from": "…EmployeeController",
  "to": "…EmployeeService",
  "evidence": "AST_FIELD_INJECTION",
  "confidence": 1.0,
  "source_layer": "STATIC",
  "attributes": { "field": "employeeService", "synthetic": false }
}
```

An unresolved call recovered by name heuristics is recorded at `confidence: 0.5` with
`evidence: "AST_NAME_HEURISTIC"`. Lombok-generated members are recorded with
`"synthetic": true, "generated_by": "lombok"`. Nothing pretends to be more certain than it is.

### Blast radius

```
$ bootshift graph blast-radius --symbol com.aura.vihanga.employeeservice.service.EmployeeService --depth 3
```

Reverse traversal over `CALLS`, `REFERENCES_TYPE`, `INJECTS`, `EXTENDS`, `IMPLEMENTS` and `TESTS`,
returning transitive dependents at each depth. This is the input to impact scoring and test
selection.

### Two hashes, and why both exist

| Hash | Includes | Comparable across runs |
|---|---|---|
| `structural_hash` | Nodes, edges **and their identities** | **No** |
| `content_hash` | Nodes and edges named by fully qualified name; every run-scoped id removed | **Yes** |

`FILE_ID`s are freshly allocated ULIDs on every run, and node ids are derived from them. A hash over
identities therefore differs between two runs over a byte-identical repository — which is exactly what
makes it useful for detecting identity churn *within* a run, and exactly what makes it misleading in
a report. Two runs here produced `839 nodes / 1852 edges / attribution 0.6858` and structural hashes
`a362dad34459` and `55ee8290493a`; a reader comparing those would conclude the application changed
when nothing had.

`content_hash` is what two runs should be compared on, and `GraphHashTest` pins down both
directions: the content hash is stable across runs, and it still changes when a node is added or an
edge is rewired.

### Reference corpus

**839 nodes / 1852 edges / 239 symbols**, type attribution **0.6858**. The graph verification report
additionally cross-checks parsed compilation units against `FILE`-derived type declarations and
reports any shortfall as a gap.

---

## 38. File identity and lineage

### Diagram 5 — File identity lifecycle

```mermaid
flowchart TD
    SCAN["Inventory scan"] --> KNOWN{"Path already registered?"}
    KNOWN -->|yes| SAME{"Content hash unchanged?"}
    SAME -->|yes| R1["1. Same path + same hash<br/>→ same FILE_ID"]
    SAME -->|no| R2["2. Same path, new hash<br/>→ same FILE_ID, new version"]
    KNOWN -->|no| GIT{"Git rename detection<br/>finds a source?"}
    GIT -->|yes| R3["3. Renamed → same FILE_ID<br/>renameSource=GIT"]
    GIT -->|no| HASH{"An absent file has<br/>this exact hash?"}
    HASH -->|yes| R4["4. Moved → same FILE_ID<br/>renameSource=CONTENT_HASH"]
    HASH -->|no| SIM{"Similarity above<br/>the policy threshold?"}
    SIM -->|yes| R5["5. Similar → same FILE_ID<br/>renameSource=SIMILARITY"]
    SIM -->|no| NEW["New allocation<br/>FILE-&lt;ULID&gt;"]

    R1 --> REG["File registry"]
    R2 --> REG
    R3 --> REG
    R4 --> REG
    R5 --> REG
    NEW --> REG
    REG --> ABSENT{"Registered file not<br/>seen in this scan?"}
    ABSENT -->|yes| DEL["status = DELETED<br/><i>the id is never reused</i>"]

    style NEW fill:#d4edda,stroke:#155724
    style DEL fill:#f8d7da,stroke:#721c24
```

### The rules

- **R2**: the inventory agent, and only the inventory agent, allocates `FILE_ID`.
- **R3**: `FILE_ID` is not a path and not a content hash. A file that moves keeps its identity; two
  files with identical content have different identities.
- Reattachment is attempted **in the order 1 → 5**. The first rule that matches wins, and the rule
  that matched is recorded on the record as `renameSource`, so a reviewer can see *why* the harness
  believes two paths are the same file.
- An id is **never reused**. A deleted file's record stays in the registry with `status: DELETED`.

### Split, merge and delete lineage

| Event | Recorded as |
|---|---|
| Split | The original keeps its id and gains `splitInto: [FILE-…, FILE-…]`; each new part records `splitFrom` |
| Merge | Each source records `mergedInto`; the survivor records `mergedFrom: [FILE-…, …]` |
| Delete | `status: DELETED`, `deletedAt`, and the full version history retained |

That is what lets `provenance why-changed` answer questions about a file that no longer exists at the
path a reviewer remembers.

### Sealing

`FileRegistry.seal()` freezes the registry for the run. After the seal, an attempt to allocate a new
id or mutate a record throws. This is R23 — the sealed baseline is immutable.

The registry publishes two hashes for the same reason the graph does. `seal_hash` covers
`FILE_ID : path : content` and is therefore run-scoped; it is what makes a post-seal identity change
detectable. `content_manifest_hash` covers `path : content` only, so two runs over the same
repository produce the same value and a difference means the repository changed rather than that a
new run allocated new identifiers.

---

## 39. Migration edge model

### Diagram 8 — Migration path decomposition

```mermaid
flowchart LR
    S["Source state<br/>2.6.8 / Java 8"] --> E1
    subgraph PATH["Migration path"]
        E1["EDGE-1<br/>PREPARATORY<br/>test framework"] --> E2["EDGE-2<br/>PATCH<br/>2.6.8 → 2.6.15"]
        E2 --> E3["EDGE-3<br/>MINOR<br/>2.6 → 2.7"]
        E3 --> E4["EDGE-4<br/>PREPARATORY<br/>deprecation cleanup"]
        E4 --> E5["EDGE-5<br/>MAJOR<br/>2.7 → 3.0<br/><i>javax → jakarta</i>"]
        E5 --> E6["EDGE-6<br/>MINOR<br/>3.0 → 3.2"]
        E6 --> E7["EDGE-7<br/>MINOR<br/>3.2 → 3.4"]
        E7 --> E8["EDGE-8<br/>MINOR<br/>3.4 → 3.5.16"]
    end
    E8 --> T["Landing target<br/>3.5.16 / Java 21"]

    style E5 fill:#f8d7da,stroke:#721c24
    style E1 fill:#e7f3ff,stroke:#0366d6
    style E4 fill:#e7f3ff,stroke:#0366d6
```

### Edge classes

| Class | Meaning | Validation depth floor |
|---|---|---|
| `PREPARATORY` | Changes that make a later edge possible, at the current version | tests |
| `PATCH` | Within a minor line | build + tests |
| `MINOR` | Across a minor line | build + tests + runtime |
| `MAJOR` | Across a major line | build + tests + runtime + differential |
| `PLATFORM` | Java baseline change | build + tests + runtime |
| `ECOSYSTEM` | Spring Cloud train change | build + tests + runtime |

### An edge is a self-contained unit

Every edge carries its **own** target state, its **own** Java level and its **own** Spring Cloud
train — not the landing target's. Two defects the harness caught in its own construction make the
point:

- Applying the landing Spring Cloud train (`2025.0.3`) to a 2.7 edge removed `@EnableEurekaClient`,
  which does not exist in that train, producing 33 compile errors. The planner now resolves the train
  for the edge's own Boot line, and **omits the managed-version transformation entirely** when no GA
  train targets that line, rather than installing a mismatched one.
- Applying the landing Java level (21) to a patch edge changed the compiler target for a reason the
  edge does not carry. The Java level now moves at major boundaries only, and each edge otherwise
  uses the highest installed JDK its own Boot line supports.

### Train resolution is artifact-verified

The harness does not read a compatibility table. For every candidate Spring Cloud release it fetches
`spring-cloud-dependencies-<version>.pom` from Maven Central and reads the declared
`spring-boot-starter-parent` version — which is the **minimum** Boot version that train supports. It
then selects the newest train whose parent line is at or below the target line **within the same Boot
major**, marking the fact `VERIFIED` on an exact line match and `ADVISORY` when inferred:

```
2.7 -> 2021.0.9  (parent 2.6 — newest train at or below 2.7 within major 2)   ADVISORY
3.0 -> 2022.0.5  (parent 3.0 — matches this line exactly)                     VERIFIED
3.1 -> 2022.0.5  (parent 3.0 — newest train at or below 3.1 within major 3)   ADVISORY
3.2 -> 2023.0.5  (parent 3.2 — matches this line exactly)                     VERIFIED
3.3 -> 2023.0.6  (parent 3.3 — matches this line exactly)                     VERIFIED
3.4 -> 2024.0.3  (parent 3.4 — matches this line exactly)                     VERIFIED
3.5 -> 2025.0.3  (parent 3.5 — matches this line exactly)                     VERIFIED
4.0 -> None      (no train declares a parent within Boot major 4)             BLOCKING
4.1 -> None      (no train declares a parent within Boot major 4)             BLOCKING
```

The last two lines are a real, checkable statement about the ecosystem as of the run date — and the
reason the harness refuses to land this corpus on Boot 4.x.

---

## 40. Composite transformation and checkpoint reconciliation

### Why a checkpoint cannot be silently collapsed (R17)

A migration path is decomposed so that each edge is independently validated. Collapsing two edges
into one — because the tooling happens to support the combined jump — discards exactly the
information the decomposition was for: which edge introduced a regression.

The harness permits a **composite transformation** only when all of the following hold:

1. The composite is explicitly declared in the plan, with the constituent edges named.
2. Each constituent edge's transformation is still recorded individually in the ledger.
3. A **reconciliation record** is produced showing, per constituent edge, the state that edge would
   have reached and the state actually reached.
4. Validation runs at the **maximum** depth of the constituent edges, never the minimum.
5. A `CHECKPOINT_COLLAPSE` approval gate is raised if any constituent edge's mandatory checkpoint was
   not independently observable.

### Reconciliation record

```json
{
  "composite_id": "COMP-EDGE-6-7",
  "constituent_edges": ["EDGE-6-MINOR-3.0-3.2", "EDGE-7-MINOR-3.2-3.4"],
  "reason": "The recipe estate applies both minor migrations as one atomic unit",
  "per_edge_reconciliation": [
    {
      "edge": "EDGE-6-MINOR-3.0-3.2",
      "expected_state": "3.2.x",
      "observed_independently": false,
      "checkpoint": "COLLAPSED",
      "gate": "CHECKPOINT_COLLAPSE"
    }
  ],
  "validation_depth": "DIFFERENTIAL",
  "depth_source": "MAX(constituent edges)"
}
```

The composite is not forbidden. It is **recorded as a reduction in evidence**, gated for approval,
and reflected in the coverage statement.

---

## 41. Adaptive validation

### Depth is computed, then frozen (R14)

```
DEPTH = MAX(
    edge class floor,      // MAJOR ⇒ differential
    residual risk,         // AI-repaired code ⇒ differential
    impact severity,       // HIGH impact on security/persistence ⇒ differential
    policy minimum         // production policy ⇒ at least runtime
)
```

Once computed for an edge, the depth is **frozen** into the plan before any mutation. Nothing that
happens later — a slow test suite, a flaky container, a tight schedule — can lower it. Lowering depth
requires a new run with a new plan and a recorded decision.

### The ladder

```mermaid
flowchart LR
    D0["NONE<br/><i>static only</i>"] --> D1["BUILD<br/><i>compiles</i>"]
    D1 --> D2["TESTS<br/><i>+ suite + coverage</i>"]
    D2 --> D3["RUNTIME<br/><i>+ starts + binds</i>"]
    D3 --> D4["DIFFERENTIAL<br/><i>+ OLD vs NEW</i>"]

    style D4 fill:#d4edda,stroke:#155724
```

### What raises depth

| Trigger | Raised to |
|---|---|
| Edge class `MAJOR` | `DIFFERENTIAL` |
| Any accepted AI-authored repair on the edge | `DIFFERENTIAL` |
| `HIGH` impact touching security, persistence or transactions | `DIFFERENTIAL` |
| Namespace relocation applied (`javax` → `jakarta`) | `DIFFERENTIAL` |
| Configuration property migration affecting a bound key | `RUNTIME` |
| Policy `production` | at least `RUNTIME` |

### What depth being unreachable means

If depth `RUNTIME` is required and no module starts, the edge does **not** pass at a lower depth. It
produces an `EVIDENCE_SHORTFALL` gate and blind spots naming each unobservable dimension. A skipped
level is always reported with its reason — R21 in its validation form.
---

## 42. Evidence levels

### Diagram 15 — Evidence ladder and claim publication

```mermaid
flowchart TD
    E0["E0 — ASSERTED<br/><i>a statement with no artifact</i>"] --> E1
    E1["E1 — DOCUMENTED<br/><i>vendor documentation snapshot</i>"] --> E2
    E2["E2 — STATIC<br/><i>source, AST or build model</i>"] --> E3
    E3["E3 — ARTIFACT_VERIFIED<br/><i>the published artifact was inspected</i>"] --> E4
    E4["E4 — EXECUTED<br/><i>it compiled / the tests ran</i>"] --> E5
    E5["E5 — DIFFERENTIALLY_VERIFIED<br/><i>OLD and NEW behaved the same</i>"]

    CLAIM["Claim"] --> REQ["levelRequired<br/><i>set by the claim's kind</i>"]
    CLAIM --> GOT["levelReached<br/><i>from its supporting artifacts</i>"]
    REQ --> CMP{"reached >= required?"}
    GOT --> CMP
    CMP -->|yes| PUBOK["published"]
    CMP -->|no| PUBNO["EVIDENCE_SHORTFALL<br/><i>the claim is reported as unproven</i>"]

    style E0 fill:#f8d7da,stroke:#721c24
    style E5 fill:#d4edda,stroke:#155724
    style PUBNO fill:#fff3cd,stroke:#856404
```

### The level a claim needs depends on what it authorizes

| Claim kind | Minimum level | Why |
|---|---|---|
| "This version exists" | `E3` artifact-verified | Documentation lags releases; R6 |
| "This API was removed" | `E3` artifact-verified | `javap` on the published jar, not a migration guide |
| "This property was renamed" | `E3` artifact-verified | `spring-configuration-metadata.json` in the published jar |
| "This edge compiles" | `E4` executed | The build tool said so; R5 |
| "This edge does not regress the suite" | `E4` executed | Surefire reports |
| "Behaviour is unchanged" | `E5` differentially verified | Only OLD-vs-NEW can support it |
| "This component is compatible" | `E3` + policy | R20 — unknown internal components are never assumed compatible |

`E0` and `E1` never authorize a mutation. R6 and R8, made mechanical: `Claim.isPublishable()` is the
only gate that lets a statement into the report as fact, and the two documentation-only levels sit
below every threshold that matters.

### Coverage statements

Every dimension gets a statement, whether or not it was observed:

```json
{
  "dimension": "SECURITY_AUTHORIZATION",
  "level_required": "E5",
  "level_reached": "E2",
  "covered": false,
  "reason": "No characterized security scenario could be executed: the OLD side did not expose an authenticated endpoint under the MANAGED environment",
  "blind_spots": ["BS-DIFF-SECURITY-UNOBSERVED"]
}
```

An uncovered dimension is a first-class report entry. The harness never reports on twelve dimensions
by describing the four it managed to observe.

---

## 43. OSS tooling

### The strict-OSS rule (R7)

Every runtime dependency must be under a permissive or weak-copyleft OSS licence that has been
verified. **Unknown licence is treated as blocked**, not as permitted-until-proven-otherwise.

Explicitly forbidden as runtime dependencies:

- `rewrite-spring` and the wider source-available recipe estates
- MSAL and other proprietary identity SDKs
- Proprietary transformation or analysis engines
- Proprietary hosted LLM APIs

The rule is not aesthetic. A harness whose evidence chain depends on a component nobody may inspect
cannot claim its conclusions are reproducible.

### What the harness actually depends on

| Component | Version | Licence | Role |
|---|---|---|---|
| Jackson (`databind`, `jsr310`, `dataformat-xml`) | 2.17.2 | Apache-2.0 | Canonical JSON and XML models |
| Picocli | 4.7.6 | Apache-2.0 | CLI |
| JGit | 6.10.0 | BSD-3-Clause (EDL) | Snapshots, checkpoints, rename detection, patch series |
| JavaParser (`symbol-solver-core`) | 3.26.2 | Apache-2.0 / LGPL-3.0 dual | AST and symbol resolution |
| networknt `json-schema-validator` | 1.5.1 | Apache-2.0 | Artifact schema conformance |
| SLF4J (`api`, `simple`) | 2.0.13 | MIT | Logging facade |
| JUnit 5 | 5.10.3 | EPL-2.0 | Harness tests |
| AssertJ | 3.26.3 | Apache-2.0 | Harness test assertions |
| ArchUnit | 1.3.0 | Apache-2.0 | Architecture rules as tests |

Nine rows, twelve artifacts: the Jackson and SLF4J rows each cover more than one coordinate, which
is why the OSS gate reports twelve components rather than nine.

JavaParser is dual-licensed; the harness elects **Apache-2.0**, recorded in
`policies/license/license-policy.json`.

### Tools invoked, not linked

Maven, Gradle, the JDK toolchain (`javap`, `jar`, `java`), JaCoCo and OpenRewrite's **core** engine
are invoked as external processes through the command allowlist. `javap` in particular is not
optional decoration: it is the only channel that can distinguish "documented as deprecated" from
"actually gone", which is why `API_REMOVED` requires evidence level `E3` and Agent 08 runs a
published-bytecode diff over every directly-declared coordinate whose managed version moves. That keeps the harness's own
dependency surface small and keeps the build tools authoritative (R4) rather than re-implemented.

**OpenRewrite core only, and it actually runs.** `OpenRewriteCoreProvider` is a real transformation
provider, not a probe. Its predecessor asked whether `org.openrewrite.Recipe` was loadable, declared
a capability when it was, and then returned no changes when asked to apply anything — so the
capability registry claimed coverage the transformation stage could not deliver, which is worse than
declaring the tool absent.

The declared modules are `rewrite-core`, `rewrite-java`, `rewrite-java-21`, `rewrite-maven`,
`rewrite-yaml` and `rewrite-properties`, all Apache-2.0, all listed in the harness bill of materials
that Agent 00 gates. Four Bootshift recipe ids map onto concrete OpenRewrite recipes:

| Bootshift recipe | OpenRewrite recipe | Used for |
|---|---|---|
| `openrewrite.java.change-package` | `org.openrewrite.java.ChangePackage` | the `javax` → `jakarta` relocation |
| `openrewrite.java.remove-annotation` | `org.openrewrite.java.RemoveAnnotation` | annotations deleted at the target version |
| `openrewrite.maven.change-parent-pom` | `org.openrewrite.maven.ChangeParentPom` | the parent POM version |
| `openrewrite.maven.change-property` | `org.openrewrite.maven.ChangePropertyValue` | the declared Java level |

Four constraints shape the integration:

- **OpenRewrite is not the orchestrator.** Bootshift decides which recipe runs on which edge, from
  verified migration facts, and hands OpenRewrite one named transformation over one explicit file set.
- **It never writes to the migration workspace.** Sources are parsed into an in-memory model; results
  come back as text and go through `FileMutationGateway` like everything else.
- **It never bypasses evidence.** Each change carries the engine version, module versions, the recipe
  class, the input and output hashes, the edge id, and the knowledge and impact references.
- **Strict OSS is enforced, not assumed.** If a source-available Spring recipe estate is loadable, the
  provider reports `LICENSE_BLOCK` and refuses to run *at all* — not selectively.

The forbidden list has exactly one definition, in `LicensePolicy`. The provider probes against it, an
ArchUnit rule forbids compiling against it, and Agent 00 probes the runtime classpath for it. Three
copies of that list is how one of them silently stops matching the other two.

Where OpenRewrite's Java module is available the planner schedules the namespace relocation through
`ChangePackage` instead of the harness's own textual transformer. The difference is not cosmetic: a
textual rewrite matches the token wherever it appears, including inside comments and string literals,
while `ChangePackage` operates on a parsed model and rewrites declarations, imports and type
references only. When the module is absent the harness transformer is used and the reduced precision
is recorded in the plan.

### License enforcement

There is no separate licence command, because the gate is not optional. Agent 00 runs it **before
any other stage**, and a failure stops the run — a harness with an unverified dependency cannot make
verifiable claims. The verdict is published as an artifact:

```
$ cat output/00-bootstrap/<timestamp>/oss-license-gate.json

  "gate": "PASSED",
  "allowlist": ["APACHE-2.0", "MIT", "BSD-2-CLAUSE", "BSD-3-CLAUSE", "EPL-2.0", …],
  "denylist":  ["PROPRIETARY", "SOURCE-AVAILABLE", "MSAL",
                "MODERNE SOURCE AVAILABLE LICENSE", "BUSL-1.1", "ELASTIC LICENSE", …],
  "forbidden_artifacts": ["org.openrewrite.recipe:rewrite-spring",
                          "org.openrewrite.recipe:rewrite-migrate-java-spring",
                          "io.moderne:moderne-recipe", "io.moderne.recipe:rewrite-spring"],
  "findings": [
    { "component": "com.fasterxml.jackson.core:jackson-databind",
      "version": "2.17.2",
      "declaredLicense": "Apache-2.0",
      "verdict": "ALLOWED",
      "reason": "License matches allowlist entry APACHE-2.0" },
    …
  ]
```

On the reference corpus the gate reports `PASSED` for **12 of 12** harness components, with none
unknown and none forbidden.

The same policy is applied to the **AI model** when a local model is enabled: the model's own licence
is checked separately from its runtime's, because a permissively-licensed server does not make its
weights permissively licensed.

---

## 44. GenAI role and boundaries

### The three rules that matter

- **R9 — AI cannot authorize.** No AI output is ever an authorization for a change.
- **R10 — AI is optional.** The harness runs end-to-end with AI disabled, and does so in CI.
- **R11 — Single writer.** AI output reaches the filesystem only through `FileMutationGateway`, on
  the same 13-step path as every other change.

### Where AI is permitted, and where it is not

| Permitted | Forbidden |
|---|---|
| Suggesting a repair for a compiler diagnostic | Deciding a version is compatible |
| Clustering diagnostics by probable root cause | Authorizing a mutation |
| Drafting a rationale for a human to review | Approving its own patch |
| Summarizing a documentation snapshot | Classifying a test failure as expected |
| Proposing characterization scenarios | Deciding a difference is acceptable |
| Explaining a graph query result | Writing to disk directly |

### The AI repair path

```mermaid
flowchart TD
    DIAG["Unresolved compiler diagnostic cluster"] --> EN{"AI enabled by policy?"}
    EN -->|no| STOP["Residual recorded; edge blocks"]
    EN -->|yes| CTX["Build context:<br/>diagnostic + surrounding source +<br/>VERIFIED facts. NO SECRETS."]
    CTX --> RED["Redact through SensitiveValues"]
    RED --> LLM["Local OSS provider<br/><i>loopback only</i>"]
    LLM --> PATCH["Proposed patch"]
    PATCH --> G1{"Inside the authorized<br/>edge scope?"}
    G1 -->|no| REJ["REJECTED"]
    G1 -->|yes| G2{"Parses?"}
    G2 -->|no| REJ
    G2 -->|yes| G3{"Compiles?"}
    G3 -->|no| REJ
    G3 -->|yes| G4{"Tests still pass?"}
    G4 -->|no| REJ
    G4 -->|yes| G5{"Within the patch budget?"}
    G5 -->|no| GATE1["BROAD_RESIDUAL_PATCH gate"]
    G5 -->|yes| ACC["Applied via the gateway,<br/>author=AI, ledger-recorded"]
    ACC --> GATE2["HIGH_RISK_AI_PATCH gate<br/>+ depth raised to DIFFERENTIAL"]

    style REJ fill:#f8d7da,stroke:#721c24
    style GATE2 fill:#fff3cd,stroke:#856404
```

Every attempt — accepted or rejected — is recorded (R12). A rejected AI patch is evidence about the
migration, not noise to discard.

### The provider

`LocalOssAIProvider` refuses any endpoint that is not loopback. There is no code path in the harness
that sends repository content to a remote inference service. `AiBoundaryTest` asserts this with seven
tests, including one that fails the build if a non-loopback endpoint is accepted.

### Attribution

Any file touched by an AI-authored patch carries `author: AI` on its ledger event, appears in
`ai-attributed-changes.json`, raises `HIGH_RISK_AI_PATCH`, and raises the edge's validation depth to
`DIFFERENTIAL`. A reviewer can always ask "what here was written by a model?" and get a complete
answer.

---

## 45. Security and threat model

### The core assumption

**The repository under analysis is untrusted input.** It contains code the harness will compile and
execute. Everything below follows from that.

### Threats and controls

| Threat | Control |
|---|---|
| Repository build script executes arbitrary commands | Command allowlist in `ProcessRunner`; only build tools and JDK tools may be launched |
| A long-running or non-terminating build | Per-invocation timeout; process tree destroyed on expiry |
| Output flooding to exhaust memory or disk | Output capped per invocation, with truncation recorded |
| Repository code exfiltrates data over the network | Egress allowlist in `HttpFetcher`; only declared artifact and documentation hosts |
| Path traversal via a crafted file path | Every path is normalized and asserted to remain inside the workspace root — a check that says nothing about symlinks, which is why the next row is separate |
| Symlink escape from the workspace | The write target is rejected if it is a symlink, and the nearest existing ancestor's `toRealPath()` must resolve inside the real workspace root |
| Mutation of the user's actual source tree | `./src/` is copied, never written; the original workspace is read-only |
| Bypassing the single writer | `detectBypass()` — static scan for direct write APIs outside the gateway, plus a runtime manifest re-hash |
| Tampering with recorded history | Hash-chained append-only ledger; `verify` detects modification, insertion, deletion, reordering and truncation |
| Secrets leaking into evidence or prompts | `SensitiveValues` redaction at every boundary; `NEVER_STORE_PLAINTEXT` |
| A malicious or compromised AI response | Deterministic pre-apply gates: scope, parse, compile, tests, budget |
| Supply-chain injection through a recipe estate | Strict-OSS policy; `OpenRewriteCoreProbe` blocks non-core recipe estates |

### Isolation

Execution happens in a workspace outside the repository, under the OS temp root:

```
%TEMP%/bootshift-workspaces/<runId>/
    original/      read-only sealed copy
    runtime-old/   writable copy for OLD-side execution
    migration/     the mutable working tree
    runtime-new/   writable copy for NEW-side execution
```

The user's `./src/` is read once, at snapshot time, and never written.

### What is out of scope

The harness does not sandbox at the kernel level. It does not defend against a repository that
attacks the host through a JDK zero-day, nor against a malicious Maven plugin that the build already
trusted before the harness ran. Running against an untrusted repository should be done in a
disposable environment. This limitation is stated rather than papered over.

---

## 46. Sensitive data handling

### The rule

**A sensitive value is represented by metadata. The value itself is never stored.**

```json
{
  "secretRef": "SECRET-8F2A…",
  "kind": "MONGODB_CONNECTION_STRING",
  "location": { "fileId": "FILE-…", "line": 3, "key": "spring.data.mongodb.uri" },
  "evidence_policy": "NEVER_STORE_PLAINTEXT",
  "shape": { "scheme": "mongodb+srv", "has_credentials": true, "host_class": "EXTERNAL_MANAGED" },
  "detector": "URI_WITH_INLINE_CREDENTIALS",
  "confidence": 1.0
}
```

`shape` is deliberately structural. It is enough to tell a reviewer *what kind of thing* is there and
*why it matters*, and not enough to use.

### Where redaction applies

Every boundary, without exception: artifacts, the ledger, logs, telemetry, AI prompts, exported
bundles and reports. `SensitiveValues.describe()` returns metadata only, and there is no method on
that class that returns a detected plaintext secret.

### Detected on the reference corpus

The harness found **MongoDB Atlas credentials committed in four `application.properties` files**.
That finding appears in the report as four `SECRET_REFERENCE` nodes with locations and shapes. The
credentials themselves do not appear anywhere in `output/`, and `grep` over the entire artifact tree
confirms it — which is exactly the check `SchemaConformanceTest` performs against the sensitive-value
schema.

### Keyed hashes

Where policy explicitly permits correlation across runs, a **keyed** hash (HMAC with a
deployment-local key) may be stored so that "the same secret appears in these five files" is
answerable. Unkeyed hashes of secrets are forbidden: they are trivially reversible for
low-entropy values.

---

## 47. Retention

### Tiers

| Tier | Retained | Default |
|---|---|---|
| `EVIDENCE_MANIFEST` | Manifest, ledger, decisions, reports | Indefinite |
| `PRIMARY_ARTIFACTS` | Stage JSON artifacts | 365 days |
| `LARGE_BLOBS` | Documentation snapshots, jars, build logs | 90 days |
| `WORKSPACES` | Execution workspaces | 7 days |

### Pruning is honest

When blobs are pruned, the manifest is **not** rewritten. `EvidenceManifest.verify()` then returns
`ARCHIVED` rather than `VERIFIED`, and the report states which artifacts are no longer present.
Silently dropping a reference so a later verification looks clean would defeat the purpose of the
manifest.

`TAMPERED` and `ARCHIVED` are distinct verdicts for exactly this reason: absence is not the same as
alteration.

---

## 48. Observability

### Structured telemetry

Every stage emits structured events with a stable shape:

```json
{
  "ts": "2026-09-10T06:06:33.116Z",
  "run_id": "RUN-01M2550D2MSSFRN1FJD72QAMK3",
  "stage": "03-graph",
  "event": "STAGE_COMPLETED",
  "outcome": "SUCCESS",
  "duration_ms": 3471,
  "metrics": { "nodes": 839, "edges": 1852, "symbols": 239, "attribution": 0.6858 },
  "artifacts": 17
}
```

Emitted to `output/<stage>/<timestamp>/telemetry.jsonl` and to the run-level
`output/telemetry.jsonl`. Values pass through `SensitiveValues` before emission.

### Counters worth watching

| Counter | Why it matters |
|---|---|
| `graph.attribution_ratio` | A drop means the symbol solver lost the classpath — the graph is degrading |
| `knowledge.deterministic_coverage` | A drop means generated rules are missing |
| `mutation.bypass_detections` | Must be zero; anything else is an integrity failure |
| `ledger.chain_verified` | Must be true on every append |
| `validation.unexplained` | The number that blocks; trending up means the plan is wrong |
| `ai.patches_rejected` / `ai.patches_accepted` | The AI's actual hit rate, not its claimed one |

### Progress output

The CLI prints per-stage status with artifact paths as it goes, so a long run is legible while it
runs and not only afterwards. Every artifact path printed is a real, already-published file — the
pointer is written after the content (§9), so a path in the log is always readable.

---

## 49. Repository structure

```
bootshift/
├── pom.xml                      aggregator; packaging=pom; ./src is NOT a module
├── README.md                    this document
├── LICENSE                      MIT
│
├── src/                         ← THE APPLICATION UNDER ANALYSIS (read-only input)
│                                  never a Maven module of this build,
│                                  never written to, never re-downloaded
│
├── core/                        domain model — no I/O, no frameworks
│   └── src/main/java/com/bootshift/core/
│       ├── domain/              Envelope, ExitCode, StageResult, RunContext, OutputLayout
│       ├── identity/            FileRecord, FileRegistry, FileRole, RenameSource
│       ├── graph/               NodeType, EdgeType, GraphNode, GraphEdge, ApplicationGraph, GraphDiff
│       ├── ledger/              ChangeEvent, ChangeLedger
│       ├── evidence/            EvidenceLevel, Claim, CoverageStatement, EvidenceManifest
│       ├── policy/              HarnessPolicy, LicensePolicy, ValidationDepth
│       ├── state/               RunState, StateMachine
│       ├── provenance/          ProvenanceGraph
│       ├── security/            SensitiveValues
│       └── util/                Hashing, Ids, Json, Similarity, SchemaValidator
│
├── ports/                       interfaces only — the hexagon boundary
│   └── src/main/java/com/bootshift/ports/
│
├── adapters/                    every outside-world implementation
│   └── src/main/java/com/bootshift/adapters/
│       ├── exec/                ProcessRunner (allowlist, timeout, output cap)
│       ├── scm/                 GitScmAdapter
│       ├── build/               MavenBuildAdapter, GradleBuildAdapter, ToolchainProbe
│       ├── analysis/            JavaParserCodeModelAdapter
│       ├── mutation/            FileMutationGateway  ← the single writer
│       ├── transform/           MavenPom, JakartaNamespace, ConfigurationProperty, TestFramework
│       ├── compat/              MavenCentralVersionSpaceAdapter
│       ├── docs/                HttpDocumentationAdapter, DocumentTextExtractor
│       ├── runtime/             SpringProcessRuntimeProbe
│       ├── apidiff/             JavapApiDiffAdapter
│       ├── environment/         Managed / Delegated providers
│       ├── evidence/            FilesystemEvidenceStore
│       ├── state/               FilesystemRunStateStore
│       ├── http/                HttpFetcher (egress allowlist + content cache)
│       ├── ai/                  LocalOssAIProvider (loopback only)
│       └── telemetry/           StructuredTelemetryAdapter
│
├── stages/                      the twenty agents + orchestration
│   └── src/main/java/com/bootshift/stages/
│       ├── Stage, StageContext, StageSupport, EdgeSupport, ValidationSupport
│       ├── RunFactory, PipelineOrchestrator
│       ├── bootstrap/           RunBootstrap
│       └── stage01/ … stage20/  one package per agent
│
├── apps/migration-cli/          Picocli entry point → bootshift.jar
│
├── tests/                       the harness's own test suite
│
├── .claude/
│   ├── agents/                  21 stage contracts (00-pipeline-conductor + 01…20)
│   └── skills/                  skill catalog
│
├── docs/adr/                    ADR-001 … ADR-007
├── schemas/                     JSON Schemas for every published artifact
├── policies/                    default, license, ai, evidence, normalization,
│                                persistence, retention, security, validation
├── migration-rules/             deterministic rules, incl. 542 generated property rules
├── fixtures/                    test corpora and held-out evaluation fixtures
└── output/                      published artifacts (git-ignored)
    └── <stage>/<timestamp>/     immutable; `latest.json` is the pointer
```

### The one structural rule

`./src/` is **input**. It appears nowhere in the aggregator's `<modules>`, no harness class is
written into it, and `MutationBoundaryTest` fails the build if any code path can write there outside
the snapshot copy. Every other directory is harness.
---

## 50. State machine

### Diagram 16 — Run state machine

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> WORKSPACE_READY
    WORKSPACE_READY --> OSS_POLICY_VERIFIED
    OSS_POLICY_VERIFIED --> INVENTORY_COMPLETE
    INVENTORY_COMPLETE --> FILE_REGISTRY_SEALED
    FILE_REGISTRY_SEALED --> BUILD_RESOLVED
    BUILD_RESOLVED --> APPLICATION_GRAPH_BUILT
    APPLICATION_GRAPH_BUILT --> GRAPH_VERIFIED
    GRAPH_VERIFIED --> BASELINE_CAPTURED
    BASELINE_CAPTURED --> BASELINE_SEALED

    note right of BASELINE_SEALED
        R6: no mutation may occur
        before this point
    end note

    BASELINE_SEALED --> COMPATIBILITY_REGISTRY_READY
    COMPATIBILITY_REGISTRY_READY --> TARGET_RESOLVED
    TARGET_RESOLVED --> TARGET_FROZEN
    TARGET_FROZEN --> DOCUMENTATION_RETRIEVED
    DOCUMENTATION_RETRIEVED --> KNOWLEDGE_VERIFIED
    KNOWLEDGE_VERIFIED --> IMPACT_ANALYZED
    IMPACT_ANALYZED --> CHARACTERIZATION_COMPLETE
    CHARACTERIZATION_COMPLETE --> PLAN_FROZEN

    PLAN_FROZEN --> EDGE_TRANSFORMED
    state "per migration edge" as EDGELOOP {
        EDGE_TRANSFORMED --> EDGE_COMPILED
        EDGE_COMPILED --> REPAIRING : diagnostics remain
        REPAIRING --> EDGE_COMPILED : repair applied
        EDGE_COMPILED --> EDGE_GRAPH_REBUILT
        EDGE_GRAPH_REBUILT --> EDGE_SCOPE_VERIFIED
        EDGE_SCOPE_VERIFIED --> EDGE_TESTED
        EDGE_TESTED --> EDGE_RUNTIME_VALIDATED
        EDGE_RUNTIME_VALIDATED --> EDGE_RUNTIME_GRAPH_ENRICHED
        EDGE_RUNTIME_GRAPH_ENRICHED --> EDGE_DIFFERENTIAL_VALIDATED
        EDGE_DIFFERENTIAL_VALIDATED --> EDGE_COMPLETE
        EDGE_COMPLETE --> EDGE_TRANSFORMED : next edge
    }

    EDGE_COMPLETE --> FINAL_APPROVAL : last edge
    FINAL_APPROVAL --> EVIDENCE_SEALED
    EVIDENCE_SEALED --> MIGRATION_COMPLETE
    MIGRATION_COMPLETE --> [*]

    EDGE_TRANSFORMED --> ROLLED_BACK : gate failed
    ROLLED_BACK --> BLOCKED

    CREATED --> FAILED : stage error
    TARGET_RESOLVED --> BLOCKED : policy block
    EDGE_TESTED --> BLOCKED : unexplained regression
    EDGE_DIFFERENTIAL_VALIDATED --> NEEDS_HUMAN : approval gate
    FINAL_APPROVAL --> NEEDS_HUMAN : outstanding gates

    FAILED --> [*]
    BLOCKED --> [*]
    NEEDS_HUMAN --> [*]
    CANCELLED --> [*]
```

### The state machine is not the truth

`RunState` says **where** a run is. It never says **what is true** — that is the artifact plane
(R23). Re-running a stage does not consult the state machine for its answer; it re-reads the
artifacts. The state machine exists so a run can be resumed and so illegal orderings are refused.

### Mutating states are gated

Every state carries an `isMutating()` flag. `StateMachine` refuses any transition into a mutating
state unless `BASELINE_SEALED` has been reached with a recorded baseline hash. That is R6 as code
rather than as a convention:

```java
public void recordBaselineSeal(String hash) {
    if (baselineHash != null && !baselineHash.equals(hash)) {
        throw new HarnessException("Baseline already sealed with a different hash");
    }
    ...
}
```

### Re-run tolerance

`alreadyReached(state)` lets a stage that has already produced a valid artifact be re-entered
idempotently. A stage that is re-run publishes a **new** timestamped artifact directory and advances
`latest.json`; the previous directory stays immutable. Nothing is ever overwritten in place.

---

## 51. Artifact schemas

### Every published artifact is schema-validated before it is published

`OutputLayout.publish()` refuses to write `latest.json` if any artifact in the directory fails
validation. A failed validation is a stage failure, not a warning — a malformed artifact would
poison every downstream stage that reads it.

### The envelope

Every artifact shares one envelope, so that provenance is uniform:

```json
{
  "schema_version": "1.0.0",
  "artifact_type": "application-graph",
  "run_id": "RUN-01M2550D2MSSFRN1FJD72QAMK3",
  "stage": "03-graph",
  "produced_at": "2026-09-10T06:06:33.116Z",
  "producer": "com.bootshift.stages.stage03.GraphStage",
  "inputs": [
    { "artifact": "inventory-artifact", "hash": "50e47c58f78a…" },
    { "artifact": "build-model", "hash": "8c1e7b09a44f…" }
  ],
  "edge_id": null,
  "environment_fingerprint": "ENV-9d31…",
  "blind_spots": [],
  "gaps": [],
  "payload": { }
}
```

`Envelope.RESERVED_KEYS` names every field a stage may **not** shadow in its payload.
`StageSupport.compose()` throws on a collision, which is how four real reserved-key defects
(`environment_fingerprint`, `run_id`, `gaps`, `edge_id`) were caught during construction rather than
in a downstream consumer.

### The 21 schemas

| Group | Schema |
|---|---|
| common | `artifact-envelope` |
| inventory | `inventory-artifact` |
| file-registry | `file-registry` |
| build | `build-model` |
| graph | `application-graph`, `graph-diff` |
| symbol-registry | `symbol-registry` |
| baseline | `baseline-manifest` |
| compatibility | `lifecycle-registry` |
| target | `target-state` |
| migration-knowledge | `migration-knowledge` |
| impact | `impact-report` |
| characterization | `characterization-report` |
| migration-plan | `migration-plan`, `edge-plan` |
| change-event | `change-event` |
| validation | `test-report`, `runtime-report` |
| differential | `differential-report` |
| evidence | `approval-report`, `migration-result` |

### Pointer-after-write

```
output/03-graph/
├── 20260910-060633-116/          ← immutable, complete, validated
│   ├── application-graph.json
│   ├── … 16 more artifacts …
│   └── manifest.json
└── latest.json                    ← written last, by atomic move:
                                       {"directory": "20260910-060633-116", …}
```

A reader that follows `latest.json` can never observe a partially written stage. A crash mid-stage
leaves an orphan directory that no pointer references, and the previous `latest.json` still resolves
to a complete set.

---

## 52. CLI usage

> **Export is validated by default.** `bootshift export` refuses anything but
> `MIGRATION_COMPLETE` and explains which of unexplained differences, unexpected differences,
> outstanding approvals, evidence shortfalls or incomplete edges is blocking it. A missing
> `final_source_tree_hash` is a failure, not a match — previously a null recorded hash compared equal
> to anything, so a bundle could be exported that had never been checked against the validated tree.
> `--diagnostic` exports an incomplete run for inspection and labels the bundle `NOT VALIDATED` inside
> `export-manifest.json`.
>
> The SBOM and license report are generated against the **final migrated** resolved dependency graph,
> re-resolved from the migration workspace after the last edge and sealed as `final-build-model.json`.
> Generating them from the Stage 02 model would describe the dependency graph the migration replaced.
> When the migrated build cannot be resolved authoritatively the SBOM says so in its `basis` field
> rather than quietly falling back.
>
> `bootshift migrate --edge <id>` and `bootshift validate --edge <id>` now reach `EDGE_COMPLETE`
> exactly as the orchestrated route does. They previously did not, so a run driven one edge at a time
> was left permanently short of the state approval requires, with no way to finish.

### Running everything

```bash
bootshift run --target auto --policy production
bsh run --target auto --policy production      # bsh is an alias for bootshift
```

`--target auto` means **the highest safe supported stable GA version** the evidence permits (R16) —
not the newest version that exists.

### The commands

Twenty-five top-level commands plus `help`, and four nested under `graph` and `explain`.

| Command | What it does |
|---|---|
| `run` | The full pipeline, all twenty agents |
| `stages` | List the stage catalog and its contracts |
| `inventory` | Agent 01 — scan, allocate `FILE_ID`s, seal the registry |
| `resolve-build` | Agent 02 — authoritative build model |
| `graph` | Agent 03 — build the graph |
| `graph file <FILE_ID>` | *(nested)* Every graph fact about one file |
| `graph blast-radius <id> --depth N` | *(nested)* Transitive dependents |
| `graph symbol <fqn>` | *(nested)* Callers and callees of a symbol |
| `baseline` | Agent 04 — capture and seal the baseline |
| `compatibility` | Agent 05 — lifecycle registry |
| `resolve-target` | Agent 06 — resolve and freeze the landing target |
| `documentation` | Agent 07 — pin documentation snapshots |
| `knowledge` | Agent 08 — two-channel migration facts |
| `impact` | Agent 09 — impact analysis |
| `evaluate-impact` | Measure impact precision/recall against held-out fixtures |
| `characterize` | Agent 10 — behavioural contracts |
| `plan` | Agent 11 — decompose and freeze the plan |
| `migrate` | Agents 12–17 for one edge or all edges |
| `validate` | Agents 15–17 without mutating |
| `approve` | Record a signed decision against an open gate |
| `report` | Agent 19 — evidence, coverage and reports |
| `evidence` | Inspect the evidence manifest |
| `verify` | Re-verify the ledger chain and every artifact hash |
| `explain` | Agent 20 — provenance queries |
| `explain impact <IMPACT_ID>` | *(nested)* Why an impact finding exists |
| `explain change <CHG_ID>` | *(nested)* Why a change event was applied |
| `lineage <FILE_ID>` | Identity, split / merge / rename / delete history |
| `blind-spots` | Everything the run could not observe |
| `gaps` | Everything the run could not explain |
| `export` | Bundle + CycloneDX 1.5 SBOM |

### Common options

| Option | Meaning |
|---|---|
| `-r, --repo <path>` | The application under analysis (default `./src`) |
| `--output <dir>` | Artifact root (default `./output`) |
| `--policy <name>` | `production` \| `development` \| a named policy |
| `--policy-file <path>` | An explicit policy JSON file |
| `--target <version\|auto>` | Landing target; `auto` resolves it from evidence |
| `--ai <on\|off>` | AI repair (default `off`) |
| `--environment <MANAGED\|DELEGATED>` | Environment provider mode |
| `--offline` | Refuse all network egress; use only the content cache |
| `--run-id <id>` | Resume or re-enter an existing run |
| `--env-attribute k=v` | Declare an environment attribute for the equivalence contract |
| `--skip-tests` / `--skip-runtime` | Baseline only; recorded as a blind spot, never silent |

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Stage failure — something broke |
| `2` | Structured refusal — the harness will not proceed on this evidence |
| `3` | Policy block — a policy forbids the only available path |
| `4` | Human decision required — approval gates are open |

`1` and `2` are deliberately distinct. A crash and a refusal are different events, and automation
should treat them differently.

---

## 53. Example end-to-end run

The output below is from an actual run against the reference corpus in `./src` — a six-module Spring
Boot 2.7.12 / Java 17 / Spring Cloud 2021.0.7 microservice estate — under
`policies/default/production-eol-exception.json`.

```
$ java -jar apps/migration-cli/target/bootshift.jar run \
      --target auto --policy-file policies/default/production-eol-exception.json

  00-bootstrap  [SUCCESS]
  Workspaces created under …/bootshift-workspaces/RUN-01M2550D2MSSFRN1FJD72QAMK3;
  OSS gate passed for 12 harness components

  01-inventory  [SUCCESS]
  99 files across 6 module(s); 104 migration signal(s); registry sealed as
  91b9b4ed1e141d6df29fa8a43462c21de210ff574106e3080b71791481714c2c (content 9fb5026060f8a88c)

  02-build  [SUCCESS]
  MAVEN model: 6 module(s), 962 dependency record(s), 9186 managed version(s)

  03-graph  [SUCCESS]
  839 nodes / 1852 edges, 239 symbols, attribution 0.6858, content hash 5b764c29af1e

  04-baseline  [SUCCESS]
  Baseline sealed as 1b8f054d5db20b16 (6 module(s) built, 12 test(s), 5/6 runtime probe(s)
  started)

  05-compatibility  [SUCCESS]
  Current state Spring Boot 2.7.12 / Java 17 / Spring Cloud 2021.0.7;
  9 candidate line(s), 0 unavailable, 0 unknown internal component(s)

  06-target  [SUCCESS]
  Landing target Spring Boot 3.5.16 (Java 21, Spring Cloud 2025.0.3),
  8 migration edge(s), -2 month support horizon

  07-documentation  [SUCCESS]
  15 document(s) pinned for 7 edge(s); 7 official migration guide(s)

  08-knowledge  [SUCCESS]
  1690 migration fact(s): 1683 VERIFIED, 7 CANDIDATE, 0 CONFLICTING for 2.7.12 -> 3.5.16

  09-impact  [SUCCESS]
  2072 impact finding(s) across 27 file(s); {DEFINITELY_AFFECTED=502, POSSIBLY_AFFECTED=5,
  UNAFFECTED_WITHIN_OBSERVED_COVERAGE=1565}

  10-characterization  [SUCCESS]
  517 characterization contract(s): 0 frozen, 0 mapped to existing tests, 517 awaiting OLD, 0
  unobservable

  11-plan  [SUCCESS]
  8 edge(s) frozen, deterministic coverage 0.3886, 1 differential dimension(s) required

  12-transformation  [SUCCESS]
  Edge EDGE-1-PREP-TEST: 3 applied, 0 rejected, 0 failed; ledger head 8e380a1b5b01

  13-build-repair  [SUCCESS]
  Edge EDGE-1-PREP-TEST compiles after 1 round(s); 0 AI attempt(s) used of 12

  14-graph-diff  [SUCCESS]
  Edge EDGE-1-PREP-TEST graph COMPLETE: +0/-0 nodes, +0/-0 edges,
  0 symbol(s) changed; scope OK

  15-test  [SUCCESS]
  EDGE-1-PREP-TEST: 12 test(s), {PASSED=10, PRE_EXISTING_FAILURE=2}

  16-runtime  [SUCCESS]
  EDGE-1-PREP-TEST: 5/6 module(s) started, 426 bound propert(ies),
  441 runtime graph edge(s)

  17-differential  [SUCCESS]
  EDGE-1-PREP-TEST: 6 comparison(s) across 1 dimension(s);
  {IDENTICAL=5, NOT_COMPARED=1}

  15-test  [SUCCESS]
  Edge EDGE-1-PREP-TEST: 12 test(s), {PASSED=10, PRE_EXISTING_FAILURE=2}

  16-runtime  [SUCCESS]
  Edge EDGE-1-PREP-TEST: 5/6 module(s) started, 426 bound propert(ies),
  441 runtime graph edge(s)

  17-differential  [SUCCESS]
  Edge EDGE-1-PREP-TEST: 6 comparison(s) across 1 dimension(s);
  {IDENTICAL=5, NOT_COMPARED=1}

  …edge 2 (PATCH → 2.7.18): 16 applied across three recipes, compiles,
    12 test(s) {PASSED=10, PRE_EXISTING_FAILURE=2}, 5/6 started, 5 IDENTICAL…

  12-transformation  [SUCCESS]
  Edge EDGE-3-MAJOR: 14 applied, 0 rejected, 0 failed; ledger head 4d8405eb6382

  13-build-repair  [SUCCESS]
  Edge EDGE-3-MAJOR compiles after 1 round(s); 0 AI attempt(s) used of 12

  14-graph-diff  [SUCCESS]
  Edge EDGE-3-MAJOR graph COMPLETE: +0/-0 nodes, +0/-0 edges, 0 symbol(s) changed; scope OK

  15-test  [POLICY_BLOCK]
  Edge EDGE-3-MAJOR: 2 blocking test or coverage finding(s)

  ! EDGE_LOCAL_REGRESSION: EmployeeControllerTest#getEmployee
      'void org.springframework.http.ResponseEntity.<init>(java.lang.Object,
       org.springframework.http.HttpStatus)'
  ! EDGE_LOCAL_REGRESSION: EmployeeControllerTest#createEmployee — same signature

  edge EDGE-3-MAJOR stopped at 15-test

exit 3
```

### Where this run stops, and why that is the point

The migration does **not** complete on this corpus, and the harness says so at the first edge where
it cannot justify continuing.

Spring Framework 6 replaced `ResponseEntity(T, HttpStatus)` with `ResponseEntity(T, HttpStatusCode)`.
`EmployeeController` uses the old form twice. The call site **still compiles** — `HttpStatus`
implements `HttpStatusCode` — so a build-only gate passes this edge and ships a runtime failure. The
test suite catches it, and the classification is the strict one: new at this edge, absent from the
sealed baseline, explained by no `VERIFIED` fact and covered by no signed approval. That is
`EDGE_LOCAL_REGRESSION`, and R21 blocks on it.

What the run does establish, verified against the tree rather than the log: the application is
genuinely on `spring-boot-starter-parent` **3.0.13**, `javax.servlet` and `javax.persistence` are
**entirely gone**, the jakarta imports are in place, and all of it was reached by deterministic
transformers with **zero** AI attempts against a budget of twelve and no scope violations.

That is the shape of an honest result: real progress, a precise residual, and a refusal with a name.

### Reading the numbers

Several of these are worth reading carefully, because they are the honest version of results that are
usually reported optimistically:

- **`-2 month support horizon`** — the landing target's OSS support window has *already closed*. Under
  the strict `production` policy the harness emits `POLICY_BLOCK` (exit 3) rather than landing here.
  The run above uses an explicit EOL exception policy, and the negative horizon is stated in the
  report rather than hidden.
- **`5/6 runtime probe(s) started`** — one module did not start at baseline. Everything derived from
  that module's runtime behaviour is a blind spot, and the differential correspondingly reports
  `NOT_COMPARED=1`.
- **`517 awaiting OLD`** — contracts are declared before they are frozen. They freeze against
  observed OLD behaviour, not against expectations.
- **`{PASSED=10, PRE_EXISTING_FAILURE=2}`** — the two failures are MongoDB-dependent repository
  tests, failing identically at the sealed baseline, with `probable_cause:
  INFRASTRUCTURE_UNAVAILABLE: MongoDB`. They are not counted as migration regressions, and they are
  not hidden either.
- **`0 AI attempt(s) used of 12`** — the deterministic transformers handled the edge. The AI budget
  existed and went unused, which is the intended common case (R10).
- **`deterministic coverage`** — read the per-fact-type breakdown in `residual-report.json`, not the
  single number. An earlier version of this harness reported `0.9983` for a run whose next edge
  produced 33 compile errors, because a capability that rewrites JUnit 4 declared the whole
  `API_REMOVED` *type* as handled and every removed API in the run counted as covered. Coverage is
  now computed per fact — a capability must claim the fact's *subject* — and the artifact reports
  covered and uncovered counts per type with example subjects for the uncovered ones. The number is
  also scoped: it measures rule availability for facts the harness knows about, and says nothing
  about whether the fact set is complete. That second question is answered separately by the
  artifact channel's `api_diff` coverage.

### Under the strict production policy

```
$ bootshift run --target auto --policy production
…
  06-target  [POLICY_BLOCK]
  No candidate line satisfies the production policy.
    4.1  eliminated  NO_SPRING_CLOUD_TRAIN — no GA Spring Cloud train declares a
                     spring-boot-starter-parent within Boot major 4
    4.0  eliminated  NO_SPRING_CLOUD_TRAIN — (as above)
    3.5  eliminated  SUPPORT_WINDOW_CLOSED — OSS support ended 2026-06-24 (evidence: VERIFIED)
    3.4  eliminated  SUPPORT_WINDOW_CLOSED — OSS support ended 2025-12-24 (evidence: VERIFIED)
    …
  remedy: land on 3.5 with an explicit, signed EOL exception, or wait for the
          Spring Cloud 2025.1 GA train that targets Boot 4.0.

exit 3
```

This is the harness working correctly. The corpus genuinely has no policy-compliant landing target
today, and saying so is more useful than picking one and calling it safe.

---

## 54. Failure behavior

### The five outcomes

| Outcome | Exit | When | What the operator does |
|---|---|---|---|
| `SUCCESS` | 0 | Every gate passed at the frozen depth | Review the report and the diff |
| `FAILURE` | 1 | A stage threw — a tool crashed, a path was unreadable | Fix the environment and re-run the stage |
| `REFUSAL` | 2 | The evidence does not support proceeding | Supply the missing evidence, or accept the blind spot explicitly |
| `POLICY_BLOCK` | 3 | The only available path violates policy | Change the policy with a decision, or change the target |
| `NEEDS_HUMAN` | 4 | Approval gates are open | `bootshift approve` with a rationale |

### A refusal is structured

```json
{
  "outcome": "REFUSAL",
  "stage": "08-knowledge",
  "reason": "DOCUMENTATION_ONLY_FACT_WOULD_AUTHORIZE_MUTATION",
  "detail": "FACT-00218 (spring.redis.* → spring.data.redis.*) is CANDIDATE: the documentation channel asserts it but no artifact-verified metadata entry confirms it for 3.0.0",
  "rule": "R8",
  "remedy": [
    "Provide spring-boot-autoconfigure-3.0.0.jar so its spring-configuration-metadata.json can be read, or",
    "Record a decision accepting the documentation-only fact, naming the reviewer"
  ],
  "blocking": true
}
```

A refusal always names the rule it is enforcing and a concrete remedy. "It didn't work" is not an
acceptable output.

### Rollback

Every edge is bracketed by checkpoints. A gate failure inside an edge rolls the working tree back to
the edge's entry checkpoint and records `ROLLED_BACK` — the applied changes stay in the ledger as
attempted-and-reverted (R12), because "we tried this and it failed validation" is evidence.

The one deliberate exception: if the **checkpoint itself** fails, the gateway does not discard the
applied work. It records a `FAILED_VALIDATION` ledger event with `checkpointRef:
"CHECKPOINT_FAILED"` and returns, so the operator can inspect what happened rather than losing it.
`MutationBoundaryTest` covers this path.

### Nothing partial is ever published

`OutputLayout.publish()` validates first, writes the pointer last. A failed stage leaves no
`latest.json` advance, so downstream stages see the last good artifacts and refuse to proceed rather
than reading a half-written one.

---

## 55. Testing strategy

### The suite

**143 tests, 142 passing and 1 environment-skipped.** They test the invariants, not the happy path — every one of them exists
because the property it asserts is load-bearing, and several exist because the property was *not*
holding when it was first checked.

| Suite | Tests | What it protects |
|---|---|---|
| `ArchitectureTest` | 9 | The hexagon: core depends on nothing, ports are interfaces, adapters never depend on stages, only the gateway writes |
| `ChangeLedgerTamperTest` | 8 | The chain detects modification, insertion, deletion, reordering, truncation, and re-open after seal |
| `FileIdentityTest` | 12 | All five reattachment rules in order, split/merge/delete lineage, no id reuse, seal immutability |
| `MutationBoundaryTest` | 14 | Every mutation goes through the gateway; bypass detection; scope enforcement; checkpoint-failure recording; stale-proposal rejection; symlink escape |
| `AiBoundaryTest` | 7 | AI cannot authorize, cannot write, cannot reach a non-loopback endpoint; every attempt is recorded |
| `TransformerTest` | 23 | POM edits, the 28 relocated / 26 preserved `javax` packages, property migration, test-framework rewrite, no-op annotation removal |
| `CoreDomainTest` | 27 | Hashing, canonical JSON, ULIDs, similarity, graph traversal, blast radius, evidence levels, state machine, artifact-directory isolation |
| `SchemaConformanceTest` | 4 | Every published artifact validates; reserved keys are never shadowed; no plaintext secret reaches `output/` |
| `CompilerDiagnosticsTest` | 7 | javac continuation lines fold into their diagnostic; Maven's framing is not a diagnostic at all; a real toolchain fault still is |
| `GraphHashTest` | 6 | The content hash is comparable across runs and the identity-bearing structural hash is not, and both still detect a real change |
| `ImpactAccuracyTest` | 5 | Accuracy is `UNMEASURED` with no fixtures; the prediction comes from the analyzer, never from the fixture; tuning cases are excluded |
| `ControlsAreWiredTest` | 5 | Every control the documentation claims has an actual call site; every planned recipe has a transformer |
| `DocumentedCommandsExistTest` | 2 | Every `bootshift` command shown in the docs is declared by the CLI; nested commands really are nested |
| `PublishedArtifactSecretScanTest` | 2 | The corpus's real credential components appear in no published artifact; both halves of a credential pair are redacted |
| `ExecutionAndEgressBoundaryTest` | 6 | `cmd.exe` is not launchable; the egress allowlist matches hosts not suffixes; the keyed hash is HMAC; an atomic write leaves no debris |
| `ArtifactChannelBudgetTest` | 6 | Packaging-only artifacts are excluded from the diff budget; a redirect off the allowlist is refused; a redirect loop terminates; an imported BOM's entries are resolved |

### Architecture rules are tests

```java
noClasses().that().resideInAPackage("..core..")
    .should().dependOnClassesThat().resideInAnyPackage("..adapters..", "..stages..");

noClasses().that().resideOutsideOfPackage("..adapters.mutation..")
    .should().callMethodWhere(writesToTheFilesystem());
```

The second rule is why "only the gateway writes" is a fact about the codebase rather than a
convention people remember. `StageContext` and `RunFactory` are exempted as composition roots, and
that exemption is explicit in the rule rather than implicit in a passing build.

### The one skipped test

`MutationBoundaryTest.symlinkEscapeIsRefused` needs to create a symbolic link, which Windows refuses
without the create-symbolic-link privilege. It skips with the reason recorded rather than passing
vacuously, so the count reads 118 passed / 1 skipped rather than 119 passed. The control it covers is
still in the gateway; it is the *test* that cannot run here, and saying so is the point.

### Four tests exist because a control was not wired

`ControlsAreWiredTest` is unusual and worth explaining. Two capabilities in this repository were
written to spec, unit-tested, and called by nothing:

- `JavapApiDiffAdapter` — the artifact channel diffed BOMs and read configuration metadata but never
  opened a jar, which is the only place a *removed type* is visible. An edge reached the compiler
  with 33 errors against a knowledge base reporting 99.83% deterministic coverage.
- `detectBypass()` — three passing unit tests, no caller. The single-writer rule was enforced
  statically by ArchUnit and verified at runtime by nothing, while this document described a runtime
  manifest re-hash and a counter that "must be zero".

No test that existed at the time could catch either, because both components passed their own tests
perfectly. A control with no call site is documentation, and this suite now asserts the call sites
exist.

### What is deliberately not unit-tested

Adapters that shell out to Maven, Git and the JDK are exercised by the pipeline itself against the
reference corpus, not by mocks. A mocked `mvn` would test the harness's beliefs about Maven, which is
precisely the thing R4 says not to trust.

### Running them

```bash
mvn -q test                  # 143 tests
mvn -q -pl tests test        # the harness suite alone
mvn -q -Dtest=ArchitectureTest -pl tests test
```

---

## 56. Fixture corpus

### Layout

```
fixtures/
├── identity/                  file identity and lineage cases
│   ├── rename-git/            a git-detected rename
│   ├── move-content-hash/     a move with identical content, no git history
│   ├── split/                 one class split into two
│   └── merge/                 two classes merged into one
├── transform/                 transformer input/expected pairs
│   ├── pom-parent-version/
│   ├── jakarta-namespace/     including the 26 prefixes that must NOT move
│   ├── config-property/
│   ├── removed-annotation/    including one that must NOT be removed
│   └── test-framework/
├── ledger/                    tamper vectors for the hash chain
├── schema/                    valid and invalid artifact instances
└── impact-evaluation/         held-out ground truth for precision/recall
    ├── ground-truth.json
    └── cases/
```

### Held-out impact evaluation

`evaluate-impact` measures the impact analyzer against fixtures whose true affected-set is known and
which the analyzer's rules were **not** derived from:

```
$ bootshift evaluate-impact

  Impact analyzer accuracy
  evaluated        true
  held-out fixtures 6
  true positives   5
  false positives  0
  false negatives  0
  precision        1.0
  recall           1.0
  f1               1.0
  ! 1 tuning fixture(s) excluded from the reported numbers
```

**Read that number for what it is.** Six held-out cases is a small sample. It says the analyzer
handles the six failure modes those fixtures encode — namespace relocation against a preserved JDK
package, a removed supertype, a property rename, a managed-version change, and a fact that must
produce nothing at all. It does not say the analyzer is accurate in general, and the harness reports
`UNMEASURED` rather than extrapolating to a corpus unlike the fixtures.

**The fixture never supplies the prediction.** It supplies a small source tree, one migration fact,
and the ground truth. `AccuracyHarness` builds a file registry over that tree, runs `ImpactStage`'s
own matcher, and grades what the matcher actually returned. An earlier version of this class read a
`predicted_paths` array out of the fixture and compared it against `true_affected_paths` in the same
file — which measured whether the fixture author had written two consistent lists and published the
answer as the analyzer's accuracy. That defect is the reason `ImpactAccuracyTest` exists, and why the
`Fixture` record has no field a prediction could be smuggled into.

When no held-out fixtures are present, the harness reports `UNMEASURED` — never a default, never an
estimate. `AccuracyHarness` has no code path that invents a number, and a recall below the policy
floor sets `impactRecallBelowFloor`, which raises the planner's validation depth rather than being
noted and ignored.

### The 26 preserved `javax` packages

The `jakarta-namespace` fixture is worth calling out because getting it wrong is a classic silent
migration defect. Twenty-eight `javax.*` prefixes relocate to `jakarta.*`; twenty-six do **not** —
`javax.sql`, `javax.crypto`, `javax.net`, `javax.naming`, `javax.management`, `javax.security.auth`,
`javax.xml.parsers` and the rest are JDK packages that never moved. The fixture asserts both
directions, and `TransformerTest` fails if a preserved package is rewritten — and pins the two counts,
so a future edit to either list fails the build rather than quietly making this paragraph wrong.
---

## 57. Installation

### Requirements

| Requirement | Version | Why |
|---|---|---|
| JDK | 21 | The harness itself is built and run on 21 |
| Maven | 3.9+ | To build the harness |
| Git | 2.30+ | Snapshots, checkpoints, rename detection |
| An additional JDK | 8 / 11 / 17 | Only if the application under analysis needs one; discovered automatically |

The harness runs the **application's** build on whichever JDK that application requires, which is not
necessarily the JDK the harness runs on. `ToolchainProbe` discovers installed JDKs and selects one
per edge.

### Build

```bash
git clone <this repository>
cd bootshift
mvn -q -DskipTests package
```

Produces `apps/migration-cli/target/bootshift.jar`, a single executable jar.

```bash
java -jar apps/migration-cli/target/bootshift.jar --help
```

### Placing the application under analysis

```
bootshift/
└── src/          ← put the repository to be migrated here
```

`./src/` is the default value of `--repo`. It is **input**, never a module of this build, and never
written to. Any other path works:

```bash
bootshift run --repo /path/to/other/repo --target auto
```

### Toolchain discovery

```bash
$ bootshift stages --show-toolchain
JDKs discovered
  21  C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot     (harness runtime)
  17  C:\Users\annav\tools\jdk-17                           (BOOTSHIFT_JDK_17)
hazards
  lombok 1.18.24 cannot run on JDK 21 (JCTree$JCImport.qualid)
    → edges on Boot ≤ 2.7 will select JDK 17
```

Point the harness at a specific JDK with `BOOTSHIFT_JDK_<major>`:

```bash
export BOOTSHIFT_JDK_17="C:\\Users\\annav\\tools\\jdk-17"
export BOOTSHIFT_JDK_8="/usr/lib/jvm/temurin-8"
```

Or override the JDK for a single invocation with `-Dbootshift.javaHome=…`.

### Offline operation

```bash
bootshift run --offline
```

Uses only the content-addressed cache in `~/.bootshift/cache/`. Facts that would require a network
fetch are reported as unverifiable rather than assumed — a fact that cannot reach `E3` cannot
authorize a change, so an offline run legitimately blocks more often.

---

## 58. Configuration

### Policies

A policy is a JSON document. Three ship by default:

| Policy | Posture |
|---|---|
| `production` | Blocks anything the harness cannot prove. No EOL landing target, no milestones, no AI, 6-month minimum support horizon |
| `production-eol-exception` | `production`, with an EOL landing target permitted because the ecosystem offers no compliant alternative |
| `development` | Permissive: milestones allowed, AI allowed, lower floors — for exploring a migration, not for authorizing one |

### Every setting

```jsonc
{
  "name": "production",
  "version": "1.0.0",

  // Coverage regression gate (R29)
  "coverage_gate_enabled": true,
  "coverage_drop_block_percentage_points": 5.0,

  // Residual risk → validation depth escalation
  "residual_no_escalation_threshold": 0.90,
  "residual_one_level_threshold": 0.60,

  // Impact analyzer accuracy floor, measured against held-out fixtures
  "impact_recall_floor": 0.80,

  // File-identity reattachment rule 5
  "similarity_threshold": 0.72,

  // Target selection (R16)
  "allow_milestone_targets": false,
  "allow_eol_landing_target": false,
  "minimum_support_horizon_months": 6,

  // Checkpoint discipline (R17)
  "allow_checkpoint_collapse": true,

  // Unknown internal components (R20)
  "unknown_internal_component_action": "BLOCK",

  // AI boundaries (R9, R10)
  "ai_allowed_in_production": false,
  "ai_max_total_attempts": 12,
  "ai_max_attempts_per_root_cause": 3,
  "ai_max_files_per_patch": 3,
  "ai_max_changed_lines_per_patch": 80,

  // Deterministic repair budget
  "repair_max_attempts_per_root_cause": 4,
  "repair_max_total_rounds": 10,

  // R21
  "unexplained_difference_blocks": true,

  // Graph quality floors
  "graph_java_coverage_floor": 0.98,
  "graph_attribution_floor": 0.70
}
```

A value below a floor does not silently degrade the run: it raises a named gap
(`GAP-GRAPH-001` for attribution) and caps what the affected findings may claim — unresolved
relations can only reach `POSSIBLY_AFFECTED`, never `DEFINITELY_AFFECTED`.

### The license policy

`policies/license/license-policy.json` lists allowed SPDX identifiers, forbidden classes
(`source-available`, `commercial-only`, `unknown`), and per-component licence elections such as
JavaParser's Apache-2.0.

### Internal component registry

`policies/default/internal-components/` is where an organization declares its own starters. Each
entry must carry evidence of compatibility, not an assertion of it (R20). A component with no
evidence is `UNKNOWN`, and `unknown_internal_component_action` decides whether that blocks.

### Environment attributes

```bash
bootshift run --environment DELEGATED \
  --env-attribute database.vendor=postgresql \
  --env-attribute database.version=15.4 \
  --env-attribute jvm.vendor=temurin
```

Declared attributes feed the Environment Equivalence Contract. An attribute that is `MUST_MATCH` and
does not match makes the affected dimension `NOT_COMPARED` — a number produced across a
non-equivalent environment is not evidence.

---

## 59. Development guide

### Module boundaries

```
core       ← no dependencies on anything in this repository. No I/O. No frameworks.
ports      ← interfaces only. May depend on core.
adapters   ← implementations. May depend on core and ports. NEVER on stages.
stages     ← the agents. May depend on core, ports and adapters.
apps       ← the CLI. Wires everything together.
tests      ← the harness suite.
```

`ArchitectureTest` enforces every line of that.

### Adding a stage

1. Create `stages/…/stageNN/`, implement `Stage`.
2. Declare inputs with `StageSupport.upstream()` / `optionalUpstream()` — never read another stage's
   directory directly.
3. Build the envelope with `StageSupport.envelope()` and compose the payload with
   `StageSupport.compose()`. It will throw if the payload shadows a reserved key.
4. Write a JSON Schema under `schemas/<group>/`, and validate with `StageSupport.validate()`.
5. Register the stage in `PipelineOrchestrator` and add its CLI subcommand.
6. Write `.claude/agents/NN-<name>.md` — the stage contract.

### Adding an adapter

Define the interface in `ports` first, implement it in `adapters`, and wire it in `StageContext`.
A stage must never import a concrete adapter type; `ArchitectureTest` fails the build if it does.

### The one absolute rule

**If your code writes to the filesystem inside the migration workspace, it must go through
`FileMutationGateway`.** There are no exceptions, and the architecture test enforces it statically
while `detectBypass()` enforces it at runtime by re-hashing the tree manifest after every stage.

### Adding a transformer

Transformers are deterministic and pure: given the same input file and parameters, the same output,
every time. They do not write files — they return the intended content, and the gateway writes it.
Add a fixture pair under `fixtures/transform/` and a case in `TransformerTest`.

### A note on escaping

Java string literals containing regexes are the single most common source of silent breakage in this
codebase, because a collapsed backslash (`"\\s+"` becoming `"\s+"`) is sometimes a compile error and
sometimes a *different working regex*. When editing regex literals, verify the compiled pattern, not
the source line. Two real defects in this repository — `TREE_LINE` failing on CRLF and
`split("\\.")` collapsing to `split(".")` — were of exactly this kind.

---

## 60. Troubleshooting

### `mvnw.cmd` is not found or is not executable

Windows wrappers are batch scripts and cannot be launched directly by `ProcessBuilder`.
`ProcessRunner.shellWrap()` prefixes `cmd.exe /c` for `.cmd` and `.bat`. If you see this error, the
wrapper is probably being probed from the wrong directory: a Maven wrapper resolves
`.mvn/wrapper/maven-wrapper.properties` relative to the **current working directory**, so it must be
probed from its own module. That is what `MavenBuildAdapter.probeDirectory()` does.

### `dependency:list` fails with a malformed POM

The reference corpus hits this on `xml-apis:xml-apis-ext:1.3.04`. The harness falls back to
`dependency:tree`, which tolerates the malformed descriptor. On the reference corpus this is the
difference between **530** and **962** dependency records — the fallback is not cosmetic.

### `dependency:tree` parses zero lines

Almost always CRLF. Split on `\R`, not `\n`, and `stripTrailing()` before matching — a trailing `\r`
makes `Matcher.matches()` fail on every line while `find()` still appears to work.

### `NoSuchFieldError: JCTree$JCImport.qualid`

Lombok 1.18.24 or earlier on JDK 21. `ToolchainProbe.knownHazard()` detects this and selects JDK 17
for the affected edges. If no JDK 17 is installed, set `BOOTSHIFT_JDK_17` or install one — the harness
will not silently run the build on an incompatible JDK.

### `processing of -javaagent failed` and zero test reports

Surefire 2.19.1 cannot fork with the JaCoCo agent attached. `ValidationSupport.runTests()` detects
this from the dumpstream and re-runs without coverage instrumentation, reporting coverage as
unavailable with the reason. On the reference corpus this recovered **12** observed tests where **5**
had been observed before.

### Type attribution is low

The symbol solver needs the real dependency classpath. If `dependency:build-classpath` failed, the
solver falls back to source-only resolution and attribution collapses — on the reference corpus, from
**0.6858** to **0.19**. Check `resolution-issues.json` in the build stage output.

### The discovery server does not start

A blanket `eureka.client.enabled=false` breaks a Eureka **server**. `ValidationSupport.runtimeSettings()`
derives isolation settings from what each module *is*, so a discovery server keeps its client beans.
If you add a new module archetype, extend that method rather than the global settings.

### Everything is `POLICY_BLOCK`

Read the elimination reasons. The strict production policy blocks EOL landing targets, and as of this
writing every Spring Boot line that has a GA Spring Cloud train has passed its OSS support date. Use
`production-eol-exception.json`, or record a decision.

### The report says a dimension is `UNOBSERVED`

That is the harness working. Find the blind spot id in `blind-spots.json`; it names what could not be
observed and why. Supply the missing capability — start the database, install the JDK, provide the
jar — and re-run.

---

## 61. Limitations and blind spots

These are stated because a harness that claims to report blind spots must report its own.

### What the harness does not do

- **It does not sandbox at the kernel level.** Commands are allowlisted and time-limited, but a
  malicious Maven plugin that the repository already trusted runs with the harness's privileges. Run
  against untrusted repositories in a disposable environment.
- **It does not rebuild the graph incrementally.** Every edge triggers a full rebuild (ADR-005).
  Correct, and slow on large corpora.
- **It cannot characterize behaviour it cannot execute.** A module that will not start under the
  MANAGED environment produces contracts that stay `awaiting OLD` forever. On the reference corpus,
  one of six modules is in this state.
- **It does not observe production traffic.** Characterization is derived from the graph and from the
  existing test suite. A code path exercised only by real users is invisible to it.
- **It does not migrate across build systems**, generate missing tests, or rewrite business logic.

### Known blind spots on any run

| Blind spot | Meaning |
|---|---|
| `BS-GRAPH-RUNTIME` | The static graph contains no runtime observations, by construction |
| `BS-GRAPH-PARTIAL` | Raised when an edge does not compile; full type attribution is impossible |
| `BS-RUNTIME-<MODULE>` | A module did not start; every runtime dimension for it is unobservable |
| `BS-DIFF-<DIMENSION>` | No executable scenario existed for a dimension |
| `GAP-GRAPH-001` | Type attribution below the policy floor; unresolved relations capped at `POSSIBLY_AFFECTED` |

### Reflection, dynamic proxies and SpEL

Static analysis cannot resolve `Class.forName(config.get("handler"))`. Such sites are recorded as
`DYNAMIC_DISPATCH` nodes with the resolution marked `UNRESOLVED`, and any impact finding that
depends on one is capped at `POSSIBLY_AFFECTED`. Runtime observation recovers some of them; nothing
recovers all of them.

### Documentation currency

The lifecycle table in `LifecycleSource` carries an explicit `AS_OF` date (`2026-09-01`) and an
evidence-quality grade per row. Rows older than the staleness window are downgraded from `VERIFIED`,
and only `VERIFIED` rows may eliminate a candidate. The harness therefore becomes *more* cautious as
its data ages, rather than confidently wrong.

### Accuracy that is genuinely unmeasured

Impact precision and recall are measured only against held-out fixtures. Against a corpus unlike
those fixtures, the true figures are unknown, and the harness reports `UNMEASURED` rather than
extrapolating.

---

## 62. Future work

Ordered by how much each would improve the evidence, not by how interesting it is to build.

1. **Incremental graph rebuild, behind an equivalence proof.** Only with a test asserting
   `INCREMENTAL_GRAPH == FULL_REBUILD_GRAPH` by structural hash across the fixture corpus. Without
   that test, the speedup buys silent drift (ADR-005).
2. **Container-backed MANAGED environments.** Real PostgreSQL, MongoDB and Kafka per side would move
   several dimensions from `NOT_COMPARED` to compared, and would close the largest current blind spot
   on the reference corpus.
3. **Recorded-traffic characterization.** Contracts derived from captured production requests, with
   the recording itself treated as evidence with a level and a provenance chain.
4. **Bytecode-level API differencing.** `javap` covers signatures. Comparing method bodies for
   behavioural changes in a dependency would catch the class of defect where a signature is stable
   and the semantics are not.
5. **Gradle parity.** The Gradle adapter resolves the model; it does not yet reach Maven's fidelity
   on plugin and BOM resolution.
6. **Multi-repository migrations.** Today the unit is one repository. Cross-repository contracts —
   a shared library and its consumers migrating together — need a graph that spans repositories.
7. **A signed evidence bundle.** The export is content-addressed and verifiable; signing it with an
   organizational key would let a third party verify provenance without trusting the exporter.

Explicitly **not** planned: autonomous approval, remote LLM inference on repository content, and
"confidence scores" that are not derived from measured accuracy.

---

## 63. License

Bootshift is released under the **MIT License**.

```
MIT License

Copyright (c) 2026 KrishnaAnnavaram

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
…
```

The full text is in [LICENSE](LICENSE), which is authoritative. MIT is on the harness's own
allowlist in `policies/license/license-policy.json`, so the OSS gate that runs before every stage
covers this project as well as its dependencies.

### Third-party licences

Every runtime dependency and its licence is listed in §43, verified by
`policies/license/license-policy.json`, and re-checked on every run by the OSS gate in Agent 00.
`bootshift export` emits a CycloneDX 1.5 SBOM alongside the evidence bundle.

### The application under analysis

`./src/` is **not** part of this project and is **not** covered by this licence. It is input,
supplied by whoever runs the harness, and it is neither redistributed nor modified in place.

---

<div align="center">

**Bootshift** — evidence, not optimism.

*Every claim in this document is either a property the tests enforce, or a number a real run
produced. Where a number could not be produced, the document says so.*

</div>
