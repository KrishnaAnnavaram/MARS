#!/usr/bin/env node
/**
 * Fix Strategist — Remediation Workload Listing
 *
 * Discovery step. Prints every root cause report in docs/agent_output/02-root-cause/ — the workload, one
 * fix plan per root cause — with its current pipeline state and, once a plan exists, its
 * approval status.
 *
 * Root cause and blast radius reports are read-only input; this script never writes to
 * docs/agent_output/02-root-cause/ or docs/agent_output/03-blast-radius/.
 *
 * Usage:
 *   node scripts/list-remediation-workload.js            # table for humans
 *   node scripts/list-remediation-workload.js --json      # machine-readable
 *   node scripts/list-remediation-workload.js --pending   # only plans not yet Approved/Rejected
 */
const fs = require('fs');
const {
  ROOT_CAUSE_DIR, rel, listRootCauseReports, readBlastRadiusReport,
  contextJsonPathFor, strategyPathFor, planPathFor, existingPlanStatus,
} = require('./lib/plans');

function stateOf(rc) {
  const context = fs.existsSync(contextJsonPathFor(rc.id));
  const strategy = fs.existsSync(strategyPathFor(rc.id));
  const plan = fs.existsSync(planPathFor(rc.id));
  const status = plan ? (existingPlanStatus(rc.id) || 'Proposed') : null;

  let stage = 'not started';
  if (plan) stage = `plan rendered (${status})`;
  else if (strategy) stage = 'strategy written — render pending';
  else if (context) stage = 'context collected — strategy pending';

  return {
    context, strategy, plan, status, stage,
    blastRadius: Boolean(readBlastRadiusReport(rc.id)),
  };
}

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const pendingOnly = argv.includes('--pending');

  let items = listRootCauseReports().map((rc) => ({ ...rc, ...stateOf(rc) }));
  if (pendingOnly) items = items.filter((i) => i.status !== 'Approved' && i.status !== 'Rejected');

  if (asJson) {
    console.log(JSON.stringify(items, null, 2));
    return;
  }

  if (!items.length) {
    console.log(`No root cause reports found in ${rel(ROOT_CAUSE_DIR)}. Run the Root Cause Analyst agent first — remediation planning starts from a confirmed diagnosis.`);
    return;
  }

  const rows = items.map((i) => [i.id, i.severity || '-', i.blastRadius ? 'yes' : 'no', i.stage, i.title]);
  const headers = ['ID', 'SEVERITY', 'BLAST RADIUS', 'PIPELINE STATE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Remediation workload: ${rel(ROOT_CAUSE_DIR)} (${items.length} root cause report${items.length === 1 ? '' : 's'}${pendingOnly ? ', pending approval' : ''})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log(`\nNext: node scripts/collect-remediation-context.js --all`);
  console.log(`A plan already rendered stays at Proposed until a human edits its Status cell to Approved — see docs/agent_output/04-remediation/README.md.`);
}

try {
  main();
} catch (err) {
  console.error('Listing failed:', err.message);
  process.exit(1);
}
