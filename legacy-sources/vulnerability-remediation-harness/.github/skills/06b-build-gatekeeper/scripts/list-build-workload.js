#!/usr/bin/env node
/**
 * Build Gatekeeper — Workload Listing
 *
 * Usage:
 *   node scripts/list-build-workload.js
 */
const fs = require('fs');
const {
  FIXES_DIR, rel, listStep2Fixes, resultPathFor, reportPathFor,
} = require('./lib/gate');

function stateOf(fix) {
  const result = fs.existsSync(resultPathFor(fix.id));
  const report = fs.existsSync(reportPathFor(fix.id));
  if (report) return 'report written';
  if (result) return 'gate run — render pending';
  return 'not started';
}

function main() {
  const asJson = process.argv.includes('--json');
  const items = listStep2Fixes().map((f) => ({ ...f, stage: stateOf(f) }));

  if (asJson) { console.log(JSON.stringify(items, null, 2)); return; }
  if (!items.length) { console.log(`No fixes with a drafted diff found in ${rel(FIXES_DIR)}. Run the Fixer agent first.`); return; }

  const rows = items.map((f) => [f.id, f.cwe || '-', f.stage, f.title]);
  const headers = ['ID', 'CWE', 'PIPELINE STATE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Build gate workload: ${rel(FIXES_DIR)} (${items.length} Compiled fix${items.length === 1 ? '' : 'es'})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log('\nNext: node scripts/run-build-gate.js --issue <ID>');
}

try { main(); } catch (err) { console.error('Listing failed:', err.message); process.exit(1); }
