# MARS Control Center: architecture

The Control Center is a web interface for watching MARS runs live and recording human decisions.
It does not replace the engine. It is a second composition root next to the CLI, and every change
it makes to a run is a call to the engine's own public operations.

```
 CLI (harness) ─────────┐
                        ▼
                   MARS engine  ── HarnessEngine, RunAdvancer, RunStateMachine, ApprovalPort,
                        ▲          MutationGateway, IdentitySynchronizer, UnifiedValidator, …
 Control Center API ────┘
        ▲   REST (snapshots, commands)    SSE (execution events)
        │
 Control Center UI (React)
```

The rest of this document covers the modules, how data flows, and the invariants that hold.
Related documents:

- [control-center-api.md](control-center-api.md): endpoints, conventions and errors
- [control-center-events.md](control-center-events.md): the event model and the SSE protocol
- [control-center-security.md](control-center-security.md): the authentication boundary, RBAC and limitations
- [control-center-user-guide.md](control-center-user-guide.md): how to run and use it
- [ADR-U008](adr/ADR-U008-control-center-event-streaming.md): why events, SSE, and decisions through the engine

## Modules

| Location | What it is |
|---|---|
| `kernel/core/.../event` | `ExecutionEvent`, `ExecutionEventType` (with category, and which types capabilities may report), `ActivityStatus` |
| `kernel/core/.../decision/DecisionActor` | who decides and how that identity was established (closed vocabulary) |
| `kernel/ports/.../event` | `ExecutionEventStore` (append, replay), `ActivityReporter` (capabilities' restricted reporting port) |
| `kernel/adapters/.../store/FilesystemExecutionEventStore` | `events/events.jsonl`: append-only, gap-free sequence, torn-write tolerant, byte-offset tailing |
| `kernel/engine/.../event` | `ExecutionEventRecorder` (one per `RunSession`), `HumanGates` (describes the gate a run is stopped at), `Components` (executor names) |
| `apps/control-center-api` | Spring Boot 3.5 service: query projections, SSE, commands, security, telemetry |
| `ui/mars-control-center` | React 19 + TypeScript + Vite single-page application |
| `tests/.../controlcenter`, `tests/.../architecture/ControlCenterArchitectureTest` | HTTP-level integration tests over the real engine, projection and security tests, architecture rules |

## Where events come from

Events are emitted where the engine does the work. The component name in each event is the real
executor, not an invented "agent".

| Phase | Emitted by | Events |
|---|---|---|
| Create, ingest | `HarnessEngine.analyze` | `RUN_CREATED`, `INGEST_STARTED`, `INGEST_STAGE_COMPLETED` (one per Bootshift stage), `INGEST_COMPLETED` |
| Inventory, identity, graph | `HarnessEngine.analyze` | `INVENTORY_COMPLETED` (file count), `IDENTITY_STARTED/COMPLETED`, `GRAPH_BUILD_STARTED/COMPLETED` |
| Baseline | `HarnessEngine.sealBaseline` | `BASELINE_BUILD_STARTED/COMPLETED` (or SKIPPED), `BASELINE_PROBE_*`, `BASELINE_SEALED` |
| Discovery | `HarnessEngine.discover` and the security capability | `DISCOVERY_*`, `SECURITY_DISCOVERY_*`, `SECURITY_SCAN_COMPLETED`, `RCA_STARTED/COMPLETED` and `BLAST_RADIUS_STARTED/COMPLETED` per finding (with finding counts), `MIGRATION_ASSESSMENT_*`, `SEQUENCE_ASSESSMENT_COMPLETED` |
| Gates | `HarnessEngine` (after each stop) | `HUMAN_ACTION_REQUIRED` with the gate, reason and accepted options; `DECISION_RECORDED` for every decision |
| Migration | `RunAdvancer`, the reference-pack engine | `MIGRATION_PLAN_CREATED`, `MIGRATION_EXECUTION_*`, `MIGRATION_ROUND_STARTED/COMPLETED` (round, intent, outcome), `MIGRATION_RULE_APPLIED`, `MIGRATION_VALIDATION_*` |
| Security remediation | `RunAdvancer`, the security capability | `REMEDIATION_PLANNING_*`, `REMEDIATION_ROUTED` per finding (catalog, KB, research, evidence gap), `FIX_VERIFICATION_STARTED/COMPLETED` (arbiter decision and score) |
| Mutation | `MutationGateway` | `PROPOSAL_REGISTERED`, `MUTATION_STARTED`, `MUTATION_APPLIED` (checks passed, checkpoints, change IDs, identity sync, bypass result), `MUTATION_REFUSED` (reason code), `MUTATION_ROLLED_BACK`, `MUTATION_BYPASS_DETECTED`, `PROPOSAL_VALIDATION_RECORDED` |
| Graph | `RunAdvancer.rebuildGraph` | `GRAPH_REBUILT` after each mutation batch |
| Final | `RunAdvancer.finalValidation` | `FINAL_VALIDATION_STARTED/COMPLETED` (every dimension's status), `VERDICT_COMPUTED` |
| Any state change | `RunSession.saveRecord` | `STATE_TRANSITION` for every persisted transition, in order, exactly once per `transition_index` |

## Read side

The API never loads a `RunSession`, because saving one publishes state. `RunReader` reads the
published artifacts directly through the read side of the kernel stores: the run record, findings,
proposals, decisions (with an integrity check), evidence (with a chain check), plans, executions,
validation, verdict, lineage, checkpoints and the canonical graph. Projections turn those artifacts
into DTOs:

- **Pipeline** (`PipelineProjector`): the stages are named groups of `RunPhase`s, and their order
  and edges come from `RunStateMachine.successorsOf`. A stage's status follows from the persisted
  history. Analysis stages complete at their completion state (`INVENTORY_READY` means the
  inventory is done), and the next one is in progress. Execution-branch states name the work in
  progress. Unvisited stages are PENDING, or SKIPPED once the run's own facts rule them out (the
  strategy, a Gate A2 that cannot be offered, or reaching final validation). The highlighted path is
  the actual sequence of stages the history went through.
- **Liveness**: whether this server is advancing the run (`ADVANCING`), whether MARS stopped for a
  human, finished or failed, and whether another process appended events recently
  (`EXTERNAL_ACTIVITY`). An idle run that is not waiting is `IDLE` ("resume continues it"). An
  analysis that stopped before Gate A is `INTERRUPTED_ANALYSIS` and is not offered as resumable,
  because MARS analysis is not resumable.
- **Human actions** (`HumanActionProjector`): for the gate the run is stopped at, why it stopped,
  what to decide, the options with the consequence the engine's routing implements, MARS's advice
  (never preselected), what to inspect, and the hash the decision binds to.
- **Findings, proposals, migration, validation, verdict**: the capability and kernel records,
  unchanged in meaning. The verdict is the calculator's; the API computes none.

`EventIndex` mirrors each run's `events.jsonl` in memory by reading only newly appended bytes, and
drops and rebuilds the mirror from the file whenever needed.

## Command side

`RunCommandService` is the only class that calls `HarnessEngine.analyze`, `decide*` or `resume`
(an architecture rule enforces this). For every command:

1. **Permit.** `RunCoordinator` holds one permit per run. A second command, a second tab or a
   duplicate click gets `RUN_BUSY`; nothing interleaves. A decision that also advances the run
   (Gate A, A2, plan approval) hands its permit straight to the advancement job.
2. **Preconditions against what the reviewer saw.** The run is at the right gate; the assessment,
   plan or proposal still hashes to `expected_*_hash`; no decision exists yet, or the request
   names the one it supersedes.
3. **The engine records.** `HarnessEngine.decide*` builds the `Decision` and records it through the
   `ApprovalPort` (validation, HMAC, write-once), then emits `DECISION_RECORDED`.
4. **The engine advances.** `HarnessEngine.resume` runs the unchanged `RunAdvancer`. The Mutation
   Gateway's authorization, identity, scope, base-hash and bypass checks apply exactly as for the
   CLI.

Gate B decisions do not advance by themselves. A reviewer usually decides several proposals, and
applying one while the next is being reviewed would block that review. "Continue execution" is an
explicit resume.

## Live flow

```
engine operation ─► ExecutionEventRecorder ─► FilesystemExecutionEventStore (events.jsonl)
                                                         │  (the CLI appends to the same file)
                        EventIndex (tail by byte offset) ◄┘
                                 │
                    EventStreamService (SSE: replay after Last-Event-ID, then live; heartbeat)
                                 │
                    RunEventStream (browser; dedupe by sequence; gap → refetch)
                                 │
                    LiveRunProvider ─► event buffer (feed)  +  invalidate snapshot queries
                                 │
                             screens (always re-read authoritative snapshots)
```

## Human approval flow

```
UI: choose (nothing preselected) ─► rationale ─► review ─► POST …/proposals/{id}/decision
     {verdict, rationale, expected_proposal_hash, supersedes_decision_id?}
API: authenticate ─► role APPROVER/ADMIN ─► CSRF (dev) ─► permit ─► gate and state check
     ─► hash check ─► duplicate/supersede check
Engine: HarnessEngine.decideProposal ─► ApprovalPort.record (validate, HMAC, write-once)
        ─► evidence + DECISION_RECORDED
UI: receipt with the returned DEC- ID, integrity, hash, actor, authentication
... "Continue execution" ─► HarnessEngine.resume ─► RunAdvancer.processApprovals
        ─► MutationGateway.apply (all checks) ─► MUTATION_APPLIED ─► fix verification
```

## Invariants

- The persisted run is the state. The engine is authoritative for lifecycle (`RunStateMachine`),
  advancement (`RunAdvancer`), decisions (`ApprovalPort`) and mutations (`MutationGateway`).
- The Control Center writes no files. The existing named-writer ArchUnit rule covers it, and
  `ControlCenterArchitectureTest` also forbids it from touching mutation machinery, state
  transitions, run-state saves, evidence, event or artifact writes, decision construction and
  apply-to-project.
- The kernel, capabilities and CLI depend on neither Spring nor the Control Center.
- Nothing in the UI invents state. Progress is a domain count, "approved" appears only after a
  recorded decision, "applied" only after the gateway's record, and "validated" only from the
  validator.

## Build and dependencies

- The API module resolves Jackson (and the XML, caching and codec libraries the engine uses) to
  exactly the versions the CLI resolves. `KernelJson` registers every Jackson module on the
  classpath, and canonical JSON is what decision-bound hashes are computed over, so the web
  classpath must not change it. Only this module is compiled with `-parameters`.
- The Spring Boot repackaged jar is attached with the `exec` classifier, so the plain jar stays
  usable as a dependency (the `tests` module and ArchUnit import it).
- The UI is served by the API from `mars.control-center.ui-dir`, or from `classpath:/static/` when
  built with the `ui` Maven profile. During development, Vite proxies `/api` to the API.
- No database, broker or cache was added. State stays in the run directories, which is what the
  CLI already uses. The ports (`ExecutionEventStore`, `ApprovalPort`) are where a distributed
  deployment would plug in.
