#!/usr/bin/env node
/**
 * 04a1 — Self-contained NO-branch test.
 *
 * Runs the fallback end to end against the bundled sample input (fixtures/SAMPLE-CWE22.context.json,
 * a path-traversal issue whose only CWE, CWE-22, is a catalog gap), then renders it with 04a's REAL
 * render-fix-plan.js — all inside a throwaway temp directory, so the repo's real docs/ is never
 * touched (PIPELINE_CONTEXT_DATA_DIR + PIPELINE_OUTPUT_DIR redirect both sides).
 *
 * Part 2 proves the HYBRID ranking actually earns its keep: fixtures/SAMPLE-CWE22-SYNONYM.context.json
 * describes the identical defect but paraphrased so it shares ZERO exact keywords with either
 * historical fix. Under the old keyword-only ranker this scored 0-0 and silently fell back to
 * alphabetical order; the hybrid keyword+synonym / TF-IDF ranker still correctly separates the two
 * candidates via matched_synonyms and tfidf_score alone.
 *
 * Usage:  node scripts/test-sample.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { pythonAvailable } = require('./lib/embeddings');

const SKILL_DIR = path.resolve(__dirname, '..');
const STRATEGIST_DIR = path.resolve(SKILL_DIR, '..', '04a-fix-strategist');
const FIXTURE = path.join(SKILL_DIR, 'fixtures', 'SAMPLE-CWE22.context.json');
const ID = 'SAMPLE-CWE22';
const SYN_FIXTURE = path.join(SKILL_DIR, 'fixtures', 'SAMPLE-CWE22-SYNONYM.context.json');
const SYN_ID = 'SAMPLE-CWE22-SYNONYM';

function run(cmd, args, cwd, env) {
  return execFileSync(cmd, args, { cwd, env, encoding: 'utf8', stdio: 'pipe' });
}

function runScenario(fixture, id, dataDir, outDir, env, line) {
  fs.copyFileSync(fixture, path.join(dataDir, 'fix-strategy', `${id}.context.json`));
  line(run('node', ['scripts/run-fallback.js', '--issue', id], SKILL_DIR, env).trim());
  line(run('node', ['scripts/render-fix-plan.js', '--issue', id], STRATEGIST_DIR, env).trim());

  const planFile = path.join(outDir, `fix_plan_${id}.md`);
  const strategyFile = path.join(dataDir, 'fix-strategy', `${id}.strategy.json`);
  return {
    plan: fs.readFileSync(planFile, 'utf8'),
    strategy: JSON.parse(fs.readFileSync(strategyFile, 'utf8')),
    planFile,
    strategyFile,
  };
}

function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), '04a1-test-'));
  const dataDir = path.join(tmp, 'pipeline-context');
  const outDir = path.join(tmp, 'out');
  fs.mkdirSync(path.join(dataDir, 'fix-strategy'), { recursive: true });
  fs.mkdirSync(outDir, { recursive: true });

  const env = { ...process.env, PIPELINE_CONTEXT_DATA_DIR: dataDir, PIPELINE_OUTPUT_DIR: outDir };
  const line = (s) => console.log(s);
  let ok = true;

  const embeddingsSetUp = pythonAvailable();
  line(`Local embedding model (.venv): ${embeddingsSetUp ? 'found — embedding_score will be a real number' : 'NOT found — embedding_score will be null; ranking still works on keyword + TF-IDF'}`);

  // ---------------------------------------------------------------------
  // Part 1 — the original NO-branch happy path.
  // ---------------------------------------------------------------------
  line('======================================================================');
  line(' Part 1 — 04a1 NO-branch: fixtures/SAMPLE-CWE22.context.json');
  line('======================================================================\n');

  line('[1/3] Detect the gap for CWE-22 (is it catalogued?)');
  line(run('node', ['scripts/detect-gap.js', '--cwe', 'CWE-22'], SKILL_DIR, env).trim());

  line('\n[2/3] Run the fallback -> derive a grounded strategy');
  line('\n[3/3] Render with 04a\'s real render-fix-plan.js (isolated output dir)');
  const p1 = runScenario(FIXTURE, ID, dataDir, outDir, env, line);

  const ranked1 = p1.strategy.derived_pattern.ranked_examples;
  const checks1 = [
    ['strategy.json written', fs.existsSync(p1.strategyFile)],
    ['plan rendered', fs.existsSync(p1.planFile)],
    ['Status is Proposed', /\*\*Status\*\*\s*\|\s*Proposed/.test(p1.plan)],
    ['flagged as catalog gap', /catalog gap/i.test(p1.plan)],
    ['CWE-22 named', /CWE-22/.test(p1.plan)],
    ['provenance visible (04a1 fallback)', /04a1/i.test(p1.plan) && /Derived/i.test(p1.plan)],
    ['confidence Low', /\*\*Confidence\*\*\s*\|\s*Low/.test(p1.plan)],
    ['appendix marks gap, not a false catalog entry', /no catalog entry \(gap\)/.test(p1.plan) && !/cwe-patterns\.json`, entry/.test(p1.plan)],
    ['appendix cites derived sources', /Derived remediation \| 04a1 fallback/.test(p1.plan) && /knowledge\/refs\/path-traversal\.md/.test(p1.plan)],
    ['ranked_examples carries hybrid score breakdown', ranked1.every((r) => typeof r.score === 'number' && typeof r.keyword_score === 'number' && typeof r.tfidf_score === 'number')],
    ['embedding_score present as number-or-null on every candidate', ranked1.every((r) => r.embedding_score === null || typeof r.embedding_score === 'number')],
    ['embedding_score is a real number when the venv is set up', !embeddingsSetUp || ranked1.every((r) => typeof r.embedding_score === 'number')],
    ['HF-PATH-001 ranked first (exact keywords dominate here)', ranked1[0].id === 'HF-PATH-001'],
    ['top score strictly higher than runner-up (real ranking, not a tie)', ranked1[0].score > ranked1[1].score],
  ];

  line('\n----------------------------------------------------------------------');
  line(' Part 1 assertions');
  line('----------------------------------------------------------------------');
  for (const [name, pass] of checks1) { line(`  ${pass ? 'PASS' : 'FAIL'}  ${name}`); if (!pass) ok = false; }

  // ---------------------------------------------------------------------
  // Part 2 — proves the HYBRID signal, not just the keyword upgrade: the same defect, described with
  // ZERO exact keyword overlap against either historical fix. Under the old keyword-only ranker both
  // candidates scored 0 and the "winner" was just alphabetical order. The hybrid ranker must still
  // correctly separate them via matched_synonyms + tfidf_score alone.
  // ---------------------------------------------------------------------
  line('\n======================================================================');
  line(' Part 2 — hybrid proof: fixtures/SAMPLE-CWE22-SYNONYM.context.json');
  line(' (identical defect, paraphrased so NO exact keyword overlaps either fix)');
  line('======================================================================\n');

  const p2 = runScenario(SYN_FIXTURE, SYN_ID, dataDir, outDir, env, line);
  const ranked2 = p2.strategy.derived_pattern.ranked_examples;
  const top2 = ranked2[0];

  const checks2 = [
    ['strategy.json written', fs.existsSync(p2.strategyFile)],
    ['plan rendered', fs.existsSync(p2.planFile)],
    ['zero exact keyword overlap on the top candidate (proves this is NOT a keyword-only win)', top2.matched_keywords.length === 0],
    ['synonym matches present (the keyword_synonyms map fired)', top2.matched_synonyms.length > 0],
    ['tfidf_score > 0 (TF-IDF cosine also contributed)', top2.tfidf_score > 0],
    ['embedding_score > 0 when the venv is set up (real semantic similarity also contributed)', !embeddingsSetUp || ranked2.every((r) => typeof r.embedding_score === 'number' && r.embedding_score > 0)],
    ['HF-PATH-001 still ranked first despite zero exact overlap', top2.id === 'HF-PATH-001'],
    ['top score strictly higher than runner-up (a real decision, not an alphabetical fallback)', ranked2[0].score > ranked2[1].score],
    ['Status is Proposed', /\*\*Status\*\*\s*\|\s*Proposed/.test(p2.plan)],
    ['confidence Low', /\*\*Confidence\*\*\s*\|\s*Low/.test(p2.plan)],
  ];

  line('\n----------------------------------------------------------------------');
  line(' Part 2 assertions');
  line('----------------------------------------------------------------------');
  for (const [name, pass] of checks2) { line(`  ${pass ? 'PASS' : 'FAIL'}  ${name}`); if (!pass) ok = false; }

  line('\n----------------------------------------------------------------------');
  line(' Part 2 ranked_examples (raw, from strategy.json)');
  line('----------------------------------------------------------------------');
  line(JSON.stringify(ranked2, null, 2));

  line('\n----------------------------------------------------------------------');
  line(`Temp workspace (safe to delete): ${tmp}`);
  line(ok ? 'RESULT: PASS — NO branch produced grounded, Proposed, provenance-carrying plans, and the\n                hybrid ranker correctly ranks candidates even with zero exact keyword overlap.'
         : 'RESULT: FAIL — see assertions above.');
  if (!ok) process.exit(1);
}

try { main(); } catch (err) {
  console.error('test-sample failed:', err.message);
  if (err.stdout) console.error('stdout:', err.stdout.toString());
  if (err.stderr) console.error('stderr:', err.stderr.toString());
  process.exit(1);
}
