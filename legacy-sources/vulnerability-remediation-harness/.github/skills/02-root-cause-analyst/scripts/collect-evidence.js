#!/usr/bin/env node
/**
 * Root Cause Analyst — Evidence Collector
 *
 * Gathers everything needed to diagnose one issue, from four inputs:
 *   1. docs/agent_output/00-issues/<ISSUE>.md      — the reported symptom (front matter + body)
 *   2. docs/agent_output/01-architecture/architecture.md         — module map, service topology, REST surface
 *   3. docs/agent_output/01-architecture/function-reference.md   — per-method signatures, locations, source, call graph
 *   4. the Neo4j knowledge graph    — live callers/callees, endpoints, fan-in, module impact
 *      (falls back to .github/.pipeline-context/artifacts.json when Neo4j is unreachable)
 *
 * Writes a deterministic evidence bundle:
 *   .github/.pipeline-context/rca/<issue_id>.evidence.json   — machine-readable, consumed by render-root-cause.js
 *   .github/.pipeline-context/rca/<issue_id>.evidence.md     — human/agent-readable briefing
 *
 * This script never draws conclusions. It only collects facts.
 *
 * The issue register is read-only input — this script never creates or edits an issue file.
 *
 * Usage:
 *   node scripts/collect-evidence.js --all                      # every issue in docs/agent_output/00-issues/
 *   node scripts/collect-evidence.js --issue ISSUE-001 [--depth 4] [--no-graph]
 *   node scripts/collect-evidence.js --issue docs/agent_output/00-issues/ISSUE-001-....md
 */
const fs = require('fs');
const path = require('path');
const {
  SKILL_DIR, REPO_ROOT, ARTIFACTS_FILE, RCA_DIR, ISSUE_REGISTER_FILE, ARCHITECTURE_MD, FUNCTION_REFERENCE_MD,
  rel, parseFrontMatter, asArray, listIssues, resolveIssue,
} = require('./lib/issues');

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
};

// Field types that signal an outbound HTTP call to another service.
const HTTP_CLIENT_TYPES = ['WebClient', 'WebClient.Builder', 'RestTemplate', 'RestClient', 'HttpClient'];

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { depth: 4, graph: true };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--depth' || arg === '-d') args.depth = parseInt(argv[++i], 10);
    else if (arg === '--no-graph') args.graph = false;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  if (!Number.isInteger(args.depth) || args.depth < 1 || args.depth > 8) args.depth = 4;
  return args;
}

function usage() {
  console.log(`Root Cause Analyst — Evidence Collector

  node scripts/collect-evidence.js --all
  node scripts/collect-evidence.js --issue <ISSUE-ID | path/to/issue.md> [options]

Options:
  --all, -a     Collect evidence for every issue in docs/agent_output/00-issues/
  --issue, -i   Issue id (e.g. ISSUE-001) or a path to the issue markdown file
  --depth, -d   Call-graph traversal depth in each direction (1-8, default 4)
  --no-graph    Skip Neo4j and use artifacts.json only
  --help, -h    Show this message

Exactly one of --all or --issue is required. The issue register is read-only input.`);
}

// ---------------------------------------------------------------------------
// Static model, derived from artifacts.json (same resolution rules as Graph Forge)
// ---------------------------------------------------------------------------

function simpleName(typeText) {
  if (!typeText) return null;
  return typeText.split('<')[0].trim();
}

function joinPath(base, extra) {
  const b = (base || '').replace(/^\/|\/$/g, '');
  const e = (extra || '').replace(/^\/|\/$/g, '');
  return '/' + [b, e].filter(Boolean).join('/');
}

function methodIdOf(typeId, method) {
  const paramSig = (method.params || []).map((p) => p.type).join(',');
  return `${typeId}#${method.name}(${paramSig})`;
}

function signatureOf(method) {
  const params = (method.params || []).map((p) => `${p.type} ${p.name}`).join(', ');
  return `${method.returnType || 'void'} ${method.name}(${params})`;
}

