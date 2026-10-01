/**
 * Version Migration — the per-run summary.
 *
 * Every 04D script ends by calling `finalizeRun` from a `finally`, so MIGRATION_SUMMARY.md and
 * migration-summary.json exist for every run — PASS, PARTIAL PASS, FAIL, BLOCKED, or a run that
 * stopped halfway (IN_PROGRESS, naming the last state the evidence proves). The summary is built
 * only from the session's evidence files (baseline, plan, rounds, transformations, runtime records,
 * the judgement file, state.json); nothing in it is written by the agent after the fact.
 *
 * Output: <MIGRATION_REPORT_DIR or docs/agent_output/04-remediation>/migration-runs/<run_id>/
 *
 * For an issue-linked session (Stage 2 of 04_fix-generator), the same step writes the standard
 * fix_<id>.md handoff once the migration is finished or blocked (see handoff.js).
 */
const fs = require('fs');
const path = require('path');
const {
  PATHS, sessionPaths, readJson, writeJson, rel, run, listRounds, listTransformations, readState, inferState,
  changedSince, isSandboxRepo, inventoryProject, compareProbeRecords, scanEndpoints, compareEndpoints,
} = require('./migration');

const RUNS_DIR = path.join(PATHS.OUT_DIR, 'migration-runs');
const PACKAGING_INTENTS = ['package', 'verify', 'package-skip-tests'];
const TEST_INTENTS = ['test', 'package', 'verify'];

let notedSlug = null;
/** Lets a script that derives its slug itself (detect-baseline.js) tell the finalizer which session it touched. */
function noteSession(slug) { notedSlug = slug; }

function newRunId(slug) {
  return `${slug}-${new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')}`;
}

function gitFact(dir, args) {
  if (!dir || !fs.existsSync(dir)) return null;
  const r = run('git', ['-C', dir, ...args]);
  return r.status === 0 ? (r.stdout || '').trim() || null : null;
}

function testTotals(round) {
  if (!round || !TEST_INTENTS.includes(round.build.intent)) return null;
  const matches = [...String(round.log_tail || '').matchAll(/Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)(?:,\s*Skipped:\s*(\d+))?/g)];
  if (!matches.length) return null;
  const last = matches[matches.length - 1];
  const t = { round: round.round, total: Number(last[1]), failed: Number(last[2]), errors: Number(last[3]), skipped: Number(last[4] || 0) };
  t.passed = Math.max(0, t.total - t.failed - t.errors - t.skipped);
  return t;
}

function dependencyChanges(baseline, workspace) {
  if (!baseline || !workspace || !fs.existsSync(workspace)) return [];
  let after;
  try { after = inventoryProject(workspace); } catch { return []; }
  const key = (d) => `${d.groupId}:${d.artifactId}`;
  const out = [];
  const beforeParent = baseline.platform && baseline.platform.parent;
  if (beforeParent && after.parent && beforeParent.version !== after.parent.version) {
    out.push({ dependency: `${key(beforeParent)} (parent)`, old: beforeParent.version, new: after.parent.version, reason: 'platform parent' });
  }
  const before = new Map(((baseline.platform && baseline.platform.dependencies) || []).map((d) => [`${key(d)}@${d.scope || ''}`, d]));
  const now = new Map((after.dependencies || []).map((d) => [`${key(d)}@${d.scope || ''}`, d]));
  for (const [k, d] of now) {
    const b = before.get(k);
    if (!b) out.push({ dependency: key(d), old: null, new: d.version || '(managed)', reason: 'added' });
    else if ((b.version || null) !== (d.version || null)) out.push({ dependency: key(d), old: b.version || '(managed)', new: d.version || '(managed)', reason: 'version changed' });
  }
  for (const [k, b] of before) if (!now.has(k)) out.push({ dependency: key(b), old: b.version || '(managed)', new: null, reason: 'removed' });
  // Build plugins too (including those inside profiles): a coverage or compiler plugin bump is a dependency change.
  const pluginKey = (p) => `${p.groupId || 'org.apache.maven.plugins'}:${p.artifactId}`;
  const pluginsBefore = new Map(((baseline.platform && baseline.platform.plugins) || []).filter((p) => p.version).map((p) => [pluginKey(p), p.version]));
  for (const p of (after.plugins || []).filter((x) => x.version)) {
    const old = pluginsBefore.get(pluginKey(p));
    if (old && old !== p.version) out.push({ dependency: `${pluginKey(p)} (plugin)`, old, new: p.version, reason: 'plugin version changed' });
  }
  const bp = (baseline.platform && baseline.platform.properties) || {};
  const ap = after.properties || {};
  for (const p of new Set([...Object.keys(bp), ...Object.keys(ap)])) {
    if (bp[p] !== ap[p] && /version|java/i.test(p)) out.push({ dependency: `property ${p}`, old: bp[p] === undefined ? null : bp[p], new: ap[p] === undefined ? null : ap[p], reason: 'build property' });
  }
  return out;
}

