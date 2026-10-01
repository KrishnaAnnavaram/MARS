# MARS Mission Control — Build, Test, Critique and Correction Report

Companion to [`MARS-Mission-Control-Proposal.md`](MARS-Mission-Control-Proposal.md) (the approved design).
Operator documentation: [`mission-control/README.md`](../../mission-control/README.md).

## 1. What was built

A local, read-only operations console plus an additive observability layer for the MARS harness.

- **Server** (`mission-control/server`, `node:http`, no framework). It projects the evidence files MARS already writes into a typed API, `/api/v1/*`, plus an SSE stream that sends a snapshot, then deltas, with `Last-Event-ID` and `?after=` replay, gap backfill and a heartbeat.
  - **Evidence is authority.** Issue state is derived from `docs/agent_output/**`.
  - **Events are witnesses.** The ledger adds *when* and *who*, never state.
  - **Integrity rules R1–R14** surface contradictions instead of hiding them. Examples: a gate report header that disagrees with its own exit codes; a verdict that no longer replays; an approval carried over a re-proposal; claimed files the diff does not change; an uncorroborated "human" decision.
  - **Verdict replay.** It re-scores the evidence with the arbiter's policy and is parity-tested against the real `compute-score.js`.
- **Web console** (`mission-control/web`: React 19, TanStack Router/Query/Table/Virtual, React Flow + dagre, Tailwind 4).
  - *Home*: "What is MARS doing right now?". Only MARS agents, or MARS skills run in a session, count as MARS work. It shows current, previous and next-expected operation, elapsed time, human waits, a "Needs a human" band that leads with causes, a trust strip, the remediation board and a live activity feed.
  - *Issues*: board and verification matrix.
  - *Issue*: lifecycle graph, the five independent checks shown separately with failure classes and "cause unverified", the verdict computation (hard gates, points, override, header-based and exit-code replay), plan and approval, patch versus claimed files, diagnosis, impact, timeline (observed vs reconstructed vs gaps), evidence lineage, code architecture overlay, findings.
  - *Approval Center*: read-only decision packet with a pinned plan hash. "Request changes" is explained as outside the contract. Approvals that no longer cover the plan or patch are flagged. The decision form appears only when enabled, with a launch token, typed confirmation and stale-plan re-check.
  - *Runs*: observed traces (run → skill segment → operation → write/gate event) in a virtualised waterfall, and reconstructed runs labelled "not observed".
  - *Evidence*: artifacts with hashes, producer, provenance and findings; a sanitised viewer for markdown, diff and JSON.
  - *Audit*: deterministic vs AI-authored vs human vs gap, with conflict markers.
  - *Architecture*: application graph (code model) and MARS pipeline graph, kept separate and linked by issue.
  - *Harness*: agents, skills, health, drift.
- **Additive MARS instrumentation** (`.claude/scripts/telemetry/`):
  - an append-only ledger (`mars.event/1`);
  - a Claude Code hook adapter with an evidence guard (observe by default);
  - gate/score witness events from 5 scripts (5 guarded lines each);
  - the human decision command `.claude/scripts/record-decision.js`.

## 2. Files changed

| Area | Files |
|---|---|
| Modified MARS scripts (additive, guarded, output byte-identical) | `04b-fixer/scripts/verify-patch.js`, `04c-dependency-upgrader/scripts/apply-version-bump.js`, `06a-qa-runner/scripts/run-qa-gate.js`, `06b-build-gatekeeper/scripts/run-build-gate.js`, `07a-merge-arbiter/scripts/compute-score.js` (5 lines each) |
| New harness files | `.claude/scripts/telemetry/{ledger,classify,mars-hook,gate-events}.js`, `.claude/scripts/record-decision.js`, `.claude/settings.json` (hooks) |
| Config | `.gitignore` (`.mars/`, MC build/test output) |
| New app | `mission-control/` (server, shared, web, tests, e2e, configs, README): about 9,900 lines |
| Docs | `docs/mission-control/MARS-Mission-Control-Proposal.md`, this report |

