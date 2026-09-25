#!/usr/bin/env node
/**
 * Blast Radius Analyst — Impact Collector
 *
 * Computes how far one defect reaches, from five inputs:
 *   1. docs/agent_output/02-root-cause/root_cause_<id>.md  — the confirmed diagnosis (defines the workload)
 *   2. issue-register.xlsx via 00-issue-register            — the reported symptom and affected symbols
 *   3. docs/agent_output/01-architecture/architecture.md                — service topology and the full REST surface
 *   4. docs/agent_output/01-architecture/function-reference.md          — defect-site signatures and source
 *   5. the Neo4j knowledge graph           — live service, module and call connections
 *      (falls back to .github/.pipeline-context/artifacts.json when Neo4j is unreachable)
 *
 * Writes:
 *   .github/.pipeline-context/blast-radius/<id>.facts.json  — machine-readable, consumed by the renderer
 *   .github/.pipeline-context/blast-radius/<id>.facts.md    — briefing for the agent
 *
 * This script measures reach. It does not judge business impact — that is the agent's job.
 *
 * Usage:
 *   node scripts/collect-impact.js --all
 *   node scripts/collect-impact.js --issue ISSUE-001 [--depth 6] [--no-graph]
 */
const fs = require('fs');
const path = require('path');
const {
  SKILL_DIR, REPO_ROOT, ARTIFACTS_FILE, WORK_DIR, ROOT_CAUSE_DIR, ARCHITECTURE_MD, FUNCTION_REFERENCE_MD,
  rel, readIfPresent, listRootCauses, resolveRootCause, factsPathFor, briefingPathFor,
} = require('./lib/inputs');
const {
  buildModel, resolveSymbol, reachedFrom, moduleInventory, httpConsumers, platformTopology,
  signatureOf, methodIdOf,
} = require('./lib/code-model');
const { STATUS, STATUS_ICON } = require('./lib/diagrams');

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { depth: 6, graph: true };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--depth' || arg === '-d') args.depth = parseInt(argv[++i], 10);
    else if (arg === '--no-graph') args.graph = false;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  if (!Number.isInteger(args.depth) || args.depth < 1 || args.depth > 10) args.depth = 6;
  return args;
}

function usage() {
  console.log(`Blast Radius Analyst — Impact Collector

  node scripts/collect-impact.js --all
  node scripts/collect-impact.js --issue <ISSUE-ID> [options]

Options:
  --all, -a     Measure reach for every root cause report in docs/agent_output/02-root-cause/
  --issue, -i   A single issue id, e.g. ISSUE-001
  --depth, -d   Call-graph traversal depth (1-10, default 6)
  --no-graph    Skip Neo4j and use artifacts.json only
  --help, -h    Show this message

Exactly one of --all or --issue is required. Root cause reports and issues are read-only input.`);
}

// ---------------------------------------------------------------------------
// Neo4j — cross-checks the static model and adds live service/module connections
// ---------------------------------------------------------------------------

function loadEnv() {
  const local = path.join(SKILL_DIR, '.env');
  const shared = path.join(SKILL_DIR, '..', '01c-graph-forge', '.env');
  const file = fs.existsSync(local) ? local : shared;
  try {
    require('dotenv').config({ path: file });
    return fs.existsSync(file) ? rel(file) : null;
  } catch (err) {
    return null;
  }
}

