#!/usr/bin/env node
/**
 * Context Weaver — Workload Lister
 *
 * Decides which graph nodes are worth a contextual description, and packages the
 * evidence an author needs to write one: signatures, annotations, collaborators,
 * call edges and the exact source text.
 *
 * Writes:
 *   .github/.pipeline-context/context/context-workload.json  — machine-readable, consumed by
 *                                               generate-descriptions.js
 *   .github/.pipeline-context/context/context-workload.md    — the authoring brief the architect
 *                                               agent reads
 *
 * Re-runs are incremental. Every node carries a fingerprint of the code it describes;
 * a node whose description already exists at the same fingerprint is skipped, so a
 * re-scan after a one-file change costs one description, not four hundred.
 *
 * This script never writes a description. It only decides what needs one.
 *
 * Usage:
 *   node scripts/list-context-workload.js                 # new + stale nodes only
 *   node scripts/list-context-workload.js --all           # every selected node
 *   node scripts/list-context-workload.js --max-nodes 50 --type-threshold 4
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, CONTEXT_DIR, ARTIFACTS_FILE, WORKLOAD_JSON, WORKLOAD_MD, DESCRIPTIONS_FILE,
  DEFAULTS, rel, buildModel, selectNodes,
} = require('./lib/context');

function parseArgs(argv) {
  const args = { all: false, ...DEFAULTS };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--max-nodes') args.maxNodes = parseInt(argv[++i], 10);
    else if (arg === '--type-threshold') args.typeThreshold = parseInt(argv[++i], 10);
    else if (arg === '--method-threshold') args.methodThreshold = parseInt(argv[++i], 10);
    else if (arg === '--min-package-types') args.minPackageTypes = parseInt(argv[++i], 10);
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Context Weaver — Workload Lister

  node scripts/list-context-workload.js [options]

Options:
  --all, -a               Include every selected node, not just new/stale ones
  --max-nodes N           Cap the workload (default ${DEFAULTS.maxNodes}); highest-scoring nodes win
  --type-threshold N      Minimum significance score for a Type (default ${DEFAULTS.typeThreshold})
  --method-threshold N    Minimum significance score for a Method (default ${DEFAULTS.methodThreshold})
  --min-package-types N   Skip packages holding fewer types (default ${DEFAULTS.minPackageTypes})
  --help, -h              Show this message

Reads .github/.pipeline-context/artifacts.json. Writes the workload + authoring brief to .github/.pipeline-context/context/.`);
}

function loadExistingDescriptions() {
  if (!fs.existsSync(DESCRIPTIONS_FILE)) return new Map();
  try {
    const parsed = JSON.parse(fs.readFileSync(DESCRIPTIONS_FILE, 'utf8'));
    return new Map((parsed.nodes || []).map((n) => [`${n.kind}:${n.id}`, n]));
  } catch (err) {
    console.warn(`Warning: ${rel(DESCRIPTIONS_FILE)} could not be parsed (${err.message}) — treating every node as new.`);
    return new Map();
  }
}

/** new = never described; stale = described against different code; current = reusable. */
function classify(candidates, existing) {
  const rows = [];
  for (const candidate of candidates) {
    const prior = existing.get(`${candidate.kind}:${candidate.id}`);
    if (!prior) rows.push({ ...candidate, status: 'new' });
    else if (prior.fingerprint !== candidate.fingerprint) {
      rows.push({ ...candidate, status: 'stale', priorSummary: prior.summary || null });
    } else rows.push({ ...candidate, status: 'current' });
  }
  return rows;
}

// ---------------------------------------------------------------------------
// Authoring brief
// ---------------------------------------------------------------------------

function bullet(label, value) {
  if (value === null || value === undefined) return null;
  if (Array.isArray(value)) {
    if (!value.length) return null;
    return `- **${label}:** ${value.map((v) => `\`${v}\``).join(', ')}`;
  }
  if (value === '') return null;
  return `- **${label}:** \`${value}\``;
}

function renderNode(node) {
  const out = [];
  out.push(`### \`${node.kind}\` — ${node.label}`);
  out.push('');
  out.push(`- **id:** \`${node.id}\``);
  out.push(`- **status:** ${node.status.toUpperCase()}${node.status === 'stale' ? ' (code changed since the last description)' : ''}`);
  out.push(`- **selected because:** ${node.reasons.join('; ')}`);
  if (node.priorSummary) out.push(`- **previous description (now stale):** _${node.priorSummary}_`);

  const f = node.facts || {};
  const lines = [
    bullet('Module', f.module),
    bullet('Package', f.package),
    bullet('Kind', f.kind),
    bullet('Packaging', f.packaging),
    bullet('Location', f.file),
    bullet('Signature', f.signature),
    bullet('Handler', f.handler),
    bullet('Owner', f.owner),
    bullet('Annotations', f.annotations),
    bullet('Extends / implements', f.extends),
    bullet('Implemented by', f.implementations),
    bullet('Implementors', f.implementors),
    bullet('Injected fields', f.fields),
    bullet('Methods', f.methods),
    bullet('REST endpoints', f.endpoints),
    bullet('Endpoint', f.endpoint),
    bullet('Types in package', f.types),
    f.typeCount ? `- **Types in module:** ${f.typeCount}` : null,
    bullet('Maven dependencies', f.dependencies),
    bullet('Calls', f.calls),
    bullet('Called by', f.calledBy),
    f.dependedOnBy ? `- **Depended on by:** ${f.dependedOnBy} type(s)` : null,
  ].filter(Boolean);
  out.push(...lines);
  out.push('');

  if (f.source) {
    out.push('```java');
    out.push(f.source);
    out.push('```');
    out.push('');
  }
  return out.join('\n');
}

