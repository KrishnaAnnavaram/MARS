<div align="center">

# Agentic Remediation Harness

**A 3-phase, 7-agent pipeline that takes a reported vulnerability from raw source code
to a scored, auditable ship decision, with optional cleared-only PR publication on explicit request.**

![Agents](https://img.shields.io/badge/Agents-7-1F3864?style=for-the-badge)
![Skills](https://img.shields.io/badge/Skills-14-2E5FD9?style=for-the-badge)
![Pipeline stages](https://img.shields.io/badge/Pipeline_stages-9-6E86E8?style=for-the-badge)
![Zero-dependency skills](https://img.shields.io/badge/Zero--dep_skills-8_of_14-3DA35B?style=for-the-badge)
![PR publication](https://img.shields.io/badge/PR_publish-explicit%20cleared--only-A0399B?style=for-the-badge)

</div>

---

## What this is

Most "AI fixes your code" demos stop at generating a patch. The hard part is everything after:
*does the patch actually close the vulnerability? can it be bypassed a different way? did it quietly
change something else? does it build? is it safe to ship?*

This harness answers those questions with **seven specialised agents** driving **nine distinct
pipeline stages**, each leaving a written artifact the next stage reads. Agents `01`–`03` each own
one stage; agents `04`–`07` each own a small, strictly gated *group* of stages (e.g. `04_fix-generator`
still separates *planning* from *coding* internally, with a human approval checkpoint between them —
consolidating the agent that runs a stage never blurs the boundary between the stages themselves). No
stage decides more than it should, and exactly one stage is allowed to say a patch is safe to ship.

> **Two ideas do most of the work here.**
> 1. **Facts are collected by scripts; judgement is written by agents.** They live in separate files
>    and are merged into the final report, so every factual claim is traceable and no agent can
>    quietly invent evidence.
> 2. **Normal analysis never edits the real repository.** Patches are applied inside throwaway `git
>    worktree` copies that are destroyed immediately. Only an explicit, cleared-only PR-publish request
>    creates and pushes a branch after revalidating the rendered diff in an isolated worktree.

---

## The pipeline at a glance

```mermaid
flowchart LR
  subgraph IN[" INPUT "]
    R["📊 00<br/>Issue Register<br/>issue-register.xlsx"]
  end

  subgraph A[" PHASE A · UNDERSTAND "]
    direction LR
    A1["01<br/>Architect"]
    A2["02<br/>Root Cause<br/>Analyst"]
    A3["03<br/>Blast Radius<br/>Analyst"]
    A1 --> A2 --> A3
  end

  subgraph B[" PHASE B · FIX "]
    direction LR
    B1["04<br/>Fix Generator<br/>(strategize)"]
    HC{{"⏸ HUMAN<br/>approves plan"}}
    B2["04<br/>Fix Generator<br/>(implement)"]
    B1 --> HC --> B2
  end

  subgraph C[" PHASE C · VERIFY & SHIP "]
    direction LR
    C1["05<br/>Existing App<br/>Test Agent"]
    C2["06<br/>Additional Test<br/>Execution"]
    C3["07<br/>Audit & PR"]
    C1 --> C2 --> C3
  end

  R --> A2
  A3 --> B1
  B2 --> C1
  C3 --> OUT["📦 PR content<br/>+ audit trail"]

  classDef input fill:#5B6B7F,stroke:#3d4a5a,color:#fff,font-weight:bold
  classDef phaseA fill:#1F3864,stroke:#14254a,color:#fff,font-weight:bold
  classDef phaseA2 fill:#2E5FD9,stroke:#1e42a0,color:#fff,font-weight:bold
  classDef phaseA3 fill:#6E86E8,stroke:#4a63c8,color:#fff,font-weight:bold
  classDef phaseB fill:#3DA35B,stroke:#26773f,color:#fff,font-weight:bold
  classDef human fill:#F5C542,stroke:#b8901f,color:#3d2f00,font-weight:bold
  classDef phaseC fill:#16213E,stroke:#0a1020,color:#fff,font-weight:bold
  classDef ship fill:#A0399B,stroke:#732770,color:#fff,font-weight:bold
  classDef out fill:#3DA35B,stroke:#26773f,color:#fff,font-weight:bold

  class R input
  class A1 phaseA
  class A2 phaseA2
  class A3 phaseA3
  class B1,B2 phaseB
  class HC human
  class C1,C2 phaseC
  class C3 ship
  class OUT out
```

| Phase | Agent(s) | Question it answers |
|:--|:--|:--|
| 🔵 **A · Understand** | 01 – 03 | What does this codebase look like, *why* is this defect real, and *how far* does it reach? |
| 🟢 **B · Fix** | 04 | *How* should it be fixed, and what is the smallest diff that does it? |
| 🟣 **C · Verify & Ship** | 05 – 07 | Is the fix actually closed, bypass-proof, side-effect-free, tested, buildable — and shippable? |

---

## The 7 agents

Each agent is a markdown persona in [`agents/`](./agents/), numbered in execution order. Agents `04`
onward each drive more than one of the original nine pipeline stages internally — every constraint
and gate the original, more granular agents enforced (the human approval checkpoint, the "never edit
the real working tree" rule, "zero agent-authored judgment" for the build gate, "never runs `git` or
`gh`") still applies exactly as before; only the number of separate personas you invoke changed.

### 🔵 Phase A — Understand

| # | Agent | What it does | Writes |
|:--|:--|:--|:--|
| **01** | `01_architect` | Parses all Java source into structured artifacts, writes the semantic layer that gives the graph meaning, loads a Neo4j knowledge graph, and produces the architecture document. **Runs once per application**, then is reused by every later stage. | `01-architecture/` |
| **02** | `02_root-cause-analyst` | Diagnoses *why* each reported defect exists, correlating the issue row, the architecture doc, the function reference and live graph traversal. Confirms the bug is real — not a scanner false positive. | `02-root-cause/` |
| **03** | `03_blast-radius-analyst` | Measures how far the defect reaches: which services break, which degrade, which endpoints are exposed, who feels it. Assigns priority by **real reachability**, not theoretical severity. | `03-blast-radius/` |

### 🟢 Phase B — Fix

| # | Agent | What it does | Writes |
|:--|:--|:--|:--|
| **04** | `04_fix-generator` | **Stage 1 (strategize):** matches the defect against a curated **CWE-aligned remediation catalog** and writes a strategy — *never* a diff — at `Status: Proposed`. **Stage 2 (implement, Approved plans only):** the **only agent that produces code**. Turns an *Approved* plan into the smallest diff in the app's existing style, compiles it inside a throwaway worktree, and refuses any plan not marked Approved. | `04-remediation/` |

### 🟣 Phase C — Verify & Ship

| # | Agent | What it does | Writes |
|:--|:--|:--|:--|
| **05** | `05_existing-app-test-agent` | Runs all three static, reasoning-based checks against the existing patched app: **re-scan** (does the finding **still trigger**?), **red-team** (residual/alternate attack vectors the fix didn't cover, grounded in the CWE entry's anti-patterns), **behavior guard** (anything the fix plan doesn't explain — log format, exception types, return values, visibility). | `05-verify/rescan_*`, `redteam_*`, `behavior_*` |
| **06** | `06_additional-test-execution` | Drafts exactly one new regression test, then a **script** applies and runs it for real (the agent cannot interpret or override the exit code); then runs `mvn verify` plus a dependency-tree diff — **zero agent-authored content** in that half, deliberately, because there is nothing here to author. | `06-test-gate/qa_*`, `build_*` |
| **07** | `07_audit-and-pr` | Aggregates all five upstream reports into one deterministic weighted score against hard gates — **the only agent that may declare a patch safe to ship** — then writes PR content and a full chain-of-custody audit trail, **always**, Cleared or Blocked. It creates a PR only when explicitly requested for a Cleared verdict. | `07-ship/verdict_*`, `pr_*`, `audit_*` |

> **Stages 06·07·08 run together** inside `05_existing-app-test-agent`, and so do **09·10** inside
> `06_additional-test-execution`. Within an agent, the individual checks/gates remain independent —
> none needs another's result.

---

## The 14 skills

An **agent** is the persona and the judgement. A **skill** is the toolbox it drives: deterministic
Node scripts, JSON schemas, and reference catalogs. Skills live in [`skills/`](./skills/) and are
numbered to match **the agent that runs them**.

Three relationships are worth knowing:

```mermaid
flowchart LR
  A0["4 agents<br/>02 · 03 · 04 · 07"] -->|"all share"| K0["00-issue-register"]

  AA["1 agent<br/>01_architect"] -->|"runs 4, in order"| K1["01a-code-cartographer"]
  K1 --> K2["01b-context-weaver"]
  K2 --> K3["01c-graph-forge"]
  K3 --> K4["01d-blueprint-scribe"]

  AV["1 agent<br/>05_existing-app-test-agent"] -->|"runs all three checks"| K6["05-verify"]

  classDef skill fill:#2E5FD9,stroke:#1e42a0,color:#fff,font-weight:bold
  classDef agent fill:#1F3864,stroke:#14254a,color:#fff,font-weight:bold
  classDef shared fill:#A0399B,stroke:#732770,color:#fff,font-weight:bold
  class K1,K2,K3,K4 skill
  class K0,K6 shared
  class A0,AA,AV agent
```

| Skill | Run by | Purpose |
|:--|:--|:--|
| `00-issue-register` | 02, 03, 04, 07 | Reads the Excel register; owns the column contract for all four consumers |
| `01a-code-cartographer` | 01 | Parses every `pom.xml` + `.java` file into `artifacts.json` via tree-sitter |
| `01b-context-weaver` | 01 | Selects architecturally significant nodes and validates the descriptions the agent writes |
| `01c-graph-forge` | 01 | Loads artifacts **and** the semantic layer into Neo4j |
| `01d-blueprint-scribe` | 01 | Synthesizes `architecture.md` + `function-reference.md` |
| `02-root-cause-analyst` | 02 | Evidence collection, analysis schema, report rendering |
| `03-blast-radius-analyst` | 03 | Reach measurement, narrative schema, diagram-led report |
| `04a-fix-strategist` | 04 (Stage 1) | CWE pattern catalog + fix-plan rendering |
| `04b-fixer` | 04 (Stage 2) | Patch verification in an isolated worktree + fix-report rendering |
| `05-verify` | 05 | One agent, three independent checks (re-scan, red-team, behavior guard) against identical inputs |
| `06a-qa-runner` | 06 (Gate 1) | Regression-test scaffolding + deterministic test gate |
| `06b-build-gatekeeper` | 06 (Gate 2) | `mvn verify` + dependency-tree diff gate |
| `07a-merge-arbiter` | 07 (Part 1) | Deterministic scoring against externalised weights |
| `07b-scribe` | 07 (Part 2) | Chain-of-custody collection + PR/audit rendering |

> **Skill folders are numbered to match the agent that runs them, exactly like the Architect's
> `01a`–`01d`.** A single-skill agent gets a plain number (`02-root-cause-analyst`, `03-blast-radius-analyst`,
> `05-verify`); an agent driving more than one skill gets a lettered suffix per skill, in run order
> (`04a-fix-strategist`/`04b-fixer`, `06a-qa-runner`/`06b-build-gatekeeper`,
> `07a-merge-arbiter`/`07b-scribe`). `00-issue-register` keeps its own `00` prefix — it's shared
> infrastructure with no single owning agent.

---

## How the agents connect

**The pipeline is wired through files, not through direct calls.** Each stage writes a document into
`docs/agent_output/`, and the next stage's workload is simply "every file the previous stage wrote."

```mermaid
flowchart TD
  REG[/"📊 00-issues/issue-register.xlsx<br/><i>human input · read-only</i>"/]
  SRC[/"☕ Java source<br/>6 Maven modules"/]

  ARCH["01_architect"]
  DOCS[/"01-architecture/<br/>architecture.md<br/>function-reference.md"/]
  GRAPH[("🔗 Neo4j<br/>knowledge graph")]

  RCA["02_root-cause-analyst"]
  RC[/"02-root-cause/<br/>root_cause_&lt;id&gt;.md"/]

  BR["03_blast-radius-analyst"]
  BRD[/"03-blast-radius/<br/>blast_radius_&lt;id&gt;.md"/]

  FS["04_fix-generator (strategize)"]
  FP[/"04-remediation/<br/>Status: Proposed"/]
  GATE{{"⏸ HUMAN CHECKPOINT<br/>Status → Approved"}}

  FX["04_fix-generator (implement)"]
  FIX[/"04-remediation/<br/>fix_&lt;id&gt;.md + .diff"/]

  V1["05_existing-app-test-agent"]
  VER[/"05-verify/<br/>rescan · redteam · behavior"/]

  QA["06_additional-test-execution"]
  QAD[/"06-test-gate/<br/>qa_* + build_*"/]

  MA["07_audit-and-pr (arbitrate)"]
  VD[/"07-ship/verdict_&lt;id&gt;.md<br/>Cleared | Blocked"/]
  SC["07_audit-and-pr (write up)"]
  OUT[/"07-ship/<br/>pr_&lt;id&gt;.md + audit_&lt;id&gt;.md"/]

  SRC --> ARCH
  ARCH --> DOCS
  ARCH --> GRAPH
  REG --> RCA
  DOCS --> RCA
  GRAPH --> RCA
  RCA --> RC
  RC --> BR
  DOCS --> BR
  GRAPH --> BR
  BR --> BRD
  RC --> FS
  BRD --> FS
  FS --> FP
  FP --> GATE
  GATE --> FX
  FX --> FIX
  FIX --> V1
  V1 --> VER
  FIX --> QA
  QA --> QAD
  VER --> MA
  QAD --> MA
  MA --> VD
  VD --> SC
  SC --> OUT

  classDef data fill:#EEF2FA,stroke:#5B6B7F,color:#16213E
  classDef store fill:#5B6B7F,stroke:#3d4a5a,color:#fff
  classDef pa fill:#1F3864,stroke:#14254a,color:#fff,font-weight:bold
  classDef pa2 fill:#2E5FD9,stroke:#1e42a0,color:#fff,font-weight:bold
  classDef pa3 fill:#6E86E8,stroke:#4a63c8,color:#fff,font-weight:bold
  classDef pb fill:#3DA35B,stroke:#26773f,color:#fff,font-weight:bold
  classDef human fill:#F5C542,stroke:#b8901f,color:#3d2f00,font-weight:bold
  classDef pc fill:#16213E,stroke:#0a1020,color:#fff,font-weight:bold
  classDef ship fill:#A0399B,stroke:#732770,color:#fff,font-weight:bold

  class REG,SRC,DOCS,RC,BRD,FP,FIX,VER,QAD,VD,OUT data
  class GRAPH store
  class ARCH pa
  class RCA pa2
  class BR pa3
  class FS,FX pb
  class GATE human
  class V1,QA pc
  class MA,SC ship
```

**Why file-based handoff matters:** every stage is independently re-runnable, independently
auditable, and independently reviewable by a human. If one of the checks inside
`05_existing-app-test-agent` is wrong, you fix that one check and re-run it — nothing else has to
move.

---

## The pattern every stage follows

This is the single most important architectural idea in the harness. **Facts and judgement are
never written by the same thing.**

```mermaid
flowchart LR
  IN[/"Upstream<br/>documents"/]
  COL["1 · COLLECT<br/>script"]
  F[/"&lt;id&gt;.facts.json<br/>measured facts"/]
  AG["2 · AUTHOR<br/>agent"]
  J[/"&lt;id&gt;.verdict.json<br/>schema-validated<br/>judgement"/]
  REN["3 · RENDER<br/>script"]
  OUT[/"docs/NN-stage/report.md<br/>the deliverable"/]

  IN --> COL --> F --> AG --> J --> REN --> OUT
  F --> REN

  classDef script fill:#16213E,stroke:#0a1020,color:#fff,font-weight:bold
  classDef agent fill:#3DA35B,stroke:#26773f,color:#fff,font-weight:bold
  classDef data fill:#EEF2FA,stroke:#5B6B7F,color:#16213E
  class COL,REN script
  class AG agent
  class IN,F,J,OUT data
```

| Step | Who | Can it invent anything? |
|:--|:--|:--|
| **1 · Collect** | Deterministic Node script | ❌ No — it only reads and measures |
| **2 · Author** | The agent | ⚠️ Only within a **JSON schema**, validated before rendering |
| **3 · Render** | Deterministic Node script | ❌ No — it merges 1 and 2 into Markdown |

Because of this split, a reviewer reading any report can tell **which claims are measured and which
are reasoned** — and the agent physically cannot overwrite a measured fact.

The intermediate files live in [`.pipeline-context/`](./.pipeline-context/) (gitignored — regeneratable at any time):

| Folder | Contents |
|:--|:--|
| `.pipeline-context/artifacts.json` | The parsed code model |
| `.pipeline-context/context/` | Semantic descriptions (the one tracked file — expensive to reproduce) |
| `.pipeline-context/rca/` · `blast-radius/` · `fix-strategy/` | Facts + agent analysis per stage |
| `.pipeline-context/verify/` · `merge/` · `scribe/` | Phase C facts, scores and narratives |

---

## Human checkpoints

The pipeline stops and waits for a person **twice** — both deliberately placed where automation
should not be trusted alone.

| # | Where | What the human does | Why here |
|:--|:--|:--|:--|
| 1️⃣ | After **04 · Fix Generator, Stage 1 (strategize)** | Edits the plan's `Status` from `Proposed` → `Approved` (or `Rejected`) | **No code is written before this.** A human signs off on the *approach* while changing it is still cheap |
| 2️⃣ | After **07 · Audit & PR, Part 2 (write up)** | Explicitly requests publication for a Cleared verdict | Agent 07 revalidates the rendered diff in an isolated worktree, then creates and pushes a PR branch |

`04_fix-generator` refuses — plainly, without negotiating — any plan not marked `Approved` before it
will draft a diff for it.

---

## The ship decision

The **Arbitrate** step of `07_audit-and-pr` is the only place a patch is declared safe. Scoring is
**fully deterministic** and the weights live in an editable, auditable
[`scoring.json`](./skills/07a-merge-arbiter/scoring.json) — never in code.

```mermaid
flowchart TD
  S["Five upstream reports"] --> HG{"Hard gates"}
  HG -->|"re-scan = STILL_VULNERABLE"| BL["🚫 BLOCKED"]
  HG -->|"build = Failed"| BL
  HG -->|"neither"| SC["Weighted score<br/>0 – 100"]
  SC --> TH{"score ≥ severity<br/>threshold?"}
  TH -->|yes| CL["✅ CLEARED"]
  TH -->|no| BL

  classDef gate fill:#F5C542,stroke:#b8901f,color:#3d2f00,font-weight:bold
  classDef bad fill:#C0392B,stroke:#8d2618,color:#fff,font-weight:bold
  classDef good fill:#3DA35B,stroke:#26773f,color:#fff,font-weight:bold
  classDef neutral fill:#16213E,stroke:#0a1020,color:#fff
  class HG,TH gate
  class BL bad
  class CL good
  class S,SC neutral
```

**Weights** &nbsp;·&nbsp; Red-team `30` &nbsp;|&nbsp; Behavior `30` &nbsp;|&nbsp; QA `40`

**Severity-scaled thresholds**

| Severity | Threshold |
|:--|:--|
| 🔴 Critical | 90 |
| 🟠 High | 85 |
| 🟡 Medium | 75 |
| 🟢 Low | 65 |

> **Hard gates cannot be out-scored.** A patch that still triggers the original finding, or that
> fails the build, is Blocked no matter how well it scores elsewhere.
>
> `07_audit-and-pr` may only make a computed `Cleared` decision more conservative by overriding it to
> `Blocked`. An override requires an explicit reason citing evidence, and the rendered verdict always
> shows the computed decision and the override side by side.

---

## Status vocabulary

Every pipeline stage communicates through a small, fixed set of statuses that downstream stages gate
on. These numbers identify the **output folder and pipeline stage** (`docs/agent_output/NN-*/`), which is
unchanged even where one agent now drives several stages — see [The 7 agents](#the-7-agents) above
for which agent owns which stage.

| Stage | Field | Values |
|:--|:--|:--|
| 04 · Fix plan | `Status` | `Proposed` → `Approved` \| `Rejected` &nbsp;*(human-set)* |
| 05 · Fix | `Status` | `Compiled` \| `Compile Failed` \| `Refused` |
| 06 · Re-scan | `verdict` | `FIXED` \| `STILL_VULNERABLE` \| `INCONCLUSIVE` |
| 07 · Red-team | `verdict` | `NO_BYPASS_FOUND` \| `BYPASS_FOUND` \| `INCONCLUSIVE` |
| 08 · Behavior | `verdict` | `BEHAVIOR_PRESERVED` \| `BEHAVIOR_CHANGED` \| `INCONCLUSIVE` |
| 09 · QA | `Status` | `Passed` \| `Failed` &nbsp;*(script-decided from the real exit code)* |
| 10 · Build | `Status` | `Passed` \| `Failed` &nbsp;*(script-decided from the real exit code)* |
| 11 · Verdict | `Decision` | `Cleared` \| `Blocked` |

> Stages 06–10 accept `Compile Failed` fixes too — a patch that doesn't build still has a real diff
> worth rechecking and independently gating. Only `Refused` (no diff drafted) is out of scope. An individual test marked
> `SKIPPED` inside a Passed QA report is never counted as a pass — it means this sandbox lacked the
> dependency to run it.

The canonical transition and ownership rules are in [pipeline-contract.md](./pipeline-contract.md).
Run `node .github/scripts/pipeline-lint.js` after changing an agent, skill, renderer, or active
output index to catch contract drift.

---

## The input: an Excel issue register

Issues are **not** markdown files. They live in one spreadsheet — because that is how scanner
exports, tracker dumps and security reviewers already work.

📊 **[`docs/agent_output/00-issues/issue-register.xlsx`](../docs/agent_output/00-issues/issue-register.xlsx)** — one row per
vulnerability, 19 columns. Add a row in Excel, re-run from stage 02.

| Group | Columns |
|:--|:--|
| **Identity** | `issue_id` · `title` · `type` · `severity` · `status` · `reported_on` · `reported_by` |
| **Location** *(one value per line in the cell)* | `affected_services` · `affected_symbols` · `affected_files` · `entry_points` |
| **Narrative** *(long text)* | `summary` · `affected_area` · `data_flow` · `observed_behavior` · `expected_behavior` · `steps_to_reproduce` · `impact` · `detection_notes` |

The loader rebuilds each row into a markdown document (`## Summary`, `## Observed Behavior`,
`## Detection Notes`) before any agent reads it, so the narrative columns keep full markdown
formatting — and `detection_notes` is where stage 06 gets the grep signatures it re-checks against
patched code.

Full column contract: [`docs/00-issues/README.md`](./docs/00-issues/README.md).

---

## Repository layout

Everything the harness owns lives under `.github/`. **Skill and output-folder numbers still match
each other** (skill `04a-fix-strategist`/`04b-fixer` ↔ output `04-remediation/`) even though agents
were consolidated — an agent now simply drives more than one skill, writing into one merged output
folder per agent.

```
.github/
├── README.md                    ← you are here
│
├── agents/                      7 personas, numbered in execution order
│   ├── 01_architect.agent.md
│   ├── 02_root-cause-analyst.agent.md
│   ├── 03_blast-radius-analyst.agent.md
│   ├── 04_fix-generator.agent.md            plan (⏸ human approval) then implement
│   ├── 05_existing-app-test-agent.agent.md  re-scan + red-team + behavior guard
│   ├── 06_additional-test-execution.agent.md  QA gate + build gate
│   └── 07_audit-and-pr.agent.md             arbitrate (Cleared|Blocked) then write up
│
├── skills/                      14 toolboxes, numbered to the agent(s) that run them
│   ├── 00-issue-register/       shared by every issue-consuming agent
│   ├── 01a-code-cartographer/   ┐
│   ├── 01b-context-weaver/      ├─ the Architect runs all four, in order
│   ├── 01c-graph-forge/         │
│   ├── 01d-blueprint-scribe/    ┘
│   ├── 02-root-cause-analyst/
│   ├── 03-blast-radius-analyst/
│   ├── 04a-fix-strategist/      + catalog/cwe-patterns.json  ┐ both run by 04_fix-generator
│   ├── 04b-fixer/                                              ┘
│   ├── 05-verify/               all three checks run by 05_existing-app-test-agent
│   ├── 06a-qa-runner/           ┐ both run by 06_additional-test-execution
│   ├── 06b-build-gatekeeper/    ┘
│   ├── 07a-merge-arbiter/       + scoring.json
│   └── 07b-scribe/
│
├── docs/                        generated deliverables — committed
│   ├── 00-issues/               issue-register.xlsx  ← human input
│   ├── 01-architecture/         architecture.md · function-reference.md
│   ├── 02-root-cause/
│   ├── 03-blast-radius/
│   ├── 04-remediation/          ⏸ human approves here; fix_plan_*.md + fix_*.md/.diff
│   ├── 05-verify/               rescan · redteam · behavior
│   ├── 06-test-gate/            qa_*.md + build_*.md
│   ├── 07-ship/                 verdict · pr · audit
│   └── VULNERABILITY_REMEDIATION_SUMMARY.md
│
└── .pipeline-context/                  working data — gitignored, regeneratable
```

Each skill folder is self-contained (its own `package.json` and scripts) so it can be lifted into
another repository on its own.

---

## Getting started

**Prerequisites** — Node.js 18+, Maven, and optionally a Neo4j instance (Aura or Docker). Without
Neo4j the pipeline still runs; graph-backed sections are simply omitted.

```powershell
# 1. Install dependencies for the Architect's skills
cd .github/skills/01a-code-cartographer; npm install
cd ../01b-context-weaver;                npm install
cd ../01c-graph-forge;                   npm install
cd ../01d-blueprint-scribe;              npm install

# 2. Point Graph Forge at your own Neo4j instance
cd ../01c-graph-forge
Copy-Item .env.example .env      # then fill in NEO4J_URI / USERNAME / PASSWORD
```

> Skills `00`, `04a`, `04b`, `05`, `06a`, `06b`, `07a` and `07b` have **zero dependencies** — nothing
> to install. Even the Excel reader is built on Node's own `zlib`.

**Then drive it from Copilot Chat**, one agent at a time, in numeric order:

```
run the 01_architect agent          → architecture + knowledge graph
run the 02_root-cause-analyst agent → why each issue is real
run the 03_blast-radius-analyst agent
run the 04_fix-generator agent      → writes plans, then approve one by hand ⏸, then re-run to implement
run the 05_existing-app-test-agent agent  → re-scan + red-team + behavior guard
run the 06_additional-test-execution agent → QA gate + build gate
run the 07_audit-and-pr agent        → arbitrate, then write PR + audit content
```

Every skill also has a `list-*.js` script that shows its workload and pipeline state without
changing anything — the fastest way to see where a given issue currently stands:

```powershell
cd .github/skills/00-issue-register; node scripts/list-register.js
cd ../02-root-cause-analyst;         node scripts/list-issues.js
cd ../07a-merge-arbiter;              node scripts/list-merge-workload.js
```

---

## Design principles

| Principle | How it is enforced |
|:--|:--|
| 🔒 **Never touch the working tree** | Every patch is applied inside a throwaway `git worktree` created from `HEAD` and destroyed immediately after |
| 📏 **Facts ≠ judgement** | Collected by scripts, authored by agents, merged by renderers — in separate files |
| 🎯 **One job per agent** | No agent both writes code and judges it; no agent both measures reach and diagnoses cause |
| ⚖️ **Determinism where it counts** | QA, build and scoring outcomes are decided by scripts and exit codes; agents cannot soften a `FAIL` |
| 📚 **Externalised knowledge** | CWE patterns and scoring weights are editable JSON, not logic buried in code |
| 🧾 **Always leave a record** | The Scribe writes an audit trail for Blocked patches too — a rejection is evidence, not a dead end |
| 🚫 **Read-only inputs** | The issue register and every upstream report are never written to by a downstream stage |

---

<div align="center">

**Issue format →** [`docs/00-issues/README.md`](./docs/00-issues/README.md)

</div>
