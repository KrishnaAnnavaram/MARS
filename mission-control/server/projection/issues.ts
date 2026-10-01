/**
 * Issue × stage projection. Everything here is derived from evidence (authoritative), decision records
 * (authoritative for attribution) and the event ledger (witness, used only to show what is running now).
 * No state is invented: a value the evidence cannot support is shown as unknown / unverified.
 */
import type {
  ApprovalInfo, ApprovalState, Blocker, BlockerCause, Cell, CellModifier, CellState, CodeRef, DecisionRecordView, EvidenceRef,
  FailureClass, IntegrityFinding, IssueDetail, IssueSummary, Lane, NextAction, PlanInfo, Provenance, StageId, TimelineItem, VerdictDetail,
} from '../../shared/types.js';
import { BOARD_STAGES, STAGE_BY_ID } from '../../shared/stages.js';
import type { Evidence, GateRec, PlanRec, VerifyRec } from '../sources/evidence.js';
import { evidenceRef } from '../sources/evidence.js';
import type { RegisterIssue } from '../lib/harness.js';
import type { DecisionRecord } from '../sources/decisions.js';
import type { CodeModel } from '../sources/codemodel.js';
import { resolveSymbol } from '../sources/codemodel.js';
import { computeScore, type Scoring } from './scoring.js';
import { extractField, plain } from '../lib/md.js';

export interface OpenOp {
  issueId: string;
  stage: StageId;
  name: string;
  startedAt: string;
  runId: string;
}

export interface IssueContext {
  ev: Evidence;
  register: RegisterIssue[];
  decisions: DecisionRecord[];
  findings: IntegrityFinding[];
  scoring: Scoring | null;
  cm: CodeModel;
  openOps: OpenOp[];
  ledgerByIssue: Map<string, { time: string; type: string; actor: string; actorKind: string; stage: string | null; title: string; provenance: Provenance }[]>;
  decisionsEnabled: { enabled: boolean; reason: string };
  now: Date;
}

const SEV_ORDER: Record<string, number> = { Critical: 4, High: 3, Medium: 2, Low: 1 };

