# 04d — Remediation Research

The **deepest** remediation fallback in Agent 4. It runs only on a **KB gap** — a diagnosed CWE that
is in *neither* the catalog (`04a`) *nor* the knowledge base (`04c`) — and turns that former dead-end
into a **novel, evidence-backed, Low-confidence remediation proposal** for human review.

See [`SKILL.md`](./SKILL.md) for the full contract. Quick map:

| Path | What it is |
|---|---|
| `scripts/research-context.js` | Deterministic: confirm the double gap + extract the vulnerability understanding from Agent 1/2/3 evidence (invents nothing). |
| `scripts/generate-strategy.js` | Validate the agent-authored analysis, assemble a base-contract `strategy.json` (+ promotion candidate), or SAFE-STOP on `insufficient_evidence`. |
| `scripts/lib/research.js` | Shared logic; reuses `04a/plans.js` + `04c/fallback.js` so all three skills agree on catalog/KB membership. |
| `scripts/test-sample.js` | Self-contained end-to-end test on the `SAMPLE-CWE502` double-gap fixture. |
| `templates/*.schema.json` | The deterministic structure: research result, remediation candidate, research strategy. |
| `fixtures/` | `SAMPLE-CWE502` (established research) and `SAMPLE-THIN` (safe-stop) demo inputs. |

## The rules 04d never breaks

- Never invents a remediation without analysis; never fabricates a citation (internally derived → Low confidence).
- Never writes a diff, never edits source (04b implements after approval).
- Never self-approves — output is always `Status: Proposed`.
- Stops safely (`research_status: insufficient_evidence`) rather than forcing a fix.
- Never auto-writes the KB — it only proposes a promotion candidate.

## Zero dependencies

Nothing to `npm install`. Reuses the sibling 04a/04c libraries.
