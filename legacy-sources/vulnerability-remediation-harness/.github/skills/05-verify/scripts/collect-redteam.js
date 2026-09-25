#!/usr/bin/env node
/**
 * Red-team Recon — Fact Collector
 *
 * Answers "can the patch be bypassed?" — gathers the patched source (materialized by applying
 * the fix diff in a throwaway worktree), the diff itself, the fix plan's stated approach and
 * risk notes, and the full CWE catalog entry (including anti_patterns) the plan cited. The
 * adversarial reasoning itself — enumerating bypass vectors against the new code — is the
 * agent's job; this script only assembles what it needs to do that without inventing anything.
 *
 * Usage:
 *   node scripts/collect-redteam.js --all
 *   node scripts/collect-redteam.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, WORK_DIR, rel, listStep1Fixes, resolveFix, upstreamChainFor, loadCatalogEntry,
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
  console.log(`Red-team Recon — Fact Collector

  node scripts/collect-redteam.js --all
  node scripts/collect-redteam.js --issue <ISSUE-ID>`);
}

function renderBriefing(facts) {
  const out = [];
  out.push(`# Red-team Recon Facts — ${facts.id}`);
  out.push('');
  out.push(`_Collected ${facts.generatedAt}. Materials only — the adversarial reasoning is yours._`);
  out.push('');
  out.push(`**Fix:** ${facts.title}`);
  out.push(`**CWE cited by the plan:** ${facts.cwe || 'n/a'}`);
  out.push('');

  out.push('## 1. The fix\'s own claim');
  out.push('');
  out.push(facts.planApproach || '_Fix plan approach not available._');
  out.push('');
  if (facts.planRiskNotes.length) {
    out.push('**Risks the plan already flagged:**');
    out.push('');
    facts.planRiskNotes.forEach((r) => out.push(`- ${r}`));
    out.push('');
  }

  out.push('## 2. CWE catalog entry cited');
  out.push('');
  if (!facts.catalogEntry) {
    out.push('_No catalog entry resolved for this CWE — reason from first principles._');
  } else {
    out.push(`**${facts.catalogEntry.title}**`);
    out.push('');
    out.push(`Canonical approach: ${facts.catalogEntry.canonical_approach}`);
    out.push('');
    if (facts.catalogEntry.anti_patterns && facts.catalogEntry.anti_patterns.length) {
      out.push('Known anti-patterns to check the fix did NOT fall into:');
      out.push('');
      facts.catalogEntry.anti_patterns.forEach((a) => out.push(`- ${a}`));
      out.push('');
    }
  }

  out.push('## 3. The diff');
  out.push('');
  out.push(facts.diffText ? ['```diff', facts.diffText.trim(), '```'].join('\n') : '_Diff not available._');
  out.push('');

  out.push('## 4. Patched source (isolated worktree, never the real tree)');
  out.push('');
  if (!facts.worktree.applied) {
    out.push(`_Could not materialize the patch: ${facts.worktree.error}_`);
  } else {
    for (const [file, content] of Object.entries(facts.patchedFiles)) {
      out.push(`### \`${file}\``);
      out.push('');
      out.push(content === null ? '_Not found._' : ['```java', content, '```'].join('\n'));
      out.push('');
    }
  }

  out.push('## 5. What to write next');
  out.push('');
  out.push(`Read this briefing, then write \`${rel(path.join(WORK_DIR, `${facts.id}.redteam.verdict.json`))}\` `
    + `following \`templates/redteam.schema.json\`. Enumerate concrete alternate vectors against the `
    + `*new* code — a different field, a different operator class, a boundary the fix didn't cover — `
    + `not a restatement of the original vulnerability.`);
  out.push('');

  return out.join('\n');
}

function collectForFix(fix) {
  const chain = upstreamChainFor(fix);
  const catalogEntry = loadCatalogEntry(fix.cwe);

  const patchAbs = path.join(REPO_ROOT, fix.patchFile || '');
  const diffText = fix.patchFile && fs.existsSync(patchAbs) ? fs.readFileSync(patchAbs, 'utf8') : null;
  const worktree = diffText
    ? materializePatchedFiles(fix.id, patchAbs, fix.filesChanged)
    : { applied: false, files: {}, error: `No patch file resolved from fix report (looked for ${fix.patchFile || 'none'}).` };

  const facts = {
    generatedAt: new Date().toISOString(),
    id: fix.id,
    title: fix.title,
    cwe: fix.cwe,
    planApproach: chain.fixPlan ? chain.fixPlan.approach : null,
    planRiskNotes: chain.fixPlan ? chain.fixPlan.riskNotes : [],
    catalogEntry,
    diffText,
    worktree: { applied: worktree.applied, error: worktree.error },
    patchedFiles: worktree.files,
    sources: { fixReport: fix.relativeFixReportFile },
  };

  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(factsPathFor(fix.id, 'redteam'), JSON.stringify(facts, null, 2));
  fs.writeFileSync(briefingPathFor(fix.id, 'redteam'), renderBriefing(facts));

  return {
    id: fix.id, worktreeOk: worktree.applied, hasCatalogEntry: Boolean(catalogEntry), briefing: rel(briefingPathFor(fix.id, 'redteam')),
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listStep1Fixes();
    if (!targets.length) throw new Error('No fixes found with a drafted diff (Compiled or Compile Failed). Nothing to red-team.');
    console.log(`Red-team Recon — ${targets.length} fix(es): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-001) or --all.');
    targets = [resolveFix(args.issue)];
    if (targets[0].status === 'Refused') throw new Error(`${targets[0].id}'s fix report Status is "Refused" — Fixer never drafted a diff to red-team.`);
  }

  const done = []; const failed = [];
  for (const fix of targets) {
    try {
      const s = collectForFix(fix);
      done.push(s);
      console.log(`\n${s.id} — facts collected`);
      console.log(`  worktree       : ${s.worktreeOk ? 'applied ok' : 'FAILED'}`);
      console.log(`  catalog entry  : ${s.hasCatalogEntry ? 'found' : 'not found'}`);
      console.log(`  briefing       : ${s.briefing}`);
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: fix.id, message: err.message });
      console.error(`\n${fix.id} — FAILED: ${err.message}`);
    }
  }
  if (targets.length > 1) console.log(`\nCollected ${done.length}/${targets.length}.`);
  console.log(`\nNext: read each briefing, then write <id>.redteam.verdict.json alongside it.`);
  if (failed.length) process.exitCode = 1;
}

try {
  main();
} catch (err) {
  console.error('Red-team collection failed:', err.message);
  process.exit(1);
}
