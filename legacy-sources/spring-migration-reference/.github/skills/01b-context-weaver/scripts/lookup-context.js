#!/usr/bin/env node
/**
 * Context Weaver — Context Lookup
 *
 * The read side, for the agents downstream of the architect. Given symbols an issue
 * report names — a type, a method, a module, an endpoint, or just a phrase — returns
 * the stored context for them and for everything they touch.
 *
 * Reads descriptions.json directly rather than Neo4j: the file is the source of truth,
 * the graph is a loaded copy, and this way a Root Cause or QA run needs no database to
 * get its bearings. (For graph-side lookup, see the Cypher in graph-forge/SKILL.md.)
 *
 * Every result is labelled with its author, confidence and staleness. Callers must
 * treat these as orienting context to verify against source, never as evidence.
 *
 * Usage:
 *   node scripts/lookup-context.js --symbol DepartmentController.createDepartment
 *   node scripts/lookup-context.js --module employee-service --format md
 *   node scripts/lookup-context.js --search "payroll" --search "scheduler"
 *   node scripts/lookup-context.js --ids "Type:com.example.EmployeeService" --format json
 */
const fs = require('fs');
const {
  ARTIFACTS_FILE, DESCRIPTIONS_FILE, rel, buildModel, methodIdOf, endpointOf,
  fingerprintType, fingerprintMethod,
} = require('./lib/context');

