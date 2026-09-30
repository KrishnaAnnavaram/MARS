#!/usr/bin/env node
/**
 * Verification Layer — Workload Listing (shared by re-scanner, red-team-recon, behavior-guard)
 *
 * Discovery step. Prints every fix report Fixer actually drafted a diff for (Status: Compiled or
 * Compile Failed — Step 1 never compiles anything, so a failed build doesn't stop it) with the
 * pipeline state of all three Step 1 checks side by side, so each agent knows exactly what its
 * own workload is without re-deriving the fix register three times.
 *
 * docs/agent_output/04-remediation/ is read-only input; nothing here writes to it.
 *
 * Usage:
 *   node scripts/list-workload.js            # table for humans
 *   node scripts/list-workload.js --json      # machine-readable
 *   node scripts/list-workload.js --pending <name>   # only fixes missing that check's report (rescan|redteam|behavior)
 */
const fs = require('fs');
const {
  FIXES_DIR, rel, listStep1Fixes, verdictPathFor, reportPathFor,
} = require('./lib/verify');

const CHECKS = [
  { name: 'rescan', label: 'RE-SCAN', prefix: 'rescan' },
  { name: 'redteam', label: 'RED-TEAM', prefix: 'redteam' },
  { name: 'behavior', label: 'BEHAVIOR', prefix: 'behavior' },
];

function stateOf(fix, check) {
  const verdict = fs.existsSync(verdictPathFor(fix.id, check.name));
  const report = fs.existsSync(reportPathFor(fix.id, check.prefix));
  if (report) return 'report written';
  if (verdict) return 'verdict written — render pending';
  return 'not started';
}

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const pendingIdx = argv.indexOf('--pending');
  const pendingCheck = pendingIdx >= 0 ? argv[pendingIdx + 1] : null;

  let fixes = listStep1Fixes().map((f) => ({
    ...f,
    checks: Object.fromEntries(CHECKS.map((c) => [c.name, stateOf(f, c)])),
  }));

  if (pendingCheck) fixes = fixes.filter((f) => f.checks[pendingCheck] !== 'report written');

  if (asJson) {
    console.log(JSON.stringify(fixes, null, 2));
    return;
  }

  if (!fixes.length) {
    console.log(`No fixes found in ${rel(FIXES_DIR)} with a drafted diff (Compiled or Compile Failed). Run the Fixer agent first.`);
    return;
  }

  const rows = fixes.map((f) => [f.id, f.cwe || '-', f.checks.rescan, f.checks.redteam, f.checks.behavior, f.title]);
  const headers = ['ID', 'CWE', 'RE-SCAN', 'RED-TEAM', 'BEHAVIOR', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Step 1 workload: ${rel(FIXES_DIR)} (${fixes.length} fix${fixes.length === 1 ? '' : 'es'} with a drafted diff)\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));
  console.log('\nEach check runs independently: node scripts/collect-<rescan|redteam|behavior>.js --all');
}

try {
  main();
} catch (err) {
  console.error('Listing failed:', err.message);
  process.exit(1);
}