Not touched: agents, SKILL.md files, contracts, `docs/agent_output/**` (verified by `git status`, and by a test asserting the workspace is byte-identical after browsing every view).

## 3. Instrumentation added

| Source | Events | Never recorded |
|---|---|---|
| Hooks: SessionStart/End, SubagentStart/Stop, Pre/PostToolUse(+Failure) on Bash, PowerShell, Write, Edit, Skill, and Notification | session, agent run, operation (MARS script id + allow-listed flags, or program class), artifact written (sha256, MARS areas only), human waiting, guard events | prompts, model output, tool output, file contents, full commands, paths outside MARS areas |
| Gate scripts (04b, 04c, 06a, 06b, 07a) | `fix.verified`, `gate.completed` (failure class: infrastructure / patch.apply / compile.error / qa.test_failed / build.failed), `verdict.computed`, with input hashes and policy sha256 | the same exclusions |
| `record-decision.js` | `approval.recorded` (mandatory witness) | — |

Safety controls added:
- The hook always denies agent commands that run the decision command.
- Evidence guard: `observe` by default, `enforce` optional. Guard events are critical "Needs a human" items for 24 hours.

## 4. Tests added

| Suite | Count | Covers |
|---|---|---|
| `tests/server/real-data.test.ts` | 10 | conformance with MARS's own register lister, real Blocked issues, R1/R3, unattributed and carried-over approvals, no fabricated runs |
| `tests/server/scenarios.test.ts` | ~24 | scenarios A–M on temp-dir fixtures, orphaned operations, session lifecycle, attention wording, guard expiry, "Cleared, untrusted" |
| `tests/server/decision.test.ts` | ~19 | stale hash, NOT_PROPOSED, machine actors, agent markers, non-interactive refusal, launch-token channel, lock, tamper (R13), direct-file forgery (R14) |
| `tests/server/telemetry.test.ts` | 22 | concurrent seq, redaction, disable, classifier, hook never breaks, guard observe/enforce, decision-command denial plus bypass variants, privacy |
| `tests/server/api.test.ts` | 32 | every GET route, typed 404s, path allow-list, Host guard, CSRF/origin/token/confirmation, the decision flow, SSE hello/events/heartbeat, Last-Event-ID replay, snapshot push |
| `tests/server/safety.test.ts` | 9 | byte-identical workspace after browsing, no write paths in the server, guarded instrumentation, pipeline-lint still passes |
| `tests/server/scoring-parity.test.ts` | 4 | MC replay = real `compute-score.js` on all 4 issues |
| `tests/web/live.test.ts` | 9 | SSE client: dedupe, gap backfill (mid-batch too), seeding, reconnect backoff, watchdog, debounced invalidation |
| `tests/web/components.test.tsx` | ~13 | glyph+text (never colour alone), markdown sanitisation, board labels, tabs keyboard, error/empty states, decision form (token, stale plan, no auto-approve) |
| `e2e/app.spec.ts` (Playwright, installed Edge) | 9 × 4 viewports | Home honesty, Blocked issue end to end, read-only approvals, all views, Ctrl+K, missing artifact/404, built-server security, board text collisions, no horizontal overflow |

**Scenario coverage:**

| Scenario | Source |
|---|---|
| A, D, E, G, H | real data |
| B (running agent) | real hook adapter |
| C (Proposed plan) | fixture mutation |
| F (pure test failure) | fixture mutation |
| I (Cleared) | **synthetic**, labelled |
| J (reconnect) | SSE tests |
| K (missing artifact) | fixture mutation |
| L (malformed artifact/ledger) | fixture mutation |
| M (empty workspace) | fixture mutation |

## 5. Validation results (final)