async function queryGraph(defectIds, affectedModules, depth) {
  const envFile = loadEnv();
  const uri = process.env.NEO4J_URI;
  const user = process.env.NEO4J_USERNAME;
  const password = process.env.NEO4J_PASSWORD;
  if (!uri || !user || !password) {
    return { live: false, reason: `No Neo4j credentials found (looked for ${envFile || '.env'}). Reach computed from artifacts.json.` };
  }

  let neo4j;
  try {
    neo4j = require('neo4j-driver');
  } catch (err) {
    return { live: false, reason: 'neo4j-driver is not installed in this skill folder — run `npm install`. Reach computed from artifacts.json.' };
  }

  const driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  const database = process.env.NEO4J_DATABASE || 'neo4j';
  const d = Math.max(1, Math.min(10, depth));
  const result = {
    live: true,
    host: uri.replace(/\/\/.*@/, '//'),
    envFile,
    depth: d,
    endpointsReachingDefect: [],
    modulesReachingDefect: [],
    moduleEndpointCounts: [],
    typeFanIn: [],
    sharedDependencies: [],
    queries: [],
  };

  const run = async (session, name, cypher, params) => {
    result.queries.push({ name, cypher: cypher.trim() });
    const res = await session.run(cypher, params);
    return res.records.map((r) => r.toObject());
  };
  const toNumber = (v) => (v && typeof v === 'object' && 'low' in v ? v.low : v);

  let session;
  try {
    await driver.verifyConnectivity();
    session = driver.session({ database });

    result.endpointsReachingDefect = await run(session, 'endpoints whose handler reaches the defect',
      `MATCH (ty:Type)-[:EXPOSES]->(e:Endpoint)
       MATCH (ty)-[:HAS_METHOD]->(handler:Method)
       MATCH (handler)-[:CALLS*0..${d}]->(target:Method)
       WHERE target.id IN $defectIds
       RETURN DISTINCT e.id AS endpoint, ty.module AS module, ty.name AS controller`, { defectIds });

    result.modulesReachingDefect = (await run(session, 'modules that reach the defect in code',
      `MATCH (caller:Method)-[:CALLS*1..${d}]->(target:Method)
       WHERE target.id IN $defectIds
       MATCH (ct:Type)-[:HAS_METHOD]->(caller)
       RETURN DISTINCT ct.module AS module`, { defectIds })).map((r) => r.module).filter(Boolean);

    result.moduleEndpointCounts = (await run(session, 'endpoint count per affected module',
      `MATCH (m:Module)-[:CONTAINS]->(ty:Type)-[:EXPOSES]->(e:Endpoint)
       WHERE m.name IN $modules
       RETURN m.name AS module, collect(DISTINCT e.id) AS endpoints`, { modules: affectedModules }))
      .map((r) => ({ module: r.module, endpoints: r.endpoints }));

    result.typeFanIn = await run(session, 'types depending on the affected modules',
      `MATCH (other:Type)-[:USES]->(t:Type)
       WHERE t.module IN $modules AND other.module <> t.module
       RETURN DISTINCT other.module AS module, other.name AS type, t.name AS dependsOn`, { modules: affectedModules });

    result.sharedDependencies = (await run(session, 'infrastructure shared with other modules',
      `MATCH (m:Module)-[:DEPENDS_ON]->(dep:MavenDependency)<-[:DEPENDS_ON]-(other:Module)
       WHERE m.name IN $modules AND NOT other.name IN $modules
         AND dep.artifactId IN ['spring-boot-starter-data-mongodb','spring-cloud-starter-config','spring-cloud-starter-netflix-eureka-client']
       RETURN dep.artifactId AS dependency, collect(DISTINCT other.name) AS alsoUsedBy`, { modules: affectedModules }))
      .map((r) => ({ dependency: r.dependency, alsoUsedBy: r.alsoUsedBy }));

    const counts = await run(session, 'graph size', 'MATCH (n) RETURN labels(n)[0] AS label, count(*) AS count ORDER BY count DESC', {});
    result.nodeCounts = counts.map((r) => ({ label: r.label, count: toNumber(r.count) }));

    return result;
  } catch (err) {
    return { live: false, reason: `Neo4j unavailable (${err.message}). Reach computed from artifacts.json.`, envFile };
  } finally {
    if (session) await session.close();
    await driver.close();
  }
}

// ---------------------------------------------------------------------------
// Architecture document slices (input 3)
// ---------------------------------------------------------------------------