function fileChanges(paths, meta, migration) {
  if (!meta || !isSandboxRepo(paths.workspace)) return [];
  let changed = [];
  try { changed = changedSince(paths.workspace, meta.baseline_commit) || []; } catch { return []; }
  // A file is usually changed by several steps (a recipe, then a residual repair): keep all of them.
  const notes = new Map();
  for (const c of (migration && migration.code_changes) || []) {
    for (const f of [].concat(c.file || c.files || [])) {
      const k = String(f).replace(/\\/g, '/');
      notes.set(k, [...(notes.get(k) || []), c]);
    }
  }
  return changed.map((c) => {
    const file = c.file || c.path || String(c);
    const entries = notes.get(file) || ([...notes.entries()].find(([k]) => file.endsWith(k) || k.endsWith(file)) || [])[1] || [];
    const uniq = (xs) => [...new Set(xs.filter(Boolean))];
    return {
      file,
      change: { A: 'added', M: 'modified', D: 'deleted', R: 'renamed' }[String(c.state || '').charAt(0)] || c.state || 'changed',
      tool: uniq(entries.map((e) => e.origin)).join(' + ') || null,
      reason: uniq(entries.map((e) => e.why || e.summary || e.description || e.evidence)).join(' · ') || null,
    };
  });
}

function deriveStatus(ctx) {
  const { state, inferred, rounds, baseline, migration, comparison, tests, finalDeclared, requested } = ctx;
  if (baseline && baseline.issue && baseline.issue.refused) return { status: 'BLOCKED', reason: `Stage 2 refused: ${baseline.issue.refused}` };
  if (state && state.state === 'BLOCKED') return { status: 'BLOCKED', reason: state.reason || 'session blocked' };
  if (baseline && baseline.migration_path && baseline.migration_path.status && baseline.migration_path.status !== 'SUPPORTED') {
    return { status: 'BLOCKED', reason: baseline.migration_path.reason || baseline.migration_path.status };
  }
  if (state && state.state === 'FAILED') return { status: 'FAIL', reason: state.reason || 'session failed' };
  const round0 = rounds.find((r) => r.baseline || r.round === 0);
  if (round0 && !['passed', 'tests-failed', 'build-failed'].includes(round0.outcome)) return { status: 'FAIL', reason: `round 0 (the untouched project) did not compile: ${round0.outcome}` };
  if (!ctx.finished) {
    return { status: 'IN_PROGRESS', reason: `stopped after ${inferred.state} — the migration has not been rendered; if the run ends here it is incomplete` };
  }
  const last = rounds[rounds.length - 1];
  const reasons = [];
  const compiled = last && ['passed', 'tests-failed', 'build-failed'].includes(last.outcome);
  if (!compiled) return { status: 'FAIL', reason: `final round ${last ? last.round : '?'} did not compile on the target (${last ? last.outcome : 'no rounds'})` };
  if (requested && finalDeclared && finalDeclared !== requested) return { status: 'FAIL', reason: `final build declares ${finalDeclared}, not the requested ${requested}` };
  if (tests && tests.worse) return { status: 'FAIL', reason: `${tests.after.failed + tests.after.errors - tests.before.failed - tests.before.errors} new test failure(s) against round 0` };
  const diffs = (migration && migration.behaviour && migration.behaviour.differences) || [];
  // A changed HTTP status is a contract break for clients. It FAILs the run unless the agent read both
  // responses and classified it as an expected framework change (e.g. Boot 3's trailing-slash
  // matching) — and even then the run is only a PARTIAL PASS: a human must accept the change.
  const statusRows = comparison ? comparison.rows.filter((r) => r.verdict === 'status-differs') : [];
  const unaccepted = statusRows.filter((r) => r.classification !== 'expected-framework-change');
  if (unaccepted.length) return { status: 'FAIL', reason: `${unaccepted.length} probe(s) changed HTTP status after the migration with no expected-framework-change classification: ${unaccepted.map((r) => r.name).join(', ')}` };
  if (diffs.some((d) => d.classification === 'regression')) return { status: 'FAIL', reason: 'a behavioural difference is classified as a regression' };
  if (last.outcome === 'build-failed') return { status: 'FAIL', reason: `final round ${last.round} compiled but a build plugin failed — the artifact was not produced` };
  if (statusRows.length) reasons.push(`${statusRows.length} client-visible HTTP status change(s) classified as expected framework changes need human acceptance: ${statusRows.map((r) => r.name).join(', ')}`);
  const lost = ctx.endpoints
    ? [...new Set([...(ctx.endpoints.static.missing || []), ...((ctx.endpoints.runtime && ctx.endpoints.runtime.missing) || [])])]
    : [];
  if (lost.length) return { status: 'FAIL', reason: `${lost.length} endpoint(s) the application served before are gone: ${lost.join(', ')}` };
  const openEdges = (ctx.edges || []).filter((e) => !e.complete);
  if (openEdges.length) return { status: 'FAIL', reason: `the planned path is not finished — edge(s) ${openEdges.map((e) => e.id).join(', ')} never built green on their planned version` };
  if (last.outcome !== 'passed' && !(tests && !tests.worse)) reasons.push(`final round ended ${last.outcome} without a comparable round-0 test baseline`);
  if (!PACKAGING_INTENTS.includes(last.build.intent)) reasons.push(`final round goal is ${last.build.intent}, not a packaging goal`);
  if (!requested) reasons.push('no exact target was requested, so reaching it cannot be checked');
  if (!comparison || !comparison.total) reasons.push('runtime behaviour was not compared before/after');
  if (diffs.some((d) => d.classification === 'unexplained')) reasons.push('a behavioural difference is unexplained');
  const open = ((migration && migration.blocking_conditions) || []).filter((c) => c.status === 'open');
  if (open.length) reasons.push(`${open.length} open blocking condition(s) in migration.json: ${open.map((c) => c.condition).join('; ')}`);
  if (!tests || !tests.before || !tests.after) reasons.push('the test suite was not run on both sides');
  return reasons.length ? { status: 'PARTIAL PASS', reason: reasons.join('; ') } : { status: 'PASS', reason: 'green on the requested target, no new test failures, behaviour compared with no status changes' };
}

