#!/usr/bin/env node
/**
 * Version Migration — regenerate the per-run summary on demand.
 *
 * Every other 04D script already ends by writing MIGRATION_SUMMARY.md + migration-summary.json (and,
 * for an Agent 04 issue, the standard fix_<id>.md handoff once the migration is finished or
 * blocked). This script regenerates the summary only: after the downstream agents (05, 06, 07) have
 * run, to refresh its "Pipeline handoff" answers; or to produce the summary for a session whose
 * scripts predate it. It reads the session's evidence files and the existing handoff, and never
 * rewrites fix_<id>.md / .diff — those are 04's output, and 07 must leave 04-remediation/ as it is.
 *
 * Usage:
 *   node scripts/finalize-run.js --slug <slug>
 *   node scripts/finalize-run.js --issue ISSUE-005      # the session Agent 04 Stage 2 created for that issue
 */
const fs = require('fs');
const { slugify, sessionPaths } = require('./lib/migration');
const { finalizeRun } = require('./lib/summary');

function main() {
  const argv = process.argv.slice(2);
  const at = (flag, short) => { const i = argv.findIndex((a) => a === flag || a === short); return i === -1 ? null : argv[i + 1]; };
  const slug = at('--slug', '-s') || (at('--issue', '-i') ? slugify(at('--issue', '-i')) : null);
  if (!slug) {
    console.error('Pass --slug <slug> or --issue <ISSUE-ID>.');
    process.exitCode = 1;
    return;
  }
  if (!fs.existsSync(sessionPaths(slug).baseline) && !fs.existsSync(sessionPaths(slug).state)) {
    console.error(`No migration session "${slug}" — nothing to summarise.`);
    process.exitCode = 1;
    return;
  }
  const summary = finalizeRun(slug, 'finalize-run.js', { handoff: false }); // summary only — never rewrites 04's handoff
  if (!summary) process.exitCode = 1;
  else console.log(`  ${summary.run_id}: ${summary.final_status} — ${summary.status_reason}`);
}

if (require.main === module) main();
