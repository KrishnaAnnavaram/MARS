#!/usr/bin/env node
/**
 * Blast Radius Analyst — Workload Listing
 *
 * Discovery step. Every root cause report in docs/agent_output/02-root-cause/ needs exactly one blast
 * radius report; this prints that list with each one's current pipeline state.
 *
 * Root cause reports and issues are read-only input — this skill never edits them.
 *
 * Usage:
 *   node scripts/list-root-causes.js            # table
 *   node scripts/list-root-causes.js --json     # machine-readable
 *   node scripts/list-root-causes.js --pending  # only those without a blast radius report
 */
const fs = require('fs');
const {
  ROOT_CAUSE_DIR, rel, listRootCauses, factsPathFor, narrativePathFor, reportPathFor,
} = require('./lib/inputs');

function stateOf(id) {
  const facts = fs.existsSync(factsPathFor(id));
  const narrative = fs.existsSync(narrativePathFor(id));
  const report = fs.existsSync(reportPathFor(id));
  let stage = 'not started';
  if (report) stage = 'report written';
  else if (narrative) stage = 'narrative written — render pending';
  else if (facts) stage = 'reach measured — narrative pending';
  return { facts, narrative, report, stage };
}

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const pendingOnly = argv.includes('--pending');

  let targets = listRootCauses().map((r) => ({
    id: r.id,
    title: r.title,
    severity: r.severity,
    rootCause: r.rootCause.statement,
    rootCauseReport: r.relativeReportFile,
    hasIssue: Boolean(r.issue),
    ...stateOf(r.id),
  }));
  if (pendingOnly) targets = targets.filter((t) => !t.report);

  if (asJson) {
    console.log(JSON.stringify(targets, null, 2));
    return;
  }

  if (!targets.length) {
    if (pendingOnly) {
      console.log('No pending work — every root cause already has a blast radius report.');
      return;
    }
    console.log(`No root cause reports in ${rel(ROOT_CAUSE_DIR)}.`);
    console.log('Blast radius analysis starts from a confirmed diagnosis — run the Root Cause Analyst agent first.');
    return;
  }

  const rows = targets.map((t) => [t.id, t.severity || '-', t.stage, t.hasIssue ? 'yes' : 'no', t.title]);
  const headers = ['ID', 'SEVERITY', 'PIPELINE STATE', 'ISSUE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Root cause reports: ${rel(ROOT_CAUSE_DIR)} (${targets.length}${pendingOnly ? ' pending' : ''})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log('\nNext: node scripts/collect-impact.js --all');
}

try {
  main();
} catch (err) {
  console.error('Listing failed:', err.message);
  process.exit(1);
}