- `npm run typecheck`: clean (web + server).
- `npm run lint`: clean, 0 warnings.
- `npm test`: **137 passed** (9 files).
- `npm run build`: ok. Main chunk 484 KB, down from 1.18 MB; heavy pages are lazy; no source maps.
- `npx playwright test` against the built server: **31 passed, 5 skipped by design** at 1920/1440/1280/390.
- Rendered inspection: screenshots reviewed at all four viewports throughout. Final checks: the 1280 board, the 1280 architecture graph, the 390 audit view.
- Real workspace: only the intended files changed. `docs/agent_output` is untouched; pipeline-lint passes.

## 6. LLM judge pass 1 (independent subagent, read-only)

**Average 6.8 / 10.**

| Category | Score | Category | Score | Category | Score |
|---|---|---|---|---|---|
| Repository fidelity | 8 | Research fidelity | 7 | Architecture correctness | 7 |
| Runtime observability | 6 | Live-status clarity | 5 | Agent visibility | 6 |
| Skill visibility | 6 | Trace/debugging | 6 | Artifact/evidence | 8 |
| Approval UX | 8 | Verification UX | 8 | Failure diagnosis | 8 |
| Information architecture | 7 | Visual hierarchy | 6 | Interaction design | 7 |
| Accessibility | 6 | Responsive behavior | 7 | Performance | 6 |
| Security | 6 | Auditability | 8 | Maintainability | 7 |
| Test quality | 7 | Developer usefulness | 7 | Manager usefulness | 6 |

43 defects in total. The five P0s:

- **D1:** the served build lacked the DNS-rebinding guard; tests ran against source only.
- **D2:** "MARS is running 3 runs" for non-MARS sessions.
- **D3:** orphaned operations stayed "running" forever.
- **D4:** an agent could mint a human-attributed decision.
- **D5:** board unreadable at 1440px.

The 10 P1s included:

- the Home headline framing altered gate reports as "would be Cleared";
- publication eligibility ignoring integrity findings;
- re-proposed approvals not surfaced;
- guard events not surfaced;
- inconsistent counts;
- hooks recording paths of all work.

## 7. Corrections made

| Finding | Correction |
|---|---|
| D1 | Rebuilt. Loopback-only Host guard (421). Typed 400s for malformed URLs. HEAD support. No source maps. **E2E security checks now run against the built server.** |
| D2 | The headline counts only MARS agents, or MARS skills run from a session. Other sessions are collapsed. |
| D3 | Superseded or old foreground operations become "outcome unknown"; background commands are recorded and exempt; pending markers are purged at SessionEnd. |
| D4 (+ judge 2 N1/N2) | Hook denial (pattern refined after it blocked one of my own `find` commands); refusal on any Claude Code env marker; CLI requires a TTY confirmation (module-private flag, no injectable env); the HTTP path needs a per-launch token printed only to the launching terminal; mandatory ledger witness; **R14 detects uncorroborated records**, including a direct-file forgery with a valid hash chain; README rewritten to "deterrence + detection". |
| D5 / N6 | Stacked cells (glyph + flags, then label), shorter gate labels, full-width board below 1600px, icon sidebar below 1440px, **E2E text-collision check**. |
| D6 | Attention leads with "gate reports contradict their own exit codes" and the exit-code replay. |
| D8 | A Cleared verdict with critical findings becomes "Cleared, untrusted"; never "Eligible". |
| D9 / N15 | Re-proposed or deviating approvals become `invalidated_approval` items, a header badge, and "Approved · re-review" (warning glyph). |
| D10 / N4 / N5 | Guard events are critical items, labelled correctly (decision-command denial vs evidence edit), deduplicated, and expire after 24 h. |
| D11 | The feed is seeded from the ledger on connect, with human-readable titles. |
| D12–D26 | Real-path artifacts; unified count vocabulary; area-only paths outside MARS; session identity kept apart from subagents; reconstructed runs marked "not observed" with failures per evidence; R6 downgrades provenance; lineage labelled "expected inputs"; audit conflict pills; corrected mirror label; legend descriptions and node heights (N7); route code-splitting; narrowed file watching; "publication not recorded". |
| D30–D43 and N8–N16 | Live region announces only pipeline-significant events. Scoring parity test. Threat model documented. Duration rounding. `path.relative` static check. Throttle applied after success. Per-event gap detection. Path wrapping. Audit card layout at 390px. Issue IDs never split. `limit` validated. Pending markers without non-MARS paths. |
| Additional fixes found during testing | A gate exiting 1 is a gate result, not a tool failure. `sanitizeAttrs` now redacts values under secret-named keys. The live client was started twice. Unlayered CSS made every link blue. Quoted `cd` prefixes broke command naming. |

