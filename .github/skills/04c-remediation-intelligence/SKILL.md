---
name: 04c-remediation-intelligence
description: 'Fallback for the Fix Strategist (04a) that activates only when a diagnosed CWE has NO entry in the cwe-patterns.json catalog. Instead of leaving a hollow "catalog gap" plan, it searches a local, version-controlled remediation knowledge base, ranks historical fixes by relevance to the root cause, extracts a reusable pattern, and derives an evidence-backed remediation strategy — written to the same <id>.strategy.json that 04a''s render-fix-plan.js turns into a Status: Proposed plan for human review. Never invents a pattern from memory, never writes a diff, never approves. Use when the Strategist reports a catalog gap and every detected CWE is uncatalogued.'
argument-hint: 'A specific issue id whose CWE is a catalog gap, e.g. SAMPLE-CWE22'
---

# Remediation Intelligence (04c) — the catalog-gap fallback

The Fix Strategist (`04a`) draws its remediation strategy from one source: the CWE pattern catalog
[`../04a-fix-strategist/catalog/cwe-patterns.json`](../04a-fix-strategist/catalog/cwe-patterns.json).
When a diagnosed CWE has **no entry** there, 04a's rule is to stop and report a *catalog gap* rather
than invent a pattern. That is safe but a dead-end.

This skill fills that dead-end. It turns a gap into a **grounded, cited, low-confidence proposal** —
without loosening a single downstream gate. Its output re-joins the normal pipeline at exactly the
same place a catalogued plan does: a `Status: Proposed` fix plan a human must approve before the
Fixer writes any code.

## When it fires (and when it must not)

| Condition | Action |
|---|---|
| At least one detected CWE **is** catalogued | **Do not use this skill.** That is 04a's happy path (YES branch). |
| CWE(s) detected but **none** is catalogued | **Fire** (NO branch) — this is a catalog gap. |
| The gap CWE **is** in this skill's knowledge base | Derive a strategy from it. |
| The gap CWE is **not** in the KB either | Report a **KB gap** — do not invent. Add a vetted KB entry or escalate. |
| No CWE detected at all | Not this skill's job — the agent must state the CWE first. |

The trigger is deliberately narrow in v1: **the fallback runs only when every detected CWE is a
gap.** An issue that mixes a catalogued CWE with an uncatalogued one is handled by 04a against the
catalogued one (see ISSUE-002 in this repo, which mentions the catalogued CWE-306 alongside the
uncatalogued CWE-862/CWE-359).

## What it will not do

- **Never invents** a pattern. Everything it cites resolves to a real file under `knowledge/`.
- **Never reaches the network or model memory** for a pattern — that is what keeps a derived plan
  auditable and reproducible.
- **Never writes a diff, never edits real source, never approves** a plan. It writes a proposal only.

## Inputs

| # | Input | Source |
|---|---|---|
| 1 | The remediation context bundle `<id>.context.json` | produced by 04a's `collect-remediation-context.js` |
| 2 | The main CWE catalog | `../04a-fix-strategist/catalog/cwe-patterns.json` (read via 04a's own lib, so gap detection is identical) |
| 3 | The local knowledge base | [`knowledge/remediation-kb.json`](./knowledge/remediation-kb.json) + [`knowledge/refs/`](./knowledge/refs/) |
| 4 | Ranking weights | [`ranking-weights.json`](./ranking-weights.json) — editable, never buried in code |
| 5 | *(optional)* Local sentence-embedding model | this skill's own `.venv/` — see **Setup** below |

## Setup — the optional local embedding signal

Everything in this skill works with **zero setup** except one optional signal: real semantic
similarity via a local `sentence-transformers` model. Without it, ranking still works (keyword +
synonym + TF-IDF), it just skips the third signal. To enable it:

```powershell
cd .github/skills/04c-remediation-intelligence
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt      # macOS/Linux: .venv/bin/pip install -r requirements.txt
```

The first ranking call after that downloads the model (`all-MiniLM-L6-v2`, ~80MB) to your local
Hugging Face cache (`~/.cache/huggingface/`, outside this repo); every call after that runs fully
offline. `.venv/` is gitignored (machine-specific, ~900MB with PyTorch) — never committed.

**This is a genuinely optional dependency, not a silent requirement.** `scripts/lib/embeddings.js`
checks whether `.venv/` exists before every call; if it doesn't (or the call fails for any reason —
missing model, corrupted venv, timeout), `embedTexts()` returns `null` and `rankExamples()`
transparently ranks on `keyword_score` + `tfidf_score` alone, with `embedding_score: null` recorded
in the output so the omission is visible, not silent. Nothing throws, nothing blocks the pipeline.

