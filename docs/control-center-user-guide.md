# MARS Control Center: user guide

The Control Center shows MARS runs live and lets authorized people make the decisions MARS stops
for. It reads everything from the run directories the CLI writes, so CLI runs appear too, and both
can be used on the same runs root.

## Run it

Prerequisites: JDK 21, Maven 3.9, Node.js 20.19+ (22 recommended).

### Development (two processes, hot reload)

```bash
# 1. the API (from the repository root)
mvn -pl apps/control-center-api -am install -DskipTests
java -jar apps/control-center-api/target/control-center-api-1.0.0-exec.jar
#    http://127.0.0.1:8080 — API, Swagger UI at /swagger-ui.html

# 2. the UI (Vite proxies /api to the API)
cd ui/mars-control-center
npm ci --include=dev
npm run dev
#    http://127.0.0.1:5173
```

Sign in with a development user (`viewer`, `operator`, `approver` or `admin`; the password is
`mars-dev`, or `MARS_CC_DEV_PASSWORD`). Development identities are not authentication, and every
decision they record says `DEVELOPMENT_ASSERTED`.

If your environment sets `NODE_ENV=production`, npm skips dev dependencies. Use
`npm ci --include=dev`, as above.

### Integrated (one process)

```bash
cd ui/mars-control-center && npm ci --include=dev && npm run build && cd ../..
java -jar apps/control-center-api/target/control-center-api-1.0.0-exec.jar \
     --mars.control-center.ui-dir=ui/mars-control-center/dist
# or package the UI into the jar:
mvn -pl apps/control-center-api -am package -Pui -DskipTests
```

### Configuration

| Property | Default | |
|---|---|---|
| `mars.control-center.harness-root` | auto-detected like the CLI | the MARS installation |
| `mars.control-center.runs-root` | `<harness-root>/runs` | the same runs the CLI writes |
| `mars.control-center.repository-roots` | `fixtures` | where runs may be started from; paths outside are refused |
| `mars.control-center.max-concurrent-runs` | 2 | engine operations in parallel (each may run builds) |
| `mars.control-center.maven-offline` / `network` / `today` | false / false / today | as the CLI's `--maven-offline`, `--network`, `--today` |
| `mars.control-center.ui-dir` | unset | serve a built UI from this directory |
| `mars.control-center.auth.mode` | `dev` | `dev` or `oidc` (see [control-center-security.md](control-center-security.md)) |
| `server.address` / `server.port` | `127.0.0.1` / `8080` | `MARS_CC_ADDRESS`, `MARS_CC_PORT` |

Builds use the same adapters as the CLI. If Maven is not on the server's `PATH`, builds are
recorded as `TOOL_UNAVAILABLE`, never as passed.

## The screens

**Runs.** The run history from the runs root. It filters by status (active, waiting, needs human,
complete, failed) and capability, and searches run ID, application and source. **New run** (for
OPERATOR and ADMIN) starts phases 0–4 on a repository under a configured root, with its finding
inputs and probes. MARS then stops at Gate A.

**Overview.** The run at a glance: current state and what it means, run health, the stage strip,
the strategy, migration, findings, approvals, fixes cleared, validation and verdict, plus the
current activity. Run health is one of:

- **Advancing:** this server is running the engine.
- **Waiting for a human.**
- **Idle:** not waiting and not advancing; resume continues it.
- **Activity from another process:** for example the CLI.
- **Analysis interrupted:** start a new run.

The stage strip counts completed stages out of those on this run's path. It is not a time
estimate.

**Pipeline.** The real MARS lifecycle as a graph. The stages and their possible transitions come
from the state machine, and the highlighted edges are the path this run actually took. Click a stage
to see its times, transition reason, next possible stages, and the events, evidence and errors
recorded while the run was in it. An active stage has an animated border. A skipped stage was ruled
out by the run's own facts, such as the strategy or reaching final validation.

**Human actions.** Why MARS stopped, what to decide, what each option does (in the engine's own
terms), MARS's advice (labelled as advice and never preselected), what to inspect, and the hash the
decision is bound to. A decision needs a rationale and a review step. Its receipt (decision ID,
integrity, bound hash, actor, authentication) appears only when the server returns the recorded
decision, and it stays visible after the gate closes.

- **Gate A** chooses the execution strategy. MARS starts advancing once the decision is recorded.
- **Gate B** lists the remediation proposals awaiting a decision. Decide each one, then choose
  **Continue**. To continue while some are still undecided, tick "Leave the N undecided proposal(s)
  pending": they stay unapproved, are never applied, and are reported as pending.
- **Gate A2** (post-security migration) and **migration plan approval** work like Gate A.
- **NEEDS_HUMAN** explains what stopped the run and what resolves it. A migration round that no
  pack rule can fix needs a human patch (`harness submit-patch`), approved here or at the CLI. A
  NEEDS_HUMAN verdict is not resumable. There is no "continue anyway".

**Activity.** Every execution event, live. Filter by category (kernel, migration, security, human,
mutation, validation, error) and search. **Pause follow** only stops the view from scrolling; MARS
keeps running. Click an event to see its facts, subjects, evidence and artifacts.