function architectureContext(text) {
  if (!text) return null;
  const lines = text.split(/\r?\n/);
  const context = { restSurface: [], serviceMap: null, observations: [] };

  const mermaid = /```mermaid\r?\n([\s\S]*?)```/.exec(text);
  if (mermaid) context.serviceMap = mermaid[0];

  // Rows of the "REST API Surface" table: | `GET` | `/path` | Handler | `module` |
  for (const line of lines) {
    const row = /^\|\s*`(GET|POST|PUT|DELETE|PATCH|ANY)`\s*\|\s*`([^`]+)`\s*\|\s*([^|]+)\|\s*`([^`]+)`\s*\|/.exec(line.trim());
    if (row) {
      context.restSurface.push({ method: row[1], path: row[2], handler: row[3].trim(), module: row[4] });
    }
  }

  const obsIndex = lines.findIndex((l) => /^##\s+Ideas & Observations/.test(l));
  if (obsIndex >= 0) {
    for (let i = obsIndex + 1; i < lines.length; i += 1) {
      if (/^##\s/.test(lines[i])) break;
      if (lines[i].trim().startsWith('- ')) context.observations.push(lines[i].trim());
    }
  }
  return context;
}

/** The defect site's entry in docs/agent_output/01-architecture/function-reference.md (input 4). */
function functionReferenceExcerpt(text, methodLabels) {
  if (!text) return [];
  const wanted = new Set(methodLabels);
  const excerpts = [];
  let current = null;
  for (const line of text.split(/\r?\n/)) {
    const heading = /^####\s+`([^`]+)`/.exec(line);
    if (heading) {
      if (current) excerpts.push(current);
      current = wanted.has(heading[1]) ? { heading: heading[1], lines: [] } : null;
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
// Reach computation
// ---------------------------------------------------------------------------

function computeReach(model, rootCause, args) {
  const issue = rootCause.issue;
  const symbols = (issue && issue.symbols) || [];

  // --- Defect sites ---------------------------------------------------------
  const defectSites = [];
  const defectIds = [];
  const unresolvedSymbols = [];
  for (const symbol of symbols) {
    const entries = resolveSymbol(model, symbol);
    if (!entries.length) {
      unresolvedSymbols.push(symbol);
      continue;
    }
    for (const entry of entries) {
      if (defectIds.includes(entry.id)) continue;
      defectIds.push(entry.id);
      defectSites.push({
        id: entry.id,
        label: `${entry.type.name}.${entry.method.name}()`,
        type: entry.type.name,
        module: entry.type.module,
        file: entry.type.file,
        startLine: entry.method.startLine,
        endLine: entry.method.endLine,
        signature: signatureOf(entry.method),
        endpoint: entry.endpoint,
      });
    }
  }

  const affectedModules = [...new Set([
    ...((issue && issue.services) || []),
    ...defectSites.map((d) => d.module),
  ])].filter(Boolean);

  // --- Everything in code that reaches a defect site ------------------------
  const upstreamIds = reachedFrom(model, defectIds, 'up', args.depth);
  const upstreamMethods = [...upstreamIds].map((id) => model.methodsById.get(id)).filter(Boolean);

  const endpointsReachingDefect = [...new Set(upstreamMethods.map((m) => m.endpoint).filter(Boolean))].sort();
  const jobsReachingDefect = upstreamMethods
    .filter((m) => (m.method.annotations || []).some((a) => a.name === 'Scheduled'))
    .map((m) => ({
      job: `${m.type.name}.${m.method.name}()`,
      module: m.type.module,
      schedule: ((m.method.annotations || []).find((a) => a.name === 'Scheduled')?.args || {}).cron || null,
    }));
  const modulesReachingDefect = [...new Set(upstreamMethods.map((m) => m.type.module))].sort();

  // --- Service inventory and cross-service calls ----------------------------
  const inventory = moduleInventory(model);
  const consumers = httpConsumers(model, affectedModules);
  const confirmedConsumers = consumers.filter((c) => c.confirmed);
  const platform = platformTopology(model);

  const consumerModules = [...new Set(confirmedConsumers.map((c) => c.module))].sort();
  const infraModules = [platform.discoveryServer, platform.configServer].filter(Boolean);

  // --- Status per service ---------------------------------------------------
  // Broken: contains a defect site. Degraded: calls a broken service over HTTP.
  // At risk: shares a data store or config source with a broken service.
  // Everything else is explicitly unaffected — saying so is half the value of the report.
  const serviceStatus = {};
  for (const module of platform.modules) {
    if (affectedModules.includes(module)) serviceStatus[module] = STATUS.BROKEN;
    else if (consumerModules.includes(module)) serviceStatus[module] = STATUS.DEGRADED;
    else serviceStatus[module] = STATUS.UNAFFECTED;
  }

  const sharesDataStore = platform.sharesDataStore.filter((m) => !affectedModules.includes(m) && serviceStatus[m] === STATUS.UNAFFECTED);
  const affectedUsesDataStore = affectedModules.some((m) => platform.sharesDataStore.includes(m));

  // --- Endpoint-level status ------------------------------------------------
  // A consumer service is only degraded on the endpoints that actually run the
  // outbound call. Marking every endpoint in the module would overstate the reach —
  // creating an employee does not touch the department service, looking up a salary does.
  const consumerEndpoints = new Set();
  for (const consumer of confirmedConsumers) {
    const startIds = (consumer.callingMethods || []).map((m) => m.id).filter((id) => model.methodsById.has(id));
    if (!startIds.length) continue;
    for (const id of reachedFrom(model, startIds, 'up', args.depth)) {
      const entry = model.methodsById.get(id);
      if (entry && entry.endpoint) consumerEndpoints.add(entry.endpoint);
    }
  }

  const allEndpoints = [];
  for (const [module, entry] of inventory) {
    for (const ep of entry.endpoints) {
      allEndpoints.push({
        endpoint: ep.endpoint,
        module,
        handler: ep.handler,
        reachesDefect: endpointsReachingDefect.includes(ep.endpoint),
        inAffectedModule: affectedModules.includes(module),
        consumerOfAffected: consumerEndpoints.has(ep.endpoint),
      });
    }
  }
  allEndpoints.sort((a, b) => a.module.localeCompare(b.module) || a.endpoint.localeCompare(b.endpoint));

  const endpointsInAffectedModules = allEndpoints.filter((e) => e.inAffectedModule).map((e) => e.endpoint);

  // --- HTTP edges for the service map ---------------------------------------
  const httpEdges = confirmedConsumers.map((c) => ({
    from: c.module,
    to: affectedModules[0] || 'affected service',
    label: `HTTP via ${c.type}`,
  }));

  return {
    defectSites,
    defectIds,
    unresolvedSymbols,
    affectedModules,
    endpointsReachingDefect,
    endpointsInAffectedModules,
    jobsReachingDefect,
    modulesReachingDefect,
    consumers,
    confirmedConsumers,
    consumerModules,
    infraModules,
    platform,
    serviceStatus,
    sharesDataStore,
    affectedUsesDataStore,
    allEndpoints,
    httpEdges,
    inventory: [...inventory.values()],
    scale: {
      servicesTotal: platform.modules.length,
      servicesBroken: Object.values(serviceStatus).filter((s) => s === STATUS.BROKEN).length,
      servicesDegraded: Object.values(serviceStatus).filter((s) => s === STATUS.DEGRADED).length,
      endpointsTotal: allEndpoints.length,
      endpointsReaching: endpointsReachingDefect.length,
      endpointsInAffectedModules: endpointsInAffectedModules.length,
      scheduledJobsAffected: jobsReachingDefect.length,
    },
  };
}

// ---------------------------------------------------------------------------
// Briefing
// ---------------------------------------------------------------------------

function renderBriefing(facts) {
  const { rootCause, reach, graph, sources } = facts;
  const out = [];

  // Sections are numbered as they are emitted, so an omitted optional section
  // (no scheduled jobs, no function reference) never leaves a gap in the sequence.
  let sectionNumber = 0;
  const section = (heading) => {
    sectionNumber += 1;
    out.push(`## ${sectionNumber}. ${heading}`);
  };

  out.push(`# Impact Facts — ${facts.id}`);
  out.push('');
  out.push(`_Collected ${facts.generatedAt}. Measured reach only — the business judgement is yours._`);
  out.push('');
  out.push(`**Issue:** ${facts.title}`);
  out.push(`**Root cause (from the report):** ${rootCause.statement || '_not parsed — read the report_'}`);
  if (rootCause.defectLocation) out.push(`**Defect location:** \`${rootCause.defectLocation}\``);
  out.push(`**Type / severity:** ${rootCause.severity || 'n/a'} · **Diagnosis confidence:** ${rootCause.confidence || 'n/a'}`);
  out.push('');

  section('Inputs');
  out.push('');
  out.push('| Input | Status |');
  out.push('|---|---|');
  out.push(`| Root cause report | \`${sources.rootCauseReport}\` |`);
  out.push(`| Issue report | ${sources.issueFile ? `\`${sources.issueFile}\`` : '_not found — reach derived from the root cause report only_'} |`);
  out.push(`| \`docs/agent_output/01-architecture/architecture.md\` | ${sources.architecture ? 'loaded' : 'MISSING — run Blueprint Scribe'} |`);
  out.push(`| \`docs/agent_output/01-architecture/function-reference.md\` | ${sources.functionReference ? 'loaded' : 'MISSING — run Blueprint Scribe'} |`);
  out.push(`| Knowledge graph | ${graph.live ? `live Neo4j (depth ${graph.depth})` : `not used — ${graph.reason}`} |`);
  out.push('');
  if (reach.unresolvedSymbols.length) {
    out.push(`> ⚠️ Unresolved symbols in the issue file: ${reach.unresolvedSymbols.map((s) => `\`${s}\``).join(', ')}. Reach for these is not measured.`);
    out.push('');
  }

  section('Scale');
  out.push('');
  out.push('| Measure | Value |');
  out.push('|---|---|');
  out.push(`| Services broken | ${reach.scale.servicesBroken} of ${reach.scale.servicesTotal} |`);
  out.push(`| Services degraded (call it over HTTP) | ${reach.scale.servicesDegraded} |`);
  out.push(`| Endpoints whose handler reaches the defect | ${reach.scale.endpointsReaching} of ${reach.scale.endpointsTotal} |`);
  out.push(`| Endpoints hosted by the broken service(s) | ${reach.scale.endpointsInAffectedModules} |`);
  out.push(`| Scheduled jobs on a reaching path | ${reach.scale.scheduledJobsAffected} |`);
  out.push('');
  out.push('_Note the two endpoint counts. If the service still starts, only the reaching endpoints fail. If the defect stops the service from starting or serving at all, every endpoint it hosts fails. Decide which applies from the root cause and set `scope` in the narrative accordingly._');
  out.push('');

  section('Defect sites');
  out.push('');
  for (const d of reach.defectSites) {
    out.push(`- \`${d.label}\` — \`${d.module}\`, \`${d.file}\` lines ${d.startLine}-${d.endLine}${d.endpoint ? `, exposed as \`${d.endpoint}\`` : ''}`);
  }
  if (!reach.defectSites.length) out.push('_No defect site resolved from the issue\'s affected_symbols._');
  out.push('');

  section('Service status');
  out.push('');
  out.push('| Service | Status | Why |');
  out.push('|---|---|---|');
  for (const [service, status] of Object.entries(reach.serviceStatus)) {
    let why = 'no code path and no HTTP call to the broken service';
    if (status === STATUS.BROKEN) why = 'contains a defect site';
    else if (status === STATUS.DEGRADED) {
      const c = reach.confirmedConsumers.filter((x) => x.module === service);
      why = `calls it over HTTP from ${c.map((x) => `\`${x.type}\``).join(', ')}`;
    }
    out.push(`| \`${service}\` | ${STATUS_ICON[status]} ${status} | ${why} |`);
  }
  out.push('');
  if (reach.sharesDataStore.length && reach.affectedUsesDataStore) {
    out.push(`_Otherwise-unaffected services sharing the same MongoDB data store: ${reach.sharesDataStore.map((s) => `\`${s}\``).join(', ')}. Only relevant if the defect exhausts a shared resource — for a pure logic error these stay unaffected._`);
    out.push('');
  }

  section('Endpoints');
  out.push('');
  out.push('| Endpoint | Service | Handler | Reaches defect | Hosted by broken service | Calls the broken service |');
  out.push('|---|---|---|---|---|---|');
  for (const e of reach.allEndpoints) {
    out.push(`| \`${e.endpoint}\` | \`${e.module}\` | \`${e.handler}\` | ${e.reachesDefect ? '**yes**' : 'no'} | ${e.inAffectedModule ? 'yes' : 'no'} | ${e.consumerOfAffected ? '**yes**' : 'no'} |`);
  }
  out.push('');
  out.push('_"Calls the broken service" marks endpoints whose own handler runs an outbound HTTP call to it. Other endpoints in the same consumer service are not affected._');
  out.push('');

  if (reach.jobsReachingDefect.length) {
    section('Scheduled jobs on a reaching path');
    out.push('');
    for (const j of reach.jobsReachingDefect) {
      out.push(`- \`${j.job}\` in \`${j.module}\`${j.schedule ? ` — cron \`${j.schedule}\`` : ''}`);
    }
    out.push('');
  }

  section('Cross-service calls');
  out.push('');
  if (!reach.consumers.length) {
    out.push('_No service in this workspace holds an HTTP client, so nothing calls the affected service over REST._');
  } else {
    out.push('| Service | Type | HTTP client | Confirmed target |');
    out.push('|---|---|---|---|');
    for (const c of reach.consumers) {
      out.push(`| \`${c.module}\` | \`${c.type}\` | ${c.httpClients.map((h) => `\`${h}\``).join(', ')} | ${c.confirmed ? `yes — ${c.hints.map((h) => `\`${h}\``).join(', ')}` : 'holds a client but no reference to the affected service'} |`);
    }
  }
  out.push('');

  section('Platform');
  out.push('');
  out.push(`- Discovery server: ${reach.platform.discoveryServer ? `\`${reach.platform.discoveryServer}\`` : '_none detected_'}`);
  out.push(`- Config server: ${reach.platform.configServer ? `\`${reach.platform.configServer}\`` : '_none detected_'}`);
  out.push(`- Services sharing the MongoDB data store: ${reach.platform.sharesDataStore.map((s) => `\`${s}\``).join(', ') || '_none_'}`);
  out.push('');

  if (facts.functionReference.length) {
    section('Defect site, from the function reference');
    out.push('');
    for (const e of facts.functionReference) {
      out.push(`<details><summary><code>${e.heading}</code></summary>`);
      out.push('');
      out.push(e.text);
      out.push('');
      out.push('</details>');
      out.push('');
    }
  }

  section('What to write next');
  out.push('');
  out.push(`Read the root cause report at \`${sources.rootCauseReport}\`, then write \`${rel(path.join(WORK_DIR, `${facts.id}.narrative.json`))}\` following \`templates/narrative.schema.json\`. Say who feels this in plain language, set \`scope\`, and keep every claim inside the reach measured above.`);
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function collectForRootCause(rootCause, args, context) {
  const { model, architectureText, functionReferenceText } = context;
  const reach = computeReach(model, rootCause, args);

  const graph = args.graph
    ? await queryGraph(reach.defectIds, reach.affectedModules, args.depth)
    : { live: false, reason: 'skipped via --no-graph' };

  const facts = {
    generatedAt: new Date().toISOString(),
    id: rootCause.id,
    title: rootCause.title,
    severity: rootCause.severity,
    rootCause: rootCause.rootCause,
    sources: {
      rootCauseReport: rootCause.relativeReportFile,
      issueFile: rootCause.issue ? rootCause.issue.relativeFile : null,
      architecture: Boolean(architectureText),
      functionReference: Boolean(functionReferenceText),
      artifacts: rel(ARTIFACTS_FILE),
      graphLive: Boolean(graph.live),
    },
    issue: rootCause.issue
      ? {
        title: rootCause.issue.title,
        type: rootCause.issue.type,
        severity: rootCause.issue.severity,
        services: rootCause.issue.services,
        entryPoints: rootCause.issue.entryPoints,
        symbols: rootCause.issue.symbols,
      }
      : null,
    reach,
    architecture: architectureContext(architectureText),
    functionReference: functionReferenceExcerpt(functionReferenceText, reach.defectSites.map((d) => d.label)),
    graph,
  };

  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(factsPathFor(rootCause.id), JSON.stringify(facts, null, 2));
  fs.writeFileSync(briefingPathFor(rootCause.id), renderBriefing(facts));

  return {
    id: rootCause.id,
    servicesBroken: reach.scale.servicesBroken,
    servicesDegraded: reach.scale.servicesDegraded,
    endpointsReaching: reach.scale.endpointsReaching,
    endpointsInModules: reach.scale.endpointsInAffectedModules,
    jobs: reach.scale.scheduledJobsAffected,
    unresolved: reach.unresolvedSymbols,
    graphNote: graph.live ? `live (depth ${graph.depth})` : graph.reason,
    briefing: rel(briefingPathFor(rootCause.id)),
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  // The workload is the set of root cause reports — one blast radius per root cause.
  let targets;
  if (args.all) {
    targets = listRootCauses();
    if (!targets.length) {
      throw new Error(`No root cause reports in ${rel(ROOT_CAUSE_DIR)}. Run the Root Cause Analyst agent first — blast radius analysis starts from a confirmed diagnosis.`);
    }
    console.log(`Blast Radius Analyst — ${targets.length} root cause report(s): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    targets = [resolveRootCause(args.issue)];
  }

  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts at ${rel(ARTIFACTS_FILE)}. Run the Code Cartographer scan first (.github/skills/01a-code-cartographer).`);
  }
  const artifacts = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));
  const context = {
    model: buildModel(artifacts),
    architectureText: readIfPresent(ARCHITECTURE_MD),
    functionReferenceText: readIfPresent(FUNCTION_REFERENCE_MD),
  };
  if (!context.architectureText) console.warn(`Warning: ${rel(ARCHITECTURE_MD)} is missing — run Blueprint Scribe for the service topology.`);
  if (!context.functionReferenceText) console.warn(`Warning: ${rel(FUNCTION_REFERENCE_MD)} is missing — run Blueprint Scribe for defect-site detail.`);

  const done = [];
  const failed = [];
  for (const target of targets) {
    try {
      const summary = await collectForRootCause(target, args, context);
      done.push(summary);
      console.log(`\n${summary.id} — reach measured`);
      console.log(`  services      : ${summary.servicesBroken} broken, ${summary.servicesDegraded} degraded`);
      console.log(`  endpoints     : ${summary.endpointsReaching} reach the defect, ${summary.endpointsInModules} hosted by the broken service(s)`);
      console.log(`  scheduled jobs: ${summary.jobs}`);
      console.log(`  graph         : ${summary.graphNote}`);
      console.log(`  briefing      : ${summary.briefing}`);
      if (summary.unresolved.length) console.log(`  UNRESOLVED    : ${summary.unresolved.join(', ')}`);
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: target.id, message: err.message });
      console.error(`\n${target.id} — FAILED: ${err.message}`);
    }
  }

  if (targets.length > 1) {
    console.log(`\nMeasured ${done.length}/${targets.length}: ${done.map((s) => s.id).join(', ') || 'none'}`);
    if (failed.length) console.log(`Failed: ${failed.map((f) => f.id).join(', ')}`);
  }
  console.log(`\nNext: read each briefing in ${rel(WORK_DIR)}, then write <issue_id>.narrative.json alongside it.`);

  if (failed.length) process.exitCode = 1;
}

main().catch((err) => {
  console.error('Impact collection failed:', err.message);
  process.exit(1);
});