## Ranking algorithm — hybrid keyword/synonym + TF-IDF cosine + local semantic embedding

When a gap CWE has more than one `historical_fixes[]` candidate, `rankExamples()` in
[`scripts/lib/fallback.js`](./scripts/lib/fallback.js) scores every candidate with up to **three
independent signals**, combined into one `score`:

1. **`keyword_score` (0-1)** — exact + synonym overlap against the root-cause text. An exact hit on
   one of the fix's `keywords[]` counts `exact_match_weight`; a hit on one of that keyword's
   paraphrases (the KB entry's optional `keyword_synonyms` map, e.g. `"write": ["save", "persist",
   "store"]`) counts the lower `synonym_match_weight`. Normalized against the maximum a fix with
   that many keywords could score, so fixes with different-length keyword lists stay comparable.
2. **`tfidf_score` (0-1)** — TF-IDF cosine similarity between the root-cause text and the fix's
   `pattern + summary + keywords` text, where the corpus is **every historical fix across the whole
   KB** (so a term common to most fixes, like "file", is automatically down-weighted relative to a
   rare, specific term). Still purely lexical — it can only match literal words, weighted by rarity.
3. **`embedding_score` (0-1, or `null`)** — cosine similarity between **real sentence embeddings**
   (`scripts/lib/embeddings.js`, a local `sentence-transformers` model — see **Setup** above), not
   pseudo-vectors built from word counts. This is the only signal that can recognise two
   differently-worded sentences mean the same thing **without** either sharing a literal word or the
   pairing being hand-declared in `keyword_synonyms` — genuine semantic understanding, not lexical
   matching dressed up. `null` if the local model isn't set up on this machine.

```
score = weighted average of whichever signals are available   (weights in ranking-weights.json;
                                                                 renormalized if embedding_score is null)
```

**Deterministic given the model** — same input, same local model file, same ranking every time (the
embedding step is a fixed on-disk model, not a live service that could silently change between
runs). Ties break on `keyword_score`, then alphabetically by `id`.

Every candidate's full breakdown (`score`, `keyword_score`, `tfidf_score`, `embedding_score`,
`matched_keywords`, `matched_synonyms`) is written into `derived_pattern.ranked_examples[]` — a
reviewer sees exactly *why* a fix ranked where it did, not just a final number. The top match's
breakdown is also quoted directly in the plan's `approach` text via the provenance sentence.

**Why hybrid, and why embeddings on top of TF-IDF:** a root-cause report that says "the handler
persists a resource under a caller-supplied name" instead of the KB's literal words ("write", "file",
"filename") used to score `0` on every candidate under pure keyword overlap — the ranking then
silently fell back to alphabetical order. `keyword_synonyms` and TF-IDF both patch specific,
hand-anticipated gaps; the embedding signal is the only one that generalises to paraphrases nobody
thought to add a synonym for (see the self-test for a worked example, including a case where the
embedding and keyword signals actually *disagree* on which fix looks closer, and the weighted
combination is what breaks the tie).

## Output

`.github/.pipeline-context/fix-strategy/<id>.strategy.json` — the same file 04a would have written,
satisfying [`../04a-fix-strategist/templates/strategy.schema.json`](../04a-fix-strategist/templates/strategy.schema.json),
plus one added block: **`derived_pattern`** (provenance — see
[`templates/derived_pattern.schema.json`](./templates/derived_pattern.schema.json)). It is marked
`confidence: Low`, `catalog_reference.title: null`, and carries a visible "Derived by the 04c
fallback" note so a reviewer scrutinises the *source*, not just the plan.

From there, **04a's existing `render-fix-plan.js` renders it unchanged** into
`docs/agent_output/04-remediation/fix_plan_<id>.md` at `Status: Proposed`.

## Procedure

```powershell
cd .github/skills/04c-remediation-intelligence   # zero dependencies, no npm install

