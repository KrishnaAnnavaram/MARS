# CWE Remediation Pattern Catalog

`cwe-patterns.json` is the Fix Strategist's only source of remediation strategy. It is a plain JSON
object keyed by CWE id (`"CWE-943"`), read by `scripts/collect-remediation-context.js` and cited by
the agent in `strategy.json`'s `catalog_reference`. The agent does not invent a remediation approach
— it picks the applicable entry and adapts `canonical_approach` to the specific defect site.

## Entry shape

```json
"CWE-<nnn>": {
  "title": "Full CWE title",
  "owasp": "The most relevant OWASP Top 10 2021 category",
  "applicable_when": "The concrete code shape/pattern that signals this CWE — specific enough that the agent can match it against a defect site, not a restatement of the CWE name.",
  "canonical_approach": "The framework-level fix pattern: what to replace the vulnerable construct with, and why that construct closes the hole rather than papering over it.",
  "anti_patterns": ["Fixes that look plausible but don't actually close the hole — the agent should recognise and avoid these."],
  "references": ["https://cwe.mitre.org/data/definitions/<nnn>.html", "other primary sources"]
}
```

All five fields (`title`, `owasp`, `applicable_when`, `canonical_approach`, `references`) are
required; `anti_patterns` is optional but strongly recommended — it is what keeps the Strategist from
recommending a fix that addresses the symptom rather than the CWE.

## Adding a new CWE

1. Add an entry following the shape above. Keep `canonical_approach` generic — a pattern applicable
   anywhere in this codebase or a similar one, not a description of one specific file.
2. No script needs to change: `collect-remediation-context.js` reads every key in this file, and
   `render-fix-plan.js` renders whatever the agent cites.
3. If an issue's `CWE-\d+` mention has no matching entry here, the context briefing records it as a
   **catalog gap** rather than silently dropping it — the Strategist must say so in
   `strategy.json.catalog_reference` (`cwe` set, `title: null`) instead of inventing a pattern.

## Scope

This catalog covers the CWEs already cited in `docs/agent_output/00-issues/` (CWE-943, CWE-306/CWE-284, CWE-532,
CWE-200, CWE-770/CWE-400) plus a handful of common web-application CWEs (CWE-89, CWE-79, CWE-798) for
issues not yet reported. Extend it as new issue classes show up — it is meant to grow with the
register, not be exhaustive on day one.
