#!/usr/bin/env node
/**
 * Root Cause Analyst — Issue Register Listing
 *
 * Discovery step. Prints every issue found in docs/agent_output/00-issues/ with its current pipeline
 * state, so the agent knows exactly what to analyse and what is already done.
 *
 * The register is read-only input supplied by the reporting party — this skill never
 * creates, edits or removes an issue file.
 *
 * Usage:
 *   node scripts/list-issues.js            # table for humans
 *   node scripts/list-issues.js --json     # machine-readable
 *   node scripts/list-issues.js --pending  # only issues without a finished report
 */
const fs = require('fs');
const {
  ISSUE_REGISTER_FILE, rel, listIssues, evidencePathFor, analysisPathFor, reportPathFor,
} = require('./lib/issues');

function stateOf(issue) {
  const evidence = fs.existsSync(evidencePathFor(issue.id));
  const analysis = fs.existsSync(analysisPathFor(issue.id));
  const report = fs.existsSync(reportPathFor(issue.id));
  let stage = 'not started';
  if (report) stage = 'report written';
  else if (analysis) stage = 'analysis written — render pending';
  else if (evidence) stage = 'evidence collected — analysis pending';
  return { evidence, analysis, report, stage };
}

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const pendingOnly = argv.includes('--pending');

  let issues = listIssues().map((i) => ({ ...i, ...stateOf(i) }));
  if (pendingOnly) issues = issues.filter((i) => !i.report);

  if (asJson) {
    console.log(JSON.stringify(issues, null, 2));
    return;
  }

  if (!issues.length) {
    console.log(pendingOnly
      ? 'No pending issues — every issue in the register already has a report.'
      : `No issues found in ${rel(ISSUE_REGISTER_FILE)}. Every row needs an "issue_id" to be picked up.`);
    return;
  }

  const rows = issues.map((i) => [i.id, i.severity || '-', i.status || '-', i.stage, i.title]);
  const headers = ['ID', 'SEVERITY', 'STATUS', 'PIPELINE STATE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Issue register: ${rel(ISSUE_REGISTER_FILE)} (${issues.length} issue${issues.length === 1 ? '' : 's'}${pendingOnly ? ' pending' : ''})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log(`\nNext: node scripts/collect-evidence.js --all`);
}

try {
  main();
} catch (err) {
  console.error('Listing failed:', err.message);
  process.exit(1);
}