function buildSummary(slug, { assumeRendered = false } = {}) {
  const paths = sessionPaths(slug);
  const baseline = readJson(paths.baseline);
  const state = readState(slug);
  const inferred = inferState(slug);
  const plan = readJson(paths.plan);
  const migration = readJson(paths.migration);
  const meta = readJson(paths.workspaceMeta);
  const rounds = listRounds(slug);
  const transformations = listTransformations(slug);
  const runtimeBaseline = readJson(path.join(paths.runtimeDir, 'baseline.json'));
  const runtimeFinal = readJson(path.join(paths.runtimeDir, 'final.json'));
  const history = (state && state.history) || [];
  // A session started before run ids existed is filed under the time its first state was recorded.
  const firstAt = history.length ? history[0].at : (baseline && baseline.generated_at) || null;
  const runId = (state && state.run_id) || (baseline && baseline.run_id)
    || `${slug}-${firstAt ? firstAt.replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z') : 'unrecorded'}`;
  const startedAt = (state && state.run_started_at) || (history.length ? history[0].at : (baseline && baseline.generated_at) || null);
  const lastAt = history.length ? history[history.length - 1].at : null;
  const mp = (baseline && baseline.migration_path) || {};
  const requested = baseline && baseline.target && baseline.target.platform ? baseline.target.platform.version : null;
  const last = rounds[rounds.length - 1];
  const finalDeclared = last && last.declared
    ? ((last.declared.platform && last.declared.platform.version) || (last.declared.parent && last.declared.parent.version) || null)
    : null;
  const round0 = rounds.find((r) => r.baseline || r.round === 0);
  const before = testTotals(round0) || testTotals(rounds.find((r) => TEST_INTENTS.includes(r.build.intent)));
  const after = [...rounds].reverse().map(testTotals).find((t) => t && (!before || t.round !== before.round)) || null;
  const tests = before || after ? { before, after, worse: Boolean(before && after && (after.failed + after.errors) > (before.failed + before.errors)) } : null;
  const comparison = runtimeBaseline && runtimeFinal ? compareProbeRecords(runtimeBaseline, runtimeFinal, (migration && migration.behaviour && migration.behaviour.differences) || []) : null;

  // Migration-path edges (ladder sessions): what each rung planned, ran and reached.
  const edges = ((baseline && baseline.migration_path && baseline.migration_path.edges) || []).map((e) => {
    const ts = transformations.filter((t) => t.edge === e.id);
    const rs = rounds.filter((r) => r.edge === e.id);
    const done = rs.find((r) => ['passed', 'tests-failed', 'build-failed'].includes(r.outcome) && r.declared
      && ((r.declared.platform && r.declared.platform.version === e.to) || (r.declared.parent && r.declared.parent.version === e.to)));
    return {
      id: e.id, class: e.class, role: e.role, from: e.from, to: e.to, java: e.java, jdk: e.jdk ? e.jdk.version : null,
      recipe_source: e.recipe_source, recipe: e.recipe || e.composite || null, stack: e.stack, license: e.license,
      open_source: e.open_source, cloud_train: e.cloud_train, pack: e.pack, notes: e.notes || [],
      previews: ts.filter((t) => t.mode === 'dry-run').map((t) => `${t.id} ${t.status} (${(t.proposed_files || []).length} files)`),
      applies: ts.filter((t) => t.mode === 'apply').map((t) => `${t.id} ${t.status} (${(t.changed_files || []).length} files)`),
      rounds: rs.map((r) => `R${r.round} ${r.build.intent} ${r.outcome}`),
      complete: Boolean(done),
      completed_by: done ? `R${done.round}` : null,
    };
  });
  // Endpoint preservation: the source's mappings before vs the sandbox now; the running app before vs after.
  const staticBefore = (baseline && baseline.observations && baseline.observations.endpoints) || null;
  const staticAfter = staticBefore && meta && isSandboxRepo(paths.workspace) ? scanEndpoints(paths.workspace) : null;
  const inv = (record) => (record && record.endpoint_inventory && record.endpoint_inventory.available ? record.endpoint_inventory.endpoints : null);
  const rtBefore = inv(runtimeBaseline);
  const rtAfter = inv(runtimeFinal);
  const asObjects = (keys) => keys.map((k) => ({ method: k.split(' ')[0], path: k.slice(k.indexOf(' ') + 1) }));
  const endpoints = staticBefore ? {
    static: staticAfter
      ? compareEndpoints(staticBefore, staticAfter)
      : { before: staticBefore.length, after: null, preserved: [], missing: [], added: [], note: 'no sandbox to compare yet' },
    runtime: rtBefore && rtAfter
      ? { ...compareEndpoints(asObjects(rtBefore), asObjects(rtAfter)), source: 'actuator /mappings' }
      : { source: 'actuator /mappings', missing: [], note: rtBefore || rtAfter ? 'only one side exposed /actuator/mappings' : '/actuator/mappings not exposed or not probed — static inventory only' },
  } : null;
  // The report renders before the RENDERED state is recorded; it asks for the status as if rendered.
  const finished = assumeRendered || inferred.reached.includes('RENDERED');
  const { status, reason } = deriveStatus({
    state, inferred, rounds, baseline, migration, comparison, tests, finalDeclared, requested,
    endpoints: finished ? endpoints : null, edges: finished ? edges : [], finished,
  });
  const projectDir = baseline && baseline.project ? baseline.project.dir : null;
  const platformName = (baseline && baseline.target && baseline.target.platform && baseline.target.platform.name)
    || (mp.stack) || ((baseline && baseline.reference_pack_eligibility && baseline.reference_pack_eligibility[0]) || {}).stack || null;
  const sourcePlatform = (mp.platform && mp.platform.version) || mp.source || ((baseline && baseline.platform && baseline.platform.parent) || {}).version || null;
  const reportMd = paths.reportMd;
  const reportDiff = paths.reportDiff;
  const runDir = path.join(RUNS_DIR, runId);

  const summary = {
    schema: 'migration-summary/1',
    run_id: runId,
    slug,
    generated_at: new Date().toISOString(),
    final_status: status,
    status_reason: reason,
    state: inferred.state,
    states_reached: inferred.reached,
    metadata: {
      started_at: startedAt,
      last_transition_at: lastAt,
      duration_ms: startedAt && lastAt ? Date.parse(lastAt) - Date.parse(startedAt) : null,
      input_location: projectDir,
      source_commit: gitFact(projectDir, ['rev-parse', 'HEAD']),
      working_branch: gitFact(projectDir, ['rev-parse', '--abbrev-ref', 'HEAD']),
      worktree: gitFact(projectDir, ['rev-parse', '--show-toplevel']),
      output_location: rel(paths.root),
      selected_pack: baseline && baseline.reference_packs && baseline.reference_packs[0] ? baseline.reference_packs[0].id : null,
      agent04_issue_id: baseline && baseline.issue ? baseline.issue.id : null,
      approval_mode: baseline && baseline.issue ? baseline.issue.approval_mode : 'n/a — direct request, not routed by Agent 04',
    },
    versions: {
      source_platform_name: platformName,
      source_platform: sourcePlatform,
      target_platform_name: platformName,
      target_platform: requested,
      final_declared_platform: finalDeclared,
      source_java: baseline && baseline.language ? baseline.language.declared : null,
      source_jdk: baseline && baseline.language && baseline.language.from_jdk ? baseline.language.from_jdk.version : null,
      target_java: baseline && baseline.language ? baseline.language.target : null,
      target_jdk: baseline && baseline.language && baseline.language.to_jdk ? baseline.language.to_jdk.version : null,
      final_declared_java: last && last.declared ? last.declared.java : null,
    },
    trigger: baseline && baseline.issue
      ? { kind: 'agent-04-routing', issue_id: baseline.issue.id, plan: baseline.issue.plan, fix_type: baseline.issue.fix_type, routing_evidence: baseline.issue.routing_evidence || [] }
      : { kind: 'direct-request', note: 'invoked directly, not by 04_fix-generator Stage 2' },
    detection: baseline && baseline.project ? [
      { what: 'build tool', value: baseline.build_tool ? `${baseline.build_tool.tool} (${baseline.build_tool.kind})${baseline.build_tool.version ? ` ${baseline.build_tool.version}` : ''}` : null, where: baseline.project.descriptor },
      { what: 'declared Java', value: baseline.language.declared, where: `${baseline.project.descriptor} (java.version / compiler release)` },
      { what: 'source platform', value: sourcePlatform, where: mp.platform ? `${mp.platform.coordinate} (${mp.platform.where})` : ((baseline.reference_pack_eligibility || []).map((e) => e.source_platform && `${e.source_platform.coordinate} (${e.source_platform.where})`).find(Boolean) || 'not proven') },
      { what: 'requested target platform', value: requested, where: baseline.target && baseline.target.platform ? baseline.target.platform.source : null },
      { what: 'requested target Java', value: baseline.language.target, where: baseline.target && baseline.target.language ? baseline.target.language.source : null },
      { what: 'JDKs available', value: ((baseline.toolchain && baseline.toolchain.installed_jdks) || []).map((j) => `${j.major} (${j.version})`).join(', '), where: 'local toolchain probe' },
    ] : [],
    migration_path: {
      status: mp.status || (baseline && baseline.reference_packs && baseline.reference_packs.length ? 'SUPPORTED' : 'NO_MATCHING_PACK'),
      source: sourcePlatform,
      target: requested,
      required_path: mp.required_path || null,
      missing_capability: mp.missing_capability || [],
      pack_eligibility: (baseline && baseline.reference_pack_eligibility) || [],
      warnings: mp.warnings || [],
      unresolved: mp.unresolved || [],
    },
    license_policy: (baseline && baseline.license_policy) || 'open-source-only',
    path: edges.length ? {
      ladder: mp.ladder, granularity: mp.granularity, versions_from: mp.evidence ? mp.evidence.published : null, edges,
    } : null,
    endpoints,
    planned_transformations: plan ? {
      impact_areas: [...new Set((plan.impact || []).map((i) => i.area).filter(Boolean))],
      impact_entries: (plan.impact || []).length,
      deterministic_candidates: (plan.deterministic_candidates || []).map((c) => c.transformation || c.id || c),
      residual_candidates: (plan.residual_candidates || []).map((c) => c.description || c.area || c.id || c),
    } : null,
    openrewrite: transformations.map((t) => ({
      seq: t.seq, id: t.id || t.transformation, mode: t.mode, status: t.status,
      recipes: t.recipes || [], files: ((t.mode === 'apply' ? t.changed_files : t.proposed_files) || []).length,
      error: t.error || t.reason || null,
      decision: (((migration && migration.transformations) || []).find((d) => d.record === `rewrite-${String(t.seq).padStart(2, '0')}`) || {}).decision || null,
    })),
    actual_transformations: ((migration && migration.code_changes) || []).map((c) => ({ file: c.file || c.files, origin: c.origin || null, evidence: c.evidence || null, summary: c.what || c.summary || c.description || null })),
    file_changes: fileChanges(paths, meta, migration),
    dependency_changes: dependencyChanges(baseline, paths.workspace),
    compile_history: rounds.map((r) => {
      const note = ((migration && migration.round_notes) || []).find((n) => n.round === r.round) || {};
      return {
        round: r.round, baseline: Boolean(r.baseline || r.round === 0), label: r.label || null, intent: r.build.intent,
        jdk: r.jdk ? r.jdk.version : null, outcome: r.outcome, exit_code: r.build.exit_code, duration_ms: r.duration_ms,
        errors: (r.errors || []).length, declared_platform: r.declared && r.declared.parent ? r.declared.parent.version : null,
        diagnosis: note.diagnosis || null, repair: note.changes || null,
      };
    }),
    tests: tests ? { before: tests.before, after: tests.after, new_failures: tests.worse } : null,
    runtime: {
      baseline_probed: Boolean(runtimeBaseline),
      final_probed: Boolean(runtimeFinal),
      baseline_started: runtimeBaseline ? Boolean(runtimeBaseline.started) : null,
      final_started: runtimeFinal ? Boolean(runtimeFinal.started) : null,
      final_startup_seconds: runtimeFinal ? runtimeFinal.startup_seconds : null,
      readiness: runtimeFinal ? runtimeFinal.readiness || null : null,
      probes: runtimeFinal && runtimeFinal.probes ? runtimeFinal.probes.length : 0,
      failures: runtimeFinal && runtimeFinal.probes ? runtimeFinal.probes.filter((p) => !p.ok).map((p) => `${p.method} ${p.path}: ${p.error || p.status}`) : [],
    },
    behaviour: comparison ? {
      total: comparison.total, identical: comparison.matched, body_differs: comparison.bodyOnly, status_changed: comparison.statusChanged,
      expected_differences: ((migration && migration.behaviour && migration.behaviour.differences) || []).filter((d) => ['expected-framework-change', 'non-deterministic'].includes(d.classification)).map((d) => `${d.probe}: ${d.classification}`),
      unexpected_differences: ((migration && migration.behaviour && migration.behaviour.differences) || []).filter((d) => ['regression', 'unexplained'].includes(d.classification)).map((d) => `${d.probe}: ${d.classification}`),
      verdict: comparison.verdictText,
    } : null,
    compiled_on_target: Boolean(last && !(last.baseline || last.round === 0) && ['passed', 'tests-failed', 'build-failed'].includes(last.outcome)),
    target_reached: Boolean(requested && finalDeclared && finalDeclared === requested),
    validation: [],
    handoff: null,
    paths: {
      session: rel(paths.root),
      report_md: reportMd, report_md_exists: fs.existsSync(reportMd),
      report_diff: reportDiff, report_diff_exists: fs.existsSync(reportDiff),
      summary_md: path.join(runDir, 'MIGRATION_SUMMARY.md'),
      summary_json: path.join(runDir, 'migration-summary.json'),
      evidence: [paths.baseline, paths.plan, paths.probes, paths.migration, paths.state, paths.roundsDir, paths.runtimeDir, paths.transformationsDir]
        .filter((p) => fs.existsSync(p)).map((p) => rel(p)),
    },
  };
  summary.validation = [
    ['Build rounds', `${rounds.length} (${rounds.map((r) => `R${r.round} ${r.outcome}`).join(', ') || 'none'})`],
    ['Final round', last ? `R${last.round} ${last.build.intent} on JDK ${last.jdk ? last.jdk.version : '?'} — ${last.outcome}` : 'none'],
    ['Target declared by final build', finalDeclared ? `${finalDeclared}${requested ? (finalDeclared === requested ? ' (matches request)' : ` (requested ${requested})`) : ''}` : 'n/a'],
    ['Tests before → after', tests && tests.before && tests.after ? `${tests.before.total} run / ${tests.before.failed + tests.before.errors} failing → ${tests.after.total} run / ${tests.after.failed + tests.after.errors} failing${tests.worse ? ' — NEW FAILURES' : ''}` : 'not comparable'],
    ['Runtime probes', comparison ? `${comparison.total} compared: ${comparison.matched} identical, ${comparison.bodyOnly} body-only differences, ${comparison.statusChanged} status changes` : 'not compared'],
    ['Migration result', `${status} — ${reason}`],
  ];
  return summary;
}

