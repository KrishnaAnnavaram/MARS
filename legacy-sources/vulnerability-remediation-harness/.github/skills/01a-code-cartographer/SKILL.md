---
name: 01a-code-cartographer
description: 'Parses Java source files and pom.xml across every Maven module in this workspace into an AST-derived artifacts.json (packages, classes, interfaces, methods, fields, annotations, REST mappings, Maven dependencies). Use when asked to scan/analyze the codebase, extract code structure, list classes/endpoints, or as the first step before building a Neo4j graph or architecture document.'
argument-hint: 'Optional: a module name to scan only (defaults to the whole workspace)'
---

# Code Cartographer

Reads Java source across the workspace and turns it into structured, queryable data — the raw material for the Graph Forge and Blueprint Scribe skills.

## When to Use
- The user asks to "scan the codebase", "analyze the code", "list all classes/controllers/endpoints", or "map out the modules"
- As the required first step before running Graph Forge (needs `artifacts.json`) or Blueprint Scribe

## How It Works
- Discovers every Maven module by finding `pom.xml` files, parsing `groupId`/`artifactId`/`version`/`packaging` and `<dependencies>`
- Parses every `src/main/java/**/*.java` file into a real AST using `tree-sitter` (Java grammar), not regex — so nested generics, annotations with arguments, inheritance, and overloaded methods are captured correctly
- Extracts per type: package, kind (`class`/`interface`/`enum`/`record`), source line range, annotations (with arguments), `extends`/`implements`, fields (name + type + annotations), methods (name, return type, params, annotations, call sites for Graph Forge's function-level call graph, source line range, and the exact method source text for Blueprint Scribe's function reference doc)

## Procedure
1. Install dependencies once: `cd .github/skills/01a-code-cartographer && npm install`
2. Run the scan: `npm run scan` (or `node scripts/scan.js`)
3. Output is written to `.github/.pipeline-context/artifacts.json` at the repo root (override location via `PIPELINE_CONTEXT_DATA_DIR` env var)
4. Report a short summary to the user (module/file/type counts, printed by the script) — do not paste the full JSON into chat

See [scan.js](./scripts/scan.js) for implementation details.

## Notes
- This skill's folder is self-contained (own `package.json`) so it can be copied/moved independently of the other two skills
- Re-running the scan overwrites `artifacts.json`; it's safe to re-run any time the source changes
- Only Java is parsed today. If the workspace gains other languages, extend `scan.js` with additional tree-sitter grammars rather than creating a parallel skill