**Security.** Findings by severity, how many were analysed, planned, awaiting a decision, applied,
cleared, blocked and deferred, and the findings table. A finding's page shows its remediation
journey (intake, anchoring, RCA, blast radius, routing, proposal, approval, mutation, verification,
arbiter, outcome), the anchored source lines, the root cause, the blast radius, the plan and the
verification report.

**Migration.** The assessment (traffic light and rationale, need, complexity, the decomposed effort
score, lifecycle, target), the frozen plan (rules, round limit, stop conditions), every round (rules
applied, errors by category, tests, diagnosis, build log), the behaviour and test comparison with
round 0, and validation. A plan that cannot execute is shown as MIGRATION BLOCKED with the real
reason: MARS does not improvise an unsupported migration.

**Changes.** Every proposal (migration rules, security fixes, manual patches), with its capability,
status, decision, mutation and validation. A proposal's page shows the exact diff bound to its hash
(side by side, unified, or the full new content), provenance and scope. It also shows the Mutation
Gateway's recorded checks (baseline, identity, authorization, scope, base hash, checkpoints,
identity sync, bypass detection), the decision history and lineage. Checks that were not recorded
are shown as not recorded, never as passed.

**Graph.** The canonical graph (Bootshift's application graph and the identity overlay). Search it,
focus a node and expand its neighbours, filter by node type, and highlight findings, changed code,
blast radius or migration issues. Views are bounded, and a truncated view says so.

**Evidence.** The hash-chained evidence records in order, with the chain status recomputed from
disk. Filter them and open any record and its artifact.

**Validation.** Each validation dimension separately, with dimensions that have no result listed
as such, plus migration validation, per-fix security verification, proposal validation and the
baseline.

**Verdict.** The verdict exactly as MARS calculated it: reasons, hard failures, unknown dimensions,
pending decisions, every item's status, and the evidence package (reports, patch).

**Logs.** Tool output (baseline and round builds, verification builds, runtime). Filter by level
and search; follow while the run advances. Logs are supplementary; the dashboard does not depend on
them.

## A demo that shows real work

1. Sign in as `admin`. Choose **New run**, `composite/inventory-service`, keep both finding inputs
   and the probes, and start. With Maven on the server's `PATH`, the baseline build is real.
2. Open **Pipeline** and watch ingest, inventory, identity, graph, baseline and discovery complete.
   Open **Activity** to see RCA and blast radius progress per finding.
3. At **Gate A**, walk through the assessment (migration RED: the springdoc fix needs Boot 4) and
   the advice, choose MIGRATE_FIRST, and give a rationale. Show the receipt.
4. Watch the migration rounds on **Migration** (each round's rules and build outcome).
5. At **Gate B**, open the INV-101 fix on **Changes**: the exact diff, the bound hash, and the
   "not recorded" gateway checks. Approve it, then **Continue** and leave the rest pending.
6. The gateway record now shows every check verified. Open **Verdict**, **Validation** and
   **Evidence**.

Operations that finish in milliseconds are still in the timeline afterwards. Nothing is slowed down
for the demo.

To rehearse the demo unattended, `node e2e/live-demo.mjs <url> <screenshot dir> MIGRATE_FIRST`
drives steps 1–3 through the UI and captures each screen. `node e2e/continue-demo.mjs <url> <run id>
<screenshot dir>` then does steps 5–6 for a run waiting at Gate B.

## Operations

- **Health:** `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`
  (includes the runs root and harness installation).
- **Metrics:** `/actuator/metrics` (ADMIN). `mars.control_center.jobs` is a timer by kind and
  outcome; `mars.control_center.jobs.active`; `mars.control_center.decisions` counts by type and
  selection; `mars.execution.events` counts by category, type and status. Labels are bounded: run,
  finding and proposal IDs are never metric labels.
- **Traces:** OpenTelemetry through Micrometer Tracing. Set `MARS_CC_OTLP_ENABLED=true` and
  `management.otlp.tracing.endpoint=http://collector:4318/v1/traces`. Each engine job is a span
  (`mars.run.analyze` or `mars.run.advance`), with child spans per activity rebuilt from the run's
  events at their recorded times. Spans carry `mars.run.id`, `mars.state`, `mars.activity`,
  `mars.capability`, `mars.finding.id`, `mars.proposal.id`, `mars.round` and `mars.result`.
- **Logs:** every line carries `corr=<correlation id>` and `run=<run id>`. The correlation ID is
  returned in `X-Correlation-Id` and in error bodies.

## Tests

```bash
mvn install                                   # everything, including the Control Center Java tests
cd ui/mars-control-center
npm run typecheck && npm run lint && npm test # unit tests (Vitest + Testing Library)
npm run build && npm run e2e                  # Playwright against the packaged real backend
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| "RECONNECTING" or "OFFLINE" in the top bar | the event stream is down. The run is not affected; the page keeps refreshing the snapshot and reconnects on its own. |
| `RUN_BUSY` | MARS is advancing the run, or another command is being recorded. Wait for it to stop. |
| `STALE_ASSESSMENT` or `PROPOSAL_HASH_MISMATCH` | what you reviewed changed. Reload and review again. |
| "Activity from another process" | something else (usually `harness resume` in a terminal) appended events recently. Do not advance the same run from two places. |
| A run shows no events | it was recorded before the event stream existed. It is shown from its state history. |
| Builds are `TOOL_UNAVAILABLE` | Maven is not on the server's `PATH`. |
