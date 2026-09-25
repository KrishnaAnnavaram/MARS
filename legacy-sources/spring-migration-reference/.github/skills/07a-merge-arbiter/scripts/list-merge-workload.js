#!/usr/bin/env node
/**
 * Merge Arbiter — Workload Listing
 *
 * A fix is ready for arbitration once all five upstream reports exist: re-scan, red-team,
 * behavior, QA and build. Usage:
 *   node scripts/list-merge-workload.js
 */
const fs = require('fs');
const {
  FIXES_DIR, rel, listStep3Fixes, readUpstream, scorePathFor, arbitrationPathFor, verdictPathFor,
} = require('./lib/arbiter');

function stateOf(fix) {
  const upstream = readUpstream(fix.id);
  const missing = Object.entries(upstream).filter(([, v]) => !v.present).map(([k]) => k);
  const score = fs.existsSync(scorePathFor(fix.id));
  const arbitration = fs.existsSync(arbitrationPathFor(fix.id));
  const verdict = fs.existsSync(verdictPathFor(fix.id));

  let stage = 'not started';
  if (missing.length) stage = `waiting on: ${missing.join(', ')}`;
  else if (verdict) stage = 'verdict rendered';
  else if (arbitration) stage = 'arbitration written — render pending';
  else if (score) stage = 'score computed — arbitration pending';
  else stage = 'ready — score pending';

  return { upstream, missing, stage };
}

function main() {
  const asJson = process.argv.includes('--json');
  const items = listStep3Fixes().map((f) => ({ ...f, ...stateOf(f) }));

  if (asJson) { console.log(JSON.stringify(items, null, 2)); return; }
  if (!items.length) { console.log(`No drafted fixes (Compiled or Compile Failed) found in ${rel(FIXES_DIR)}.`); return; }

  const rows = items.map((f) => [f.id, f.cwe || '-', f.stage, f.title]);
  const headers = ['ID', 'CWE', 'PIPELINE STATE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Merge workload: ${rel(FIXES_DIR)} (${items.length} Compiled fix${items.length === 1 ? '' : 'es'})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log('\nNext: node scripts/compute-score.js --issue <ID>   (only once all five upstream reports exist)');
}

try { main(); } catch (err) { console.error('Listing failed:', err.message); process.exit(1); }