function endpointOf(type, method) {
  const classMapping = (type.annotations || []).find((a) => a.name === 'RequestMapping');
  const basePath = classMapping ? classMapping.args.value || '' : '';
  const mapping = (method.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
  if (!mapping) return null;
  return `${MAPPING_ANNOTATIONS[mapping.name]} ${joinPath(basePath, mapping.args.value || mapping.args.path || '')}`;
}

function buildModel(artifacts) {
  const { types } = artifacts;
  const typesById = new Map(types.map((t) => [t.id, t]));
  const nameToId = new Map();
  for (const t of types) if (!nameToId.has(t.name)) nameToId.set(t.name, t.id);

  const methodsById = new Map(); // methodId -> { id, type, method, endpoint }
  const methodsByType = new Map(); // typeId -> name -> [methodId]
  for (const t of types) {
    const byName = new Map();
    for (const m of t.methods || []) {
      const id = methodIdOf(t.id, m);
      methodsById.set(id, { id, type: t, method: m, endpoint: endpointOf(t, m) });
      const list = byName.get(m.name) || [];
      list.push(id);
      byName.set(m.name, list);
    }
    methodsByType.set(t.id, byName);
  }

  const fieldTypeByType = new Map(); // typeId -> fieldName -> targetTypeId
  for (const t of types) {
    const byField = new Map();
    for (const f of t.fields || []) {
      const targetId = nameToId.get(simpleName(f.type));
      if (targetId) byField.set(f.name, targetId);
    }
    fieldTypeByType.set(t.id, byField);
  }

  const callees = new Map(); // methodId -> Set(methodId)
  const callers = new Map(); // methodId -> Set(methodId)
  for (const t of types) {
    for (const m of t.methods || []) {
      const fromId = methodIdOf(t.id, m);
      const out = callees.get(fromId) || new Set();
      for (const call of m.calls || []) {
        if (!call.name) continue;
        let targetTypeId = null;
        if (call.receiverKind === 'none' || call.receiverKind === 'this') {
          targetTypeId = t.id;
        } else if (call.receiverKind === 'identifier' || call.receiverKind === 'this-field') {
          targetTypeId = fieldTypeByType.get(t.id)?.get(call.receiverName) || nameToId.get(call.receiverName) || null;
        }
        if (!targetTypeId) continue;
        for (const toId of methodsByType.get(targetTypeId)?.get(call.name) || []) {
          out.add(toId);
          const inverse = callers.get(toId) || new Set();
          inverse.add(fromId);
          callers.set(toId, inverse);
        }
      }
      callees.set(fromId, out);
    }
  }

  // Interface -> implementation. Spring injects the implementation behind the interface, so a
  // call to DepartmentService.createDepartment actually runs DepartmentServiceImpl.createDepartment.
  // The raw call graph (and Graph Forge's Neo4j CALLS edges) stop at the interface, which would
  // hide the real defect site — bridge them here and record the bridged edges for transparency.
  const implementationsOf = new Map(); // interfaceTypeId -> [implTypeId]
  for (const t of types) {
    for (const raw of [...(t.implements || []), ...(t.extends || [])]) {
      const targetId = nameToId.get(simpleName(raw));
      if (!targetId || targetId === t.id) continue;
      const list = implementationsOf.get(targetId) || [];
      list.push(t.id);
      implementationsOf.set(targetId, list);
    }
  }

  const dispatchEdges = [];
  for (const [interfaceId, implIds] of implementationsOf) {
    const interfaceType = typesById.get(interfaceId);
    if (!interfaceType) continue;
    for (const m of interfaceType.methods || []) {
      const fromId = methodIdOf(interfaceId, m);
      if (!methodsById.has(fromId)) continue;
      for (const implId of implIds) {
        for (const toId of methodsByType.get(implId)?.get(m.name) || []) {
          if (toId === fromId) continue;
          const out = callees.get(fromId) || new Set();
          out.add(toId);
          callees.set(fromId, out);
          const inverse = callers.get(toId) || new Set();
          inverse.add(fromId);
          callers.set(toId, inverse);
          dispatchEdges.push({ from: fromId, to: toId });
        }
      }
    }
  }

  return { types, typesById, nameToId, methodsById, methodsByType, fieldTypeByType, callees, callers, implementationsOf, dispatchEdges };
}

/** Resolve "Type.method", "pkg.Type.method" or "Type" into concrete method entries. */
function resolveSymbol(model, spec) {
  const cleaned = spec.replace(/\(\)\s*$/, '').trim();
  const parts = cleaned.split('.');
  const last = parts[parts.length - 1];
  const maybeTypeName = parts.length > 1 ? parts[parts.length - 2] : null;

  // "Type" only — return every method on the type.
  const typeIdDirect = model.nameToId.get(last);
  if (typeIdDirect && !maybeTypeName) {
    const type = model.typesById.get(typeIdDirect);
    return (type.methods || []).map((m) => model.methodsById.get(methodIdOf(type.id, m)));
  }

  const typeId = maybeTypeName ? model.nameToId.get(maybeTypeName) : null;
  if (typeId) {
    const ids = model.methodsByType.get(typeId)?.get(last) || [];
    if (ids.length) return ids.map((id) => model.methodsById.get(id));
    const type = model.typesById.get(typeId);
    if (type) return (type.methods || []).map((m) => model.methodsById.get(methodIdOf(type.id, m)));
  }

  // Last resort: unique method name anywhere in the workspace.
  const matches = [...model.methodsById.values()].filter((e) => e.method.name === last);
  return matches.length ? matches : [];
}

/** Breadth-first walk over the call graph, returning both the reached set and the paths. */
function walk(model, startId, direction, depth) {
  const edges = direction === 'up' ? model.callers : model.callees;
  const reached = new Map(); // methodId -> distance
  const paths = [];
  const queue = [[startId]];
  const seen = new Set([startId]);

  while (queue.length) {
    const chain = queue.shift();
    const tail = chain[chain.length - 1];
    if (chain.length - 1 >= depth) continue;
    const next = edges.get(tail) || new Set();
    let extended = false;
    for (const id of next) {
      if (chain.includes(id)) {
        paths.push([...chain, id]); // cycle — record it, do not follow
        continue;
      }
      extended = true;
      const distance = chain.length;
      if (!reached.has(id) || reached.get(id) > distance) reached.set(id, distance);
      const nextChain = [...chain, id];
      if (!seen.has(id)) {
        seen.add(id);
        queue.push(nextChain);
      } else {
        paths.push(nextChain);
      }
      if ((edges.get(id) || new Set()).size === 0) paths.push(nextChain);
    }
    if (!extended && chain.length > 1) paths.push(chain);
  }

  const unique = [];
  const seenPaths = new Set();
  for (const chain of paths) {
    const key = chain.join('>');
    if (seenPaths.has(key)) continue;
    seenPaths.add(key);
    unique.push(chain);
  }
  return { reached, paths: unique };
}

function describeMethod(model, methodId) {
  const entry = model.methodsById.get(methodId);
  if (!entry) return { id: methodId, label: methodId, module: null };
  return {
    id: methodId,
    label: `${entry.type.name}.${entry.method.name}()`,
    type: entry.type.name,
    typeId: entry.type.id,
    module: entry.type.module,
    file: entry.type.file,
    startLine: entry.method.startLine,
    endLine: entry.method.endLine,
    signature: signatureOf(entry.method),
    endpoint: entry.endpoint,
  };
}

/**
 * Injected collaborators of the type that owns the focus method — what that method
 * could have delegated to. A collaborator the focus method never touches, especially
 * one exposing a method of the same name, is a strong "missing delegation" signal.
 */
function collaboratorsOf(model, entry) {
  const type = entry.type;
  const focusId = entry.id;
  const focusName = entry.method.name;
  const calledIds = model.callees.get(focusId) || new Set();
  const collaborators = [];

  for (const field of type.fields || []) {
    const targetId = model.nameToId.get(simpleName(field.type));
    if (!targetId || targetId === type.id) continue;
    const targetType = model.typesById.get(targetId);
    if (!targetType) continue;

    const implementations = (model.implementationsOf.get(targetId) || [])
      .map((id) => model.typesById.get(id))
      .filter(Boolean);

    const sameNameIds = [targetId, ...implementations.map((i) => i.id)]
      .flatMap((id) => model.methodsByType.get(id)?.get(focusName) || []);

    const calledMethods = [...calledIds]
      .filter((id) => id.startsWith(`${targetId}#`) || implementations.some((i) => id.startsWith(`${i.id}#`)))
      .map((id) => describeMethod(model, id).label);

    // Calls on this field that resolved to no method node — typically framework methods
    // inherited from a base interface (e.g. MongoRepository.findAll). They are real calls
    // and must not be reported as "never called".
    const knownNames = new Set(calledMethods.map((label) => label.replace(/^.*\./, '').replace(/\(\)$/, '')));
    const inheritedCalls = [...new Set((entry.method.calls || [])
      .filter((call) => call.receiverName === field.name && call.name && !knownNames.has(call.name))
      .map((call) => `${call.name}()`))];

    collaborators.push({
      field: `${field.type} ${field.name}`,
      inheritedCalls,
      typeName: targetType.name,
      typeId: targetId,
      kind: targetType.kind,
      module: targetType.module,
      implementations: implementations.map((i) => i.name),
      calledByFocusMethod: calledMethods.length > 0 || inheritedCalls.length > 0,
      calledMethods,
      sameNameMethods: sameNameIds.map((id) => {
        const m = model.methodsById.get(id);
        return {
          id,
          label: `${m.type.name}.${m.method.name}()`,
          kind: m.type.kind,
          file: m.type.file,
          startLine: m.method.startLine,
          endLine: m.method.endLine,
          signature: signatureOf(m.method),
          source: m.type.kind === 'interface' ? null : m.method.source,
        };
      }),
    });
  }
  return collaborators;
}

/**
 * Cross-service consumers the code graph cannot see: services that reach the affected
 * service over HTTP rather than through a Java call edge. Detected from outbound HTTP
 * client fields plus a name match against the affected module.
 */
function findCrossServiceConsumers(model, affectedModules) {
  const keywords = affectedModules.map((m) => m.replace(/-service$/, '').replace(/[^a-z]/gi, '').toLowerCase()).filter(Boolean);
  const consumers = [];
  for (const t of model.types) {
    if (affectedModules.includes(t.module)) continue;
    const httpFields = (t.fields || []).filter((f) => HTTP_CLIENT_TYPES.includes(simpleName(f.type)) || HTTP_CLIENT_TYPES.includes(f.type));
    if (!httpFields.length) continue;
    const hints = (t.fields || [])
      .map((f) => `${f.type} ${f.name}`)
      .filter((text) => keywords.some((k) => k && text.toLowerCase().includes(k)));
    consumers.push({
      module: t.module,
      type: t.name,
      typeId: t.id,
      file: t.file,
      httpClients: httpFields.map((f) => `${f.type} ${f.name}`),
      referencesAffectedService: hints.length > 0,
      hints,
    });
  }
  return consumers.sort((a, b) => Number(b.referencesAffectedService) - Number(a.referencesAffectedService));
}

// ---------------------------------------------------------------------------
// Slices of the two generated documents
// ---------------------------------------------------------------------------

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

function architectureSlices(text, { modules, endpoints }) {
  if (!text) return null;
  const lines = text.split(/\r?\n/);
  const slice = { moduleRows: [], typeBreakdownRows: [], endpointRows: [], serviceMap: null, observations: [] };

  const mermaid = /```mermaid\r?\n([\s\S]*?)```/.exec(text);
  if (mermaid) slice.serviceMap = mermaid[0];

  for (const line of lines) {
    if (!line.trim().startsWith('|')) continue;
    const cells = line.split('|').map((c) => c.trim());
    const first = cells[1] || '';
    const moduleMatch = modules.some((m) => first.includes(`\`${m}\``));
    if (moduleMatch && cells.length >= 4) {
      if (/^\|\s*`[^`]+`\s*\|\s*(war|jar|pom)\b/.test(line)) slice.moduleRows.push(line.trim());
      else slice.typeBreakdownRows.push(line.trim());
    }
    if (endpoints.some((e) => line.includes(`\`${e.split(' ')[1]}\``))) slice.endpointRows.push(line.trim());
  }

  const obsIndex = lines.findIndex((l) => /^##\s+Ideas & Observations/.test(l));
  if (obsIndex >= 0) {
    for (let i = obsIndex + 1; i < lines.length; i += 1) {
      if (/^##\s/.test(lines[i])) break;
      if (lines[i].trim().startsWith('- ')) slice.observations.push(lines[i].trim());
    }
  }
  return slice;
}

function functionReferenceExcerpts(text, methodLabels) {
  if (!text) return [];
  const wanted = new Set(methodLabels);
  const excerpts = [];
  const lines = text.split(/\r?\n/);
  let current = null;

  for (const line of lines) {
    const heading = /^####\s+`([^`]+)`/.exec(line);
    if (heading) {
      if (current) excerpts.push(current);
      current = wanted.has(heading[1]) ? { heading: heading[1], lines: [line] } : null;
      continue;
    }
    if (/^#{1,3}\s/.test(line)) {
      if (current) excerpts.push(current);
      current = null;
      continue;
    }
    if (current) current.lines.push(line);
  }
  if (current) excerpts.push(current);

  return excerpts.map((e) => ({ heading: e.heading, text: e.lines.join('\n').trim() }));
}

// ---------------------------------------------------------------------------
// Neo4j knowledge graph (optional — degrades to the static model)
// ---------------------------------------------------------------------------

function loadEnv() {
  const local = path.join(SKILL_DIR, '.env');
  const shared = path.join(SKILL_DIR, '..', '01c-graph-forge', '.env');
  const file = fs.existsSync(local) ? local : shared;
  try {
    require('dotenv').config({ path: file });
    return fs.existsSync(file) ? path.relative(REPO_ROOT, file) : null;
  } catch (err) {
    return null;
  }
}

async function queryGraph(focusIds, affectedModules, depth) {
  const envFile = loadEnv();
  const uri = process.env.NEO4J_URI;
  const user = process.env.NEO4J_USERNAME;
  const password = process.env.NEO4J_PASSWORD;
  if (!uri || !user || !password) {
    return { live: false, reason: `No Neo4j credentials found (looked for ${envFile || '.env'}). Falling back to artifacts.json.` };
  }

  let neo4j;
  try {
    neo4j = require('neo4j-driver');
  } catch (err) {
    return { live: false, reason: 'neo4j-driver is not installed in this skill folder — run `npm install`. Falling back to artifacts.json.' };
  }

  const driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  const database = process.env.NEO4J_DATABASE || 'neo4j';
  const d = Math.max(1, Math.min(8, depth));
  const result = { live: true, host: uri.replace(/\/\/.*@/, '//'), envFile, depth: d, perMethod: {}, nodeCounts: [], relationshipCounts: [], moduleDependencies: [], queries: [] };

  const run = async (session, name, cypher, params) => {
    result.queries.push({ name, cypher: cypher.trim() });
    const res = await session.run(cypher, params);
    return res.records.map((r) => r.toObject());
  };

  let session;
  try {
    await driver.verifyConnectivity();
    session = driver.session({ database });

    for (const id of focusIds) {
      const callerChains = await run(session, 'upstream callers',
        `MATCH path = (caller:Method)-[:CALLS*1..${d}]->(target:Method {id: $id})
         RETURN [n IN nodes(path) | n.id] AS chain LIMIT 200`, { id });
      const calleeChains = await run(session, 'downstream callees',
        `MATCH path = (target:Method {id: $id})-[:CALLS*1..${d}]->(callee:Method)
         RETURN [n IN nodes(path) | n.id] AS chain LIMIT 200`, { id });
      const owner = await run(session, 'owning type, module and exposed endpoints',
        `MATCH (ty:Type)-[:HAS_METHOD]->(m:Method {id: $id})
         OPTIONAL MATCH (ty)-[:EXPOSES]->(e:Endpoint)
         RETURN ty.id AS typeId, ty.name AS typeName, ty.module AS module,
                collect(DISTINCT e.id) AS endpoints`, { id });
      const fanIn = await run(session, 'types depending on the owning type',
        `MATCH (other:Type)-[:USES]->(ty:Type)-[:HAS_METHOD]->(:Method {id: $id})
         RETURN DISTINCT other.id AS typeId, other.name AS name, other.module AS module`, { id });
      const upstreamModules = await run(session, 'modules reaching the focus method',
        `MATCH (caller:Method)-[:CALLS*1..${d}]->(:Method {id: $id})
         MATCH (ct:Type)-[:HAS_METHOD]->(caller)
         RETURN DISTINCT ct.module AS module`, { id });

      result.perMethod[id] = {
        callerChains: callerChains.map((r) => r.chain),
        calleeChains: calleeChains.map((r) => r.chain),
        owner: owner[0] || null,
        fanIn,
        upstreamModules: upstreamModules.map((r) => r.module).filter(Boolean),
      };
    }

    result.nodeCounts = await run(session, 'graph node counts',
      'MATCH (n) RETURN labels(n)[0] AS label, count(*) AS count ORDER BY count DESC', {});
    result.relationshipCounts = await run(session, 'graph relationship counts',
      'MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS count ORDER BY count DESC', {});
    result.moduleDependencies = await run(session, 'maven dependencies of affected modules',
      `MATCH (m:Module)-[:DEPENDS_ON]->(dep:MavenDependency)
       WHERE m.name IN $modules
       RETURN m.name AS module, collect(dep.ga) AS dependencies`, { modules: affectedModules });

    // Neo4j integers come back as {low, high}; flatten to plain numbers.
    const toNumber = (v) => (v && typeof v === 'object' && 'low' in v ? v.low : v);
    result.nodeCounts = result.nodeCounts.map((r) => ({ label: r.label, count: toNumber(r.count) }));
    result.relationshipCounts = result.relationshipCounts.map((r) => ({ type: r.type, count: toNumber(r.count) }));

    return result;
  } catch (err) {
    return { live: false, reason: `Neo4j unavailable (${err.message}). Falling back to artifacts.json.`, envFile };
  } finally {
    if (session) await session.close();
    await driver.close();
  }
}

// ---------------------------------------------------------------------------
// Evidence briefing (markdown)
// ---------------------------------------------------------------------------

function renderEvidenceMarkdown(evidence) {
  const { issue, focus, affectedArea, graph, architectureContext, functionReferenceExcerpts: excerpts, sources } = evidence;
  const out = [];

  out.push(`# Evidence Bundle — ${issue.id}`);
  out.push('');
  out.push(`_Collected ${evidence.generatedAt} by the Root Cause Analyst evidence collector. Facts only — no diagnosis._`);
  out.push('');
  out.push(`**Issue:** ${issue.title}`);
  out.push(`**Type / Severity:** ${issue.type || 'n/a'} / ${issue.severity || 'n/a'}`);
  out.push(`**Reported services:** ${issue.services.join(', ') || 'n/a'}`);
  out.push(`**Source file:** \`${issue.file}\``);
  out.push('');

  out.push('## 1. Input Sources');
  out.push('');
  out.push('| Input | Status |');
  out.push('|---|---|');
  out.push(`| Issue report | \`${issue.file}\` |`);
  out.push(`| \`docs/agent_output/01-architecture/architecture.md\` | ${sources.architecture ? 'loaded' : 'MISSING — run Blueprint Scribe'} |`);
  out.push(`| \`docs/agent_output/01-architecture/function-reference.md\` | ${sources.functionReference ? 'loaded' : 'MISSING — run Blueprint Scribe'} |`);
  out.push(`| \`.github/.pipeline-context/artifacts.json\` | scanned ${sources.artifactsGeneratedAt} |`);
  out.push(`| Neo4j graph | ${graph.live ? `live (depth ${graph.depth})` : `not used — ${graph.reason}`} |`);
  out.push('');
  out.push(`_Resolution note: ${evidence.sources.dispatchEdges} interface → implementation dispatch edge(s) were bridged into the call graph, because Spring injects the implementation behind the interface. Neo4j's raw \`CALLS\` edges stop at the interface, so paths below may be one hop longer than the graph shows._`);
  out.push('');

  out.push('## 2. Focus Points (issue symbols resolved to code)');
  out.push('');
  for (const f of focus) {
    if (!f.resolved) {
      out.push(`### \`${f.symbol}\` — UNRESOLVED`);
      out.push('');
      out.push(`Could not match this symbol in \`artifacts.json\`. Re-run the Code Cartographer scan or correct \`affected_symbols\` in the issue file.`);
      out.push('');
      continue;
    }
    out.push(`### \`${f.label}\``);
    out.push('');
    out.push(`- **Module:** \`${f.module}\``);
    out.push(`- **Signature:** \`${f.signature}\``);
    out.push(`- **Location:** \`${f.file}\` (lines ${f.startLine}-${f.endLine})`);
    if (f.endpoint) out.push(`- **REST endpoint:** \`${f.endpoint}\``);
    if (f.annotations.length) out.push(`- **Annotations:** ${f.annotations.join(', ')}`);
    out.push(`- **Direct callers:** ${f.directCallers.length ? f.directCallers.map((c) => `\`${c.label}\`${c.id === f.id ? ' **(self — recursion)**' : ''}`).join(', ') : '_none resolved_'}`);
    out.push(`- **Direct callees:** ${f.directCallees.length ? f.directCallees.map((c) => `\`${c.label}\`${c.id === f.id ? ' **(self — recursion)**' : ''}`).join(', ') : '_none resolved_'}`);
    if (f.selfRecursive) out.push('- **⚠️ Self-referencing call edge detected on this method.**');
    out.push('');
    out.push('```java');
    out.push(f.source);
    out.push('```');
    out.push('');
    if (f.collaborators.length) {
      out.push(`**Injected collaborators of \`${f.typeName}\`** — what this method could delegate to:`);
      out.push('');
      out.push('| Field | Resolves to | Called by focus method | Method of the same name available |');
      out.push('|---|---|---|---|');
      for (const c of f.collaborators) {
        const resolvesTo = `\`${c.typeName}\` (${c.kind}${c.implementations.length ? `, implemented by ${c.implementations.map((i) => `\`${i}\``).join(', ')}` : ''})`;
        const calls = [
          ...c.calledMethods.map((m) => `\`${m}\``),
          ...c.inheritedCalls.map((m) => `\`${m}\` _(inherited from a framework base type — no method node)_`),
        ];
        const called = calls.length ? `yes — ${calls.join(', ')}` : '**no**';
        const sameName = c.sameNameMethods.length
          ? c.sameNameMethods.map((m) => `\`${m.label}\``).join(', ')
          : '_none_';
        out.push(`| \`${c.field}\` | ${resolvesTo} | ${called} | ${sameName} |`);
      }
      out.push('');
      const bypassed = f.collaborators.filter((c) => !c.calledByFocusMethod && c.sameNameMethods.length);
      for (const c of bypassed) {
        out.push(`> ⚠️ \`${f.label}\` never calls \`${c.typeName}\`, yet ${c.sameNameMethods.map((m) => `\`${m.label}\``).join(' / ')} exists and is unreached from here.`);
        out.push('');
        for (const m of c.sameNameMethods.filter((x) => x.source)) {
          out.push(`<details><summary>Unreached implementation: <code>${m.label}</code> — <code>${m.file}</code> lines ${m.startLine}-${m.endLine}</summary>`);
          out.push('');
          out.push('```java');
          out.push(m.source);
          out.push('```');
          out.push('');
          out.push('</details>');
          out.push('');
        }
      }
    }
    if (f.upstreamChains.length) {
      out.push('**Reaching paths (caller → … → focus):**');
      out.push('');
      for (const chain of f.upstreamChains.slice(0, 12)) out.push(`- ${chain.join(' → ')}`);
      out.push('');
    }
    if (f.downstreamChains.length) {
      out.push('**Outgoing paths (focus → … → callee):**');
      out.push('');
      for (const chain of f.downstreamChains.slice(0, 12)) out.push(`- ${chain.join(' → ')}`);
      out.push('');
    }
  }

  out.push('## 3. Affected Area');
  out.push('');
  out.push(`- **Modules touched (Java call graph):** ${affectedArea.modules.map((m) => `\`${m}\``).join(', ') || '_none beyond the reported service_'}`);
  out.push(`- **REST endpoints on a reaching path:** ${affectedArea.endpoints.map((e) => `\`${e}\``).join(', ') || '_none resolved_'}`);
  out.push(`- **Types on a reaching path:** ${affectedArea.types.map((t) => `\`${t}\``).join(', ') || '_none_'}`);
  out.push(`- **Scheduled jobs on a reaching path:** ${affectedArea.scheduledJobs.map((j) => `\`${j}\``).join(', ') || '_none_'}`);
  out.push('');
  if (affectedArea.crossServiceConsumers.length) {
    out.push('**Cross-service HTTP consumers** (not visible as Java call edges — these services call the affected service over REST):');
    out.push('');
    out.push('| Module | Type | HTTP client fields | Mentions affected service |');
    out.push('|---|---|---|---|');
    for (const c of affectedArea.crossServiceConsumers) {
      out.push(`| \`${c.module}\` | \`${c.type}\` | ${c.httpClients.map((h) => `\`${h}\``).join(', ')} | ${c.referencesAffectedService ? `yes — ${c.hints.map((h) => `\`${h}\``).join(', ')}` : 'not detected'} |`);
    }
    out.push('');
  }

  out.push('## 4. Architecture Context');
  out.push('');
  if (!architectureContext) {
    out.push('_`docs/agent_output/01-architecture/architecture.md` is missing — run Blueprint Scribe to regenerate it._');
    out.push('');
  } else {
    if (architectureContext.moduleRows.length) {
      out.push('**Affected modules (from the module table):**');
      out.push('');
      out.push('| Module | Packaging | Key Spring dependencies |');
      out.push('|---|---|---|');
      architectureContext.moduleRows.forEach((r) => out.push(r));
      out.push('');
    }
    if (architectureContext.endpointRows.length) {
      out.push('**Related REST surface rows:**');
      out.push('');
      out.push('| Method | Path | Handler | Module |');
      out.push('|---|---|---|---|');
      architectureContext.endpointRows.forEach((r) => out.push(r));
      out.push('');
    }
    if (architectureContext.serviceMap) {
      out.push('**Service topology:**');
      out.push('');
      out.push(architectureContext.serviceMap);
      out.push('');
    }
    if (architectureContext.observations.length) {
      out.push('**Documented observations:**');
      out.push('');
      architectureContext.observations.forEach((o) => out.push(o));
      out.push('');
    }
  }

  out.push('## 5. Graph Findings');
  out.push('');
  if (!graph.live) {
    out.push(`_Neo4j not used: ${graph.reason}_`);
    out.push('');
    out.push('All call-graph facts above come from `.github/.pipeline-context/artifacts.json` using the same resolution rules Graph Forge applies, so they remain valid — only live cross-checking is unavailable.');
    out.push('');
  } else {
    out.push(`Connected to \`${graph.host}\`, database traversal depth ${graph.depth}.`);
    out.push('');
    for (const [id, data] of Object.entries(graph.perMethod)) {
      out.push(`**\`${id}\`**`);
      out.push('');
      if (data.owner) {
        out.push(`- Owning type: \`${data.owner.typeName}\` in module \`${data.owner.module}\``);
        const eps = (data.owner.endpoints || []).filter(Boolean);
        out.push(`- Endpoints exposed by that type: ${eps.length ? eps.map((e) => `\`${e}\``).join(', ') : '_none_'}`);
      }
      out.push(`- Upstream caller paths in graph: ${data.callerChains.length}`);
      out.push(`- Downstream callee paths in graph: ${data.calleeChains.length}`);
      out.push(`- Modules reaching it: ${data.upstreamModules.map((m) => `\`${m}\``).join(', ') || '_none_'}`);
      out.push(`- Types depending on the owning type (\`USES\` fan-in): ${data.fanIn.map((f) => `\`${f.name}\` (${f.module})`).join(', ') || '_none_'}`);
      out.push('');
    }
    if (graph.moduleDependencies.length) {
      out.push('**Maven dependencies of the affected modules:**');
      out.push('');
      for (const row of graph.moduleDependencies) {
        out.push(`- \`${row.module}\`: ${row.dependencies.length} declared dependencies`);
      }
      out.push('');
    }
    if (graph.nodeCounts.length) {
      out.push(`**Graph size:** ${graph.nodeCounts.map((n) => `${n.label} ${n.count}`).join(', ')}`);
      out.push('');
      out.push(`**Relationships:** ${graph.relationshipCounts.map((r) => `${r.type} ${r.count}`).join(', ')}`);
      out.push('');
    }
  }

  out.push('## 6. Function Reference Excerpts');
  out.push('');
  if (!excerpts.length) {
    out.push('_No matching entries found in `docs/agent_output/01-architecture/function-reference.md`._');
    out.push('');
  } else {
    for (const e of excerpts) {
      out.push(`<details><summary><code>${e.heading}</code></summary>`);
      out.push('');
      out.push(e.text);
      out.push('');
      out.push('</details>');
      out.push('');
    }
  }

  out.push('## 7. Reported Symptom (verbatim from the issue file)');
  out.push('');
  out.push(issue.body.trim());
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

