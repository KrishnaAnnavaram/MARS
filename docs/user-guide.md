# MARS User Guide

This guide is task-based: each section answers "how do I…?" for day-to-day use. For how MARS
works internally, see the [README](../README.md). For the meaning of a term, see the
[Glossary](../README.md#24-glossary).

All examples assume `harness` runs the CLI jar (see
[Make `harness` a command](../README.md#make-harness-a-command)) and that `$RUN` holds your run ID.

---

## 1. Prepare the inputs

| Input | Required | Notes |
|---|---|---|
| The repository | Yes | A Maven project. MARS only ever **reads** it; all work happens in a copy under `runs/`. |
| Finding sources (`--findings`, repeatable) | No | A VRH issue register (`.xlsx`), SARIF from any scanner, and/or `*.advisories.json` dependency advisories. MARS's built-in rule scanner also runs and corroborates them. |
| Research analyses (`--research ID=file`) | No | Only for findings whose CWE is in neither the catalog nor the KB. Without one, such a finding gets an `EVIDENCE_GAP` plan. |
| Probe file (`--probes`) | Recommended | HTTP requests replayed against the running app before and after changes. Without it, behaviour cannot be compared. Probes may only target loopback. See the format in the [README](../README.md#first-run). |

`--skip-build` skips the baseline build. It makes analysis fast, but the build is recorded as
`NOT_RUN` (never as passed) and migration cannot execute without a real round 0.

## 2. Analyze

```bash
harness analyze /path/to/service --findings issues.xlsx --findings scan.sarif --probes probes.json
```

Analysis is read-only. It snapshots the repository, assigns identities, builds the graph, seals
the baseline and runs discovery, then stops at **Gate A**. Inspect the results:

```bash
harness status               --run $RUN   # phase, what it waits for, next commands
harness migration-assessment --run $RUN   # traffic light, need, priority, effort, blockers
harness findings             --run $RUN   # canonical findings and their identity anchors
harness finding FINDING-…    --run $RUN   # one finding with its plans, proposals and decisions
```

## 3. Choose a strategy (Gate A)

```bash
harness decide execution --run $RUN --strategy SECURITY_FIRST \
  --actor jane.doe --role owner --rationale "SQL injection cannot wait for the upgrade"
harness resume --run $RUN
```

The assessment recommends a strategy, but you choose. The table in
[README §7](../README.md#choosing-a-strategy) explains each option. The decision records both your
choice and the recommendation.

`--actor` must be a person. Machine identities (`llm`, `agent`, `copilot`, `harness`, `ci`, …)
are refused.

## 4. Review and decide proposals (Gate B)

```bash
harness proposals --run $RUN          # each proposal: status, findings, reason and its unified diff
harness proposals --run $RUN --json   # machine-readable summary (IDs, status, hashes; no diffs)
```

Decide each proposal individually:

```bash
harness approve remediation --run $RUN --proposal PROP-… --verdict APPROVED \
  --actor jane.doe --role owner --rationale "Reviewed the diff"
harness approve remediation --run $RUN --proposal PROP-… --verdict REJECTED \
  --actor jane.doe --role owner --rationale "Will fix with the ORM migration"
harness approve remediation --run $RUN --proposal PROP-… --verdict DEFERRED \
  --actor jane.doe --role owner --rationale "Next sprint"
```

Things to know:

- `--verdict` defaults to `APPROVED`. Always pass it explicitly.
- An approval is bound to the proposal's exact hash. If the proposal changes, the approval no
  longer counts.
- A **strategy-only** proposal has no patch. Approving it authorizes producing a concrete fix, which
  then needs its own approval.
- Then continue with `harness resume --run $RUN`. Add `--accept-pending` to continue while some
  proposals are undecided. They stay **unapproved** (`PENDING_APPROVAL`), and the run ends in
  `NEEDS_HUMAN` until you decide them.

## 5. Submit your own fix

When MARS has only a strategy, when migration stops in `NEEDS_HUMAN` because no rule matches an
error, or when you simply prefer your own patch:

```bash
# the local file holds the full new content of the repository file
harness submit-patch --run $RUN \
  --file src/main/java/com/acme/Foo.java=./Foo.fixed.java \
  --finding FINDING-… \
  --reason "Parameterize the query"
```

- Omit `--finding` for a migration or general patch.
- `--rename old/path=new/path` records a rename. File, class, method and statement identities
  travel with the file.
- The patch is only a **proposal**. Approve it with `harness approve remediation`, then
  `harness resume`.

**LLM-written patches** use `--provider llm` and must carry provenance: `--model`,
`--prompt-hash` and `--response-hash` (optionally `--model-version` and `--context-hash`). Without
them the patch is refused, even after approval. An LLM patch always needs a human approval.

## 6. Supply research for an unclassified finding

```bash
harness submit-research --run $RUN --finding FINDING-… --analysis analysis.json
```

The analysis is validated against the VRH 04d contract (at least two candidates, a recommendation
naming one of them, validation requirements, confidence Low). The result is a strategy-only
plan. See [ADR-U005](adr/ADR-U005-judgement-as-input.md).

## 7. Decide about migration again (Gate A2)

After security work, if migration is still not GREEN, MARS re-assesses it and asks again:

```bash
harness decide migration --run $RUN --decision PROCEED \
  --actor jane.doe --role owner --rationale "Unblocks the springdoc fix"
harness resume --run $RUN
```

`SKIP` or `STOP` goes to final validation without migrating, and platform-blocked fixes stay
`BLOCKED_BY_PLATFORM`.

## 8. Stop and come back later

Every step is saved to disk. You can close the terminal at any gate and continue days later with
`harness status` and `harness resume`. Nothing is applied twice. A fix applied just before a crash
is verified on resume, not re-applied.

## 9. Read the result

```bash
harness report --run $RUN
```

Key files in `runs/$RUN/reports/`:

| File | Contents |
|---|---|
| `final-report.md` | The full evidence report, with Mermaid diagrams |
| `verdict.json` | The verdict, its reasons, and a status per finding and capability |
| `cumulative.patch` | Every applied change as one patch |
| `fix_plan_<ISSUE>.md` | VRH-format fix plans |

The verdict is one of `CLEARED`, `PARTIAL` (something is deferred, rejected or blocked by platform),
`NEEDS_HUMAN`, `INSUFFICIENT_EVIDENCE` or `BLOCKED`. See
[README §12](../README.md#12-validation-and-the-final-verdict).

## 10. Apply the result to your project

MARS never writes your repository during a run. Applying is a separate, explicit step:

```bash
harness approve apply --run $RUN --actor jane.doe --role owner --rationale "Reviewed the report"
harness apply --run $RUN --decision DEC-…     # the DEC- ID printed by the previous command
```

`apply` refuses unless all of these hold:

- the apply decision is `APPROVED` and bound to the run's current ledger head
- the verdict is `CLEARED` or `PARTIAL` (not `BLOCKED`, `NEEDS_HUMAN` or `INSUFFICIENT_EVIDENCE`)
- if migration ran, its last round was green
- every file it will write is unchanged in your project since the snapshot, so it never overwrites
  a colleague's newer work

Only the files the run changed are written.

## 11. Audit

```bash
harness lineage FILE-… --run $RUN    # history of any identity: FILE-/MOD-/PU-/SYM-/STMT-/FINDING-/PROP-/DEC-/CHANGE-
harness change CHANGE-… --run $RUN   # one change with its ledger events, lineage and patch
harness verify --run $RUN            # recompute every hash chain and integrity check
```

`verify` exits non-zero and names the item if any ledger, evidence record or decision file was
altered after it was written.

## 12. Use a different policy

```bash
harness analyze … --policy my-policy.json
```

Copy `policies/default/unified-policy.json` and adjust it: effort weights (they must sum to 100),
complexity thresholds, the support-horizon warning, migration round limits, per-proposal budgets,
mandatory validation dimensions. See [README §18](../README.md#18-configuration-policy). The policy
version is recorded in every run.
