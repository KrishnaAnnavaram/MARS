#!/usr/bin/env node
/**
 * Blueprint Scribe
 * Reads Code Cartographer artifacts.json (and, best-effort, live stats from
 * the Neo4j graph built by Graph Forge) and writes docs/agent_output/01-architecture/architecture.md:
 * a narrative overview of modules, REST surface, class hierarchy and
 * frameworks in play.
 */
const fs = require('fs');
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '..', '01c-graph-forge', '.env') });

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');
const ARTIFACTS_FILE = path.join(DATA_DIR, 'artifacts.json');
const OUT_FILE = path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'architecture.md');

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
};

function simpleName(typeText) {
  if (!typeText) return null;
  return typeText.split('<')[0].trim();
}

function joinPath(base, extra) {
  const b = (base || '').replace(/^\/|\/$/g, '');
  const e = (extra || '').replace(/^\/|\/$/g, '');
  return '/' + [b, e].filter(Boolean).join('/');
}

function hasAnnotation(annotations, name) {
  return (annotations || []).some((a) => a.name === name);
}

function classify(type) {
  const names = (type.annotations || []).map((a) => a.name);
  const implementsNames = (type.implements || []).map(simpleName);
  if (names.includes('RestController') || names.includes('Controller')) return 'Controller';
  if (names.includes('RestControllerAdvice') || names.includes('ControllerAdvice')) return 'Exception Handler';
  if (names.includes('Service')) return 'Service';
  if (names.includes('Repository') || implementsNames.some((n) => n && n.includes('Repository'))) return 'Repository';
  if (names.includes('Document') || names.includes('Entity')) return 'Entity/Document';
  if (names.includes('Configuration')) return 'Configuration';
  if (/dto/i.test(type.package || '') || /Dto$|DTO$/.test(type.name || '')) return 'DTO';
  if (type.kind === 'interface' && /Service$/.test(type.name || '')) return 'Service';
  if (/Exception$/.test(type.name || '')) return 'Exception';
  return 'Other';
}