/** Collect evidence for exactly one issue. Returns a short summary for the caller to print. */
async function collectForIssue(registerEntry, args, context) {
  const { model, artifacts, architectureText, functionReferenceText } = context;
  if (!registerEntry || !registerEntry.id) {
    throw new Error(`A register row has no "issue_id". See docs/agent_output/00-issues/README.md for the expected columns.`);
  }

  // The register loader already normalized every column; `file` points at the
  // spreadsheet the row came from, and `body` is synthesized from its text columns.
  const issue = {
    id: registerEntry.id,
    title: registerEntry.title,
    type: registerEntry.type,
    severity: registerEntry.severity,
    status: registerEntry.status,
    reportedOn: registerEntry.reportedOn,
    reportedBy: registerEntry.reportedBy,
    services: registerEntry.services,
    symbols: registerEntry.symbols,
    files: registerEntry.files,
    entryPoints: registerEntry.entryPoints,
    file: registerEntry.relativeFile,
    body: registerEntry.body,
  };

  // --- Resolve each reported symbol and expand it through the call graph -----
  const focus = [];
  const focusIds = [];
  for (const symbol of issue.symbols) {
    const entries = resolveSymbol(model, symbol);
    if (!entries.length) {
      focus.push({ symbol, resolved: false });
      continue;
    }
    for (const entry of entries) {
      const id = entry.id;
      if (focusIds.includes(id)) continue;
      focusIds.push(id);

      const up = walk(model, id, 'up', args.depth);
      const down = walk(model, id, 'down', args.depth);
      const directCallers = [...(model.callers.get(id) || [])].map((c) => describeMethod(model, c));
      const directCallees = [...(model.callees.get(id) || [])].map((c) => describeMethod(model, c));

      focus.push({
        symbol,
        resolved: true,
        id,
        label: `${entry.type.name}.${entry.method.name}()`,
        typeId: entry.type.id,
        typeName: entry.type.name,
        module: entry.type.module,
        file: entry.type.file,
        startLine: entry.method.startLine,
        endLine: entry.method.endLine,
        signature: signatureOf(entry.method),
        endpoint: entry.endpoint,
        annotations: (entry.method.annotations || []).map((a) => `@${a.name}`),
        source: entry.method.source,
        selfRecursive: directCallees.some((c) => c.id === id),
        directCallers,
        directCallees,
        collaborators: collaboratorsOf(model, entry),
        upstream: [...up.reached.keys()].map((mid) => describeMethod(model, mid)),
        downstream: [...down.reached.keys()].map((mid) => describeMethod(model, mid)),
        upstreamChains: up.paths.map((chain) => chain.map((mid) => describeMethod(model, mid).label).reverse()),
        downstreamChains: down.paths.map((chain) => chain.map((mid) => describeMethod(model, mid).label)),
      });
    }
  }

  // --- Affected area --------------------------------------------------------
  // Context for the impact narrative: what the defect can reach in code. This report
  // states what the defect means; it does not map how far it spreads.
  const reachedIds = new Set(focusIds);
  for (const f of focus) {
    if (!f.resolved) continue;
    f.upstream.forEach((m) => reachedIds.add(m.id));
    f.downstream.forEach((m) => reachedIds.add(m.id));
  }
  const reachedMethods = [...reachedIds].map((id) => model.methodsById.get(id)).filter(Boolean);
  const affectedModules = [...new Set([...issue.services, ...reachedMethods.map((m) => m.type.module)])].filter(Boolean);

  const affectedArea = {
    modules: [...new Set(reachedMethods.map((m) => m.type.module))].sort(),
    types: [...new Set(reachedMethods.map((m) => m.type.name))].sort(),
    endpoints: [...new Set([...reachedMethods.map((m) => m.endpoint).filter(Boolean), ...issue.entryPoints])].sort(),
    scheduledJobs: [...new Set(reachedMethods
      .filter((m) => (m.method.annotations || []).some((a) => a.name === 'Scheduled'))
      .map((m) => `${m.type.name}.${m.method.name}()`))].sort(),
    crossServiceConsumers: findCrossServiceConsumers(model, issue.services.length ? issue.services : affectedModulesFallback(reachedMethods)),
  };

  // --- Documentation slices -------------------------------------------------
  const architectureContext = architectureSlices(architectureText, {
    modules: affectedModules,
    endpoints: affectedArea.endpoints,
  });
  const excerptLabels = [...new Set([
    ...focus.filter((f) => f.resolved).map((f) => f.label),
    ...reachedMethods.map((m) => `${m.type.name}.${m.method.name}()`),
  ])];
  const excerpts = functionReferenceExcerpts(functionReferenceText, excerptLabels);

  // --- Graph ----------------------------------------------------------------
  const graph = args.graph
    ? await queryGraph(focusIds, affectedModules, args.depth)
    : { live: false, reason: 'skipped via --no-graph' };

  // --- Write ----------------------------------------------------------------
  const evidence = {
    generatedAt: new Date().toISOString(),
    issue,
    sources: {
      issueFile: issue.file,
      architecture: Boolean(architectureText),
      functionReference: Boolean(functionReferenceText),
      artifacts: rel(ARTIFACTS_FILE),
      artifactsGeneratedAt: artifacts.generatedAt,
      graphLive: Boolean(graph.live),
      dispatchEdges: model.dispatchEdges.length,
    },
    focus,
    affectedArea,
    architectureContext,
    functionReferenceExcerpts: excerpts,
    graph,
  };

  fs.mkdirSync(RCA_DIR, { recursive: true });
  const jsonPath = path.join(RCA_DIR, `${issue.id}.evidence.json`);
  const mdPath = path.join(RCA_DIR, `${issue.id}.evidence.md`);
  fs.writeFileSync(jsonPath, JSON.stringify(evidence, null, 2));
  fs.writeFileSync(mdPath, renderEvidenceMarkdown(evidence));

  return {
    id: issue.id,
    severity: issue.severity,
    focusCount: focusIds.length,
    unresolved: focus.filter((f) => !f.resolved).map((f) => f.symbol),
    affectedArea,
    graphNote: graph.live ? `live (depth ${graph.depth})` : graph.reason,
    briefing: rel(mdPath),
    bundle: rel(jsonPath),
  };
}

