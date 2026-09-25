#!/usr/bin/env node
/**
 * Dumps the issue register exactly as the pipeline parses it.
 *
 * Use this to confirm a newly added row is picked up, and that its multi-value
 * cells split the way you meant, before running the agents.
 *
 *   node scripts/list-register.js
 *   node scripts/list-register.js --issue ISSUE-003
 *   node scripts/list-register.js --full
 */
const path = require('path');
const { listIssues, registerPath, COLUMNS, SECTIONS } = require('./lib/register');

const SKILL_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const ISSUES_DIR = path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues');

const rel = (t) => path.relative(REPO_ROOT, t).replace(/\\/g, '/');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--full' || a === '-f') args.full = true;
    else if (a === '--help' || a === '-h') args.help = true;
    else if (a === '--issue' || a === '-i') args.issue = argv[++i];
  }
  return args;
}

function usage() {
  console.log(`Dump the issue register as the pipeline parses it.

  --issue, -i <ID>   Only this issue
  --full,  -f        Also print the synthesized markdown body
  --help,  -h        This message

Register: ${rel(registerPath(ISSUES_DIR))}
Columns:  ${COLUMNS.join(', ')}`);
}

function pad(s, n) {
  const v = String(s === null || s === undefined ? '' : s);
  return v.length > n ? `${v.slice(0, n - 1)}…` : v.padEnd(n);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  const file = registerPath(ISSUES_DIR);
  let issues = listIssues(ISSUES_DIR, rel);

  if (args.issue) {
    issues = issues.filter((i) => i.id.toLowerCase() === String(args.issue).toLowerCase());
    if (!issues.length) {
      console.error(`No issue "${args.issue}" in ${rel(file)}.`);
      process.exitCode = 1;
      return;
    }
  }

  console.log(`Issue register: ${rel(file)} (${issues.length} issue${issues.length === 1 ? '' : 's'})\n`);
  if (!issues.length) {
    console.log('The register is empty — add a row with an "issue_id" and re-run.');
    return;
  }

  console.log(`${pad('ID', 11)}${pad('SEVERITY', 10)}${pad('TYPE', 15)}${pad('STATUS', 8)}TITLE`);
  console.log(`${'-'.repeat(10)} ${'-'.repeat(9)} ${'-'.repeat(14)} ${'-'.repeat(7)} ${'-'.repeat(60)}`);
  for (const i of issues) {
    console.log(`${pad(i.id, 11)}${pad(i.severity, 10)}${pad(i.type, 15)}${pad(i.status, 8)}${pad(i.title, 60)}`);
  }

  console.log('\nParsed multi-value cells:');
  for (const i of issues) {
    console.log(`\n  ${i.id}`);
    console.log(`    services     : ${i.services.join(', ') || '(none)'}`);
    console.log(`    symbols      : ${i.symbols.join(', ') || '(none)'}`);
    console.log(`    files        : ${i.files.length} file(s)`);
    console.log(`    entry points : ${i.entryPoints.join(', ') || '(none)'}`);
    const present = SECTIONS.filter(([col]) => (i.raw[col] || '').trim()).map(([, h]) => h);
    console.log(`    sections     : ${present.join(', ') || '(none)'}`);
  }

  if (args.full) {
    for (const i of issues) {
      console.log(`\n${'='.repeat(78)}\n${i.id} — synthesized markdown body\n${'='.repeat(78)}\n`);
      console.log(i.body);
    }
  }

  console.log('\nThis register is read-only input. Edit it in Excel; nothing in the pipeline writes to it.');
}

try {
  main();
} catch (err) {
  console.error(`Listing failed: ${err.message}`);
  process.exitCode = 1;
}
