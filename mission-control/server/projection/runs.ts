/**
 * Runs and traces. Observed runs come from the event ledger (witness); reconstructed runs are rebuilt
 * from evidence timestamps for history that predates telemetry, and say so. The execution hierarchy is
 * the one the repository supports (proposal §5.14): Run (agent invocation, many issues) → skill segment
 * (derived from script paths) → operation (script / tool) → write / gate event.
 */
import type { ActiveRun, LedgerEvent, OperationSummary, Provenance, RunDetail, RunSummary, SpanView, StageId } from '../../shared/types.js';
import type { Evidence } from '../sources/evidence.js';
import type { OpenOp } from './issues.js';
import { eventTitle, isMarsAgent, opName } from '../../shared/events.js';

export const STALE_MS = 20 * 60 * 1000;
export const ORPHAN_GAP_MS = 2 * 60 * 1000;

const NEXT_AFTER: Record<string, string> = {
  list: 'Collect facts for each workload item (collect-*.js)',
  collect: 'Agent authors the schema-validated judgement JSON',
  author: 'Render the report (render-*.js)',
  render: 'Confirm coverage (list-*.js) and report back',
  gate: 'Render the gate report (render-*.js)',
  score: 'Agent writes the arbitration narrative, then render-verdict.js',
  validate: 'Continue with the next step',
  test: 'Report the self-test result',
};

export interface RunsProjection {
  runs: RunSummary[];
  details: Map<string, RunDetail>;
  active: ActiveRun[];
  openOps: OpenOp[];
  ledgerByIssue: Map<string, { time: string; type: string; actor: string; actorKind: string; stage: string | null; title: string; provenance: Provenance }[]>;
  agentStats: Map<string, { runs: number; lastRunAt: string | null; failures: number; durations: number[]; active: boolean }>;
  skillStats: Map<string, { executions: number; lastAt: string | null; failures: number; active: boolean }>;
}

function runKey(e: LedgerEvent): string {
  if (e.run_id) return e.run_id;
  if (e.session_id) return `session_${e.session_id}`;
  return `standalone_${e.time.slice(0, 10)}`;
}

const isMars = isMarsAgent;

function provOf(e: LedgerEvent): Provenance {
  return (e.provenance as Provenance) || (e.actor?.kind === 'human' ? 'human' : e.actor?.kind === 'script' ? 'computed' : 'unknown');
}

function addMs(iso: string, ms: number | undefined | null): string | null {
  if (ms == null) return null;
  const t = Date.parse(iso);
  return Number.isFinite(t) ? new Date(t + ms).toISOString() : null;
}

