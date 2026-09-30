#!/usr/bin/env node
/**
 * Blast Radius Analyst — Report Renderer
 *
 * Combines:
 *   .github/.pipeline-context/blast-radius/<id>.facts.json     — measured reach, from collect-impact.js
 *   .github/.pipeline-context/blast-radius/<id>.narrative.json — plain-language judgement, from the agent
 *
 * Output: docs/agent_output/03-blast-radius/blast_radius_<issue_id>.md
 *
 * The report is written for someone who has not read the code: diagrams first, short
 * tables second, jargon last. Every number in it comes from the facts file.
 *
 * Usage:
 *   node scripts/render-blast-radius.js --all
 *   node scripts/render-blast-radius.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, OUT_DIR, rel, listRootCauses, factsPathFor, narrativePathFor, reportPathFor,
} = require('./lib/inputs');
const {
  STATUS, STATUS_ICON, spreadDiagram, serviceMapDiagram, requestPathDiagram,
} = require('./lib/diagrams');

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--narrative' || arg === '-n') args.narrative = argv[++i];
    else if (arg === '--out' || arg === '-o') args.out = argv[++i];
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Blast Radius Analyst — Report Renderer

  node scripts/render-blast-radius.js --all
  node scripts/render-blast-radius.js --issue <ISSUE-ID> [options]

Options:
  --all, -a         Render every root cause that has facts + narrative
  --issue, -i       A single issue id, e.g. ISSUE-001
  --narrative, -n   Narrative JSON path (default .github/.pipeline-context/blast-radius/<id>.narrative.json)
  --out, -o         Output path (default docs/agent_output/03-blast-radius/blast_radius_<id>.md)
  --help, -h        Show this message`);
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

const SCOPES = ['endpoint', 'service', 'multi-service'];

const REQUIRED = [
  ['headline', (n) => typeof n.headline === 'string' && n.headline.trim()],
  ['what_is_broken', (n) => typeof n.what_is_broken === 'string' && n.what_is_broken.trim()],
  [`scope (one of ${SCOPES.join(', ')})`, (n) => SCOPES.includes(n.scope)],
  ['ripple.same_service', (n) => n.ripple && n.ripple.same_service],
  ['ripple.dependent_services', (n) => n.ripple && n.ripple.dependent_services],
  ['user_impact (non-empty array)', (n) => Array.isArray(n.user_impact) && n.user_impact.length > 0],
  ['priority', (n) => typeof n.priority === 'string' && n.priority.trim()],
];

function validate(narrative) {
  const missing = REQUIRED.filter(([, check]) => !check(narrative)).map(([key]) => key);
  if (missing.length) {
    throw new Error(`Narrative JSON is missing required field(s):\n  - ${missing.join('\n  - ')}\nSee templates/narrative.schema.json for the expected shape.`);
  }
}

// ---------------------------------------------------------------------------
// Status model — scope decides how wide the failure really is
// ---------------------------------------------------------------------------

/**
 * The collector measures two endpoint sets: those whose handler reaches the defect, and
 * every endpoint the broken service hosts. Which one is "down" depends on whether the
 * service still runs — a judgement the agent records as `scope`.
 */
function endpointStatus(endpoint, facts, scope) {
  if (scope === 'endpoint') {
    if (endpoint.reachesDefect) return STATUS.BROKEN;
    if (endpoint.consumerOfAffected) return STATUS.DEGRADED;
    return STATUS.UNAFFECTED;
  }
  // 'service' and 'multi-service': the whole broken service is unavailable.
  if (endpoint.inAffectedModule) return STATUS.BROKEN;
  if (endpoint.consumerOfAffected) return STATUS.DEGRADED;
  return STATUS.UNAFFECTED;
}

