#!/usr/bin/env node
/**
 * Re-scanner — Fact Collector
 *
 * Answers the mechanical half of "does the finding still trigger?": re-derives the original
 * issue's Detection Notes (grep-able signatures such as `BasicQuery`) and checks whether they
 * still appear in the *patched* file, materialized by applying the fix's own diff inside a
 * throwaway git worktree (never the real working tree).
 *
 * This script does not decide whether the vulnerability is fixed — absence of a literal
 * signature does not prove the defect class is gone. That judgment is the agent's, written to
 * <id>.rescan.verdict.json after reading the briefing this script produces.
 *
 * Usage:
 *   node scripts/collect-rescan.js --all
 *   node scripts/collect-rescan.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, WORK_DIR, rel, listStep1Fixes, resolveFix, upstreamChainFor,
  materializePatchedFiles, factsPathFor, briefingPathFor,
} = require('./lib/verify');

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
  console.log(`Re-scanner — Fact Collector

  node scripts/collect-rescan.js --all
  node scripts/collect-rescan.js --issue <ISSUE-ID>`);
}

function renderBriefing(facts) {
  const out = [];
  out.push(`# Re-scan Facts — ${facts.id}`);
  out.push('');
  out.push(`_Collected ${facts.generatedAt}. Mechanical signature check only — no verdict._`);
  out.push('');
  out.push(`**Fix:** ${facts.title}`);
  out.push(`**CWE:** ${facts.cwe || 'n/a'}`);
  out.push(`**Fix report:** \`${facts.sources.fixReport}\``);
  out.push('');

  out.push('## 1. Original diagnosis');
  out.push('');
  out.push(`- **Root cause statement:** ${facts.rootCause.statement || 'not available'}`);
  out.push(`- **Explanation:** ${facts.rootCause.explanation || 'not available'}`);
  out.push('');

  out.push('## 2. Detection signatures (from the issue\'s Detection Notes)');
  out.push('');
  if (!facts.signatureChecks.length) {
    out.push('_No backtick-quoted signature found in the issue\'s Detection Notes section. Reason '
      + 'from the root cause statement and the patched source directly instead._');
  } else {
    out.push('| Signature | Still present in patched file(s) |');
    out.push('|---|---|');
    for (const s of facts.signatureChecks) {
      out.push(`| \`${s.signature}\` | ${s.foundIn.length ? `**yes** — ${s.foundIn.map((f) => `\`${f}\``).join(', ')}` : 'no'} |`);
    }
  }
  out.push('');

  out.push('## 3. Patched source (materialized by applying the fix diff in an isolated worktree)');
  out.push('');
  if (!facts.worktree.applied) {
    out.push(`_Could not materialize the patch: ${facts.worktree.error}_`);
  } else {
    for (const [file, content] of Object.entries(facts.patchedFiles)) {
      out.push(`### \`${file}\``);
      out.push('');
      out.push(content === null ? '_File not found in the patched worktree._' : ['```java', content, '```'].join('\n'));
      out.push('');
    }
  }

  out.push('## 4. What to write next');
  out.push('');
  out.push(`Read this briefing, then write \`${rel(path.join(WORK_DIR, `${facts.id}.rescan.verdict.json`))}\` `
    + `following \`templates/rescan.schema.json\`. A signature being absent is a data point, not a `
    + `conclusion — reason about whether the new construction actually closes the mechanism the CWE `
    + `describes, not just the literal grep.`);
  out.push('');

  return out.join('\n');
}

function collectForFix(fix) {
  const chain = upstreamChainFor(fix);
  const issue = chain.issue;
  const signatures = issue ? issue.signatures : [];

  const patchAbs = path.join(REPO_ROOT, fix.patchFile || '');
  const worktree = fix.patchFile && fs.existsSync(patchAbs)
    ? materializePatchedFiles(fix.id, patchAbs, fix.filesChanged)
    : { applied: false, files: {}, error: `No patch file resolved from fix report (looked for ${fix.patchFile || 'none'}).` };

  const signatureChecks = signatures.map((sig) => ({
    signature: sig,
    foundIn: Object.entries(worktree.files)
      .filter(([, content]) => content && content.includes(sig))
      .map(([file]) => file),
  }));

  const facts = {
    generatedAt: new Date().toISOString(),
    id: fix.id,
    title: fix.title,
    cwe: fix.cwe,
    rootCause: chain.rootCause || { statement: null, explanation: null },
    signatureChecks,
    worktree: { applied: worktree.applied, error: worktree.error },
    patchedFiles: worktree.files,
    sources: { fixReport: fix.relativeFixReportFile, issue: issue ? issue.relativeFile : null },
  };

  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(factsPathFor(fix.id, 'rescan'), JSON.stringify(facts, null, 2));
  fs.writeFileSync(briefingPathFor(fix.id, 'rescan'), renderBriefing(facts));

  return {
    id: fix.id,
    signaturesChecked: signatureChecks.length,
    stillPresent: signatureChecks.filter((s) => s.foundIn.length).length,
    worktreeOk: worktree.applied,
    briefing: rel(briefingPathFor(fix.id, 'rescan')),
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listStep1Fixes();
    if (!targets.length) throw new Error('No fixes found with a drafted diff (Compiled or Compile Failed). Nothing to re-scan.');
    console.log(`Re-scanner — ${targets.length} fix(es): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-001) or --all.');
    targets = [resolveFix(args.issue)];
    if (targets[0].status === 'Refused') throw new Error(`${targets[0].id}'s fix report Status is "Refused" — Fixer never drafted a diff to re-scan.`);
  }

  const done = []; const failed = [];
  for (const fix of targets) {
    try {
      const s = collectForFix(fix);
      done.push(s);
      console.log(`\n${s.id} — facts collected`);
      console.log(`  signatures    : ${s.signaturesChecked} checked, ${s.stillPresent} still present`);
      console.log(`  worktree      : ${s.worktreeOk ? 'applied ok' : 'FAILED'}`);
      console.log(`  briefing      : ${s.briefing}`);
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: fix.id, message: err.message });
      console.error(`\n${fix.id} — FAILED: ${err.message}`);
    }
  }
  if (targets.length > 1) console.log(`\nCollected ${done.length}/${targets.length}.`);
  console.log(`\nNext: read each briefing, then write <id>.rescan.verdict.json alongside it.`);
  if (failed.length) process.exitCode = 1;
}

try {
  main();
} catch (err) {
  console.error('Re-scan collection failed:', err.message);
  process.exit(1);
}