## 8. LLM judge pass 2 (independent subagent, read-only)

**Average 7.75 / 10.** Recommendation: **"yes with conditions"**.

| Category | Score | Category | Score | Category | Score |
|---|---|---|---|---|---|
| Repository fidelity | 9 | Research fidelity | 8 | Architecture correctness | 8 |
| Runtime observability | 7 | Live-status clarity | 7 | Agent visibility | 8 |
| Skill visibility | 8 | Trace/debugging | 7 | Artifact/evidence | 9 |
| Approval UX | 7 | Verification UX | 9 | Failure diagnosis | 9 |
| Information architecture | 8 | Visual hierarchy | 8 | Interaction design | 8 |
| Accessibility | 7 | Responsive behavior | 6 | Performance | 9 |
| Security | 5 | Auditability | 7 | Maintainability | 8 |
| Test quality | 8 | Developer usefulness | 8 | Manager usefulness | 8 |

It verified most pass-1 fixes against the served build. The low Security score came from **N1**: it demonstrated, against a temp workspace, that `env -i` plus quote-splitting still forged a decision. Its conditions:

1. Raise the forging barriers, add detection, and correct the docs.
2. Fix the run/session lifecycle (N3).
3. Fix guard labelling and expiry (N4/N5).
4. Fix the 1280 board and graph overlap (N6/N7).

**All four conditions were then addressed** (§7) and verified by tests, E2E and rendered inspection. The scores above are judge 2's, **before** those corrections; a third independent pass was not run.

## 9. Remaining limitations

- **Decision attribution is advisory, not tamper-proof.** Same-user processes can still write files; protection is deterrence plus R14 detection. Tamper-proof decisions need an identity provider or signing key the agent cannot reach.
- **Evidence guard defaults to `observe`.** Enforcing it would deny current MARS flows that hand-edit reports (R6 finds them); that is a pipeline-policy decision for you.
- **No MARS agent has yet been observed live.** Live agent tracking is proven by hook-driven scenario tests and by live non-MARS sessions. Gate attribution is per session (parallel MARS subagents in one session share it).
- **Not built:** Insights/analytics page (proposal §29), baseline-build and skill self-test health checks (§30), automated axe audit (no new libraries were allowed). Dark mode and screen readers were not audited end to end.
- Ledger lines written before the privacy fix still contain paths of non-MARS files (my own Mission Control edits); the ledger is append-only and was not rewritten.
- A guard event from my own session — a false positive of the first decision-command pattern — stays in "Needs a human" until it ages out after 24 h.
- Scenario I (Cleared) is synthetic: the real workspace has no Cleared verdict.

## 10. Recommended next phase

1. Decide the guard policy (`enforce` for rendered evidence) and move R6-producing agent edits into renderers.
2. Run one real MARS pass (02 → 07) with hooks on, to validate live agent, skill and gate attribution end to end, and key active-run files by agent id.
3. Add signed decisions (an OS keychain or hardware key held by the reviewer) if approvals must be non-repudiable.
4. Add the §30 baseline-build health check: it is what would turn today's "compile error, cause unverified" into a verified class for all four Blocked issues.
5. Insights page; axe in E2E; dark-mode visual baselines.
