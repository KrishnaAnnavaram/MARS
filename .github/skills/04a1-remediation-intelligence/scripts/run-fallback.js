#!/usr/bin/env node
/**
 * 04a1 — Remediation-intelligence fallback (the NO branch).
 *
 * Runs ONLY for a catalog gap: a diagnosed CWE with no entry in 04a's cwe-patterns.json. It reads
 * the remediation context bundle 04a already collected, confirms the gap, searches the local
 * knowledge base, ranks the historical fixes, derives a grounded strategy, and writes it to the SAME
 * location 04a's render-fix-plan.js reads:
 *
 *   .github/.pipeline-context/fix-strategy/<id>.strategy.json
 *
 * From there the EXISTING renderer produces docs/agent_output/04-remediation/fix_plan_<id>.md at
 * Status: Proposed — the same human-approval checkpoint every plan goes through. This skill writes a
 * proposal; it never approves, never writes a diff, never edits real source.
 *
 * Prerequisite: the context bundle must exist (04a's collect-remediation-context.js produces it).
 * Set PIPELINE_CONTEXT_DATA_DIR to run against a bundle in an isolated location (used by the test).
 *
 * Usage:
 *   node scripts/run-fallback.js --issue ISSUE-004
 *   node scripts/run-fallback.js --issue ISSUE-004 --cwe CWE-22   # force which gap CWE to remediate
 *   node scripts/run-fallback.js --issue ISSUE-004 --dry-run      # print, do not write
 */
const fs = require('fs');
const { plans, loadKb, classifyGap, deriveStrategy, pythonAvailable } = require('./lib/fallback');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--cwe') args.cwe = argv[++i];
    else if (a === '--dry-run') args.dryRun = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`04a1 — Remediation-intelligence fallback

  node scripts/run-fallback.js --issue <ISSUE-ID> [--cwe <CWE-ID>] [--dry-run]

Runs only for a catalog gap. Writes <id>.strategy.json for 04a's render-fix-plan.js to render.`);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.issue) throw new Error('Missing --issue <ISSUE-ID>.');

  const contextFile = plans.contextJsonPathFor(args.issue);
  if (!fs.existsSync(contextFile)) {
    throw new Error(`No context bundle at ${plans.rel(contextFile)}.\nRun 04a collect-remediation-context.js --issue ${args.issue} first (or point PIPELINE_CONTEXT_DATA_DIR at one).`);
  }
  const context = JSON.parse(fs.readFileSync(contextFile, 'utf8'));

  // 1. Confirm this really is a gap — refuse if 04a's catalog already covers it.
  const g = classifyGap(context.cwe || [], args.cwe);
  if (!g.warranted) {
    console.error(`04a1 declines: ${args.issue} is not a catalog gap.`);
    console.error(`  detected: ${(context.cwe || []).map((c) => `${c.cwe}${c.inCatalog ? ' (catalogued)' : ' (gap)'}`).join(', ') || 'none'}`);
    console.error('  A catalogued CWE exists -> this is the 04a happy path. The fallback is only for a pure gap.');
    process.exit(2);
  }

  const gapCwe = args.cwe ? args.cwe.toUpperCase() : g.primaryGap;

  // 2. Look the gap CWE up in the local knowledge base.
  const kb = loadKb();
  const kbEntry = kb[gapCwe];
  if (!kbEntry) {
    console.error(`04a1 cannot derive a strategy: ${gapCwe} is a gap in BOTH the catalog and the knowledge base.`);
    console.error('  This is a KB gap. Do not invent a pattern — add a vetted entry to knowledge/remediation-kb.json,');
    console.error('  or escalate to a human. See knowledge/README.md.');
    process.exit(3);
  }

  // 3-6. Search / rank (hybrid keyword+synonym + TF-IDF cosine + local semantic embedding) / derive.
  const strategy = deriveStrategy(context, gapCwe, kbEntry, kb);

  const fmtScore = (r) => `${r.id}(${r.score.toFixed(2)} = kw ${r.keyword_score.toFixed(2)} + tfidf ${r.tfidf_score.toFixed(2)}${r.embedding_score !== null ? ` + emb ${r.embedding_score.toFixed(2)}` : ' + emb n/a'})`;
  console.log(`04a1 fallback — ${args.issue}`);
  console.log(`  gap CWE        : ${gapCwe}  (no catalog entry)`);
  console.log(`  KB entry       : ${kbEntry.title}`);
  console.log(`  local embedding: ${pythonAvailable() ? 'available (.venv found)' : 'NOT set up — ranking falls back to keyword+TF-IDF only (see SKILL.md Setup)'}`);
  console.log(`  ranked fixes   : ${strategy.derived_pattern.ranked_examples.map(fmtScore).join(', ')}`);
  console.log(`  top pattern    : ${strategy.derived_pattern.ranked_examples[0] ? strategy.derived_pattern.ranked_examples[0].pattern : '(none)'}`);
  console.log(`  confidence     : ${strategy.confidence}`);
  console.log(`  sources        : ${strategy.derived_pattern.sources.join(', ')}`);

  if (args.dryRun) {
    console.log('\n--dry-run: strategy NOT written. JSON below:\n');
    console.log(JSON.stringify(strategy, null, 2));
    return;
  }

  const strategyFile = plans.strategyPathFor(args.issue);
  fs.mkdirSync(require('path').dirname(strategyFile), { recursive: true });
  fs.writeFileSync(strategyFile, JSON.stringify(strategy, null, 2));
  console.log(`\n  wrote          : ${plans.rel(strategyFile)}`);
  console.log(`  next           : node ../04a-fix-strategist/scripts/render-fix-plan.js --issue ${args.issue}`);
  console.log('                   -> renders fix_plan_' + args.issue + '.md at Status: Proposed (human approves before the Fixer runs).');
}

try { main(); } catch (err) { console.error('run-fallback failed:', err.message); process.exit(1); }
