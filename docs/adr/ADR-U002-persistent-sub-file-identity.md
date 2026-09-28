# ADR-U002: Persistent sub-file identity with evidence-based reattachment

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** MARS architecture

## Context

Findings name statements ("the concatenation on line 20"), migrations rewrite imports and methods,
and developers rename files and classes during a run. A claim such as "the SQL-injection statement
was fixed by CHANGE-000007 and survived the Boot 4 migration" needs an identity that outlives edits,
moves and renames at the granularity of a module, a type, a member and a statement. Bootshift's
ADR-001 solved this for files only.

## Decision

The kernel allocates `MOD-`, `PU-` (program unit), `SYM-` and `STMT-` identifiers once, at the
identity baseline, and persists them in `identity/identity-registry.json`. They are allocated, never
derived. A path, a fully qualified name or a fingerprint is an attribute of an identity, not the
identity itself.

After every applied batch the kernel re-observes the changed files (JavaParser) and reattaches old
identities to new code. It uses this hierarchy of evidence and stops at the first that decides:

1. **Provider mapping.** The proposal says what it renamed or rewrote (`ProviderHints`).
2. **AST diff.** Structural position plus normalized text.
3. **Exact fingerprint.**
4. **Structural similarity** (token LCS, dice, containment), above policy thresholds.
5. **Context** (parent and neighbours).
6. **Allocated new.** The old identity is recorded as deleted, never silently reused.

Matching is mutual-best with an ambiguity margin, and each match records its method and a certainty
(EXACT, HIGH, LOW, NONE). A LOW-certainty match is kept but marked uncertain. Two candidates within
the margin are treated as ambiguous and never guessed. Splits and merges record `split_from` and
`merged_into`, so lineage stays queryable after the code is gone.

## Consequences

- `IdentityGoldenTest` (14 cases) pins edit, insert, move, rename, cross-file move, class plus file
  rename, uncertain, weak-match, merge, ambiguity and split lineage.
- Scenario 9 (`RenameDuringMigrationE2ETest`) shows FILE_ID, PROGRAM_UNIT_ID, SYMBOL_IDs and
  STATEMENT_IDs surviving a class-and-file rename inside a migrating run, with the finding still
  linked.
- A file that fails to parse is reported as unsynchronized, never as a deletion.
- Only Java source has sub-file identity today. Other files keep FILE_ID only.