export function buildRuns(events: LedgerEvent[], ev: Evidence | null, now: Date): RunsProjection {
  const groups = new Map<string, LedgerEvent[]>();
  for (const e of events) {
    const k = runKey(e);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k)!.push(e);
  }
  const runs: RunSummary[] = [];
  const details = new Map<string, RunDetail>();
  const active: ActiveRun[] = [];
  const openOps: OpenOp[] = [];
  const ledgerByIssue: RunsProjection['ledgerByIssue'] = new Map();
  const agentStats: RunsProjection['agentStats'] = new Map();
  const skillStats: RunsProjection['skillStats'] = new Map();

  for (const [runId, list] of groups) {
    list.sort((a, b) => a.gseq - b.gseq);
    const first = list[0];
    const last = list[list.length - 1];
    const startEv = list.find((e) => e.type === 'agent_run.started');
    // An agent run ends only with ITS completion (same run_id as its start). A session ends only with
    // session.ended: SubagentStop events whose start was not observed fall back to the session's run_id
    // and must not close a live session.
    const endEv = startEv ? list.find((e) => e.type === 'agent_run.completed' && e.run_id === startEv.run_id && e.gseq > startEv.gseq) : undefined;
    const kind: RunSummary['kind'] = startEv || runId.startsWith('run_') ? 'agent_run' : 'session';
    // A session's identity comes from session-level events only: subagent events whose start was not
    // observed carry their own agent_id and must not relabel the parent session.
    const agentId = startEv?.agent_id || (kind === 'agent_run' ? list.find((e) => e.agent_id)?.agent_id : null) || null;
    const lastAge = now.getTime() - Date.parse(last.time);
    // A resumed session (activity after its session.ended) is live again.
    const lastEnd = [...list].reverse().find((e) => e.type === 'session.ended');
    const sessionEnded = Boolean(lastEnd && !list.some((e) => e.gseq > lastEnd.gseq && e.type !== 'session.ended'));
    const status: RunSummary['status'] = endEv || sessionEnded ? 'completed' : lastAge < STALE_MS ? 'running' : 'stale';

    const root: SpanView = {
      id: `run:${runId}`, parentId: null, kind: 'run', name: agentId || (runId.startsWith('standalone_') ? 'Scripts outside an agent session' : 'Interactive session'), detail: null,
      skill: null, script: null, stage: null, stepKind: null, issues: [], status: status === 'running' ? 'running' : 'completed', provenance: kind === 'agent_run' ? 'ai_authored' : 'unknown',
      startedAt: (startEv || first).time, endedAt: endEv ? endEv.time : (status === 'running' ? null : last.time), durationMs: endEv?.duration_ms ?? null, outcome: null, failure: null, inputs: [], outputs: [], attrs: { run_id: runId, session_id: first.session_id || null }, eventIds: [],
    };
    const spans: SpanView[] = [root];
    const ops = new Map<string, SpanView>();
    const issues = new Set<string>();
    let failures = 0;
    let writes = 0;
    for (const e of list) for (const i of e.issue_ids || []) issues.add(i);

    for (const e of list) {
      if (e.type === 'operation.started') {
        const viaSubagent = kind === 'session' && e.agent_id ? `by subagent ${e.agent_id} (its start was not observed)` : null;
        const span: SpanView = {
          id: `op:${e.span_id || e.event_id}`, parentId: root.id, kind: 'operation', name: opName(e), detail: [viaSubagent, e.attrs?.background ? 'background command' : null].filter(Boolean).join(' · ') || null, skill: e.skill_id || null, script: e.script_id || null,
          stage: (e.stage_id as StageId) || null, stepKind: e.step_kind || null, issues: e.issue_ids || [], status: 'running', provenance: e.script_id ? 'computed' : 'unknown',
          startedAt: e.time, endedAt: null, durationMs: null, outcome: null, failure: null, inputs: [], outputs: [], attrs: { ...(e.attrs || {}) }, eventIds: [e.event_id],
        };
        ops.set(e.span_id || e.event_id, span);
        spans.push(span);
      } else if (e.type === 'operation.completed' || e.type === 'operation.failed') {
        const span = (e.span_id && ops.get(e.span_id)) || null;
        const reported = Array.isArray(e.attrs?.reported) ? (e.attrs?.reported as Record<string, unknown>[]) : [];
        const outcome = reported.length ? reported.map((r) => `${r.issue_id}: ${r.result || r.decision}`).join(', ') : null;
        if (span) {
          span.status = e.status === 'failed' ? 'failed' : 'completed';
          span.endedAt = e.time;
          span.durationMs = e.duration_ms ?? (Date.parse(e.time) - Date.parse(span.startedAt));
          span.failure = e.failure || null;
          span.outcome = outcome;
          span.issues = Array.from(new Set([...span.issues, ...(e.issue_ids || [])]));
          span.attrs = { ...span.attrs, ...(e.attrs || {}) };
          span.eventIds.push(e.event_id);
        } else {
          spans.push({
            id: `op:${e.event_id}`, parentId: root.id, kind: 'operation', name: opName(e), detail: 'Start not observed', skill: e.skill_id || null, script: e.script_id || null,
            stage: (e.stage_id as StageId) || null, stepKind: e.step_kind || null, issues: e.issue_ids || [], status: e.status === 'failed' ? 'failed' : 'completed', provenance: e.script_id ? 'computed' : 'unknown',
            startedAt: addMs(e.time, -(e.duration_ms || 0)) || e.time, endedAt: e.time, durationMs: e.duration_ms ?? null, outcome, failure: e.failure || null, inputs: [], outputs: [], attrs: { ...(e.attrs || {}) }, eventIds: [e.event_id],
          });
        }
        if (e.status === 'failed') failures += 1;
      } else if (e.type === 'artifact.written') {
        writes += 1;
        spans.push({
          id: `w:${e.event_id}`, parentId: root.id, kind: 'write', name: eventTitle(e), detail: null, skill: e.skill_id || null, script: null, stage: (e.stage_id as StageId) || null,
          stepKind: e.step_kind || null, issues: e.issue_ids || [], status: 'completed', provenance: provOf(e), startedAt: e.time, endedAt: e.time, durationMs: e.duration_ms ?? null,
          outcome: null, failure: null, inputs: [], outputs: e.outputs || [], attrs: { ...(e.attrs || {}) }, eventIds: [e.event_id],
        });
      } else if (!['agent_run.started', 'agent_run.completed'].includes(e.type)) {
        const isFail = e.status === 'failed' || e.status === 'refused';
        if (e.status === 'failed') failures += 1;
        spans.push({
          id: `e:${e.event_id}`, parentId: root.id, kind: 'event', name: eventTitle(e), detail: e.summary || null, skill: e.skill_id || null, script: e.script_id || null,
          stage: (e.stage_id as StageId) || null, stepKind: e.step_kind || null, issues: e.issue_ids || [], status: isFail ? (e.status as SpanView['status']) : 'info',
          provenance: provOf(e), startedAt: addMs(e.time, -(e.duration_ms || 0)) || e.time, endedAt: e.time, durationMs: e.duration_ms ?? null, outcome: e.outcome || null,
          failure: e.failure || null, inputs: e.inputs || [], outputs: e.outputs || [], attrs: { ...(e.attrs || {}) }, eventIds: [e.event_id],
        });
      }
    }

    // Gate/score events emitted by a script nest under the operation that ran it.
    const opSpans = spans.filter((s) => s.kind === 'operation');
    // Operations whose completion never arrived (interrupted, permission denied, hook lost) must not
    // stay "running" forever. Parallel tool calls start within seconds of each other, so an open
    // foreground operation is abandoned once a later operation started ≥ ORPHAN_GAP_MS after it and
    // completed, or once it is older than STALE_MS. Background commands are exempt until stale.
    for (const o of opSpans) {
      if (o.status !== 'running') continue;
      const age = now.getTime() - Date.parse(o.startedAt);
      const superseded = !o.attrs.background && opSpans.some((x) => x !== o && x.status !== 'running' && Date.parse(x.startedAt) - Date.parse(o.startedAt) >= ORPHAN_GAP_MS);
      if (superseded || age > STALE_MS) {
        o.status = 'info';
        o.outcome = 'unknown';
        o.detail = [o.detail, 'No completion was recorded (interrupted, denied, or the hook was lost); outcome unknown'].filter(Boolean).join(' · ');
      }
    }
    for (const s of spans.filter((x) => x.kind === 'event' && x.script)) {
      const host = opSpans.filter((o) => o.script === s.script && o.startedAt <= (s.endedAt || s.startedAt) && (!o.endedAt || o.endedAt >= (s.endedAt || s.startedAt))).pop();
      if (host) s.parentId = host.id;
    }
    // Skill segments: consecutive operations of the same skill, grouped (skills are metadata, not invocations).
    let seg: SpanView | null = null;
    const segs: SpanView[] = [];
    for (const o of opSpans.sort((a, b) => a.startedAt.localeCompare(b.startedAt))) {
      if (!o.skill) {
        seg = null;
        continue;
      }
      if (!seg || seg.skill !== o.skill) {
        seg = {
          id: `skill:${runId}:${segs.length}`, parentId: root.id, kind: 'skill', name: o.skill, detail: null, skill: o.skill, script: null, stage: o.stage, stepKind: null,
          issues: [], status: 'completed', provenance: 'computed', startedAt: o.startedAt, endedAt: o.endedAt, durationMs: null, outcome: null, failure: null, inputs: [], outputs: [], attrs: {}, eventIds: [],
        };
        segs.push(seg);
      }
      o.parentId = seg.id;
      seg.issues = Array.from(new Set([...seg.issues, ...o.issues]));
      if (o.status === 'running') seg.status = 'running';
      else if (o.status === 'failed' && seg.status !== 'running') seg.status = 'failed';
      if (!o.endedAt) seg.endedAt = null;
      else if (seg.endedAt && o.endedAt > seg.endedAt) seg.endedAt = o.endedAt;
    }
    for (const s of segs) s.durationMs = s.endedAt ? Date.parse(s.endedAt) - Date.parse(s.startedAt) : null;
    spans.push(...segs);
    root.issues = Array.from(issues);
    // Spans for running operations in a stale run are not "running" — nothing is known about them.
    if (status === 'stale') for (const s of spans) if (s.status === 'running') s.status = 'info';

    const ordered = orderSpans(spans);
    const summary: RunSummary = {
      runId, source: 'observed', kind, agentId, isMarsAgent: isMars(agentId), sessionId: first.session_id || null,
      startedAt: root.startedAt, endedAt: root.endedAt, status, durationMs: root.durationMs ?? (root.endedAt ? Date.parse(root.endedAt) - Date.parse(root.startedAt) : null),
      issues: root.issues, operations: opSpans.length, failures, writes,
      label: kind === 'agent_run' ? `${agentId || 'agent'}${isMars(agentId) ? '' : ' (not a MARS agent)'}` : root.name,
    };
    runs.push(summary);
    details.set(runId, { ...summary, spans: ordered, events: list, note: null });

    // Stats
    if (agentId && kind === 'agent_run') {
      const st = agentStats.get(agentId) || { runs: 0, lastRunAt: null, failures: 0, durations: [], active: false };
      st.runs += 1;
      st.lastRunAt = !st.lastRunAt || summary.startedAt > st.lastRunAt ? summary.startedAt : st.lastRunAt;
      st.failures += failures;
      if (summary.durationMs != null && status === 'completed') st.durations.push(summary.durationMs);
      if (status === 'running') st.active = true;
      agentStats.set(agentId, st);
    }
    for (const o of opSpans) {
      if (!o.skill) continue;
      const st = skillStats.get(o.skill) || { executions: 0, lastAt: null, failures: 0, active: false };
      st.executions += 1;
      st.lastAt = !st.lastAt || o.startedAt > st.lastAt ? o.startedAt : st.lastAt;
      if (o.status === 'failed') st.failures += 1;
      if (o.status === 'running' && status === 'running') st.active = true;
      skillStats.set(o.skill, st);
    }

    for (const e of list) {
      if (!e.issue_ids?.length || !['gate.completed', 'fix.verified', 'fix.refused', 'verdict.computed', 'approval.recorded', 'guard.would_deny', 'guard.denied', 'artifact.written', 'operation.completed', 'operation.failed'].includes(e.type)) continue;
      for (const i of e.issue_ids) {
        if (!ledgerByIssue.has(i)) ledgerByIssue.set(i, []);
        ledgerByIssue.get(i)!.push({ time: e.time, type: e.type, actor: e.actor?.id || e.agent_id || 'unknown', actorKind: e.actor?.kind || 'unknown', stage: e.stage_id || null, title: eventTitle(e), provenance: provOf(e) });
      }
    }

    if (status === 'running') {
      const opsByTime = opSpans.slice().sort((a, b) => a.startedAt.localeCompare(b.startedAt));
      const open = opsByTime.filter((o) => o.status === 'running');
      const currentSpan = open.filter((o) => !o.attrs.background).pop() || open.pop() || null;
      const done = spans.filter((s) => (s.kind === 'operation' || s.kind === 'event' || s.kind === 'write') && s.status !== 'running' && s.endedAt).sort((a, b) => (a.endedAt || '').localeCompare(b.endedAt || ''));
      const prevSpan = done.pop() || null;
      const basisSpan = currentSpan || prevSpan;
      const role = basisSpan ? (basisSpan.kind === 'write' && basisSpan.stepKind === 'author' ? 'author' : basisSpan.stepKind) : null;
      const lastEvt = list[list.length - 1];
      const waitingHuman = lastEvt.type === 'human.waiting';
      active.push({
        runId, agentId, isMarsAgent: isMars(agentId), kind: kind === 'agent_run' ? 'agent_run' : 'session', sessionId: first.session_id || null,
        startedAt: root.startedAt, lastEventAt: last.time, elapsedMs: now.getTime() - Date.parse(root.startedAt),
        stage: basisSpan?.stage || null, skill: basisSpan?.skill || null, issues: Array.from(new Set([...(currentSpan?.issues || []), ...(prevSpan?.issues || [])])),
        current: currentSpan ? toOp(currentSpan, now) : null, previous: prevSpan ? toOp(prevSpan, now) : null,
        nextExpected: currentSpan ? (role && NEXT_AFTER[role] ? `After this: ${NEXT_AFTER[role]}` : null) : (role ? NEXT_AFTER[role] || null : null),
        nextExpectedBasis: role ? 'Typical next step of the skill procedure in SKILL.md (list → collect → author → render); an expectation, not a MARS output.' : null,
        // Only real repo paths, latest write per path; outside-workspace placeholders are not artifacts.
        artifacts: Array.from(new Map(spans.filter((s) => s.kind === 'write' && s.outputs[0]?.path && !s.outputs[0].path.startsWith('<'))
          .map((s) => [s.outputs[0].path, { path: s.outputs[0].path, sha256: s.outputs[0].sha256 || null, time: s.startedAt }] as const)).values()).slice(-8),
        failures, waitingHuman,
      });
      for (const o of opsByTime.filter((x) => x.status === 'running')) for (const i of o.issues) if (o.stage) openOps.push({ issueId: i, stage: o.stage, name: o.name, startedAt: o.startedAt, runId });
    }
  }

  if (ev) runs.push(...reconstructed(ev, details));
  runs.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  return { runs, details, active, openOps, ledgerByIssue, agentStats, skillStats };
}

