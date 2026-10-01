'use strict';
/**
 * Ledger events for MARS's deterministic decision points: fix verification (04b/04c), the QA and
 * build gates (06a/06b) and merge-arbiter scoring (07a). Called by those scripts AFTER they have
 * written their own result record; it reads that record and emits a witness event. It never
 * modifies a record, a report, an exit code or any pipeline input, and it never throws.
 *
 * What it adds beyond the record itself (proposal §8 gaps G2, G5, G6, G12):
 *   - duration (record.generatedAt → now), base commit (HEAD), sha256 of the inputs consumed,
 *   - a coarse, deterministic failure class derived from the gate's own stage/exit facts.
 * It does NOT decide whether a failure was caused by the environment: that needs a baseline build
 * (proposal §50, change C5) and is reported as `unclassified` / `compile.error` until then.
 */
const path = require('path');
const ledger = require('./ledger');

const ROOT = ledger.REPO_ROOT;

function abs(rel) {
  return rel ? path.resolve(ROOT, rel) : null;
}

function ref(rel) {
  if (!rel) return null;
  return { path: rel.replace(/\\/g, '/'), sha256: ledger.fileSha256(abs(rel)) };
}

function durationSince(iso) {
  const t = Date.parse(iso || '');
  return Number.isFinite(t) ? Math.max(0, Date.now() - t) : undefined;
}

const INFRA_STAGES = new Set(['worktree-create', 'module-detection']);
const APPLY_STAGES = /apply/;
const COMPILE_SIGNS = /COMPILATION ERROR|cannot find symbol|\[ERROR\][^\n]*\.java:\[\d+,\d+\]/;

/** Coarse class from facts the gate already recorded. Never "environment" — see header. */
function failureClass(record, kind) {
  if (record.refused) return { class: 'refused', code: 'REFUSED' };
  if (record.passed) return null;
  const stage = record.stage || '';
  if (INFRA_STAGES.has(stage)) return { class: 'infrastructure', code: stage.toUpperCase().replace(/-/g, '_') };
  if (APPLY_STAGES.test(stage)) return { class: 'patch.apply', code: stage.toUpperCase().replace(/-/g, '_') };
  const outputs = []
    .concat(record.output || [])
    .concat((record.steps || []).map((s) => s.output || ''))
    .join('\n');
  if (COMPILE_SIGNS.test(outputs)) return { class: 'compile.error', code: 'COMPILE_ERROR' };
  if (kind === 'qa') return { class: 'qa.test_failed', code: 'TEST_FAILED' };
  if (kind === 'build') return { class: 'build.failed', code: 'EXIT_NONZERO' };
  return { class: 'unclassified', code: 'EXIT_NONZERO' };
}

function common(scriptId, skillId, stageId, stepKind, issueIds) {
  const run = ledger.currentRun();
  const traceId = run.trace_id || ledger.deterministicId(32, ledger.workspaceId(), run.run_id || run.session_id || 'standalone');
  return {
    source: { emitter: 'script', id: scriptId, runtime: process.env.CLAUDECODE === '1' ? 'claude-code' : 'cli' },
    session_id: run.session_id || undefined,
    run_id: run.run_id || undefined,
    trace_id: traceId,
    span_id: ledger.deterministicId(16, traceId, scriptId, issueIds.join(','), Date.now(), process.pid),
    parent_span_id: run.span_id || undefined,
    agent_id: run.agent_id || undefined,
    skill_id: skillId,
    script_id: scriptId,
    stage_id: stageId,
    step_kind: stepKind,
    issue_ids: issueIds,
    actor: { kind: 'script', id: scriptId },
    provenance: 'computed',
    attrs: { run_attribution: run.attribution },
  };
}

function safe(fn) {
  return (...args) => {
    try {
      if (ledger.isDisabled()) return null;
      return fn(...args);
    } catch (_) {
      return null;
    }
  };
}