function affectedModulesFallback(reachedMethods) {
  return [...new Set(reachedMethods.map((m) => m.type.module))];
}

function printSummary(s) {
  console.log(`\n${s.id} — evidence collected`);
  console.log(`  focus methods : ${s.focusCount}${s.unresolved.length ? ` (UNRESOLVED symbols: ${s.unresolved.join(', ')})` : ''}`);
  console.log(`  affected area : ${s.affectedArea.modules.length} module(s), ${s.affectedArea.types.length} type(s), ${s.affectedArea.endpoints.length} endpoint(s)`);
  console.log(`  graph         : ${s.graphNote}`);
  console.log(`  briefing      : ${s.briefing}`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  // Work out which issues to process. The register is read-only input: whatever is in
  // docs/agent_output/00-issues/ is the workload, and this script never adds to it.
  let workload;
  if (args.all) {
    workload = listIssues();
    if (!workload.length) {
      throw new Error(`No issues found in ${rel(ISSUE_REGISTER_FILE)}. Every row must carry an "issue_id" — see docs/agent_output/00-issues/README.md.`);
    }
    console.log(`Root Cause Analyst — ${workload.length} issue(s) in the register: ${workload.map((i) => i.id).join(', ')}`);
  } else {
    workload = [resolveIssue(args.issue)];
  }

  // Shared inputs, loaded once for the whole batch.
  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts at ${rel(ARTIFACTS_FILE)}. Run the Code Cartographer scan first (.github/skills/01a-code-cartographer).`);
  }
  const artifacts = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));
  const context = {
    artifacts,
    model: buildModel(artifacts),
    architectureText: readIfPresent(ARCHITECTURE_MD),
    functionReferenceText: readIfPresent(FUNCTION_REFERENCE_MD),
  };
  if (!context.architectureText) console.warn(`Warning: ${rel(ARCHITECTURE_MD)} is missing — run Blueprint Scribe for full architecture context.`);
  if (!context.functionReferenceText) console.warn(`Warning: ${rel(FUNCTION_REFERENCE_MD)} is missing — run Blueprint Scribe for function-level excerpts.`);

  // One issue failing must not abort the rest of the batch.
  const done = [];
  const failed = [];
  for (const entry of workload) {
    try {
      const summary = await collectForIssue(entry, args, context);
      done.push(summary);
      printSummary(summary);
    } catch (err) {
      if (workload.length === 1) throw err;
      failed.push({ file: entry.id, message: err.message });
      console.error(`\n${entry.id} — FAILED: ${err.message}`);
    }
  }

  if (workload.length > 1) {
    console.log(`\nCollected ${done.length}/${workload.length} issue(s): ${done.map((s) => s.id).join(', ') || 'none'}`);
    if (failed.length) console.log(`Failed: ${failed.map((f) => f.file).join(', ')}`);
    const unresolved = done.filter((s) => s.unresolved.length);
    if (unresolved.length) {
      console.log(`Issues with unresolved symbols (fix affected_symbols in the register): ${unresolved.map((s) => s.id).join(', ')}`);
    }
  }
  console.log(`\nNext: read each briefing in ${rel(RCA_DIR)}, then write <issue_id>.analysis.json alongside it.`);

  if (failed.length) process.exitCode = 1;
}

main().catch((err) => {
  console.error('Evidence collection failed:', err.message);
  process.exit(1);
});