function list(items, empty = '_none_') {
  return items && items.length ? items.map((i) => `- ${i}`) : [empty];
}

function table(header, rows) {
  if (!rows.length) return ['_none_'];
  const esc = (v) => String(v === null || v === undefined ? '—' : v).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
  return [`| ${header.join(' | ')} |`, `|${header.map(() => '---').join('|')}|`, ...rows.map((r) => `| ${r.map(esc).join(' | ')} |`)];
}

function renderMarkdown(s) {
  const m = s.metadata;
  const v = s.versions;
  const out = [];
  out.push(`# Migration Summary — ${s.run_id}`, '');
  out.push(`> **${s.final_status}** — ${s.status_reason}`, '');
  out.push('_Generated automatically by 04d-version-migration from the session\'s evidence files at the end of every script invocation. Nothing here is hand-written._', '');

  out.push('## Run metadata', '');
  out.push(...table(['Field', 'Value'], [
    ['Run ID', s.run_id], ['Generated', s.generated_at], ['Started', m.started_at], ['Duration', m.duration_ms !== null ? `${Math.round(m.duration_ms / 1000)} s` : null],
    ['Source commit', m.source_commit], ['Working branch / worktree', `${m.working_branch || '—'} @ ${m.worktree || '—'}`],
    ['Input location', m.input_location], ['Output location', m.output_location],
    ['Source Java', `${v.source_java || '—'}${v.source_jdk ? ` (JDK ${v.source_jdk})` : ''}`], ['Target Java', `${v.target_java || '—'}${v.target_jdk ? ` (JDK ${v.target_jdk})` : ''}`],
    ['Source platform', `${v.source_platform_name || ''} ${v.source_platform || '—'}`], ['Target platform', `${v.target_platform_name || ''} ${v.target_platform || '—'}`],
    ['Selected migration pack', m.selected_pack], ['Agent 04 issue', m.agent04_issue_id], ['Approval mode', m.approval_mode],
    ['Final status', s.final_status], ['Session state', `${s.state} (reached: ${s.states_reached.join(' → ') || 'none'})`],
  ]), '');

  out.push('## Trigger', '');
  if (s.trigger.kind === 'agent-04-routing') {
    out.push(`Routed by **04_fix-generator Stage 2** for \`${s.trigger.issue_id}\` (plan \`${s.trigger.plan}\`, Fix Type \`${s.trigger.fix_type}\`). Routing evidence recorded in the plan:`, '');
    out.push(...list(s.trigger.routing_evidence));
  } else {
    out.push(s.trigger.note);
  }
  out.push('');

  out.push('## Detection', '');
  out.push(...table(['What', 'Value', 'Detected from'], s.detection.map((d) => [d.what, d.value, d.where])), '');

  out.push('## Migration path', '');
  const mp = s.migration_path;
  out.push(`**Status:** \`${mp.status}\` — ${mp.source || '?'} → ${mp.target || '?'}`, '');
  if (mp.required_path && mp.required_path.steps) {
    out.push(...table(['Step', 'Capability', 'Available'], mp.required_path.steps.map((st) => [`${st.from} → ${st.to}`, st.capability, st.available ? 'yes' : '**MISSING**'])), '');
  }
  if (mp.missing_capability.length) out.push(`**Missing capability:** ${mp.missing_capability.map((c) => `\`${c}\``).join(', ')} — the migration cannot safely proceed past it.`, '');
  if (mp.pack_eligibility.length) {
    out.push('Pack eligibility:', '');
    out.push(...table(['Pack', 'Nominated by', 'Eligible', 'Why'], mp.pack_eligibility.map((e) => [e.pack, (e.matched_on || []).join(', '), e.eligible ? 'yes' : 'no', e.reason])), '');
  }
  if (mp.warnings.length) out.push('Warnings:', '', ...list(mp.warnings), '');
  if (mp.unresolved.length) out.push('Unresolved:', '', ...list(mp.unresolved), '');

  out.push('## Migration path (edges)', '');
  if (!s.path) out.push(`_No ladder path — a single pack session._ Licence policy: **${s.license_policy}**`, '');
  else {
    out.push(`Ladder \`${s.path.ladder}\` · licence policy **${s.license_policy}** · granularity ${s.path.granularity} · versions from ${s.path.versions_from || '—'}`, '');
    out.push(...table(['Edge', 'From → To', 'Class', 'Recipe', 'Licence', 'JDK', 'Cloud train', 'Previews', 'Applies', 'Rounds', 'Complete'],
      s.path.edges.map((e) => [
        e.id, `${e.from} → ${e.to}`, `${e.class}${e.role === 'landing' ? ' (landing)' : ''}`,
        `${e.recipe_source}${e.recipe ? `: ${e.recipe}` : ''}`, `${e.license || '—'}${e.open_source === false ? ' (not OSI open source)' : ''}`,
        e.java, e.cloud_train, e.previews.join('; '), e.applies.join('; '), e.rounds.join('; '), e.complete ? `yes (${e.completed_by})` : 'no',
      ])), '');
    const notes = s.path.edges.flatMap((e) => e.notes.map((n) => `${e.id}: ${n}`));
    if (notes.length) out.push('Edge notes:', '', ...list(notes), '');
  }

  out.push('## Endpoint preservation', '');
  if (!s.endpoints) out.push('_No endpoint inventory was recorded for this session._', '');
  else {
    const st = s.endpoints.static;
    const rt = s.endpoints.runtime;
    const n = (v) => (v === undefined || v === null ? '—' : v);
    out.push(...table(['Inventory', 'Before', 'After', 'Preserved', 'Missing', 'Added'], [
      ['Source mappings (static scan)', st.before, n(st.after), st.preserved.length, st.missing.length, st.added.length],
      [`Running application (${rt.source})`, n(rt.before), n(rt.after), rt.preserved ? rt.preserved.length : '—', rt.preserved ? rt.missing.length : '—', rt.added ? rt.added.length : '—'],
    ]), '');
    const notes = [st.note, rt.note].filter(Boolean);
    if (notes.length) out.push(...list(notes), '');
    const missing = [...new Set([...(st.missing || []), ...(rt.missing || [])])];
    const compared = st.after !== null || Boolean(rt.preserved);
    out.push(missing.length
      ? `**Missing after the migration:** ${missing.map((m) => `\`${m}\``).join(', ')}`
      : (compared ? 'No endpoint observed before the migration is missing after it, in any inventory that was available.' : '_Not compared yet._'), '');
    if ((st.added || []).length || (rt.added || []).length) out.push(`Added: ${[...new Set([...(st.added || []), ...(rt.added || [])])].map((m) => `\`${m}\``).join(', ')}`, '');
  }

  out.push('## Planned transformations', '');
  if (!s.planned_transformations) out.push('_No migration plan was recorded._');
  else {
    out.push(`- Impact areas: ${s.planned_transformations.impact_areas.join(', ') || '—'} (${s.planned_transformations.impact_entries} impact entries)`);
    out.push(`- Deterministic candidates: ${s.planned_transformations.deterministic_candidates.map((c) => (typeof c === 'string' ? c : JSON.stringify(c))).join(', ') || '—'}`);
    out.push(`- Residual candidates: ${s.planned_transformations.residual_candidates.map((c) => (typeof c === 'string' ? c : JSON.stringify(c))).join('; ') || '—'}`);
  }
  out.push('');

  out.push('## Actual transformations', '');
  out.push(...table(['File', 'Origin', 'Evidence', 'What'], s.actual_transformations.map((t) => [[].concat(t.file).join(', '), t.origin, t.evidence, t.summary])), '');

  out.push('## File changes', '');
  out.push(...table(['File', 'Change', 'Tool', 'Reason'], s.file_changes.map((f) => [f.file, f.change, f.tool, f.reason])), '');

  out.push('## Dependency changes', '');
  out.push(...table(['Dependency', 'Old', 'New', 'Reason'], s.dependency_changes.map((d) => [d.dependency, d.old, d.new, d.reason])), '');

  out.push('## OpenRewrite', '');
  out.push(...table(['#', 'Transformation', 'Mode', 'Status', 'Recipes', 'Files', 'Decision', 'Error'], s.openrewrite.map((t) => [t.seq, t.id, t.mode, t.status, t.recipes.join(', '), t.files, t.decision, t.error])), '');

  out.push('## Compile history', '');
  out.push(...table(['Round', 'Label', 'Goal', 'JDK', 'Outcome', 'Errors', 'Declared platform', 'Diagnosis', 'Repair'], s.compile_history.map((r) => [`R${r.round}${r.baseline ? ' (baseline)' : ''}`, r.label, r.intent, r.jdk, r.outcome, r.errors, r.declared_platform, r.diagnosis, Array.isArray(r.repair) ? r.repair.join('; ') : r.repair])), '');

  out.push('## Tests', '');
  if (!s.tests) out.push('_The test suite did not run._');
  else {
    out.push(...table(['Side', 'Round', 'Total', 'Passed', 'Failed', 'Errors', 'Skipped'], [
      ['Before', ...(s.tests.before ? [s.tests.before.round, s.tests.before.total, s.tests.before.passed, s.tests.before.failed, s.tests.before.errors, s.tests.before.skipped] : ['—', '—', '—', '—', '—', '—'])],
      ['After', ...(s.tests.after ? [s.tests.after.round, s.tests.after.total, s.tests.after.passed, s.tests.after.failed, s.tests.after.errors, s.tests.after.skipped] : ['—', '—', '—', '—', '—', '—'])],
    ]));
    out.push('', `New failures against round 0: **${s.tests.new_failures ? 'yes' : 'no'}**`);
  }
  out.push('');

  out.push('## Runtime', '');
  const r = s.runtime;
  out.push(...table(['Check', 'Result'], [
    ['Baseline probed', r.baseline_probed ? `yes (started: ${r.baseline_started})` : 'no'],
    ['Final probed', r.final_probed ? `yes (started: ${r.final_started}, ${r.final_startup_seconds ?? '?'} s)` : 'no'],
    ['Readiness', r.readiness ? JSON.stringify(r.readiness) : '—'],
    ['Probes (final)', r.probes],
    ['Runtime failures', r.failures.join('; ') || 'none'],
  ]), '');

  out.push('## Behaviour comparison', '');
  if (!s.behaviour) out.push('_Not compared — both a baseline and a final probe are required._');
  else {
    out.push(`${s.behaviour.verdict}`, '');
    out.push(...table(['Probes', 'Identical', 'Body differs', 'Status changed'], [[s.behaviour.total, s.behaviour.identical, s.behaviour.body_differs, s.behaviour.status_changed]]), '');
    out.push('Expected differences:', '', ...list(s.behaviour.expected_differences), '');
    out.push('Unexpected differences:', '', ...list(s.behaviour.unexpected_differences));
  }
  out.push('');

  out.push('## Pipeline handoff', '');
  const h = s.handoff;
  if (!h) out.push('_Direct request — no Agent 04 handoff applies._');
  else {
    out.push(...table(['Question', 'Answer'], [
      ['Did 04D produce the Agent 04 standard output?', h.fix_report ? `yes — \`${h.fix_report}\` (Status ${h.fix_status})${h.fix_diff ? `, \`${h.fix_diff}\`` : ''}` : `no — ${h.note}`],
      ['Did Agent 05 consume it?', h.agent05],
      ['Did Agent 06 consume it?', h.agent06],
      ['Did Agent 07 consume it?', h.agent07],
    ]));
    out.push('', '_Downstream answers reflect the files present when this summary was last regenerated; `node scripts/finalize-run.js --issue <ID>` refreshes them._');
  }
  out.push('');

  out.push('## Evidence', '');
  out.push(...list([
    `Session: \`${s.paths.session}\``,
    `Report: ${s.paths.report_md_exists ? `\`${rel(s.paths.report_md)}\`` : '_not rendered_'}`,
    `Diff: ${s.paths.report_diff_exists ? `\`${rel(s.paths.report_diff)}\`` : '_none_'}`,
    ...s.paths.evidence.map((p) => `\`${p}\``),
  ]));
  out.push('');
  return out.join('\n');
}