function renderBrief(workload) {
  const out = [];
  const byKind = {};
  for (const n of workload.nodes) byKind[n.kind] = (byKind[n.kind] || 0) + 1;

  out.push('# Context Weaver — Authoring Brief');
  out.push('');
  out.push(`_Generated ${workload.generatedAt} from a scan taken ${workload.artifactsGeneratedAt}._`);
  out.push('');
  out.push(`**${workload.nodes.length} node(s) need a description** — ${Object.entries(byKind).map(([k, v]) => `${v} ${k}`).join(', ')}.`);
  out.push(`Reusable from the previous run: ${workload.reusedCount}. Selection thresholds: type ≥ ${workload.options.typeThreshold}, method ≥ ${workload.options.methodThreshold}.`);
  out.push('');

  out.push('## How to use this brief');
  out.push('');
  out.push('Write one entry per node below into `.github/.pipeline-context/context/descriptions.json`, validating against');
  out.push('[descriptions.schema.json](../../../.github/skills/01b-context-weaver/templates/descriptions.schema.json).');
  out.push('Copy each node\'s `kind`, `id` and `fingerprint` verbatim — they are how the description binds to the graph.');
  out.push('');
  out.push('Write for the agent that reads this next, not for a human browsing docs:');
  out.push('');
  out.push('- **summary** — what this is and what it is for, in the domain\'s words. `"Owns the employee roster and is the only writer of the employees collection"`, not `"a service class"`.');
  out.push('- **failureModes / invariants** — what breaks if this is wrong, and what must stay true. This is what a Root Cause agent needs and cannot derive from an AST.');
  out.push('- **sideEffects** — writes, outbound calls, mutated state. This is what a Blast Radius agent needs.');
  out.push('- **testHints** — how to exercise it. This is what a QA agent needs.');
  out.push('- **criticality** — how much damage a defect here does, not how complex the code is.');
  out.push('');
  out.push('Ground every claim in the evidence below or in the source file. If you cannot tell what something is for,');
  out.push('say so with `"confidence": "low"` — a hedged description is useful, an invented one is not. Skip a node');
  out.push('entirely rather than pad it; a missing description is a smaller problem than a wrong one.');
  out.push('');

  out.push('## Nodes');
  out.push('');
  for (const node of workload.nodes) out.push(renderNode(node));

  out.push('## Cross-cutting notes');
  out.push('');
  out.push('Some facts belong to no single node — a shared datastore, a service-to-service dependency that');
  out.push('carries no Java call edge, a deployment ordering constraint. Record those in the `crossCutting`');
  out.push('array, scoped to the modules and types they concern.');
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts at ${rel(ARTIFACTS_FILE)}. Run the Code Cartographer scan first (.github/skills/01a-code-cartographer).`);
  }
  const artifacts = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));
  const model = buildModel(artifacts);

  const candidates = selectNodes(model, args);
  const classified = classify(candidates, loadExistingDescriptions());
  const pending = args.all ? classified : classified.filter((n) => n.status !== 'current');
  const reusedCount = classified.length - pending.length;

  const workload = {
    generatedAt: new Date().toISOString(),
    artifactsGeneratedAt: artifacts.generatedAt,
    options: {
      typeThreshold: args.typeThreshold,
      methodThreshold: args.methodThreshold,
      minPackageTypes: args.minPackageTypes,
      maxNodes: args.maxNodes,
      all: args.all,
    },
    selectedCount: classified.length,
    reusedCount,
    nodes: pending,
  };

  fs.mkdirSync(CONTEXT_DIR, { recursive: true });
  fs.writeFileSync(WORKLOAD_JSON, JSON.stringify(workload, null, 2));
  fs.writeFileSync(WORKLOAD_MD, renderBrief(workload));

  const byKind = {};
  for (const n of pending) byKind[n.kind] = (byKind[n.kind] || 0) + 1;
  const total = model.types.length + model.types.reduce((sum, t) => sum + (t.methods || []).length, 0);

  console.log(`Context Weaver: ${classified.length} of ~${total} node(s) are architecturally significant.`);
  console.log(`  needs a description : ${pending.length}${pending.length ? ` (${Object.entries(byKind).map(([k, v]) => `${v} ${k}`).join(', ')})` : ''}`);
  console.log(`  reusable            : ${reusedCount}`);
  console.log(`  brief               : ${rel(WORKLOAD_MD)}`);
  if (!pending.length) {
    console.log('\nEvery selected node already has a description at the current fingerprint. Run Graph Forge to load them.');
  } else {
    console.log(`\nNext: read the brief, then write ${rel(DESCRIPTIONS_FILE)} (or run generate-descriptions.js), then validate-context.js.`);
  }
}

try {
  main();
} catch (err) {
  console.error('Context Weaver failed:', err.message);
  process.exit(1);
}
