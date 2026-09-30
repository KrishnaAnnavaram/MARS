#!/usr/bin/env node
/**
 * 04a2 — self-contained end-to-end test.
 *
 * Runs the full 04a2 path on the bundled double-gap fixture (SAMPLE-CWE502) — assemble strategy ->
 * render with 04a's REAL render-fix-plan.js -> Proposed plan — inside a throwaway temp dir, so the
 * repo is never touched. Also proves the SAFE-STOP path (SAMPLE-THIN, insufficient_evidence).
 *
 * Usage:  node scripts/test-sample.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const SKILL_DIR = path.resolve(__dirname, '..');
const STRATEGIST_DIR = path.resolve(SKILL_DIR, '..', '04a-fix-strategist');
const ID = 'SAMPLE-CWE502';

function run(cmd, args, cwd, env) { return execFileSync(cmd, args, { cwd, env, encoding: 'utf8', stdio: 'pipe' }); }
function line(s) { console.log(s); }

function main() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), '04a2-test-'));
  const dataDir = path.join(tmp, 'pipeline-context');
  const outDir = path.join(tmp, 'out');
  fs.mkdirSync(path.join(dataDir, 'fix-strategy'), { recursive: true });
  fs.mkdirSync(outDir, { recursive: true });
  const env = { ...process.env, PIPELINE_CONTEXT_DATA_DIR: dataDir, PIPELINE_OUTPUT_DIR: outDir };

  // Stand in for the collect step: a context.json the renderer needs, built from the understanding.
  const u = JSON.parse(fs.readFileSync(path.join(SKILL_DIR, 'fixtures', `${ID}.understanding.json`), 'utf8'));
  const context = {
    id: u.issue_id, title: u.title, generatedAt: new Date().toISOString(),
    sources: {
      rootCauseReport: u.evidence_sources.root_cause_report,
      blastRadiusReport: u.evidence_sources.blast_radius_report,
      issueFile: u.evidence_sources.issue_register,
    },
  };
  fs.writeFileSync(path.join(dataDir, 'fix-strategy', `${ID}.context.json`), JSON.stringify(context, null, 2));

  line('======================================================================');
  line(' 04a2 end-to-end test — double gap (SAMPLE-CWE502) -> research -> Proposed');
  line('======================================================================\n');

  line('[1/3] Assemble the novel-research strategy');
  line(run('node', ['scripts/generate-strategy.js', '--fixture', ID], SKILL_DIR, env).split('\n').slice(-4).join('\n'));

  line('\n[2/3] Render with 04a\'s real render-fix-plan.js');
  line(run('node', ['scripts/render-fix-plan.js', '--issue', ID], STRATEGIST_DIR, env).trim());

  line('\n[3/3] Evidence-gap path (insufficient evidence) — now a Proposed plan, not a dead stop');
  const uThin = JSON.parse(fs.readFileSync(path.join(SKILL_DIR, 'fixtures', 'SAMPLE-THIN.understanding.json'), 'utf8'));
  fs.writeFileSync(path.join(dataDir, 'fix-strategy', 'SAMPLE-THIN.context.json'), JSON.stringify({
    id: uThin.issue_id, title: uThin.title, generatedAt: new Date().toISOString(),
    sources: { rootCauseReport: uThin.evidence_sources.root_cause_report, blastRadiusReport: uThin.evidence_sources.blast_radius_report, issueFile: uThin.evidence_sources.issue_register },
  }, null, 2));
  line(run('node', ['scripts/generate-strategy.js', '--fixture', 'SAMPLE-THIN'], SKILL_DIR, env).split('\n').filter((l) => /EVIDENCE GAP|insufficient|wrote|render/.test(l)).join('\n'));
  run('node', ['scripts/render-fix-plan.js', '--issue', 'SAMPLE-THIN'], STRATEGIST_DIR, env);

  const planFile = path.join(outDir, `fix_plan_${ID}.md`);
  const plan = fs.readFileSync(planFile, 'utf8');
  const thinPlanFile = path.join(outDir, 'fix_plan_SAMPLE-THIN.md');
  const thinPlan = fs.existsSync(thinPlanFile) ? fs.readFileSync(thinPlanFile, 'utf8') : '';

  const checks = [
    ['strategy written', fs.existsSync(path.join(dataDir, 'fix-strategy', `${ID}.strategy.json`))],
    ['plan rendered', fs.existsSync(planFile)],
    ['Status: Proposed', /\*\*Status\*\*\s*\|\s*Proposed/.test(plan)],
    ['Remediation source = 04a2', /Remediation source\s*\|\s*04a2/.test(plan)],
    ['CWE-502 catalog gap', /CWE-502.*catalog gap/.test(plan)],
    ['Confidence Low', /\*\*Confidence\*\*\s*\|\s*Low/.test(plan)],
    ['has Security threat section', /Novel research — security threat/.test(plan)],
    ['has Security objective section', /security objective/i.test(plan)],
    ['has >=2 candidates', (plan.match(/CAND-\d+/g) || []).length >= 2],
    ['provenance: NOT AVAILABLE + no citation', /NOT AVAILABLE/.test(plan) && /no citation claimed/.test(plan)],
    ['no auto-approve / no diff', !/Status\W+Approved/.test(plan) && !/```diff/.test(plan)],
    ['evidence-gap plan rendered @ Proposed', fs.existsSync(thinPlanFile) && /\*\*Status\*\*\s*\|\s*Proposed/.test(thinPlan)],
    ['evidence-gap plan flags EVIDENCE GAP + what is missing', /EVIDENCE GAP/i.test(thinPlan) && /not a confident fix/i.test(thinPlan) && /Missing evidence|what evidence is/i.test(thinPlan)],
  ];

  line('\n----------------------------------------------------------------------');
  line(' Assertions');
  line('----------------------------------------------------------------------');
  let ok = true;
  for (const [n, p] of checks) { line(`  ${p ? 'PASS' : 'FAIL'}  ${n}`); if (!p) ok = false; }

  line('\n----------------------------------------------------------------------');
  line(` Rendered plan (fix_plan_${ID}.md — isolated copy)`);
  line('----------------------------------------------------------------------\n');
  line(plan);

  line(`\nTemp workspace (safe to delete): ${tmp}`);
  line(ok ? 'RESULT: PASS — 04a2 produced a Proposed, novel-research plan; safe-stop works.'
         : 'RESULT: FAIL — see assertions.');
  if (!ok) process.exit(1);
}

try { main(); } catch (err) {
  console.error('test-sample failed:', err.message);
  if (err.stdout) console.error(err.stdout.toString());
  if (err.stderr) console.error(err.stderr.toString());
  process.exit(1);
}