function toOp(s: SpanView, now: Date): OperationSummary {
  return {
    spanId: s.id, name: s.name, kind: s.kind === 'write' ? 'write' : s.kind === 'event' ? 'gate-event' : s.script ? 'script' : 'command',
    skill: s.skill, script: s.script, stage: s.stage, stepKind: s.stepKind, issues: s.issues, status: s.status === 'info' ? 'completed' : (s.status as OperationSummary['status']),
    startedAt: s.startedAt, endedAt: s.endedAt, durationMs: s.durationMs ?? (s.endedAt ? null : now.getTime() - Date.parse(s.startedAt)), outcome: s.outcome, detail: s.detail,
  };
}

function orderSpans(spans: SpanView[]): SpanView[] {
  const children = new Map<string | null, SpanView[]>();
  for (const s of spans) {
    const k = s.parentId;
    if (!children.has(k)) children.set(k, []);
    children.get(k)!.push(s);
  }
  for (const list of children.values()) list.sort((a, b) => a.startedAt.localeCompare(b.startedAt) || (a.kind === 'skill' ? -1 : 0));
  const out: SpanView[] = [];
  const visit = (parent: string | null) => {
    for (const s of children.get(parent) || []) {
      out.push(s);
      visit(s.id);
    }
  };
  visit(null);
  return out;
}

