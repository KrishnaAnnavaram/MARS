#!/usr/bin/env node
/**
 * 04a1 — Catalog-gap detector (the YES / NO switch).
 *
 * For each root cause report, scans its text (plus the issue row) for CWE mentions, looks each up in
 * the main 04a catalog, and classifies the issue:
 *
 *   COVERED   — at least one detected CWE is in the catalog  -> 04a proceeds normally (YES branch)
 *   GAP       — CWE(s) detected but NONE is in the catalog   -> invoke run-fallback.js  (NO branch)
 *   NO-CWE    — no CWE detected at all                        -> agent must state one first
 *
 * Read-only. Does not write anything. Run it to see which issues, if any, need the fallback.
 *
 * Usage:
 *   node scripts/detect-gap.js --all
 *   node scripts/detect-gap.js --issue ISSUE-002
 *   node scripts/detect-gap.js --cwe CWE-22        # quick: is this CWE a gap?
 */
const fs = require('fs');
const { plans, loadKb, classifyGap } = require('./lib/fallback');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--cwe') args.cwe = argv[++i];
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`04a1 — Catalog-gap detector

  node scripts/detect-gap.js --all
  node scripts/detect-gap.js --issue <ISSUE-ID>
  node scripts/detect-gap.js --cwe <CWE-ID>`);
}

function cweCandidatesFor(item, catalog) {
  const text = [item.issue && item.issue.body, fs.readFileSync(item.reportFile, 'utf8')].filter(Boolean).join('\n');
  return plans.detectCweMentions(text).map((cwe) => ({ cwe, inCatalog: Boolean(catalog[cwe]) }));
}

function verdictFor(candidates) {
  if (!candidates.length) return { verdict: 'NO-CWE', branch: 'state a CWE first' };
  const g = classifyGap(candidates);
  if (g.warranted) return { verdict: 'GAP', branch: 'NO — invoke 04a1 run-fallback', gaps: g.gaps };
  return { verdict: 'COVERED', branch: 'YES — 04a proceeds', catalogued: g.catalogued, gaps: g.gaps };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  const catalog = plans.loadCatalog();
  const kb = loadKb();

  if (args.cwe) {
    const cwe = args.cwe.toUpperCase();
    const inCatalog = Boolean(catalog[cwe]);
    const inKb = Boolean(kb[cwe]);
    const verdict = inCatalog ? 'COVERED (YES — 04a)' : (inKb ? 'GAP, KB has a pattern (NO — 04a1 can derive)' : 'GAP, and no KB entry (report KB gap)');
    console.log(`${cwe}: in catalog=${inCatalog}, in KB=${inKb} -> ${verdict}`);
    return;
  }

  let targets = plans.listRootCauseReports();
  if (!targets.length) { console.log('No root cause reports found in docs/agent_output/02-root-cause/.'); return; }
  if (args.issue) {
    targets = targets.filter((t) => t.id.toLowerCase() === args.issue.toLowerCase());
    if (!targets.length) { console.error(`No root cause report for ${args.issue}.`); process.exit(1); }
  }

  console.log('ISSUE       DETECTED CWEs                 VERDICT    NEXT');
  console.log('----------  ----------------------------  ---------  ------------------------------');
  for (const t of targets) {
    const candidates = cweCandidatesFor(t, catalog);
    const v = verdictFor(candidates);
    const cwes = candidates.map((c) => `${c.cwe}${c.inCatalog ? '' : '*'}`).join(', ') || '(none)';
    console.log(`${t.id.padEnd(10)}  ${cwes.padEnd(28)}  ${v.verdict.padEnd(9)}  ${v.branch}`);
  }
  console.log('\n* = not in the CWE catalog (a gap). GAP = every detected CWE is a gap -> the 04a1 fallback is warranted.');
}

try { main(); } catch (err) { console.error('detect-gap failed:', err.message); process.exit(1); }