/** 04b verify-patch / 04c apply-version-bump. */
const fixVerified = safe((scriptId, record, plan) => {
  const skillId = scriptId.split('/')[0];
  const outcome = record.refused ? 'Refused' : (record.passed ? 'Compiled' : 'Compile Failed');
  const c = common(scriptId, skillId, 'fix', 'gate', [record.id]);
  const steps = Array.isArray(record.steps) ? record.steps : [];
  return ledger.append({
    ...c,
    type: record.refused ? 'fix.refused' : 'fix.verified',
    status: record.refused ? 'refused' : 'completed',
    outcome,
    failure: record.passed ? undefined : failureClass(record, 'fix'),
    duration_ms: durationSince(record.generatedAt),
    inputs: [ref(record.patchFile), ref(plan && (plan.relativePlanFile || plan.planFile && path.relative(ROOT, plan.planFile)))].filter(Boolean),
    outputs: [],
    attrs: {
      ...c.attrs,
      stage: record.stage || null,
      level: record.level || null,
      base_commit: ledger.headCommit(),
      exit_codes: steps.map((s) => ({ step: s.command || s.step || s.module || null, exit_code: Number.isInteger(s.exitCode) ? s.exitCode : null })),
      refusal: record.refused ? String(record.reason || '').slice(0, 200) : undefined,
    },
  });
});

/** 06a run-qa-gate (kind 'qa') / 06b run-build-gate (kind 'build'). */
const gateCompleted = safe((kind, record, fix) => {
  const scriptId = kind === 'qa' ? '06a-qa-runner/run-qa-gate' : '06b-build-gatekeeper/run-build-gate';
  const outcome = record.refused ? 'Refused' : (record.passed ? 'Passed' : 'Failed');
  const c = common(scriptId, scriptId.split('/')[0], kind, 'gate', [record.id]);
  const steps = Array.isArray(record.steps) ? record.steps : [];
  return ledger.append({
    ...c,
    type: 'gate.completed',
    status: record.refused ? 'refused' : 'completed',
    outcome,
    failure: record.passed ? undefined : failureClass(record, kind),
    duration_ms: durationSince(record.generatedAt),
    inputs: [ref(fix && fix.patchFile), kind === 'qa' ? ref(`.claude/.pipeline-context/qa/${record.id}.new-test.diff`) : null].filter(Boolean),
    outputs: [ref(`.claude/.pipeline-context/${kind}/${record.id}.result.json`)].filter(Boolean),
    attrs: {
      ...c.attrs,
      stage: record.stage || null,
      base_commit: ledger.headCommit(),
      modules: record.modules || [],
      changed_files: Array.isArray(record.changedFiles) ? record.changedFiles.length : null,
      steps: steps.map((s) => ({ module: s.module || null, test: s.test || null, status: s.status || null, exit_code: Number.isInteger(s.exitCode) ? s.exitCode : null })),
      dependency_drift: Array.isArray(record.dependencyChecks) ? record.dependencyChecks.some((d) => (d.added || []).length || (d.removed || []).length) : null,
    },
  });
});

/** 07a compute-score: binds the computed decision to the exact bytes it scored. */
const verdictComputed = safe((record) => {
  const scoring = '.claude/skills/07a-merge-arbiter/scoring.json';
  const upstream = record.upstream || {};
  const c = common('07a-merge-arbiter/compute-score', '07a-merge-arbiter', 'verdict', 'score', [record.id]);
  return ledger.append({
    ...c,
    type: 'verdict.computed',
    status: 'completed',
    outcome: record.computedDecision,
    inputs: ['rescan', 'redteam', 'behavior', 'qa', 'build'].map((k) => (upstream[k] && upstream[k].file ? ref(upstream[k].file) : null)).filter(Boolean),
    outputs: [ref(`.claude/.pipeline-context/merge/${record.id}.score.json`)].filter(Boolean),
    attrs: {
      ...c.attrs,
      score: record.score,
      threshold: record.threshold,
      severity: record.severity || null,
      hard_gates: (record.gates || []).map((g) => g.gate),
      breakdown: Object.fromEntries(Object.entries(record.breakdown || {}).map(([k, v]) => [k, `${v.verdict}:${v.points}`])),
      verdicts: Object.fromEntries(Object.entries(upstream).map(([k, v]) => [k, v.verdict])),
      policy_sha256: ledger.fileSha256(abs(scoring)),
      base_commit: ledger.headCommit(),
    },
  });
});

module.exports = { fixVerified, gateCompleted, verdictComputed, failureClass };