const COLLECTOR: Record<string, { script: string; stage: StageId; title: string }> = {
  root_cause_report: { script: '02-root-cause-analyst/collect-evidence', stage: 'rca', title: 'RCA evidence collected' },
  blast_radius_report: { script: '03-blast-radius-analyst/collect-impact', stage: 'blast_radius', title: 'Reach measured' },
  fix_plan: { script: '04a-fix-strategist/collect-remediation-context', stage: 'plan', title: 'Remediation context collected' },
  fix_report: { script: '04b-fixer/verify-patch', stage: 'fix', title: 'Patch verified in worktree' },
  rescan_report: { script: '05-verify/collect-rescan', stage: 'rescan', title: 'Re-scan facts collected' },
  redteam_report: { script: '05-verify/collect-redteam', stage: 'redteam', title: 'Red-team facts collected' },
  behavior_report: { script: '05-verify/collect-behavior', stage: 'behavior', title: 'Behaviour facts collected' },
  qa_report: { script: '06a-qa-runner/run-qa-gate', stage: 'qa', title: 'QA gate ran' },
  build_report: { script: '06b-build-gatekeeper/run-build-gate', stage: 'build', title: 'Build gate ran' },
  verdict: { script: '07a-merge-arbiter/compute-score', stage: 'verdict', title: 'Score computed' },
};

