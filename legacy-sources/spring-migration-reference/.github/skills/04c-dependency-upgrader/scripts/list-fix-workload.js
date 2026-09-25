#!/usr/bin/env node
/**
 * Dependency Upgrader — Fix Workload Listing
 *
 * Discovery step. Prints every CWE-1104 fix plan in docs/agent_output/04-remediation/ with its
 * approval Status and its own pipeline state. Only plans at Status: Approved are real workload —
 * everything else is listed for visibility but is never acted on. A plan for any other CWE is not
 * this skill's concern at all and is left out entirely — see 04b-fixer for those.
 *
 * docs/agent_output/04-remediation/ is read-only input to this skill; nothing here edits a plan's Status.
 *
 * Usage:
 *   node scripts/list-fix-workload.js            # table for humans
 *   node scripts/list-fix-workload.js --json      # machine-readable
 *   node scripts/list-fix-workload.js --approved  # only Status: Approved plans
 */
const fs = require('fs');
const {
  FIX_PLANS_DIR, rel, listFixPlans,
  patchPathFor, rationalePathFor, verificationJsonPathFor, fixReportPathFor,
} = require('./lib/depfixplans');

function stateOf(plan) {
  const patch = fs.existsSync(patchPathFor(plan.id));
  const rationale = fs.existsSync(rationalePathFor(plan.id));
  const verification = fs.existsSync(verificationJsonPathFor(plan.id));
  const report = fs.existsSync(fixReportPathFor(plan.id));

  let stage;
  if (plan.status !== 'Approved') stage = 'waiting on approval';
  else if (report) stage = 'report written';
  else if (verification) stage = 'verified — render pending';
  else if (patch && rationale) stage = 'patch + rationale drafted — verify pending';
  else if (patch || rationale) stage = 'partially drafted';
  else stage = 'not started';

  return {
    patch, rationale, verification, report, stage,
  };
}

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const approvedOnly = argv.includes('--approved');

  let items = listFixPlans()
    .filter((p) => p.cwe === 'CWE-1104')
    .map((p) => ({ ...p, ...stateOf(p) }));
  if (approvedOnly) items = items.filter((i) => i.status === 'Approved');

  if (asJson) {
    console.log(JSON.stringify(items, null, 2));
    return;
  }

  if (!items.length) {
    console.log(`No CWE-1104 fix plans found in ${rel(FIX_PLANS_DIR)}. Run the Fix Strategist (Stage 1) against a dependency-version issue first.`);
    return;
  }

  const rows = items.map((i) => [i.id, i.dependencyUpgrade ? i.dependencyUpgrade.mavenCoordinate : '-', i.status, i.stage, i.title]);
  const headers = ['ID', 'DEPENDENCY', 'PLAN STATUS', 'PIPELINE STATE', 'TITLE'];
  const widths = headers.map((h, c) => Math.max(h.length, ...rows.map((r) => String(r[c]).length)));
  const line = (cells) => cells.map((cell, c) => String(cell).padEnd(widths[c])).join('  ').trimEnd();

  console.log(`Dependency-upgrade workload: ${rel(FIX_PLANS_DIR)} (${items.length} CWE-1104 plan${items.length === 1 ? '' : 's'}${approvedOnly ? ', Approved only' : ''})\n`);
  console.log(line(headers));
  console.log(widths.map((w) => '-'.repeat(w)).join('  '));
  rows.forEach((r) => console.log(line(r)));

  const notApproved = items.filter((i) => i.status !== 'Approved').length;
  console.log(`\nNext: node scripts/apply-version-bump.js --issue <ISSUE-ID>  (only plans at Status: Approved are ever acted on)`);
  if (notApproved) console.log(`${notApproved} plan(s) are not Approved yet and will be skipped — see docs/agent_output/04-remediation/README.md for how to approve one.`);
}

try {
  main();
} catch (err) {
  console.error('Listing failed:', err.message);
  process.exit(1);
}
