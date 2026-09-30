#!/usr/bin/env node
/**
 * Scribe — Chain of Custody Collector
 *
 * Gathers links and headline facts from every stage of the pipeline for one fix — issue, root
 * cause, blast radius, fix plan, fix, all five Phase C checks, and the merge arbiter's verdict —
 * into one briefing. Facts only; the Scribe's own writing (the PR content and the audit trail
 * prose) is the agent's job, done after reading this.
 *
 * Usage:
 *   node scripts/collect-chain.js --all
 *   node scripts/collect-chain.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  WORK_DIR, rel, listArbitratedFixes, resolveFix, chainOfCustodyFor, extractField, titleOf,
  factsPathFor,
} = require('./lib/scribe');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Scribe — Chain of Custody Collector

  node scripts/collect-chain.js --all
  node scripts/collect-chain.js --issue <ISSUE-ID>`);
}

/** Always stores the extracted field under one consistent 'status' key, whatever label it came from. */
function summarize(entry, extraLabel) {
  if (!entry) return { present: false };
  return {
    present: true,
    file: entry.file,
    title: titleOf(entry.text),
    status: extractField(entry.text, extraLabel || 'Status') || extractField(entry.text, 'Verdict'),
  };
}

function renderBriefing(id, chain, jsonSummary) {
  const out = [];
  out.push(`# Chain of Custody — ${id}`);
  out.push('');
  out.push('_Facts and links only — write the narrative and PR content yourself._');
  out.push('');

  const rows = [
    ['Issue', jsonSummary.issue],
    ['Root cause', jsonSummary.rootCause],
    ['Blast radius', jsonSummary.blastRadius],
    ['Fix plan', jsonSummary.fixPlan],
    ['Fix (Compiled)', jsonSummary.fix],
    ['Re-scan', jsonSummary.rescan],
    ['Red-team recon', jsonSummary.redteam],
    ['Behavior guard', jsonSummary.behavior],
    ['QA gate', jsonSummary.qa],
    ['Build gate', jsonSummary.build],
    ['Merge verdict', jsonSummary.verdict],
  ];
  out.push('| Stage | Status/Verdict | File |');
  out.push('|---|---|---|');
  for (const [label, summary] of rows) {
    if (!summary || !summary.present) { out.push(`| ${label} | _not available_ | — |`); continue; }
    out.push(`| ${label} | ${summary.status || '-'} | \`${summary.file}\` |`);
  }
  out.push('');

  out.push('## Full text of each stage');
  out.push('');
  for (const [label, entry] of Object.entries(chain)) {
    if (!entry || label === 'fixDiff') continue;
    out.push(`<details><summary>${label}${entry.file ? ` — \`${entry.file}\`` : ''}</summary>`);
    out.push('');
    out.push(entry.text || '_not available_');
    out.push('');
    out.push('</details>');
    out.push('');
  }

  if (chain.fixDiff) {
    out.push('## The diff');
    out.push('');
    out.push('```diff');
    out.push(chain.fixDiff.text.trim());
    out.push('```');
    out.push('');
  }

  out.push('## What to write next');
  out.push('');
  out.push(`Write \`${rel(path.join(WORK_DIR, `${id}.content.json`))}\` following `
    + `\`templates/content.schema.json\`: the audit trail's narrative prose and the PR's title/`
    + `summary/checklist. You do not decide or restate Cleared/Blocked — the render script pulls `
    + `that straight from the merge verdict and applies the Blocked banner automatically.`);
  out.push('');

  return out.join('\n');
}

function collectForFix(fix) {
  const chain = chainOfCustodyFor(fix.id);
  const jsonSummary = {
    issue: chain.issue ? { present: true, file: chain.issue.file } : { present: false },
    rootCause: summarize(chain.rootCause),
    blastRadius: summarize(chain.blastRadius),
    fixPlan: summarize(chain.fixPlan),
    fix: summarize(chain.fix),
    rescan: summarize(chain.rescan, 'Verdict'),
    redteam: summarize(chain.redteam, 'Verdict'),
    behavior: summarize(chain.behavior, 'Verdict'),
    qa: summarize(chain.qa),
    build: summarize(chain.build),
    verdict: summarize(chain.verdict, 'Decision'),
    fixDiffFile: chain.fixDiff ? chain.fixDiff.file : null,
  };

  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(path.join(WORK_DIR, `${fix.id}.chain.facts.json`), JSON.stringify(jsonSummary, null, 2));
  fs.writeFileSync(factsPathFor(fix.id), renderBriefing(fix.id, chain, jsonSummary));

  return { id: fix.id, briefing: rel(factsPathFor(fix.id)) };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listArbitratedFixes();
    if (!targets.length) throw new Error('No ship verdicts found. Run the merge-arbiter agent first.');
    console.log(`Scribe — ${targets.length} fix(es): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue or --all.');
    targets = [resolveFix(args.issue)];
  }

  for (const fix of targets) {
    const s = collectForFix(fix);
    console.log(`\n${s.id} — chain collected`);
    console.log(`  briefing : ${s.briefing}`);
  }
  console.log(`\nNext: write <id>.content.json for each, then node scripts/render-scribe.js --all`);
}

try { main(); } catch (err) { console.error('Chain collection failed:', err.message); process.exit(1); }