# 1. Is this actually a gap?
node scripts/detect-gap.js --issue <ISSUE-ID>          # or --all, or --cwe CWE-22

# 2. Derive the strategy (writes <id>.strategy.json). Refuses if it is not a pure gap,
#    or if the gap CWE is missing from the KB.
node scripts/run-fallback.js --issue <ISSUE-ID>        # --dry-run to preview without writing

# 3. Render with 04a's real renderer — produces the Proposed plan.
node ../04a-fix-strategist/scripts/render-fix-plan.js --issue <ISSUE-ID>
```

Then hand off exactly as 04a does: the plan sits at `Proposed` until a human edits its Status to
`Approved`, after which the Fixer (`04b`) → verify (`05`) → test/build (`06`) → audit (`07`) gates
run — every one of them still applies to a derived plan.

## Self-test

```powershell
node scripts/test-sample.js
```

Runs the whole NO branch against the bundled sample [`fixtures/SAMPLE-CWE22.context.json`](./fixtures/SAMPLE-CWE22.context.json)
(path traversal, CWE-22 — a real catalog gap in this repo), then again against
[`fixtures/SAMPLE-CWE22-SYNONYM.context.json`](./fixtures/SAMPLE-CWE22-SYNONYM.context.json) (the
identical defect, paraphrased so it shares **zero** exact keywords with either historical fix), and
renders both with 04a's renderer inside a throwaway temp directory. Asserts each result is a
Proposed, catalog-gap plan carrying provenance at Low confidence, and that the hybrid ranker's score
breakdown (`keyword_score`, `tfidf_score`, `embedding_score`) is present and correctly ordered —
including proving the paraphrased case still ranks correctly via synonyms + TF-IDF alone. Works
whether or not the optional embedding venv (see **Setup** above) is set up — `embedding_score` is
simply `null` in the output if it isn't. Touches nothing else in the repo.

## Promotion path

A KB pattern that proves itself should be **promoted** into the main `cwe-patterns.json` as a
first-class catalog entry — after which that CWE is no longer a gap and this skill stops firing for
it. The goal is to shrink the catalog's blind spots over time, not to run a permanent parallel
catalog. See [`knowledge/README.md`](./knowledge/README.md).

## Notes

- Zero **npm** dependencies — the Node side (`scripts/*.js`) needs no `npm install`. Reuses `04a`'s
  `scripts/lib/plans.js` for catalog loading, CWE detection and path resolution so the two stages
  agree on what a gap is.
- The embedding signal is the one optional exception: it needs a local Python venv (see **Setup**
  above), never an npm package and never a network call at rank time. Skip it entirely and ranking
  still works on keyword + synonym + TF-IDF alone.
- Isolated testing: `PIPELINE_CONTEXT_DATA_DIR` and `PIPELINE_OUTPUT_DIR` redirect the intermediate
  and output directories (the self-test uses both) so a demo never touches real docs.

## Inside MARS

This is skill **04c** of the merged Agent 04
([`.github/agents/04_fix-generator.agent.md`](../../agents/04_fix-generator.agent.md)). It works in
two modes:

- **Harness mode (a MARS run).** The `harness` CLI performs this skill's KB route itself, in Java
  (`KbStrategyDeriver` and `HybridRanker`). It reads the same knowledge base and ranking weights, and
  parity tests compare its ranking with this skill's JavaScript. Two differences matter when you
  compare results:
  - The embedding signal is always off in the harness, so it ranks on keyword/synonym and TF-IDF
    alone. If this skill's `.venv/` is set up, the scripts here add the embedding signal and can rank
    differently. Compare with the venv absent.
  - When several detected CWEs are gaps, the harness takes the first one the KB covers, as
    `run-fallback.js --cwe` would. Without `--cwe`, `run-fallback.js` stops on the first gap.

  To explain why a finding took the KB route, run `node scripts/detect-gap.js --cwe <CWE>`. It reads
  only the catalog and KB and writes nothing. The resulting plan is still `Proposed` and still needs
  a person's `harness approve remediation`.
- **Pipeline mode (the file workflow).** The procedure above, unchanged from VRH.

The knowledge base here is a copy of VRH's. `node .github/scripts/agent04-check.js` fails if it
drifts from the file the harness loads.
