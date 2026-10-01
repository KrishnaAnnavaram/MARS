# Remediation-intelligence knowledge base

This is the **local, version-controlled, citable** knowledge the `04a1` fallback skill searches when a
diagnosed CWE has **no entry** in the main Fix Strategist catalog
([`../../04a-fix-strategist/catalog/cwe-patterns.json`](../../04a-fix-strategist/catalog/cwe-patterns.json)).

It exists so the fallback can produce a *grounded, cited* remediation strategy instead of inventing
one from memory. Every derived plan links back to a real file here, satisfying the pipeline's rule
that an audit claim must cite its source.

## Files

- **`remediation-kb.json`** — the pattern store, keyed by CWE. Same shape as a `cwe-patterns.json`
  entry (`title`, `owasp`, `applicable_when`, `canonical_approach`, `anti_patterns`) plus
  `verification_hints`, `historical_fixes[]` (the concrete prior remediations the ranker scores), an
  optional `keyword_synonyms` map, and `provenance`.
- **`refs/*.md`** — the citable reference notes each `historical_fixes[].source` points at.
- **`../ranking-weights.json`** — the externalized weights the ranker combines `keyword_score`,
  `tfidf_score`, and (optionally) `embedding_score` with. Edit it to retune ranking without touching
  code.
- **`../.venv/`** *(optional, gitignored)* — a local Python virtual environment providing the
  `embedding_score` signal. See `SKILL.md`'s *Setup* section. Ranking works fine without it.

## The entry contract

Each `remediation-kb.json` entry MUST have: `title`, `owasp`, `applicable_when`,
`canonical_approach`, `anti_patterns[]`, `verification_hints[]`, `references[]`, `provenance`, and at
least one `historical_fixes[]` item with `{ id, pattern, summary, keywords[], language, source }`
where `source` resolves to a real line in a `refs/*.md` file.

**`keyword_synonyms` (optional)** — a map from one of that entry's `historical_fixes[].keywords[]`
terms to a list of paraphrases the ranker should also credit (at a lower weight than an exact hit —
see `../ranking-weights.json`'s `synonym_match_weight`), e.g. `"write": ["save", "persist",
"store"]`. This is what lets the ranker still find the right historical fix when a root-cause report
paraphrases the defect instead of using the KB's exact vocabulary. Not required — a CWE entry with no
`keyword_synonyms` just falls back to keyword-only overlap for that component. Keep entries
**specific**: a synonym that's also a generic English/programming word (e.g. "output", "return",
"invoke") will match unrelated text by coincidence — see `SKILL.md`'s *Ranking algorithm* section for
how `keyword_score` combines with `tfidf_score` and the optional `embedding_score` into the final
`score`.

Keep guidance **framework-generic** — the skill adapts the pattern to the specific defect site, exactly
as the main catalog is used.

## The two boundaries this KB must respect

1. **A CWE with no entry here is a KB gap.** The skill reports it and stops — it never invents a
   pattern. Add a vetted entry instead of loosening this.
2. **Local and citable only.** Every *pattern* the skill cites lives in this repo. The optional local
   embedding model (see `SKILL.md`'s *Setup*) only ever scores how similar the root-cause text is to
   candidates that are **already here** — it never generates, suggests, or cites a pattern of its own,
   and it never reaches the open web or an LLM's memory at rank time. Its role is narrowly "which of
   our own cited candidates is closest," not "what should the fix be."

## Promotion path

A `historical_fixes` pattern that proves itself repeatedly should be **promoted** into the main
`cwe-patterns.json` as a first-class catalog entry — after which that CWE stops being a gap and the
fallback stops firing for it. This KB is meant to shrink the catalog's blind spots over time, not to
become a permanent parallel catalog.
