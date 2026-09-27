# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for a security problem in MARS itself. Report it privately,
through GitHub's **Security → Report a vulnerability** on this repository where it is enabled, or
by contacting the maintainers directly. Include:

- what the problem is and which component it affects (for example the Mutation Gateway, decision
  handling, apply-to-project or a finding normalizer)
- steps or a minimal input to reproduce it
- the impact you expect (for example an unauthorized write, or a forged or bypassed approval)

We will acknowledge the report, investigate, and agree a disclosure timeline with you.

Vulnerabilities in the **target projects** MARS analyzes are not MARS vulnerabilities. That is
what MARS is for.

## Security model

What MARS guarantees, and how:

| Guarantee | Mechanism |
|---|---|
| Your repository is never written during a run | All work happens in a snapshot and workspace under `runs/`. Only `harness apply`, with an approved decision bound to the current ledger head, writes the project, and it refuses if any target file changed since the snapshot. |
| Tracked source changes only through one writer | The Mutation Gateway, enforced by architecture tests and a runtime bypass detector. |
| No machine self-approval | Reserved machine identities (`llm`, `agent`, `copilot`, `harness`, `ci`, …) are refused as approvers. LLM-authored patches need provenance hashes and a human approval. |
| An approval covers exactly what was reviewed | Each decision is bound to the SHA-256 of the proposal and the baseline seal. Any change makes it stale. |
| Tampering is detectable | Evidence, change and lineage ledgers are hash-chained. `harness verify` recomputes every chain and decision integrity hash. |
| Paths cannot escape the workspace | Proposals are checked for path traversal, and apply refuses paths outside the project. |
| No unexpected network access | Bootshift's allow-listed HTTP egress is off unless `--network` is given. Behaviour probes may only target loopback. |

## Known limits

These are by design and are also listed in the [README](README.md#23-limitations):

- **Approver identity is asserted, not authenticated.** `--actor` is whatever the operator types.
  MARS refuses machine names but does not verify who a person is.
- **Decision integrity is tamper evidence, not non-repudiation.** Decisions are HMAC-signed with a
  random key created per run and stored in that run's `decisions/` folder. Someone who can write
  the run folder can also re-sign a decision. Protect `runs/` with file-system permissions, and
  keep an exported copy of the evidence package if you need a durable audit record.
- **MARS builds and starts the target project.** Round 0 and verification run the project's own
  Maven build, tests and application. Only analyze code you would be willing to build and run on
  that machine.