function parseArgs(argv) {
  const args = { symbols: [], modules: [], ids: [], searches: [], format: 'md' };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--symbol' || arg === '-s') args.symbols.push(argv[++i]);
    else if (arg === '--module' || arg === '-m') args.modules.push(argv[++i]);
    else if (arg === '--ids') args.ids.push(argv[++i]);
    else if (arg === '--search') args.searches.push(argv[++i]);
    else if (arg === '--format' || arg === '-f') args.format = argv[++i];
    else if (arg === '--all') args.all = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Context Weaver — Context Lookup

  node scripts/lookup-context.js [options]

Options:
  --symbol, -s NAME   Type, Type.method, or a bare method name — repeatable
  --module, -m NAME   Everything described inside a module — repeatable
  --ids KEY           Exact "Kind:id" key — repeatable
  --search TEXT       Free-text match against summaries and responsibilities — repeatable
  --all               Every stored description
  --format, -f FMT    md (default) or json
  --help, -h          Show this message

Descriptions are interpretation, not parser output. Verify against source before acting.`);
}

/** Resolve loose symbols the way the other skills do: Type, Type.method, or method name. */
function resolveSymbol(model, spec) {
  const keys = new Set();
  const cleaned = String(spec).replace(/\(\)\s*$/, '').trim();
  const parts = cleaned.split('.');
  const last = parts[parts.length - 1];
  const maybeTypeName = parts.length > 1 ? parts[parts.length - 2] : null;

  const directTypeId = model.nameToId.get(last) || (model.typesById.has(cleaned) ? cleaned : null);
  if (directTypeId && !maybeTypeName) {
    const type = model.typesById.get(directTypeId);
    keys.add(`Type:${type.id}`);
    for (const m of type.methods || []) keys.add(`Method:${methodIdOf(type.id, m)}`);
    keys.add(`Package:${type.package}`);
    keys.add(`Module:${type.module}`);
    return keys;
  }

  const typeId = maybeTypeName ? model.nameToId.get(maybeTypeName) : null;
  if (typeId) {
    const type = model.typesById.get(typeId);
    keys.add(`Type:${typeId}`);
    keys.add(`Module:${type.module}`);
    for (const id of model.methodsByType.get(typeId)?.get(last) || []) keys.add(`Method:${id}`);
    if (keys.size) return keys;
  }

  // Last resort: a method name anywhere in the workspace.
  for (const [id, entry] of model.methodsById) {
    if (entry.method.name !== last) continue;
    keys.add(`Method:${id}`);
    keys.add(`Type:${entry.type.id}`);
    keys.add(`Module:${entry.type.module}`);
  }
  return keys;
}

function keysForModule(model, moduleName) {
  const keys = new Set([`Module:${moduleName}`]);
  for (const type of model.typesByModule.get(moduleName) || []) {
    keys.add(`Type:${type.id}`);
    keys.add(`Package:${type.package}`);
    for (const m of type.methods || []) {
      keys.add(`Method:${methodIdOf(type.id, m)}`);
      const endpoint = endpointOf(type, m);
      if (endpoint) keys.add(`Endpoint:${endpoint}`);
    }
  }
  return keys;
}

/** Recompute the current fingerprint so a caller learns when a description has drifted. */
function currentFingerprint(model, node) {
  if (node.kind === 'Type') {
    const type = model.typesById.get(node.id);
    return type ? fingerprintType(type) : null;
  }
  if (node.kind === 'Method') {
    const entry = model.methodsById.get(node.id);
    return entry ? fingerprintMethod(entry.type.id, entry.method) : null;
  }
  return node.fingerprint; // Module/Package/Endpoint/ExternalType drift is caught at load time
}

function renderMarkdown(results, meta) {
  const out = [];
  out.push('# Stored Context');
  out.push('');
  out.push(`_${results.length} description(s) from \`${rel(DESCRIPTIONS_FILE)}\`, authored by ${meta.author || 'unknown'} on ${meta.generatedAt || 'unknown date'}._`);
  out.push('');
  out.push('> These are **interpretations written to orient you**, not facts extracted by the parser.');
  out.push('> Treat them as leads: confirm anything load-bearing against the source before acting on it.');
  out.push('> Weight each claim by its confidence, and ignore anything marked STALE.');
  out.push('');

  const list = (label, values) => (values && values.length ? [`- **${label}:** ${values.map((v) => `${v}`).join('; ')}`] : []);

  for (const node of results) {
    out.push(`## ${node.kind} — \`${node.id}\``);
    out.push('');
    const flags = [`confidence: ${node.confidence}`];
    if (node.role) flags.push(`role: ${node.role}`);
    if (node.criticality) flags.push(`criticality: ${node.criticality}`);
    if (node.stale) flags.push('**STALE — the code changed after this was written**');
    out.push(`_${flags.join(' · ')}_`);
    out.push('');
    out.push(node.summary);
    out.push('');
    out.push(...list('Responsibilities', node.responsibilities));
    out.push(...list('Invariants', node.invariants));
    out.push(...list('Failure modes', node.failureModes));
    out.push(...list('Side effects', node.sideEffects));
    out.push(...list('Data touched', node.dataTouched));
    out.push(...list('Upstream', node.upstream));
    out.push(...list('Downstream', node.downstream));
    out.push(...list('Test hints', node.testHints));
    out.push(...list('Open questions', node.openQuestions));
    out.push(...list('Evidence', node.evidence));
    out.push('');
  }

  if (meta.crossCutting && meta.crossCutting.length) {
    out.push('## Cross-cutting notes');
    out.push('');
    for (const note of meta.crossCutting) {
      out.push(`- **${note.topic}** (${note.confidence}): ${note.text}`);
    }
    out.push('');
  }
  return out.join('\n');
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  if (!fs.existsSync(DESCRIPTIONS_FILE)) {
    throw new Error(`No stored context at ${rel(DESCRIPTIONS_FILE)}. Run the Architect's Context Weaver step first.`);
  }
  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts at ${rel(ARTIFACTS_FILE)}. Run the Code Cartographer scan first.`);
  }

  const descriptions = JSON.parse(fs.readFileSync(DESCRIPTIONS_FILE, 'utf8'));
  const model = buildModel(JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8')));
  const byKey = new Map((descriptions.nodes || []).map((n) => [`${n.kind}:${n.id}`, n]));

  let wanted = new Set(args.ids);
  for (const symbol of args.symbols) for (const k of resolveSymbol(model, symbol)) wanted.add(k);
  for (const moduleName of args.modules) for (const k of keysForModule(model, moduleName)) wanted.add(k);
  for (const term of args.searches) {
    const needle = term.toLowerCase();
    for (const [key, node] of byKey) {
      const haystack = [node.summary, ...(node.responsibilities || []), ...(node.dataTouched || [])].join(' ').toLowerCase();
      if (haystack.includes(needle)) wanted.add(key);
    }
  }
  if (args.all || (!wanted.size && !args.symbols.length && !args.modules.length && !args.searches.length && !args.ids.length)) {
    wanted = new Set(byKey.keys());
  }

  const results = [...wanted]
    .map((key) => byKey.get(key))
    .filter(Boolean)
    .map((node) => {
      const current = currentFingerprint(model, node);
      return { ...node, stale: Boolean(current && current !== node.fingerprint) };
    })
    .sort((a, b) => a.kind.localeCompare(b.kind) || a.id.localeCompare(b.id));

  const relevantNotes = (descriptions.crossCutting || []).filter((note) => {
    if (!results.length) return true;
    const modules = new Set(results.map((r) => r.id.split('.')[0]));
    return (note.modules || []).some((m) => wanted.has(`Module:${m}`) || modules.has(m))
      || (note.types || []).some((t) => wanted.has(`Type:${t}`));
  });

  const meta = { author: descriptions.author, generatedAt: descriptions.generatedAt, crossCutting: relevantNotes };

  if (args.format === 'json') {
    console.log(JSON.stringify({ ...meta, nodes: results }, null, 2));
  } else {
    console.log(renderMarkdown(results, meta));
  }
}

try {
  main();
} catch (err) {
  console.error('Context lookup failed:', err.message);
  process.exit(1);
}
