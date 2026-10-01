# MARS Mission Control

A local, read-only operations console and live observability layer for the MARS remediation harness.
It answers **"what is MARS doing right now, why is each issue where it is, and what needs a human?"**
from the evidence MARS already writes, plus an additive event ledger.

Design: [`docs/mission-control/MARS-Mission-Control-Proposal.md`](../docs/mission-control/MARS-Mission-Control-Proposal.md).

## Principles

- **Evidence is authority; events are witnesses.** Issue state is derived from `docs/agent_output/**`.
  The ledger (`.mars/ledger/events-YYYY-MM.jsonl`) only adds *when* and *who*. If the two disagree,
  Mission Control shows the evidence and raises an integrity finding (rules R1–R13).
- **Read-only projection.** The server never writes to the workspace. The single exception is the
  human plan decision, which is delegated to `.claude/scripts/record-decision.js` and is **off by default**.
- **No fabricated telemetry.** Anything inferred from report timestamps is labelled *reconstructed*.
  Agent reasoning is never recorded or shown; only tools, scripts, files, timings and exit status.
- **MARS boundaries are unchanged.** No dashboard path starts an agent, edits evidence, changes a verdict,
  skips a gate or opens a PR. Approving a plan does not start the Fixer.

## Run

```bash
cd mission-control
npm install
npm run build
npm start                    # http://127.0.0.1:7440 over the parent workspace (read-only)
npm start -- --enable-decisions   # also allow recording human plan decisions (see below)
npm run dev                  # API (tsx watch) + Vite on http://127.0.0.1:5173
```

Options: `--workspace <dir>` (default `..`), `--port`, `--host` (default `127.0.0.1`; the server is
local-only and has no authentication), `--ledger-dir`, `--static`.

## Live telemetry

`.claude/settings.json` registers `.claude/scripts/telemetry/mars-hook.js` for session, subagent,
tool and notification hooks. The adapter always exits 0 and records only allow-listed facts
(agent type, tool name, MARS script id and sanitised flags, repo-relative paths and sha256,
durations, exit codes, the gate/score summary lines). It never records prompts, model output,
tool output, file contents or full shell commands; secrets are redacted.

Five gate/score scripts also emit a witness event after writing their own record (a guarded
optional `require` — they behave identically if telemetry is absent or disabled).

| Variable | Effect |
|---|---|
| `MARS_TELEMETRY=0` | Disable all ledger writes |
| `MARS_LEDGER_DIR` | Write the ledger elsewhere |
| `MARS_EVIDENCE_GUARD=observe` (default) | Record `guard.would_deny` when an agent edits rendered evidence, the register or decision records |
| `MARS_EVIDENCE_GUARD=enforce` | Deny those edits (renderers write through Node and are unaffected) |
| `MARS_EVIDENCE_GUARD=off` | No guard |

Without hooks, Mission Control still works: it shows evidence-derived state and reconstructed runs.

## Recording plan decisions

MARS reserves `Proposed → Approved | Rejected` for a human. Mission Control can record that decision
with attribution when started with `--enable-decisions` **from your own terminal**. The server then prints
a one-time link (`http://127.0.0.1:7440/#mc-token=…`); only a browser opened from that link can decide.

- the packet shows the plan's sha256; the form re-reads it before submitting and the command refuses
  if it changed (`PLAN_CHANGED`);
- you must choose Approve or Reject, give a rationale and type the issue id; "request changes" is not
  part of the MARS contract;
- the command edits only the Status cell and writes `docs/agent_output/decisions/DEC-*.json`
  (hash-chained, `actor_authentication: LOCALLY_ASSERTED`, `presence: launch-token | tty-confirmation`)
  and always appends an `approval.recorded` witness to the ledger (even with `MARS_TELEMETRY=0`);
- it refuses when any Claude Code process marker is present, refuses machine-looking actors, and from a
  terminal requires an interactive TTY where you type the issue id to confirm.

The same command works without the UI, run by you in a real terminal:
`node .claude/scripts/record-decision.js --issue ISSUE-001 --decision APPROVED --expected-sha256 <sha> --actor "Your Name" --rationale "…"`.

**What this does and does not guarantee.** Attribution is *locally asserted*, not authenticated. These
checks are **deterrence**: a process running as your OS user that is determined to forge a record can
still write files. Forgery is therefore also **detected**: integrity rule **R14** flags any decision that
lacks a human presence mode, lacks its ledger witness, or was written while an agent was running shell or
file operations — and Mission Control asks you to confirm such a decision with the named person before
relying on it. A tamper-proof decision needs an identity provider or signing key the agent cannot reach;
that is out of scope for this local version.

## Security model

- **Local only.** Binds to `127.0.0.1`; requests with a non-loopback `Host` header get `421` (DNS-rebinding
  guard). There is no authentication: identity is the OS user, `LOCALLY_ASSERTED`. `--enable-decisions`
  is refused on a non-loopback bind.
- **The decision POST** requires the per-launch token (`X-MC-Token`, printed only to the launching
  terminal), `X-MC-Request: 1`, JSON, same origin, the typed issue id and the plan hash the reviewer saw;
  it is throttled per issue after a successful record. A local script that did not see the token cannot POST.
- **Agents are deterred from deciding, and caught if they do.** The PreToolUse hook denies agent commands
  that run `record-decision.js` (pattern-based: determined obfuscation can evade it); the command refuses
  on Claude Code process markers (`CLAUDECODE`, `CLAUDE_CODE_SESSION_ID`, …) and without a TTY
  confirmation; rule R14 detects uncorroborated records. See *What this does and does not guarantee* above.
- **Evidence guard defaults to observe.** Agent edits of rendered evidence are recorded and surfaced as
  critical "Needs a human" items, but allowed. `MARS_EVIDENCE_GUARD=enforce` (e.g. in `.claude/settings.json`
  `env`) denies them. It is not on by default because some current MARS agent flows still hand-edit
  reports (integrity rule R6 finds them); enforcing it is a pipeline policy decision.
- **Trust boundary = the workspace you point it at.** The server reads files, but it also loads that
  workspace's own harness modules (`register.js`, `classify.js`) and, on the Health page, runs
  `pipeline-lint.js`, `java -version` and `git worktree list`. Only point Mission Control at a repository
  whose `.claude/` you trust.
- Telemetry never records prompts, model output, tool output, file contents, full commands, or paths of
  files outside MARS areas. Source maps are not built or served unless `MC_SOURCEMAPS=1`.

## Test

```bash
npm run typecheck && npm run lint && npm test   # unit, integration, API, SSE, scenarios A–M, components
npm run build && npx playwright test            # rendered E2E at 1920/1440/1280/390 px (installed Edge)
```

Scenario fixtures copy the real evidence into the OS temp directory; the real workspace is never
written by tests. Scenarios absent from the real data (a Proposed plan, a Cleared verdict, a pure
test failure) are produced by labelled mutations in `tests/server/scenarios.test.ts`.

## Layout

```
server/     node:http API + SSE; sources/ (evidence, ledger, registry, code model, decisions),
            projection/ (issues, runs, integrity, scoring replay, lineage, architecture, health)
shared/     API types, stage model, event naming
web/        React 19 + TanStack Router/Query/Table/Virtual, React Flow + dagre, Tailwind 4
tests/      vitest (node + jsdom);  e2e/  Playwright
```