function serviceStatusFor(facts, scope) {
  const status = { ...facts.reach.serviceStatus };
  if (scope === 'multi-service') {
    // Consumers are treated as degraded already; nothing further to escalate here,
    // but shared-infrastructure services move from unaffected to at risk.
    for (const service of facts.reach.sharesDataStore) {
      if (status[service] === STATUS.UNAFFECTED) status[service] = STATUS.AT_RISK;
    }
  }
  return status;
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

// Depth-independent: derived from where the report is actually written.
const UP_TO_ROOT = path.relative(OUT_DIR, REPO_ROOT).replace(/\\/g, '/');

function linkFromOutput(repoRelativePath, label) {
  return `[${label || repoRelativePath}](${`${UP_TO_ROOT}/${repoRelativePath}`.replace(/\\/g, '/')})`;
}

function bullets(items, empty = '_none recorded_') {
  if (!items || !items.length) return [empty];
  return items.map((i) => `- ${i}`);
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

function render(facts, narrative) {
  const { reach, rootCause, sources } = facts;
  const scope = narrative.scope;
  const services = serviceStatusFor(facts, scope);
  const endpoints = facts.reach.allEndpoints.map((e) => ({ ...e, status: endpointStatus(e, facts, scope) }));

  const broken = endpoints.filter((e) => e.status === STATUS.BROKEN);
  const degraded = endpoints.filter((e) => e.status === STATUS.DEGRADED);
  const brokenServices = Object.entries(services).filter(([, s]) => s === STATUS.BROKEN).map(([m]) => m);
  const degradedServices = Object.entries(services).filter(([, s]) => s === STATUS.DEGRADED).map(([m]) => m);
  const healthyServices = Object.entries(services).filter(([, s]) => s === STATUS.UNAFFECTED).map(([m]) => m);

  const out = [];

  // --- Header ---------------------------------------------------------------
  out.push(`# Blast Radius — ${facts.id}`);
  out.push('');
  out.push(`## ${facts.title}`);
  out.push('');
  out.push(`> ${narrative.headline.trim()}`);
  out.push('');
  out.push(`_Generated by the Blast Radius Analyst on ${new Date().toISOString().slice(0, 10)}. Reach measured ${facts.generatedAt}._`);
  out.push('');

  // --- At a glance ----------------------------------------------------------
  out.push('## At a glance');
  out.push('');
  out.push('| | |');
  out.push('|---|---|');
  out.push(`| **Priority** | ${narrative.priority} |`);
  out.push(`| **Severity** | ${facts.severity || 'n/a'} |`);
  out.push(`| **How far it spreads** | ${scope === 'endpoint' ? 'One endpoint' : scope === 'service' ? 'One whole service' : 'Multiple services'} |`);
  out.push(`| **Services broken** | ${brokenServices.length ? brokenServices.map((s) => `\`${s}\``).join(', ') : 'none'} |`);
  out.push(`| **Services degraded** | ${degradedServices.length ? degradedServices.map((s) => `\`${s}\``).join(', ') : 'none'} |`);
  out.push(`| **Endpoints down** | ${broken.length} of ${endpoints.length} |`);
  out.push(`| **Scheduled jobs hit** | ${reach.jobsReachingDefect.length} |`);
  out.push(`| **Confidence** | ${narrative.confidence || 'not stated'} |`);
  out.push('');
  out.push(`Root cause: ${linkFromOutput(sources.rootCauseReport, sources.rootCauseReport)}${sources.issueFile ? ` · Issue: ${linkFromOutput(sources.issueFile, sources.issueFile)}` : ''}`);
  out.push('');

  // --- 1. What is broken ----------------------------------------------------
  out.push('## 1. What is broken');
  out.push('');
  out.push(narrative.what_is_broken.trim());
  out.push('');
  if (rootCause.statement) {
    out.push(`**The defect itself:** ${rootCause.statement}`);
    out.push('');
  }
  if (rootCause.defectLocation) {
    out.push(`**Where:** \`${rootCause.defectLocation}\``);
    out.push('');
  }

  // --- 2. How far it spreads ------------------------------------------------
  out.push('## 2. How far it spreads');
  out.push('');
  out.push(spreadDiagram({
    defectSites: reach.defectSites,
    affectedServices: brokenServices,
    consumerServices: degradedServices,
    platformServices: reach.infraModules.filter((m) => services[m] === STATUS.UNAFFECTED),
    userFacing: broken.slice(0, 6).map((e) => e.endpoint),
  }));
  out.push('');
  out.push('| Ring | What it means |');
  out.push('|---|---|');
  out.push(`| 🔴 Where it breaks | ${reach.defectSites.map((d) => `\`${d.label}\``).join(', ') || 'not resolved'} |`);
  out.push(`| 🔴 Service that fails | ${narrative.ripple.same_service.trim().replace(/\|/g, '\\|')} |`);
  out.push(`| ${degradedServices.length ? '🟠' : '🟢'} Services that call it | ${narrative.ripple.dependent_services.trim().replace(/\|/g, '\\|')} |`);
  if (narrative.ripple.wider_platform) {
    // Amber only when something really is at risk; otherwise this row reads as a warning
    // while its own text says the platform is untouched.
    const atRisk = Object.values(services).some((s) => s === STATUS.AT_RISK);
    out.push(`| ${atRisk ? '🟡' : '🟢'} Wider platform | ${narrative.ripple.wider_platform.trim().replace(/\|/g, '\\|')} |`);
  }
  out.push('');

  // --- 3. Services ----------------------------------------------------------
  out.push('## 3. Which services are affected');
  out.push('');
  out.push(serviceMapDiagram({
    serviceStatus: services,
    httpEdges: reach.httpEdges.filter((e) => services[e.from] && services[e.to]),
    platform: reach.platform,
  }));
  out.push('');
  out.push('| Service | Status | What happens to it |');
  out.push('|---|---|---|');
  for (const [service, status] of Object.entries(services)) {
    let explanation = 'Not on any path to the defect. Keeps working normally.';
    if (status === STATUS.BROKEN) explanation = 'Contains the defect. This is where the failure starts.';
    else if (status === STATUS.DEGRADED) {
      const callers = reach.confirmedConsumers.filter((c) => c.module === service).map((c) => `\`${c.type}\``);
      explanation = `Calls the broken service over HTTP${callers.length ? ` from ${callers.join(', ')}` : ''}. Its own code is fine, but the data it needs does not arrive.`;
    } else if (status === STATUS.AT_RISK) {
      explanation = 'Shares infrastructure with the broken service, so heavy load or exhaustion there can spill over.';
    }
    out.push(`| \`${service}\` | ${STATUS_ICON[status]} ${status} | ${explanation} |`);
  }
  out.push('');

  // --- 4. Endpoints ---------------------------------------------------------
  out.push('## 4. Which endpoints are affected');
  out.push('');
  if (!broken.length && !degraded.length) {
    out.push('_No endpoint is affected — the defect is not reachable from the REST surface._');
    out.push('');
  } else {
    out.push('| Endpoint | Service | Status | What a caller sees |');
    out.push('|---|---|---|---|');
    for (const e of endpoints) {
      if (e.status === STATUS.UNAFFECTED) continue;
      const sees = e.status === STATUS.BROKEN
        ? (e.reachesDefect ? 'Request fails — this path runs the defect' : 'Request fails — the service hosting it is down')
        : 'Responds, but with missing or stale data from the broken service';
      out.push(`| \`${e.endpoint}\` | \`${e.module}\` | ${STATUS_ICON[e.status]} ${e.status} | ${sees} |`);
    }
    out.push('');
    const unaffected = endpoints.filter((e) => e.status === STATUS.UNAFFECTED);
    if (unaffected.length) {
      out.push(`<details><summary>${unaffected.length} endpoint(s) not affected</summary>`);
      out.push('');
      out.push('| Endpoint | Service |');
      out.push('|---|---|');
      unaffected.forEach((e) => out.push(`| \`${e.endpoint}\` | \`${e.module}\` |`));
      out.push('');
      out.push('</details>');
      out.push('');
    }
  }

  if (reach.jobsReachingDefect.length) {
    out.push('**Scheduled jobs on the failing path**');
    out.push('');
    out.push('| Job | Service | Runs |');
    out.push('|---|---|---|');
    for (const j of reach.jobsReachingDefect) {
      out.push(`| \`${j.job}\` | \`${j.module}\` | ${j.schedule ? `cron \`${j.schedule}\`` : 'on a schedule'} |`);
    }
    out.push('');
  }

  // --- 5. Request path ------------------------------------------------------
  const primaryEndpoint = broken.find((e) => e.reachesDefect) || broken[0];
  if (primaryEndpoint && reach.defectSites.length) {
    out.push('## 5. What happens on a single request');
    out.push('');
    out.push(requestPathDiagram({
      entryPoint: primaryEndpoint.endpoint,
      chain: [primaryEndpoint.handler, ...reach.defectSites.slice(0, 2).map((d) => d.label)]
        .filter((v, i, arr) => arr.indexOf(v) === i),
      failureNote: narrative.failure_note || 'the defect runs here',
    }));
    out.push('');
  }

  // --- 6. Who feels it ------------------------------------------------------
  out.push('## 6. Who feels it');
  out.push('');
  out.push('| Who | What they experience | Status |');
  out.push('|---|---|---|');
  for (const u of narrative.user_impact) {
    out.push(`| ${u.who} | ${(u.what_they_see || '').replace(/\|/g, '\\|')} | ${u.status || 'Affected'} |`);
  }
  out.push('');

  // --- 7. Not affected ------------------------------------------------------
  out.push('## 7. What is NOT affected');
  out.push('');
  out.push('Scoping the damage matters as much as describing it.');
  out.push('');
  if (healthyServices.length) {
    out.push(`- Services working normally: ${healthyServices.map((s) => `\`${s}\``).join(', ')}`);
  }
  bullets(narrative.not_affected, '- _Nothing further recorded._').forEach((b) => out.push(b.startsWith('- ') ? b : `- ${b}`));
  out.push('');

  // --- 8. Containment -------------------------------------------------------
  out.push('## 8. Containment and priority');
  out.push('');
  out.push(`**Priority:** ${narrative.priority}`);
  out.push('');
  if (narrative.if_unfixed) {
    out.push(`**If it is not fixed:** ${narrative.if_unfixed.trim()}`);
    out.push('');
  }
  out.push('**Containment steps**');
  out.push('');
  bullets(narrative.containment, '_None recorded._').forEach((b) => out.push(b));
  out.push('');
  out.push(`The fix itself is in the root cause report: ${linkFromOutput(sources.rootCauseReport, 'Recommended Fix')}.`);
  out.push('');

  if (narrative.open_questions && narrative.open_questions.length) {
    out.push('## 9. Open questions');
    out.push('');
    bullets(narrative.open_questions).forEach((b) => out.push(b));
    out.push('');
  }

  // --- Appendix -------------------------------------------------------------
  out.push('## Appendix — how this was measured');
  out.push('');
  out.push('| Input | Detail |');
  out.push('|---|---|');
  out.push(`| Root cause report | ${linkFromOutput(sources.rootCauseReport, sources.rootCauseReport)} |`);
  out.push(`| Issue report | ${sources.issueFile ? linkFromOutput(sources.issueFile, sources.issueFile) : 'not available'} |`);
  out.push(`| Architecture document | ${sources.architecture ? linkFromOutput('docs/agent_output/01-architecture/architecture.md', 'docs/agent_output/01-architecture/architecture.md') : 'not available'} |`);
  out.push(`| Function reference | ${sources.functionReference ? linkFromOutput('docs/agent_output/01-architecture/function-reference.md', 'docs/agent_output/01-architecture/function-reference.md') : 'not available'} |`);
  out.push(`| Code scan | \`${sources.artifacts}\` |`);
  out.push(`| Knowledge graph | ${facts.graph.live ? `Neo4j, traversal depth ${facts.graph.depth}` : `not used — ${facts.graph.reason}`} |`);
  out.push('');
  out.push('**Rules used to assign status**');
  out.push('');
  out.push('- 🔴 **Broken** — the service contains the defect, or an endpoint whose handler reaches it');
  out.push('- 🟠 **Degraded** — the service calls a broken service over HTTP; its own code is sound');
  out.push('- 🟡 **At risk** — shares infrastructure with a broken service');
  out.push('- 🟢 **Unaffected** — no code path and no HTTP call to anything broken');
  out.push('');
  out.push(`Scope for this issue is **${scope}**, so ${scope === 'endpoint' ? 'only endpoints whose handler reaches the defect are marked down' : 'every endpoint hosted by the broken service is marked down'}.`);
  out.push('');

  if (facts.graph.live && (facts.graph.queries || []).length) {
    const unique = [];
    for (const q of facts.graph.queries) if (!unique.some((u) => u.cypher === q.cypher)) unique.push(q);
    out.push('<details><summary>Graph queries executed</summary>');
    out.push('');
    for (const q of unique) {
      out.push(`**${q.name}**`);
      out.push('');
      out.push('```cypher');
      out.push(q.cypher);
      out.push('```');
      out.push('');
    }
    out.push('</details>');
    out.push('');
  }

  out.push('---');
  out.push('');
  out.push('Sections 3, 4, 5 and 7 (service lists) are measured from the code scan and the knowledge graph. Sections 1, 2 (ring descriptions), 6 and 8 are the analyst\'s judgement, scoped to that measurement.');
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function renderOne({ id, narrativePath, outPath }) {
  const factsPath = factsPathFor(id);
  if (!fs.existsSync(factsPath)) {
    throw new Error(`No facts file at ${rel(factsPath)}. Run collect-impact.js --issue ${id} first.`);
  }
  const facts = JSON.parse(fs.readFileSync(factsPath, 'utf8'));

  const narrativeFile = narrativePath || narrativePathFor(id);
  if (!fs.existsSync(narrativeFile)) {
    throw new Error(`No narrative file at ${rel(narrativeFile)}.\nRead ${rel(factsPath).replace('.facts.json', '.facts.md')} and write it following templates/narrative.schema.json.`);
  }
  let narrative;
  try {
    narrative = JSON.parse(fs.readFileSync(narrativeFile, 'utf8'));
  } catch (err) {
    throw new Error(`Narrative file ${rel(narrativeFile)} is not valid JSON: ${err.message}`);
  }
  validate(narrative);
  if (narrative.issue_id && narrative.issue_id !== facts.id) {
    throw new Error(`Narrative is for ${narrative.issue_id} but the facts file is for ${facts.id}.`);
  }

  const target = outPath || reportPathFor(facts.id);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, render(facts, narrative));

  return { id: facts.id, scope: narrative.scope, priority: narrative.priority, report: rel(target) };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  if (args.all) {
    const targets = listRootCauses();
    if (!targets.length) throw new Error('No root cause reports in docs/agent_output/02-root-cause/. Nothing to render.');

    const written = [];
    const skipped = [];
    for (const target of targets) {
      try {
        const result = renderOne({ id: target.id });
        written.push(result);
        console.log(`${result.id.padEnd(10)} scope ${result.scope.padEnd(14)} ${result.priority.padEnd(28)} -> ${result.report}`);
      } catch (err) {
        skipped.push(target.id);
        console.error(`${target.id.padEnd(10)} SKIPPED  ${err.message.split('\n')[0]}`);
      }
    }
    console.log(`\nWrote ${written.length}/${targets.length} report(s) to ${rel(OUT_DIR)}.`);
    if (skipped.length) {
      console.log(`Still pending: ${skipped.join(', ')} — measure reach and write the narrative JSON for each, then re-run.`);
      process.exitCode = 1;
    }
    return;
  }

  if (!args.issue) throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-001) or --all.');
  const result = renderOne({
    id: args.issue,
    narrativePath: args.narrative ? path.resolve(REPO_ROOT, args.narrative) : null,
    outPath: args.out ? path.resolve(REPO_ROOT, args.out) : null,
  });
  console.log(`Blast Radius Analyst — wrote ${result.report}`);
  console.log(`  scope    : ${result.scope}`);
  console.log(`  priority : ${result.priority}`);
}

try {
  main();
} catch (err) {
  console.error('Rendering failed:', err.message);
  process.exit(1);
}