/** History that predates telemetry: clusters of collector/gate timestamps per agent. */
function reconstructed(ev: Evidence, details: Map<string, RunDetail>): RunSummary[] {
  const items = ev.files
    .filter((f) => COLLECTOR[f.type] && f.instants[0] && f.producerAgent)
    .map((f) => ({ f, t: f.instants[0], agent: f.producerAgent as string, c: COLLECTOR[f.type] }))
    .sort((a, b) => a.agent.localeCompare(b.agent) || a.t.localeCompare(b.t));
  const out: RunSummary[] = [];
  let cluster: typeof items = [];
  const flush = () => {
    if (!cluster.length) return;
    const agent = cluster[0].agent;
    const start = cluster[0].t;
    const end = cluster[cluster.length - 1].t;
    const runId = `recon:${agent}:${start}`;
    const issues = Array.from(new Set(cluster.map((x) => x.f.issueId).filter(Boolean) as string[]));
    const root: SpanView = {
      id: `run:${runId}`, parentId: null, kind: 'run', name: agent, detail: 'Reconstructed from evidence timestamps', skill: null, script: null, stage: null, stepKind: null, issues,
      status: 'completed', provenance: 'unknown', startedAt: start, endedAt: end, durationMs: Date.parse(end) - Date.parse(start), outcome: null, failure: null, inputs: [], outputs: [], attrs: {}, eventIds: [],
    };
    const spans: SpanView[] = [root, ...cluster.map((x) => ({
      id: `r:${x.f.path}`, parentId: root.id, kind: 'event' as const, name: `${x.c.title} — ${x.f.issueId}`, detail: `${x.c.script} (time recorded in the report's generator line)`, skill: x.c.script.split('/')[0], script: x.c.script,
      stage: x.c.stage, stepKind: null, issues: x.f.issueId ? [x.f.issueId] : [], status: 'info' as const, provenance: 'computed' as Provenance, startedAt: x.t, endedAt: x.t, durationMs: null,
      outcome: null, failure: null, inputs: [], outputs: [{ path: x.f.path, sha256: x.f.sha256 }], attrs: {}, eventIds: [],
    }))];
    // Failures are only what the evidence itself records (gate exit codes, compile-failed fixes);
    // the run's own status is unknown — nothing observed it finish.
    const failedPaths = new Set(cluster.filter((x) => {
      const gates = x.f.issueId ? ev.gate.get(x.f.issueId) : undefined;
      const g = gates && (Object.values(gates).find((r) => r?.file.path === x.f.path));
      if (g) return g.bodyStatus === 'Failed' || g.headerStatus === 'Failed';
      const fx = x.f.issueId ? ev.fix.get(x.f.issueId) : undefined;
      return Boolean(fx && fx.file.path === x.f.path && /fail/i.test(fx.status || ''));
    }).map((x) => x.f.path));
    const failures = failedPaths.size;
    for (const s of spans) if (s.outputs[0] && failedPaths.has(s.outputs[0].path)) s.status = 'failed';
    root.status = 'info';
    const summary: RunSummary = {
      runId, source: 'reconstructed', kind: 'reconstructed', agentId: agent, isMarsAgent: isMars(agent), sessionId: null, startedAt: start, endedAt: end, status: 'unknown',
      durationMs: Date.parse(end) - Date.parse(start), issues, operations: cluster.length, failures, writes: 0, label: `${agent} (reconstructed)`,
    };
    out.push(summary);
    details.set(runId, { ...summary, spans, events: [], note: 'No telemetry exists for this activity. Times are the collector/gate timestamps written into each report; durations, operations in between, agent-authored steps and any retries are unknown.' });
    cluster = [];
  };
  for (const x of items) {
    const prev = cluster[cluster.length - 1];
    if (prev && (prev.agent !== x.agent || Date.parse(x.t) - Date.parse(prev.t) > 45 * 60 * 1000)) flush();
    cluster.push(x);
  }
  flush();
  return out;
}
