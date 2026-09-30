#!/usr/bin/env node
/**
 * Fix Strategist — Remediation Context Collector
 *
 * Gathers everything needed to propose a fix for one root cause, from:
 *   1. docs/agent_output/02-root-cause/root_cause_<id>.md   — the confirmed diagnosis (defines the workload)
 *   2. docs/agent_output/03-blast-radius/blast_radius_<id>.md — reach/priority, if it exists (optional)
 *   3. issue-register.xlsx via 00-issue-register             — affected files, entry points
 *   4. the current source of every affected file, read straight off disk
 *   5. .github/skills/04a-fix-strategist/catalog/cwe-patterns.json — the remediation pattern catalog
 *
 * It also regex-scans the issue and root-cause text for "CWE-<n>" mentions and looks each up
 * in the catalog, so the agent is handed a deterministic candidate list rather than having to
 * invent or recall a CWE from memory.
 *
 * Writes:
 *   .github/.pipeline-context/fix-strategy/<id>.context.json  — machine-readable, consumed by render-fix-plan.js
 *   .github/.pipeline-context/fix-strategy/<id>.context.md    — briefing for the agent
 *
 * This script never proposes a remediation. It only collects facts and catalog candidates.
 *
 * Usage:
 *   node scripts/collect-remediation-context.js --all
 *   node scripts/collect-remediation-context.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, WORK_DIR, ROOT_CAUSE_DIR, CATALOG_FILE,
  rel, readIfPresent, listRootCauseReports, resolveRootCauseReport, readBlastRadiusReport,
  loadCatalog, detectCweMentions, contextJsonPathFor, contextBriefingPathFor,
} = require('./lib/plans');

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Fix Strategist — Remediation Context Collector

  node scripts/collect-remediation-context.js --all
  node scripts/collect-remediation-context.js --issue <ISSUE-ID>

Options:
  --all, -a     Collect context for every root cause report in docs/agent_output/02-root-cause/
  --issue, -i   A single issue id, e.g. ISSUE-001
  --help, -h    Show this message

Exactly one of --all or --issue is required. Root cause reports, blast radius reports and
issues are all read-only input.`);
}

// ---------------------------------------------------------------------------
// Source snapshot of the affected files
// ---------------------------------------------------------------------------

function snapshotFile(repoRelativePath) {
  const abs = path.join(REPO_ROOT, repoRelativePath);
  if (!fs.existsSync(abs)) {
    return { file: repoRelativePath, found: false, source: null, lines: 0 };
  }
  const source = fs.readFileSync(abs, 'utf8');
  return { file: repoRelativePath, found: true, source, lines: source.split(/\r?\n/).length };
}

// ---------------------------------------------------------------------------
// CWE candidates
// ---------------------------------------------------------------------------

function cweCandidates(text, catalog) {
  return detectCweMentions(text).map((cwe) => ({
    cwe,
    inCatalog: Boolean(catalog[cwe]),
    entry: catalog[cwe] || null,
  }));
}

// ---------------------------------------------------------------------------
// Briefing (markdown)
// ---------------------------------------------------------------------------

function renderBriefing(context) {
  const {
    id, title, rootCause, blastRadius, issue, files, cwe, sources,
  } = context;
  const out = [];

  out.push(`# Remediation Context — ${id}`);
  out.push('');
  out.push(`_Collected ${context.generatedAt} by the Fix Strategist context collector. Facts and catalog candidates only — no recommendation._`);
  out.push('');
  out.push(`**Issue:** ${title}`);
  out.push(`**Root cause report:** \`${sources.rootCauseReport}\``);
  out.push(`**Blast radius report:** ${sources.blastRadiusReport ? `\`${sources.blastRadiusReport}\`` : '_none — not analysed yet, proceed without it_'}`);
  out.push('');

  out.push('## 1. Diagnosis (from the root cause report)');
  out.push('');
  out.push(`- **Statement:** ${rootCause.statement || '_not parsed — open the report directly_'}`);
  if (rootCause.defectLocation) out.push(`- **Defect location:** \`${rootCause.defectLocation}\``);
  if (rootCause.severity) out.push(`- **Severity:** ${rootCause.severity}`);
  if (rootCause.confidence) out.push(`- **Diagnosis confidence:** ${rootCause.confidence}`);
  out.push('');
  if (rootCause.recommendedFix) {
    out.push('**The root cause report\'s own fix suggestion** (context only — your approach should reconcile with this or explain why it differs; it is not authoritative and was not written against the CWE catalog):');
    out.push('');
    out.push('> ' + rootCause.recommendedFix.replace(/\n/g, '\n> '));
    out.push('');
  }

  if (blastRadius) {
    out.push('## 2. Reach (from the blast radius report)');
    out.push('');
    out.push(`- **Headline:** ${blastRadius.headline || 'n/a'}`);
    out.push(`- **Priority:** ${blastRadius.priority || 'n/a'}`);
    out.push(`- **How far it spreads:** ${blastRadius.scope || 'n/a'}`);
    out.push('');
  }

  out.push(`## ${blastRadius ? '3' : '2'}. CWE candidates detected`);
  out.push('');
  if (!cwe.length) {
    out.push('_No `CWE-<n>` mention found in the issue or the root cause report. State the closest applicable CWE from the catalog yourself, or record a catalog gap if none fits._');
  } else {
    out.push('| CWE | In catalog | Title |');
    out.push('|---|---|---|');
    for (const c of cwe) {
      out.push(`| \`${c.cwe}\` | ${c.inCatalog ? 'yes' : '**no — catalog gap**'} | ${c.entry ? c.entry.title : '_not in catalog — see catalog/README.md to add it_'} |`);
    }
  }
  out.push('');
  out.push(`Full catalog: \`${rel(CATALOG_FILE)}\` (${Object.keys(loadCatalog()).length} entries). Read the matched entry in full before writing the strategy — do not rely on the title alone.`);
  out.push('');

  out.push(`## ${blastRadius ? '4' : '3'}. Affected files (current source)`);
  out.push('');
  for (const f of files) {
    if (!f.found) {
      out.push(`### \`${f.file}\` — NOT FOUND on disk`);
      out.push('');
      out.push('The issue\'s `affected_files` entry does not resolve to a real path. Report this rather than guessing.');
      out.push('');
      continue;
    }
    out.push(`### \`${f.file}\` (${f.lines} lines)`);
    out.push('');
    out.push('```java');
    out.push(f.source);
    out.push('```');
    out.push('');
  }

  out.push(`## ${blastRadius ? '5' : '4'}. Reported symptom (verbatim from the issue)`);
  out.push('');
  out.push(issue.body.trim());
  out.push('');

  out.push(`## ${blastRadius ? '6' : '5'}. What to write next`);
  out.push('');
  out.push(`Read this briefing, read the catalog entry in full, then write `
    + `\`${rel(path.join(WORK_DIR, `${id}.strategy.json`))}\` following `
    + `\`templates/strategy.schema.json\`. Cite the catalog entry you used in \`catalog_reference\`; `
    + `if none of the detected CWEs are in the catalog, set \`catalog_reference.title\` to null and `
    + `explain the gap rather than inventing a pattern.`);
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function collectForRootCause(rootCause, catalog) {
  const issue = rootCause.issue;
  if (!issue) {
    throw new Error(`Root cause report ${rootCause.relativeReportFile} has no matching issue in docs/agent_output/00-issues/ (looked for issue_id "${rootCause.id}"). Cannot resolve affected_files without it.`);
  }

  const blastRadius = readBlastRadiusReport(rootCause.id);
  const files = issue.files.map(snapshotFile);
  const scanText = [issue.body, readIfPresent(rootCause.reportFile) || ''].join('\n');
  const cwe = cweCandidates(scanText, catalog);

  const context = {
    generatedAt: new Date().toISOString(),
    id: rootCause.id,
    title: rootCause.title,
    severity: rootCause.severity,
    rootCause: rootCause.rootCause,
    blastRadius,
    issue: {
      title: issue.title,
      type: issue.type,
      severity: issue.severity,
      services: issue.services,
      entryPoints: issue.entryPoints,
      body: issue.body,
    },
    files,
    cwe,
    sources: {
      rootCauseReport: rootCause.relativeReportFile,
      blastRadiusReport: blastRadius ? blastRadius.relativeFile : null,
      issueFile: issue.relativeFile,
    },
  };

  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(contextJsonPathFor(rootCause.id), JSON.stringify(context, null, 2));
  fs.writeFileSync(contextBriefingPathFor(rootCause.id), renderBriefing(context));

  return {
    id: rootCause.id,
    filesFound: files.filter((f) => f.found).length,
    filesMissing: files.filter((f) => !f.found).map((f) => f.file),
    cweCandidates: cwe.map((c) => c.cwe),
    catalogGaps: cwe.filter((c) => !c.inCatalog).map((c) => c.cwe),
    blastRadius: Boolean(blastRadius),
    briefing: rel(contextBriefingPathFor(rootCause.id)),
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listRootCauseReports();
    if (!targets.length) {
      throw new Error(`No root cause reports in ${rel(ROOT_CAUSE_DIR)}. Run the Root Cause Analyst agent first.`);
    }
    console.log(`Fix Strategist — ${targets.length} root cause report(s): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    targets = [resolveRootCauseReport(args.issue)];
  }

  const catalog = loadCatalog();
  if (!Object.keys(catalog).length) {
    console.warn(`Warning: CWE catalog at ${rel(CATALOG_FILE)} is empty or missing.`);
  }

  const done = [];
  const failed = [];
  for (const target of targets) {
    try {
      const summary = collectForRootCause(target, catalog);
      done.push(summary);
      console.log(`\n${summary.id} — context collected`);
      console.log(`  files          : ${summary.filesFound} found${summary.filesMissing.length ? `, MISSING: ${summary.filesMissing.join(', ')}` : ''}`);
      console.log(`  CWE candidates : ${summary.cweCandidates.join(', ') || 'none detected'}${summary.catalogGaps.length ? ` (catalog gap: ${summary.catalogGaps.join(', ')})` : ''}`);
      console.log(`  blast radius   : ${summary.blastRadius ? 'used' : 'not available'}`);
      console.log(`  briefing       : ${summary.briefing}`);
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: target.id, message: err.message });
      console.error(`\n${target.id} — FAILED: ${err.message}`);
    }
  }

  if (targets.length > 1) {
    console.log(`\nCollected ${done.length}/${targets.length}: ${done.map((s) => s.id).join(', ') || 'none'}`);
    if (failed.length) console.log(`Failed: ${failed.map((f) => f.id).join(', ')}`);
  }
  console.log(`\nNext: read each briefing in ${rel(WORK_DIR)}, then write <issue_id>.strategy.json alongside it.`);

  if (failed.length) process.exitCode = 1;
}

try {
  main();
} catch (err) {
  console.error('Context collection failed:', err.message);
  process.exit(1);
}