function downstreamStatus(id) {
  const root = path.join(PATHS.REPO_ROOT, 'docs', 'agent_output');
  const has = (p) => fs.existsSync(path.join(root, p));
  const field = (p, label) => {
    const file = path.join(root, p);
    if (!fs.existsSync(file)) return null;
    const m = new RegExp(`\\|\\s*\\*\\*${label}\\*\\*\\s*\\|\\s*([^|]+)\\|`).exec(fs.readFileSync(file, 'utf8'));
    return m ? m[1].trim() : 'present';
  };
  const v05 = ['rescan', 'redteam', 'behavior'].filter((k) => has(`05-verify/${k}_${id}.md`));
  const v06 = ['qa', 'build'].filter((k) => has(`06-test-gate/${k}_${id}.md`));
  const v07 = ['verdict', 'pr', 'audit'].filter((k) => has(`07-ship/${k}_${id}.md`));
  return {
    agent05: v05.length ? `yes — ${v05.map((k) => `05-verify/${k}_${id}.md`).join(', ')}` : 'not yet',
    agent06: v06.length ? `yes — ${v06.map((k) => `06-test-gate/${k}_${id}.md (${field(`06-test-gate/${k}_${id}.md`, 'Status') || 'present'})`).join(', ')}` : 'not yet',
    agent07: v07.length ? `yes — ${v07.map((k) => `07-ship/${k}_${id}.md`).join(', ')}${has(`07-ship/verdict_${id}.md`) ? ` (Decision: ${field(`07-ship/verdict_${id}.md`, 'Decision') || 'see file'})` : ''}` : 'not yet',
  };
}