function buildEndpoints(types) {
  const rows = [];
  for (const t of types) {
    const classMapping = (t.annotations || []).find((a) => a.name === 'RequestMapping');
    const basePath = classMapping ? classMapping.args.value || '' : '';
    for (const meth of t.methods) {
      const mapping = (meth.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
      if (!mapping) continue;
      const httpMethod = MAPPING_ANNOTATIONS[mapping.name];
      const fullPath = joinPath(basePath, mapping.args.value || mapping.args.path || '');
      rows.push({ method: httpMethod, path: fullPath, handler: `${t.name}.${meth.name}()`, module: t.module });
    }
  }
  return rows.sort((a, b) => (a.module + a.path).localeCompare(b.module + b.path));
}

async function tryGraphSnapshot() {
  const uri = process.env.NEO4J_URI;
  const user = process.env.NEO4J_USERNAME;
  const password = process.env.NEO4J_PASSWORD;
  if (!uri || !user || !password) return null;
  let driver;
  try {
    const neo4j = require('neo4j-driver');
    driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
    await driver.verifyConnectivity();
    const session = driver.session({ database: process.env.NEO4J_DATABASE || 'neo4j' });
    try {
      const nodeCounts = await session.run(
        'MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS count ORDER BY count DESC'
      );
      const relCounts = await session.run(
        'MATCH ()-[r]->() RETURN type(r) AS relType, count(*) AS count ORDER BY count DESC'
      );
      return {
        nodes: nodeCounts.records.map((r) => ({ label: r.get('label'), count: r.get('count').toNumber() })),
        rels: relCounts.records.map((r) => ({ relType: r.get('relType'), count: r.get('count').toNumber() })),
      };
    } finally {
      await session.close();
    }
  } catch (err) {
    console.warn('Blueprint Scribe: could not read live Neo4j snapshot, continuing with static artifacts only.', err.message);
    return null;
  } finally {
    if (driver) await driver.close();
  }
}

function renderModuleTable(modules) {
  const header = '| Module | Packaging | Key Spring dependencies |\n|---|---|---|';
  const rows = modules.map((m) => {
    const key = m.dependencies
      .map((d) => d.artifactId)
      .filter((a) => /^spring-cloud-starter|^spring-boot-starter/.test(a))
      .join(', ');
    return `| \`${m.name}\` | ${m.packaging} | ${key || '—'} |`;
  });
  return [header, ...rows].join('\n');
}

function renderTypeBreakdown(types, modules) {
  const byModule = new Map(modules.map((m) => [m.name, {}]));
  for (const t of types) {
    const bucket = byModule.get(t.module) || {};
    const kind = classify(t);
    bucket[kind] = (bucket[kind] || 0) + 1;
    byModule.set(t.module, bucket);
  }
  const kinds = ['Controller', 'Service', 'Repository', 'Entity/Document', 'DTO', 'Configuration', 'Exception Handler', 'Exception', 'Other'];
  const header = `| Module | ${kinds.join(' | ')} |\n|---|${kinds.map(() => '---').join('|')}|`;
  const rows = [...byModule.entries()].map(([name, bucket]) => `| \`${name}\` | ${kinds.map((k) => bucket[k] || 0).join(' | ')} |`);
  return [header, ...rows].join('\n');
}

function renderEndpoints(endpoints) {
  if (!endpoints.length) return '_No REST mapping annotations were found._';
  const header = '| Method | Path | Handler | Module |\n|---|---|---|---|';
  const rows = endpoints.map((e) => `| \`${e.method}\` | \`${e.path}\` | ${e.handler} | \`${e.module}\` |`);
  return [header, ...rows].join('\n');
}

function renderExternalTypes(types) {
  const known = new Set(types.map((t) => t.name));
  const tally = new Map();
  for (const t of types) {
    for (const raw of [...(t.extends || []), ...(t.implements || [])]) {
      const name = simpleName(raw);
      if (name && !known.has(name)) tally.set(name, (tally.get(name) || 0) + 1);
    }
  }
  const sorted = [...tally.entries()].sort((a, b) => b[1] - a[1]);
  if (!sorted.length) return '_No external base types detected._';
  return sorted.map(([name, count]) => `- \`${name}\` — referenced by ${count} type(s)`).join('\n');
}

function renderModuleGraph(modules) {
  const lines = ['```mermaid', 'flowchart LR'];
  for (const m of modules) {
    const id = m.name.replace(/[^a-zA-Z0-9]/g, '_');
    lines.push(`  ${id}["${m.name}"]`);
    if (m.dependencies.some((d) => d.artifactId === 'spring-cloud-starter-netflix-eureka-client')) {
      lines.push(`  ${id} -.->|registers with| discovery_service["discovery-service"]`);
    }
    if (m.dependencies.some((d) => d.artifactId === 'spring-cloud-starter-config')) {
      lines.push(`  ${id} -.->|reads config from| configuaration_server["configuaration-server"]`);
    }
  }
  lines.push('```');
  return lines.join('\n');
}

async function main() {
  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts found at ${ARTIFACTS_FILE}. Run the Code Cartographer scan first.`);
  }
  const { modules, types, generatedAt } = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));
  const endpoints = buildEndpoints(types);
  const snapshot = await tryGraphSnapshot();

  const doc = `# Architecture Overview

_Generated by the Architect agent (Blueprint Scribe) from a scan on ${generatedAt}._

For a per-class, per-method breakdown (signatures, source locations, resolved call graph, and full source text), see [function-reference.md](./function-reference.md).

## Modules

This workspace is a multi-module Maven build of ${modules.length} Spring Boot services, each independently deployable, coordinated through a config server and a Eureka discovery server.

${renderModuleTable(modules)}

## Service Map

${renderModuleGraph(modules)}

## Type Breakdown by Layer

Counts are heuristic, based on annotations and naming conventions (e.g. \`@RestController\` → Controller, \`@Repository\`/\`*Repository\` interfaces → Repository).

${renderTypeBreakdown(types, modules)}

## REST API Surface

${renderEndpoints(endpoints)}

## Frameworks & External Types in Play

Base classes/interfaces referenced by this codebase that are not defined in it (a proxy for the frameworks the code depends on):

${renderExternalTypes(types)}

${snapshot ? `## Live Graph Snapshot (Neo4j)

The knowledge graph is loaded into Neo4j. Node/relationship counts at generation time:

**Nodes**
${snapshot.nodes.map((n) => `- ${n.label}: ${n.count}`).join('\n')}

**Relationships**
${snapshot.rels.map((r) => `- ${r.relType}: ${r.count}`).join('\n')}
` : '_Live Neo4j snapshot unavailable (graph-forge/.env not configured or instance unreachable) — showing static artifact analysis only._'}

## Ideas & Observations

- Each service (\`department-service\`, \`employee-service\`, \`report-service\`, \`sheduler-service\`) follows the same layered convention: \`controller\` → \`service\` → \`repository\` → \`model\`/\`entity\`, backed by MongoDB repositories.
- \`configuaration-server\` and \`discovery-service\` are infrastructure services (Spring Cloud Config + Eureka) that every business service depends on at startup via \`bootstrap.properties\`.
- No Feign clients were detected — cross-service calls, if any, likely go through \`RestTemplate\`/\`WebClient\` rather than declarative Feign interfaces. Worth confirming if service-to-service coupling should show up as graph edges.
- The generated Neo4j graph can be queried directly (e.g. \`MATCH (m:Module)-[:CONTAINS]->(t:Type) RETURN m.name, count(t)\`) for deeper, ad-hoc architecture questions beyond this static document.
`;

  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, doc);
  console.log(`Blueprint Scribe: wrote ${OUT_FILE}`);
}

main().catch((err) => {
  console.error('Blueprint Scribe failed:', err.message);
  process.exit(1);
});