export function issueIds(ctx: Pick<IssueContext, 'ev' | 'register'>): string[] {
  const ids = new Set<string>([...ctx.register.map((r) => r.id), ...ctx.ev.issueIds]);
  return Array.from(ids).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

function mk(stage: StageId, state: CellState, label: string, o: Partial<Omit<Cell, 'stage' | 'state' | 'label'>> = {}): Cell {
  return {
    stage, state, label,
    outcome: o.outcome ?? null,
    detail: o.detail ?? null,
    failureClass: o.failureClass ?? null,
    causeVerified: o.causeVerified ?? true,
    decidedBy: o.decidedBy ?? STAGE_BY_ID[stage].decidedBy,
    provenance: o.provenance ?? 'unknown',
    time: o.time ?? null,
    evidence: o.evidence ?? null,
    modifiers: o.modifiers ?? [],
    findings: o.findings ?? [],
  };
}

export function approvalOf(id: string, plan: PlanRec | undefined, decisions: DecisionRecord[]): { state: ApprovalState; label: string; records: DecisionRecordView[] } {
  const recs = decisions.filter((d) => d.issue_id === id);
  const views: DecisionRecordView[] = recs.map((d) => ({
    decisionId: d.decision_id, decision: d.decision, actor: d.actor, actorAuthentication: d.actor_authentication, channel: d.channel,
    rationale: d.rationale, timestamp: d.timestamp, shaBefore: d.subject.sha256_before, shaAfter: d.subject.sha256_after,
    appliesToCurrent: Boolean(plan && d.subject.sha256_after === plan.file.sha256), path: d.file,
  }));
  if (!plan) return { state: 'no_plan', label: 'No plan yet', records: views };
  const status = (plan.status || '').trim();
  const latest = views[views.length - 1];
  if (status === 'Proposed') return { state: 'pending', label: 'Decision required', records: views };
  if (status === 'Approved' || status === 'Rejected') {
    const want = status === 'Approved' ? 'APPROVED' : 'REJECTED';
    if (latest && latest.decision === want && latest.appliesToCurrent) return { state: status === 'Approved' ? 'approved' : 'rejected', label: `${status} by ${latest.actor}`, records: views };
    if (latest && latest.decision === want && !latest.appliesToCurrent) return { state: 'invalidated', label: `${status} for an earlier version — re-decision required`, records: views };
    return { state: status === 'Approved' ? 'approved_unattributed' : 'rejected_unattributed', label: `${status} (unattributed)`, records: views };
  }
  return { state: 'no_plan', label: `Unrecognised Status "${status}"`, records: views };
}

function laneState(check: VerifyRec['check'], verdict: string | null): { state: CellState; failureClass: FailureClass | null; label: string } {
  const v = (verdict || '').trim();
  if (!v) return { state: 'unknown', failureClass: null, label: 'Unreadable' };
  if (v === 'INCONCLUSIVE') return { state: 'inconclusive', failureClass: null, label: 'Inconclusive' };
  if (check === 'rescan') return v === 'FIXED' ? { state: 'passed', failureClass: null, label: 'Fixed' } : v === 'STILL_VULNERABLE' ? { state: 'failed', failureClass: 'security.still_vulnerable', label: 'Still vulnerable' } : { state: 'unknown', failureClass: null, label: v };
  if (check === 'redteam') return v === 'NO_BYPASS_FOUND' ? { state: 'passed', failureClass: null, label: 'No bypass' } : v === 'BYPASS_FOUND' ? { state: 'failed', failureClass: 'security.bypass', label: 'Bypass found' } : { state: 'unknown', failureClass: null, label: v };
  return v === 'BEHAVIOR_PRESERVED' ? { state: 'passed', failureClass: null, label: 'Preserved' } : v === 'BEHAVIOR_CHANGED' ? { state: 'failed', failureClass: 'behavior.regression', label: 'Behaviour changed' } : { state: 'unknown', failureClass: null, label: v };
}

/** Gate outcome: the per-step exit codes are the deterministic fact; the header is what the arbiter reads. */
function gateOutcome(g: GateRec): { status: string | null; state: CellState; failureClass: FailureClass | null; causeVerified: boolean; label: string } {
  const status = g.bodyStatus || g.headerStatus;
  if (!status) return { status, state: 'unknown', failureClass: null, causeVerified: false, label: 'Unreadable' };
  if (status === 'Refused') return { status, state: 'blocked', failureClass: 'refused', causeVerified: true, label: 'Refused' };
  if (status === 'Passed') return { status, state: 'passed', failureClass: null, causeVerified: true, label: 'Passed' };
  if (g.compileErrors.length) return { status, state: 'failed', failureClass: 'compile.error', causeVerified: false, label: g.kind === 'qa' ? 'Did not compile' : 'Compile failed' };
  return { status, state: 'failed', failureClass: g.kind === 'qa' ? 'qa.test_failed' : 'build.failed', causeVerified: true, label: g.kind === 'qa' ? 'Test failed' : 'Build failed' };
}

const CLASS_LABEL: Record<string, string> = {
  'security.still_vulnerable': 'Re-scan: vulnerability still present',
  'security.bypass': 'Red-team: bypass found',
  'behavior.regression': 'Behaviour regression beyond the plan',
  'qa.test_failed': 'QA: regression test failed',
  'build.failed': 'Build gate: build failed',
  'patch.apply': 'Patch does not apply',
  infrastructure: 'Harness / infrastructure failure',
  'environment.toolchain': 'Build environment (toolchain)',
  refused: 'Refused',
  rejected: 'Rejected by a human',
};

export function causeLabel(cls: FailureClass | null, stage: StageId): string {
  if (cls === 'compile.error') return stage === 'qa' ? 'QA: test could not compile (cause unverified)' : stage === 'fix' ? 'Patch does not compile (cause unverified)' : 'Build gate: compilation failed (cause unverified)';
  return (cls && CLASS_LABEL[cls]) || 'Failed';
}

export function buildIssue(id: string, ctx: IssueContext): IssueDetail {
  const { ev } = ctx;
  const reg = ctx.register.find((r) => r.id === id) || null;
  const rca = ev.rca.get(id);
  const blast = ev.blast.get(id);
  const plan = ev.plan.get(id);
  const fix = ev.fix.get(id);
  const diff = ev.diff.get(id);
  const checks = ev.verify.get(id) || {};
  const gates = ev.gate.get(id) || {};
  const verdict = ev.verdict.get(id);
  const pr = ev.pr.get(id);
  const audit = ev.audit.get(id);
  const findings = ctx.findings.filter((x) => x.issueId === id);
  const fIds = (stage: StageId) => findings.filter((x) => x.stage === stage).map((x) => x.id);
  const approval = approvalOf(id, plan, ctx.decisions);
  const approved = approval.state === 'approved' || approval.state === 'approved_unattributed';

  const cells = {} as Record<StageId, Cell>;
  const archReady = Boolean(ev.architecture.doc);
  cells.intake = reg ? mk('intake', 'passed', 'In register', { outcome: reg.status || null, provenance: 'human', time: null, evidence: evidenceRef(ev.byPath.get('docs/agent_output/00-issues/issue-register.xlsx')), detail: `Reporter-owned status: ${reg.status || 'unknown'}` })
    : mk('intake', 'missing', 'Not in register', { provenance: 'unknown', findings: fIds('intake') });
  cells.architecture = archReady ? mk('architecture', 'passed', 'Documented', { provenance: 'computed', evidence: evidenceRef(ev.architecture.doc) }) : mk('architecture', 'ready', 'Not run');

  cells.rca = rca
    ? mk('rca', 'passed', 'Diagnosed', { outcome: plain(rca.g.Confidence), detail: plain(rca.g['Root cause']), provenance: 'mixed', time: rca.collectedAt, evidence: evidenceRef(rca.file, rca.collectedAt), findings: fIds('rca') })
    : mk('rca', reg && archReady ? 'ready' : 'waiting', reg ? 'Not diagnosed' : 'Waiting');
  cells.blast_radius = blast
    ? mk('blast_radius', 'passed', (plain(blast.g.Priority) || '').split(' ')[0] || 'Measured', { outcome: (plain(blast.g.Priority) || '').split(' — ')[0], detail: plain(blast.g['How far it spreads']), provenance: 'mixed', time: blast.collectedAt, evidence: evidenceRef(blast.file, blast.collectedAt), findings: fIds('blast_radius') })
    : mk('blast_radius', rca ? 'ready' : 'waiting', rca ? 'Not measured' : 'Waiting');
  cells.plan = plan
    ? mk('plan', 'passed', plan.route === 'catalog' ? 'Planned · catalog' : `Planned · ${plan.route}`, { outcome: plan.cwe, detail: plan.headline, provenance: 'mixed', time: plan.collectedAt, evidence: evidenceRef(plan.file, plan.collectedAt), findings: fIds('plan') })
    : mk('plan', rca ? 'ready' : 'waiting', rca ? 'Not planned' : 'Waiting');

  const apMods: CellModifier[] = [];
  if (approval.state.endsWith('unattributed')) apMods.push('unattributed');
  if (plan?.reproposed && /Approved|Rejected/.test(plan.status || '')) apMods.push('carried_over');
  const apFind = fIds('approval');
  if (findings.some((x) => x.stage === 'approval' && x.severity !== 'info')) apMods.push('conflict');
  const apEvidence = evidenceRef(plan?.file);
  switch (approval.state) {
    case 'pending': cells.approval = mk('approval', 'awaiting_human', 'Decision required', { outcome: 'Proposed', provenance: 'human', evidence: apEvidence, findings: apFind }); break;
    case 'approved': cells.approval = mk('approval', 'passed', 'Approved', { outcome: 'Approved', detail: approval.label, provenance: 'human', time: approval.records.at(-1)?.timestamp ?? null, evidence: apEvidence, findings: apFind, modifiers: apMods }); break;
    case 'approved_unattributed': cells.approval = mk('approval', 'passed', 'Approved', { outcome: 'Approved', detail: 'Status cell reads Approved. No record of who approved, when, or which version.', provenance: 'human', evidence: apEvidence, findings: apFind, modifiers: apMods }); break;
    case 'invalidated': cells.approval = mk('approval', 'awaiting_human', 'Re-decision required', { outcome: plan?.status ?? null, detail: approval.label, provenance: 'human', evidence: apEvidence, findings: apFind, modifiers: apMods }); break;
    case 'rejected': case 'rejected_unattributed': cells.approval = mk('approval', 'blocked', 'Rejected', { outcome: 'Rejected', failureClass: 'rejected', provenance: 'human', evidence: apEvidence, findings: apFind, modifiers: apMods }); break;
    default: cells.approval = mk('approval', 'waiting', plan ? approval.label : 'Waiting for plan');
  }
  // An approval carried over a re-proposal does not cover the plan on disk: a warning, not a green pass.
  if ((approval.state === 'approved' || approval.state === 'approved_unattributed') && plan?.reproposed) {
    cells.approval = { ...cells.approval, state: 'inconclusive', label: 'Approved · re-review', detail: `${cells.approval.detail || ''} The plan was re-proposed after this approval; the approval was carried over.`.trim() };
  }

  if (fix) {
    const s = (fix.status || '').trim();
    const ref = evidenceRef(fix.file, fix.verifiedAt);
    const fmods: CellModifier[] = findings.some((x) => x.stage === 'fix' && x.severity !== 'info') ? ['conflict'] : [];
    if (s === 'Compiled') cells.fix = mk('fix', 'passed', 'Compiled', { outcome: s, detail: plain(fix.g['Verification level']), provenance: 'mixed', time: fix.verifiedAt, evidence: ref, findings: fIds('fix'), modifiers: fmods });
    else if (s === 'Compile Failed') cells.fix = mk('fix', 'failed', 'Compile failed', { outcome: s, failureClass: 'compile.error', causeVerified: false, detail: 'The patched module did not compile in the worktree. Whether the patch or the build environment is at fault is not recorded by MARS.', provenance: 'mixed', time: fix.verifiedAt, evidence: ref, findings: fIds('fix'), modifiers: fmods });
    else if (s === 'Refused') cells.fix = mk('fix', 'blocked', 'Refused', { outcome: s, failureClass: 'refused', provenance: 'computed', time: fix.verifiedAt, evidence: ref, findings: fIds('fix') });
    else cells.fix = mk('fix', 'unknown', s || 'Unreadable', { evidence: ref, findings: fIds('fix') });
  } else if (approved) cells.fix = mk('fix', 'ready', 'Ready to implement', { detail: 'Plan is Approved; 04_fix-generator Stage 2 has not produced a fix.' });
  else if (approval.state === 'pending' || approval.state === 'invalidated') cells.fix = mk('fix', 'blocked_upstream', 'Awaiting decision', { detail: 'No code is drafted until a human approves the plan.' });
  else if (approval.state.startsWith('rejected')) cells.fix = mk('fix', 'not_applicable', 'Not applicable', { detail: 'Plan rejected.' });
  else cells.fix = mk('fix', 'waiting', 'Waiting');

  const drafted = Boolean(fix && /Compiled|Compile Failed/.test(fix.status || ''));
  const compileFailedFix = Boolean(fix && /Compile Failed/.test(fix.status || ''));
  const lanes: Lane[] = [];
  for (const check of ['rescan', 'redteam', 'behavior'] as const) {
    const v = checks[check];
    if (v) {
      const ls = laneState(check, v.verdict);
      const mods: CellModifier[] = [];
      if (compileFailedFix) mods.push('compile_failed_fix');
      if (v.manualNote) mods.push('declared_manual_edit');
      if (findings.some((x) => x.stage === check && x.severity !== 'info' && x.rule !== 'R6')) mods.push('conflict');
      cells[check] = mk(check, ls.state, ls.label, { outcome: v.verdict, failureClass: ls.failureClass, detail: v.headline, provenance: 'mixed', decidedBy: 'agent', time: v.collectedAt, evidence: evidenceRef(v.file, v.collectedAt), findings: fIds(check), modifiers: mods });
      lanes.push(laneFromVerify(v, ls, fIds(check)));
    } else {
      cells[check] = mk(check, drafted ? 'ready' : (fix ? 'not_applicable' : 'waiting'), drafted ? 'Not run' : (fix ? 'Not applicable' : 'Waiting'));
      lanes.push(emptyLane(check, cells[check]));
    }
  }
  const patchedFiles = new Set((diff?.files || []).map((x) => x.path.split('/').pop() as string));
  for (const kind of ['qa', 'build'] as const) {
    const g = gates[kind];
    if (g) {
      const go = gateOutcome(g);
      const mods: CellModifier[] = [];
      if (g.headerStatus && g.bodyStatus && g.headerStatus !== g.bodyStatus) mods.push('conflict');
      if (g.insertedProse.length) mods.push('declared_manual_edit');
      if (compileFailedFix) mods.push('compile_failed_fix');
      cells[kind] = mk(kind, go.state, go.label, {
        outcome: go.status, failureClass: go.failureClass, causeVerified: go.causeVerified, provenance: kind === 'build' ? 'computed' : 'mixed',
        decidedBy: 'script', time: g.runAt, evidence: evidenceRef(g.file, g.runAt), findings: fIds(kind), modifiers: mods,
        detail: go.failureClass === 'compile.error' ? `${g.compileErrors.length} compiler error(s)${g.compileErrorsTruncated ? ' in a truncated log' : ''}; MARS does not record whether the unpatched module fails the same way.` : null,
      });
      lanes.push(laneFromGate(g, go, patchedFiles, fIds(kind)));
    } else {
      cells[kind] = mk(kind, drafted ? 'ready' : (fix ? 'not_applicable' : 'waiting'), drafted ? 'Not run' : (fix ? 'Not applicable' : 'Waiting'));
      lanes.push(emptyLane(kind, cells[kind]));
    }
  }

  let verdictDetail: VerdictDetail | null = null;
  const sev = reg?.severity || null;
  if (verdict) {
    const header = (t: string | null | undefined) => (t ? (extractField(t, 'Verdict') || extractField(t, 'Status')) : null);
    const replay = ctx.scoring ? computeScore({ rescan: header(checks.rescan?.file.text), redteam: header(checks.redteam?.file.text), behavior: header(checks.behavior?.file.text), qa: header(gates.qa?.file.text), build: header(gates.build?.file.text) }, sev, ctx.scoring) : null;
    const bodyReplay = ctx.scoring ? computeScore({ rescan: checks.rescan?.verdict ?? null, redteam: checks.redteam?.verdict ?? null, behavior: checks.behavior?.verdict ?? null, qa: gates.qa ? gates.qa.bodyStatus || gates.qa.headerStatus : null, build: gates.build ? gates.build.bodyStatus || gates.build.headerStatus : null }, sev, ctx.scoring) : null;
    verdictDetail = {
      decision: verdict.decision || 'unknown', score: verdict.score, threshold: verdict.threshold, severity: verdict.severityLabel || sev,
      hardGates: verdict.hardGateTable.length ? verdict.hardGateTable : verdict.hardGatesTriggered.map((n) => ({ name: n, triggered: true })),
      breakdown: verdict.breakdown, override: verdict.override, narrative: verdict.narrative, computedAt: verdict.computedAt,
      evidence: evidenceRef(verdict.file, verdict.computedAt),
      replay: replay ? { ...replay, basis: 'Upstream report headers as they read now (what compute-score.js reads)', matchesRecorded: replay.decision === verdict.decision } : null,
      bodyReplay: bodyReplay ? { ...bodyReplay, basis: 'Upstream verdicts with QA/build taken from their exit-code tables', matchesRecorded: bodyReplay.decision === verdict.decision } : null,
      policySha256: null,
    };
    const vmods: CellModifier[] = findings.some((x) => x.stage === 'verdict' && x.severity === 'critical') ? ['conflict'] : [];
    cells.verdict = verdict.decision === 'Cleared'
      ? mk('verdict', 'passed', 'Cleared', { outcome: 'Cleared', detail: `${verdict.score ?? '?'}/${verdict.threshold ?? '?'}`, provenance: 'mixed', decidedBy: 'script', time: verdict.computedAt, evidence: evidenceRef(verdict.file, verdict.computedAt), findings: fIds('verdict'), modifiers: vmods })
      : mk('verdict', 'blocked', 'Blocked', { outcome: verdict.decision, detail: `${verdict.score ?? '?'}/${verdict.threshold ?? '?'}${verdict.hardGatesTriggered.length ? ` · hard gate: ${verdict.hardGatesTriggered.join(', ')}` : ''}`, provenance: 'mixed', decidedBy: 'script', time: verdict.computedAt, evidence: evidenceRef(verdict.file, verdict.computedAt), findings: fIds('verdict'), modifiers: vmods });
  } else {
    const allFive = Boolean(checks.rescan && checks.redteam && checks.behavior && gates.qa && gates.build);
    cells.verdict = mk('verdict', allFive ? 'ready' : 'waiting', allFive ? 'Ready to arbitrate' : 'Waiting');
  }
  cells.writeup = pr && audit ? mk('writeup', 'passed', 'Written', { outcome: pr.blockedBanner ? 'Blocked banner' : 'Ready', provenance: 'mixed', evidence: evidenceRef(audit.file), findings: fIds('writeup') })
    : mk('writeup', verdict ? 'ready' : 'waiting', verdict ? 'Not written' : 'Waiting');
  // A Cleared verdict whose evidence has critical integrity findings is not trustworthy enough to
  // suggest publication: the human must resolve the conflicts first (MARS's own rule is unchanged).
  const criticalHere = findings.filter((x) => x.severity === 'critical');
  if (!verdict) cells.publication = mk('publication', 'waiting', 'Waiting');
  else if (verdict.decision === 'Cleared' && criticalHere.length) cells.publication = mk('publication', 'inconclusive', 'Cleared, untrusted', { detail: `Resolve ${criticalHere.length} critical integrity finding(s) before requesting publication.`, provenance: 'computed', findings: criticalHere.map((x) => x.id), modifiers: ['conflict'] });
  else if (verdict.decision === 'Cleared') cells.publication = mk('publication', 'ready', 'Eligible', { detail: 'A human may request publication. MARS reapplies and revalidates the diff in a worktree before creating a PR.', provenance: 'human' });
  else cells.publication = mk('publication', 'not_applicable', 'Not eligible', { detail: 'Only a Cleared verdict can be published.' });

  // Live overlay (witness only): a stage that a MARS script is running right now.
  for (const op of ctx.openOps.filter((o) => o.issueId === id)) {
    const c = cells[op.stage];
    if (!c) continue;
    cells[op.stage] = { ...c, state: 'running', label: 'Running', detail: `${op.name} — started ${op.startedAt}${c.outcome ? `; previous outcome ${c.outcome}` : ''}`, modifiers: [...c.modifiers, 'live'] };
  }

  const blocker = blockerOf(cells, verdict?.hardGatesTriggered || [], verdict, approval.state);
  const nextAction = nextActionOf(id, cells, approval.state, blocker, findings);
  const cve = (/CVE-\d{4}-\d+/.exec(`${reg?.title || ''} ${plan?.g.Dependency || ''} ${rca?.title || ''}`) || [])[0] || null;
  const attention = attentionScore(cells, findings, approval.state);

  const planInfo: PlanInfo | null = plan ? {
    status: plan.status, cwe: plan.cwe, cweName: plan.cweName, owasp: plain(plan.g.OWASP), dependency: plain(plan.g.Dependency), confidence: plain(plan.g.Confidence),
    route: plan.route, headline: plan.headline, approach: plan.approach, alternatives: plan.alternatives, plannedChanges: plan.plannedChanges,
    risks: plan.risks, verification: plan.verification, reproposed: plan.reproposed, reproposedNote: plan.reproposedNote, generatedAt: plan.collectedAt, evidence: evidenceRef(plan.file, plan.collectedAt),
  } : null;

  const inDiff = (diff?.files || []).map((x) => x.path);
  const plannedNotChanged = (plan?.plannedChanges || []).filter((c) => c.file && !inDiff.includes(c.file) && !/^no (behavioural )?change/i.test(c.change)).map((c) => c.file);
  const approvalInfo: ApprovalInfo = {
    issueId: id, title: reg?.title || rca?.title || id, severity: sev, state: approval.state, stateLabel: approval.label,
    planPath: plan?.file.path || null, planSha256: plan?.file.sha256 || null, plan: planInfo, decisions: approval.records,
    implementation: fix ? { status: fix.status, filesInDiff: inDiff, plannedNotChanged, deviation: plannedNotChanged.length ? `Planned but not changed by the diff: ${plannedNotChanged.map((p) => p.split('/').pop()).join(', ')}` : null } : null,
    services: reg?.services || [], priority: blast ? (plain(blast.g.Priority) || '').split(' — ')[0] : null,
    actions: decisionActions(approval.state, ctx.decisionsEnabled),
  };

  const timeline = timelineOf(id, ctx, { reg, checks, gates, approval });
  const codeRefs = codeRefsOf(ctx.cm, reg, rca, blast, diff?.files.map((x) => x.path) || [], plan);

  return {
    id,
    title: reg?.title || rca?.title || plan?.title || id,
    severity: sev,
    type: reg?.type || null,
    cwe: plan?.cwe || plain(verdict?.cwe) || null,
    cve,
    owasp: plan ? plain(plan.g.OWASP) : null,
    services: reg?.services || [],
    registerStatus: reg?.status || null,
    inRegister: Boolean(reg),
    priority: blast ? (plain(blast.g.Priority) || '').split(' — ')[0] : null,
    headline: blast?.headline || rca?.headline || null,
    cells,
    verdict: verdictDetail ? { decision: verdictDetail.decision, score: verdictDetail.score, threshold: verdictDetail.threshold, hardGates: verdict!.hardGatesTriggered, overrideApplied: verdictDetail.override.applied, replayDecision: verdictDetail.replay?.decision ?? null, replayMatches: verdictDetail.replay?.matchesRecorded ?? null } : null,
    blocker,
    nextAction,
    attention,
    findings: findings.filter((x) => x.severity !== 'info').length,
    register: reg ? { reportedOn: reg.reportedOn || null, reportedBy: reg.reportedBy || null, symbols: reg.symbols || [], files: reg.files || [], entryPoints: reg.entryPoints || [], summary: summaryOf(reg.body) } : null,
    rca: rca ? { headline: rca.headline, whatBreaks: plain(rca.g['What breaks']), rootCause: plain(rca.g['Root cause']), where: plain(rca.g.Where), service: plain(rca.g.Service), confidence: plain(rca.g.Confidence), methods: rca.methods.map((m) => ({ method: m.method, service: m.service, file: m.file })), generatedAt: rca.collectedAt, evidence: evidenceRef(rca.file, rca.collectedAt) } : null,
    blast: blast ? {
      headline: blast.headline, priority: plain(blast.g.Priority), spread: plain(blast.g['How far it spreads']),
      servicesBroken: (plain(blast.g['Services broken']) || '').split(/,\s*/).filter((s) => s && s !== 'none'),
      servicesDegraded: (plain(blast.g['Services degraded']) || '').split(/,\s*/).filter((s) => s && s !== 'none'),
      endpointsDown: plain(blast.g['Endpoints down']), scheduledJobs: plain(blast.g['Scheduled jobs hit']), confidence: plain(blast.g.Confidence),
      services: blast.services, endpoints: blast.endpoints, generatedAt: blast.collectedAt, evidence: evidenceRef(blast.file, blast.collectedAt),
    } : null,
    plan: planInfo,
    approval: approvalInfo,
    fix: fix ? { status: fix.status, verificationLevel: plain(fix.g['Verification level']), matchesPlan: plain(fix.g['Matches plan']), claimedFiles: fix.claimed, dependency: plain(fix.g.Dependency), headline: fix.headline, generatedAt: fix.verifiedAt, evidence: evidenceRef(fix.file, fix.verifiedAt) } : null,
    diff: diff ? { path: diff.file.path, sha256: diff.file.sha256, files: diff.files, text: diff.file.text || '' } : null,
    lanes,
    verdictDetail,
    writeup: { prPath: pr?.file.path || null, auditPath: audit?.file.path || null, prBlockedBanner: Boolean(pr?.blockedBanner), prTitle: pr?.title || null },
    publication: { eligible: cells.publication.state === 'ready', reason: verdict ? (cells.publication.state === 'inconclusive' ? cells.publication.detail || 'Cleared, untrusted.' : verdict.decision === 'Cleared' ? 'Cleared verdict: a human may request publication.' : 'Verdict is Blocked; publication is never allowed for a Blocked patch.') : 'No verdict yet.', records: 0 },
    timeline,
    evidence: ev.files.filter((x) => x.issueId === id).map((x) => evidenceRef(x)!).filter(Boolean) as EvidenceRef[],
    findingList: findings,
    codeRefs,
  };
}

function summaryOf(body: string | undefined): string | null {
  if (!body) return null;
  const m = /## Summary\s*\n+([\s\S]*?)(?=\n## |$)/.exec(body);
  return m ? plain(m[1].trim().split(/\n\s*\n/)[0].replace(/\n/g, ' ')) : null;
}

function laneFromVerify(v: VerifyRec, ls: ReturnType<typeof laneState>, findings: string[]): Lane {
  const label = { rescan: 'Re-scan', redteam: 'Red-team', behavior: 'Behaviour guard' }[v.check];
  const facts = Object.entries(v.g).filter(([k]) => !['Verdict', 'Confidence'].includes(k)).map(([k, val]) => ({ label: k, value: plain(val) || '' }));
  return {
    check: v.check, label, decidedBy: 'agent', decidedByNote: 'AI judgement over script-collected facts (worktree materialization of the patched files).',
    state: ls.state, outcome: v.verdict, failureClass: ls.failureClass, causeVerified: true, confidence: v.confidence, facts,
    headerStatus: v.verdict, bodyStatus: null, tests: [], steps: [], compileErrors: [], compileErrorsTruncated: false,
    aiClaims: v.manualNote ? [`Report footer: ${v.manualNote}`] : [], time: v.collectedAt, evidence: evidenceRef(v.file, v.collectedAt), findings, headline: v.headline,
  };
}

function laneFromGate(g: GateRec, go: ReturnType<typeof gateOutcome>, patched: Set<string>, findings: string[]): Lane {
  return {
    check: g.kind, label: g.kind === 'qa' ? 'QA gate' : 'Build gate',
    decidedBy: 'script',
    decidedByNote: g.kind === 'qa' ? 'Test drafted by an agent; pass/fail decided by the real exit code in an isolated worktree.' : 'mvn verify + dependency-tree diff in an isolated worktree; outcome from the exit code. No agent content by contract.',
    state: go.state, outcome: go.status, failureClass: go.failureClass, causeVerified: go.causeVerified, confidence: null,
    facts: [
      ...(g.newTest ? [{ label: 'New regression test', value: g.newTest.split('/').pop() || g.newTest }] : []),
      ...(g.g['Modules built'] ? [{ label: 'Modules built', value: plain(g.g['Modules built']) || '' }] : []),
      ...(g.g['Modules exercised'] ? [{ label: 'Modules exercised', value: plain(g.g['Modules exercised']) || '' }] : []),
      ...(g.g['Dependency changes detected'] ? [{ label: 'Dependency drift', value: plain(g.g['Dependency changes detected']) || '' }] : []),
      ...(g.g['Requires a live dependency'] ? [{ label: 'Live dependency', value: plain(g.g['Requires a live dependency']) || '' }] : []),
    ],
    headerStatus: g.headerStatus, bodyStatus: g.bodyStatus, tests: g.tests, steps: g.steps,
    compileErrors: g.compileErrors.map((c) => ({ ...c, inPatchedFile: patched.has(c.file) })), compileErrorsTruncated: g.compileErrorsTruncated,
    aiClaims: g.insertedProse.map((p) => `Inserted prose "${p}" asserts the failure is environmental. Unverified: MARS records no baseline build of the unpatched module.`),
    time: g.runAt, evidence: evidenceRef(g.file, g.runAt), findings, headline: null,
  };
}

function emptyLane(check: Lane['check'], cell: Cell): Lane {
  const label = { rescan: 'Re-scan', redteam: 'Red-team', behavior: 'Behaviour guard', qa: 'QA gate', build: 'Build gate' }[check];
  return {
    check, label, decidedBy: check === 'qa' || check === 'build' ? 'script' : 'agent', decidedByNote: '', state: cell.state, outcome: null, failureClass: null,
    causeVerified: true, confidence: null, facts: [], headerStatus: null, bodyStatus: null, tests: [], steps: [], compileErrors: [], compileErrorsTruncated: false,
    aiClaims: [], time: null, evidence: null, findings: [], headline: cell.label,
  };
}

function blockerOf(cells: Record<StageId, Cell>, hardGates: string[], verdict: { decision: string | null; score: number | null; threshold: number | null } | undefined, approvalState: ApprovalState): Blocker | null {
  const causes: BlockerCause[] = [];
  if (verdict && verdict.decision === 'Blocked') {
    const order: StageId[] = ['rescan', 'redteam', 'behavior', 'qa', 'build'];
    for (const s of order) {
      const c = cells[s];
      if (c.state === 'failed' || c.state === 'blocked') {
        const gate = (s === 'rescan' && hardGates.includes('re-scanner')) || (s === 'build' && hardGates.includes('build-gatekeeper'));
        causes.push({ label: causeLabel(c.failureClass, s), kind: gate ? 'hard_gate' : 'lane', failureClass: c.failureClass, verified: c.causeVerified, stage: s });
      }
    }
    if (verdict.score != null && verdict.threshold != null && verdict.score < verdict.threshold) causes.push({ label: `Score ${verdict.score} below threshold ${verdict.threshold}`, kind: 'score', failureClass: null, verified: true, stage: 'verdict' });
    const rank = (c: BlockerCause) => (c.failureClass?.startsWith('security') ? 0 : c.failureClass === 'behavior.regression' ? 1 : c.kind === 'hard_gate' ? 2 : c.kind === 'lane' ? 3 : 4);
    causes.sort((a, b) => rank(a) - rank(b));
    const head = causes.filter((c) => c.kind !== 'score').slice(0, 2).map((c) => c.label);
    return { summary: `Blocked — ${head.join('; ') || 'score below threshold'}`, causes };
  }
  if (approvalState.startsWith('rejected')) return { summary: 'Rejected by a human', causes: [{ label: 'Plan rejected', kind: 'decision', failureClass: 'rejected', verified: true, stage: 'approval' }] };
  if (cells.fix.state === 'failed' && !verdict) return { summary: 'Patch does not compile (cause unverified)', causes: [{ label: causeLabel('compile.error', 'fix'), kind: 'lane', failureClass: 'compile.error', verified: false, stage: 'fix' }] };
  if (cells.fix.state === 'blocked') return { summary: 'Fix refused', causes: [{ label: 'Fixer refused the plan', kind: 'lane', failureClass: 'refused', verified: true, stage: 'fix' }] };
  return null;
}

function nextActionOf(id: string, cells: Record<StageId, Cell>, approval: ApprovalState, blocker: Blocker | null, findings: IntegrityFinding[]): NextAction | null {
  const basis = 'Derived from the pipeline contract and the current evidence; not a MARS output.';
  const crit = findings.filter((f) => f.severity === 'critical');
  if (cells.intake.state === 'missing') return { stage: 'intake', owner: 'reporter', ownerKind: 'human', text: `${id} has evidence but is not in the register — add the row or retire the evidence.`, basis };
  if (approval === 'pending') return { stage: 'approval', owner: 'human approver', ownerKind: 'human', text: 'Review the plan and record Approve or Reject. No code is drafted until then.', basis };
  if (approval === 'invalidated') return { stage: 'approval', owner: 'human approver', ownerKind: 'human', text: 'The plan changed after it was decided. Review the current version and decide again.', basis };
  if (cells.rca.state === 'ready') return { stage: 'rca', owner: '02_root-cause-analyst', ownerKind: 'operator', text: 'Run 02_root-cause-analyst for this issue.', basis };
  if (cells.plan.state === 'ready') return { stage: 'plan', owner: '04_fix-generator', ownerKind: 'operator', text: 'Run 04_fix-generator (Stage 1) to propose a plan.', basis };
  if (cells.fix.state === 'ready') return { stage: 'fix', owner: '04_fix-generator', ownerKind: 'operator', text: 'Run 04_fix-generator (Stage 2) to draft and compile the approved fix.', basis };
  const pendingVerify = (['rescan', 'redteam', 'behavior'] as StageId[]).filter((s) => cells[s].state === 'ready');
  if (pendingVerify.length) return { stage: pendingVerify[0], owner: '05_existing-app-test-agent', ownerKind: 'operator', text: `Run 05_existing-app-test-agent (${pendingVerify.join(', ')}).`, basis };
  const pendingGates = (['qa', 'build'] as StageId[]).filter((s) => cells[s].state === 'ready');
  if (pendingGates.length) return { stage: pendingGates[0], owner: '06_additional-test-execution', ownerKind: 'operator', text: `Run 06_additional-test-execution (${pendingGates.join(', ')}).`, basis };
  if (cells.verdict.state === 'ready') return { stage: 'verdict', owner: '07_audit-and-pr', ownerKind: 'operator', text: 'Run 07_audit-and-pr to arbitrate.', basis };
  if (cells.verdict.state === 'blocked' && blocker) {
    const classes = blocker.causes.map((c) => c.failureClass);
    const contradiction = crit.some((f) => f.rule === 'R3' || f.rule === 'R1') ? ' The gate reports also contradict their own exit codes — re-run 06 before relying on any verdict.' : '';
    if (classes.some((c) => c?.startsWith('security'))) return { stage: 'plan', owner: '04_fix-generator', ownerKind: 'operator', text: `Verification shows the vulnerability is not closed: re-plan or re-fix (04), then re-run 05–07.${contradiction}`, basis };
    if (classes.includes('behavior.regression')) return { stage: 'fix', owner: '04_fix-generator', ownerKind: 'operator', text: `Revise the patch to remove the out-of-scope behaviour change (04 Stage 2), then re-run 05–07.${contradiction}`, basis };
    if (classes.includes('compile.error')) return { stage: 'build', owner: '06_additional-test-execution', ownerKind: 'operator', text: `Establish whether the build environment is at fault (baseline build of the unpatched module on the project's Java target), then re-run 06 → 07.${contradiction}`, basis };
    return { stage: 'verdict', owner: 'operator', ownerKind: 'operator', text: `Review the verdict decomposition and decide which stage to re-run.${contradiction}`, basis };
  }
  if (cells.writeup.state === 'ready') return { stage: 'writeup', owner: '07_audit-and-pr', ownerKind: 'operator', text: 'Run 07_audit-and-pr (write-up).', basis };
  if (cells.publication.state === 'inconclusive') return { stage: 'publication', owner: 'human', ownerKind: 'human', text: `Cleared, but the evidence has ${crit.length} critical integrity finding(s): resolve them (re-run the affected stages) before an explicit publication request.`, basis };
  if (cells.publication.state === 'ready') return { stage: 'publication', owner: 'human', ownerKind: 'human', text: 'Cleared: a human may explicitly request PR publication.', basis };
  return null;
}

function attentionScore(cells: Record<StageId, Cell>, findings: IntegrityFinding[], approval: ApprovalState): number {
  let a = 0;
  if (findings.some((f) => f.rule === 'R3' && f.severity === 'critical')) a = Math.max(a, 1000);
  if (approval === 'pending') a = Math.max(a, 900);
  if (approval === 'invalidated') a = Math.max(a, 850);
  if (findings.some((f) => f.severity === 'critical')) a = Math.max(a, 800);
  if (cells.publication.state === 'ready') a = Math.max(a, 600);
  if (Object.values(cells).some((c) => c.state === 'running')) a = Math.max(a, 550);
  if (cells.verdict.state === 'blocked') a = Math.max(a, 500);
  if (Object.values(cells).some((c) => c.state === 'failed')) a = Math.max(a, 400);
  return a;
}

function decisionActions(state: ApprovalState, d: { enabled: boolean; reason: string }): ApprovalInfo['actions'] {
  const unavailable = [{ action: 'Request changes', reason: 'Not part of the MARS contract. Reject with a rationale; 04_fix-generator then re-proposes a new plan version.' }];
  if (state !== 'pending') return { allowed: false, reason: state === 'no_plan' ? 'There is no plan to decide.' : 'Only a plan whose Status is Proposed can be decided.', available: [], unavailable };
  if (!d.enabled) return { allowed: false, reason: d.reason, available: [], unavailable };
  return { allowed: true, reason: 'Recorded via .claude/scripts/record-decision.js, bound to the plan sha256 shown.', available: ['APPROVED', 'REJECTED'], unavailable };
}

function timelineOf(
  id: string,
  ctx: IssueContext,
  x: { reg: RegisterIssue | null; checks: Partial<Record<'rescan' | 'redteam' | 'behavior', VerifyRec>>; gates: Partial<Record<'qa' | 'build', GateRec>>; approval: ReturnType<typeof approvalOf> },
): TimelineItem[] {
  const ev = ctx.ev;
  const items: TimelineItem[] = [];
  const sortKey = new Map<TimelineItem, string>();
  const push = (it: Omit<TimelineItem, 'gap'> & { gap?: boolean }, key?: string | null) => {
    const item = { gap: false, ...it };
    items.push(item);
    sortKey.set(item, key || (item.time ? (item.time.length === 10 ? `${item.time}T23:59:59.999Z` : item.time) : ''));
  };
  if (x.reg) push({ time: x.reg.reportedOn ? `${x.reg.reportedOn}`.slice(0, 10) : null, timeSource: x.reg.reportedOn ? 'reconstructed' : 'unknown', stage: 'intake', actorKind: 'human', actor: x.reg.reportedBy || 'reporter', provenance: 'human', title: 'Issue reported', detail: `${x.reg.severity} · ${x.reg.type}`, evidence: evidenceRef(ev.byPath.get('docs/agent_output/00-issues/issue-register.xlsx')) });
  const stamp = (f: { path: string; instants: string[]; generatedDate: string | null } | undefined, stage: StageId, collector: string, collectorTitle: string, author: string, authorTitle: string, prov: Provenance) => {
    if (!f) return;
    const fr = ev.byPath.get(f.path);
    if (f.instants[0]) push({ time: f.instants[0], timeSource: 'reconstructed', stage, actorKind: 'script', actor: collector, provenance: 'computed', title: collectorTitle, detail: null, evidence: evidenceRef(fr, f.instants[0]) });
    push({ time: f.generatedDate, timeSource: f.generatedDate ? 'reconstructed' : 'unknown', stage, actorKind: 'agent', actor: author, provenance: prov, title: authorTitle, detail: 'Rendered after the facts above; only the date is recorded.', evidence: evidenceRef(fr) }, f.instants[0] ? `${f.instants[0]}~` : null);
  };
  const rca = ev.rca.get(id);
  stamp(rca?.file, 'rca', '02-root-cause-analyst/collect-evidence', 'RCA evidence collected', '02_root-cause-analyst', 'Root cause analysed and rendered', 'mixed');
  const blast = ev.blast.get(id);
  stamp(blast?.file, 'blast_radius', '03-blast-radius-analyst/collect-impact', 'Reach measured', '03_blast-radius-analyst', 'Blast radius written', 'mixed');
  const plan = ev.plan.get(id);
  stamp(plan?.file, 'plan', '04a-fix-strategist/collect-remediation-context', 'Remediation context collected', '04_fix-generator', `Plan rendered${plan?.route ? ` (route: ${plan.route})` : ''}`, 'mixed');
  if (plan) {
    if (x.approval.records.length) {
      for (const d of x.approval.records) push({ time: d.timestamp, timeSource: 'observed', stage: 'approval', actorKind: 'human', actor: `${d.actor} (${d.actorAuthentication})`, provenance: 'human', title: `Plan ${d.decision === 'APPROVED' ? 'approved' : 'rejected'}`, detail: d.rationale, evidence: { path: d.path, type: 'decision_record', sha256: null, generatedAt: d.timestamp, provenance: 'human' } });
    } else if (/Approved|Rejected/.test(plan.status || '')) {
      push({ time: null, timeSource: 'unknown', stage: 'approval', actorKind: 'human', actor: 'unknown', provenance: 'human', title: `Plan ${plan.status} — no record`, detail: 'The Status cell was edited by hand. MARS records no approver, time, or reviewed version.', evidence: evidenceRef(plan.file), gap: true });
    }
    if (plan.reproposed) push({ time: (/re-proposed on ([0-9-]+)/.exec(plan.reproposedNote || '') || [])[1] || null, timeSource: 'reconstructed', stage: 'plan', actorKind: 'agent', actor: '04_fix-generator', provenance: 'ai_authored', title: 'Plan re-proposed; prior Status carried over', detail: plan.reproposedNote, evidence: evidenceRef(plan.file), gap: true });
  }
  const fix = ev.fix.get(id);
  stamp(fix?.file, 'fix', fix && /1104/.test(fix.g.CWE || '') ? '04c-dependency-upgrader/apply-version-bump' : '04b-fixer/verify-patch', `Patch verified in worktree: ${fix?.status || ''}`, '04_fix-generator', 'Fix report rendered', 'mixed');
  for (const c of ['rescan', 'redteam', 'behavior'] as const) {
    const v = x.checks[c];
    stamp(v?.file, c, `05-verify/collect-${c}`, `${c} facts collected`, '05_existing-app-test-agent', `${c}: ${v?.verdict || '?'}`, 'mixed');
  }
  for (const k of ['qa', 'build'] as const) {
    const g = x.gates[k];
    stamp(g?.file, k, k === 'qa' ? '06a-qa-runner/run-qa-gate' : '06b-build-gatekeeper/run-build-gate', `${k.toUpperCase()} gate ran: exit ${g?.steps[0]?.exitCode ?? g?.tests[0]?.exitCode ?? '?'}`, '06_additional-test-execution', `${k.toUpperCase()} report rendered (header: ${g?.headerStatus || '?'})`, k === 'build' ? 'computed' : 'mixed');
  }
  const verdict = ev.verdict.get(id);
  stamp(verdict?.file, 'verdict', '07a-merge-arbiter/compute-score', `Score computed: ${verdict?.decision || '?'} ${verdict?.score ?? '?'}/${verdict?.threshold ?? '?'}`, '07_audit-and-pr', 'Verdict rendered with narrative', 'mixed');
  const audit = ev.audit.get(id);
  if (audit) push({ time: audit.file.generatedDate, timeSource: 'reconstructed', stage: 'writeup', actorKind: 'agent', actor: '07_audit-and-pr', provenance: 'mixed', title: 'PR content and audit trail written', detail: null, evidence: evidenceRef(audit.file) });
  if (verdict) push({ time: null, timeSource: 'unknown', stage: 'publication', actorKind: 'unknown', actor: '—', provenance: 'unknown', title: verdict.decision === 'Cleared' ? 'No publication recorded' : 'Not eligible for publication (Blocked)', detail: 'MARS records no publication events; publication would be visible only on GitHub.', evidence: null, gap: verdict.decision === 'Cleared' });
  for (const l of ctx.ledgerByIssue.get(id) || []) push({ time: l.time, timeSource: 'observed', stage: (l.stage as StageId) || null, actorKind: (l.actorKind as TimelineItem['actorKind']) || 'unknown', actor: l.actor, provenance: l.provenance, title: l.title, detail: null, evidence: null });
  const keyed = items.map((it, i) => ({ it, i, k: sortKey.get(it) || '' }));
  keyed.sort((a, b) => {
    if (!a.k && !b.k) return a.i - b.i;
    if (!a.k) return 1;
    if (!b.k) return -1;
    return a.k.localeCompare(b.k) || a.i - b.i;
  });
  return keyed.map((x) => x.it);
}

function codeRefsOf(cm: CodeModel, reg: RegisterIssue | null, rca: { location: string | null; methods: { method: string; file: string; lines: string | null }[] } | undefined, blast: { services: { service: string; status: string }[]; endpoints: { endpoint: string; status: string }[] } | undefined, diffFiles: string[], plan: PlanRec | undefined): CodeRef[] {
  const refs: CodeRef[] = [];
  const add = (r: CodeRef) => {
    if (!refs.some((x) => x.id === r.id && x.role === r.role)) refs.push(r);
  };
  if (rca?.location) {
    const m = /^(.+?):(\d+)$/.exec(rca.location);
    const mid = m ? cm.methodByFileLine(m[1], Number(m[2])) : null;
    add({ kind: mid ? 'method' : 'file', id: mid || rca.location, label: mid ? cm.nodes.get(mid)?.label || mid : rca.location, role: 'defect', resolved: Boolean(mid) });
  }
  for (const m of rca?.methods || []) for (const id of resolveSymbol(cm, m.method)) add({ kind: 'method', id, label: cm.nodes.get(id)?.label || id, role: 'call path', resolved: true });
  for (const s of reg?.symbols || []) {
    const ids = resolveSymbol(cm, s);
    if (!ids.length) add({ kind: 'method', id: s, label: s, role: 'reported symbol', resolved: false });
    for (const id of ids) add({ kind: 'method', id, label: cm.nodes.get(id)?.label || id, role: 'reported symbol', resolved: true });
  }
  for (const e of blast?.endpoints || []) add({ kind: 'endpoint', id: e.endpoint, label: e.endpoint, role: e.status.toLowerCase(), resolved: cm.nodes.has(e.endpoint) });
  for (const s of blast?.services || []) if (!/unaffected/i.test(s.status)) add({ kind: 'module', id: `module:${s.service}`, label: s.service, role: s.status.toLowerCase(), resolved: cm.nodes.has(`module:${s.service}`) });
  for (const f of diffFiles) add({ kind: 'file', id: f, label: f.split('/').pop() || f, role: 'changed by patch', resolved: cm.typesByFile.has(f) });
  for (const c of plan?.plannedChanges || []) if (c.file && !diffFiles.includes(c.file) && !/^no (behavioural )?change/i.test(c.change)) add({ kind: 'file', id: c.file, label: c.file.split('/').pop() || c.file, role: 'planned, not changed', resolved: cm.typesByFile.has(c.file) });
  return refs;
}

export function toSummary(d: IssueDetail): IssueSummary {
  const { id, title, severity, type, cwe, cve, owasp, services, registerStatus, inRegister, priority, headline, cells, verdict, blocker, nextAction, attention, findings } = d;
  return { id, title, severity, type, cwe, cve, owasp, services, registerStatus, inRegister, priority, headline, cells, verdict, blocker, nextAction, attention, findings };
}

export function sortIssues(list: IssueSummary[]): IssueSummary[] {
  return [...list].sort((a, b) => b.attention - a.attention || (SEV_ORDER[b.severity || ''] || 0) - (SEV_ORDER[a.severity || ''] || 0) || a.id.localeCompare(b.id, undefined, { numeric: true }));
}

export { BOARD_STAGES };