/**
 * Writes the per-run summary (and, for an issue-linked session, the standard handoff). Never
 * throws: a summary that cannot be written is reported on stderr, and the script's own exit code
 * is left as it was.
 */
function finalizeRun(slug, by = 'unknown', { handoff = true } = {}) {
  if (!slug) return null;
  try {
    const paths = sessionPaths(slug);
    if (!fs.existsSync(paths.baseline) && !fs.existsSync(paths.state)) return null;
    const { writeStandardHandoff, fixReportPathFor, fixDiffPathFor } = require('./handoff');
    const summary = buildSummary(slug);
    const baseline = readJson(paths.baseline);
    if (baseline && baseline.issue && baseline.issue.id) {
      // The handoff is 04's output: written by the 04D scripts themselves. A summary-only refresh
      // (finalize-run.js, run later by agent 07) reads the existing handoff and never rewrites it.
      const existing = () => {
        const report = fixReportPathFor(baseline.issue.id);
        if (!fs.existsSync(report)) return null;
        const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(fs.readFileSync(report, 'utf8'));
        const diff = fixDiffPathFor(baseline.issue.id);
        return { report: rel(report), diff: fs.existsSync(diff) ? rel(diff) : null, status: status ? status[1].trim() : null };
      };
      const written = handoff ? writeStandardHandoff(slug, summary) : existing();
      summary.handoff = {
        fix_report: written ? written.report : null,
        fix_diff: written ? written.diff : null,
        fix_status: written ? written.status : null,
        note: written ? null : 'not written yet — the migration is still in progress',
        ...downstreamStatus(baseline.issue.id),
      };
    }
    summary.finalized_by = by;
    fs.mkdirSync(path.dirname(summary.paths.summary_json), { recursive: true });
    writeJson(summary.paths.summary_json, summary);
    fs.writeFileSync(summary.paths.summary_md, renderMarkdown(summary));
    console.log(`  Run summary  ${rel(summary.paths.summary_md)}  [${summary.final_status}]`);
    return summary;
  } catch (error) {
    console.error(`  ! migration summary not written: ${error.message}`);
    return null;
  }
}

function slugFromArgv(argv) {
  for (let i = 0; i < argv.length; i += 1) if (argv[i] === '--slug' || argv[i] === '-s') return argv[i + 1];
  return null;
}

/**
 * Runs a script's main() and finalizes the run afterwards — after a normal return, a thrown
 * error, or a rejected promise alike (a try/finally that also covers async mains).
 */
function runAndFinalize(main, by) {
  const finish = () => finalizeRun(notedSlug || slugFromArgv(process.argv.slice(2)), by);
  let result;
  try {
    result = main();
  } catch (error) {
    finish();
    throw error;
  }
  if (result && typeof result.then === 'function') return result.finally(finish);
  finish();
  return result;
}

module.exports = { RUNS_DIR, noteSession, newRunId, buildSummary, renderMarkdown, finalizeRun, runAndFinalize, deriveStatus };
