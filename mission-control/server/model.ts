/**
 * Mission Control's projection model. Rebuildable cache over the workspace — never authoritative.
 *   evidence-derived state  → recomputed when files under docs/agent_output, .claude or the code model change
 *   ledger-derived state    → recomputed when new ledger lines arrive
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import type {
  AttentionItem, AuditItem, BoardSummary, HealthCheck, IntegrityFinding, IssueDetail, IssueSummary, NowState, Overview, Registry, TrustItem, WorkspaceInfo,
} from '../shared/types.js';
import { STAGES } from '../shared/stages.js';
import { eventTitle } from '../shared/events.js';
import type { LedgerEvent } from '../shared/types.js';
import { Workspace } from './sources/workspace.js';
import { loadEvidence, type Evidence } from './sources/evidence.js';
import { readRegister, type RegisterIssue } from './lib/harness.js';
import { loadDecisions, type DecisionRecord } from './sources/decisions.js';
import { loadRegistry } from './sources/registry.js';
import { loadCodeModel, type CodeModel } from './sources/codemodel.js';
import { LedgerReader } from './sources/ledger.js';
import { corroborateDecisions, runIntegrity } from './projection/integrity.js';
import { DEFAULT_SCORING, type Scoring } from './projection/scoring.js';
import { buildIssue, issueIds, sortIssues, toSummary } from './projection/issues.js';
import { buildRuns, type RunsProjection } from './projection/runs.js';
import { runHealth } from './projection/health.js';

export interface ModelOptions {
  root: string;
  ledgerDir?: string;
  enableDecisions?: boolean;
  /** Test-only: permit decisions from a server started inside an agent session, but only for a temp workspace. */
  allowDecisionsInAgentSession?: boolean;
  actor?: string | null;
  watch?: boolean;
  now?: () => Date;
}

interface StaticState {
  version: number;
  ev: Evidence;
  register: RegisterIssue[];
  registerError: string | null;
  decisions: DecisionRecord[];
  registry: Registry;
  cm: CodeModel;
  scoring: Scoring | null;
  scoringSha: string | null;
  findings: IntegrityFinding[];
  hooksConfigured: boolean;
  builtAt: string;
}

interface LiveState {
  key: string;
  runs: RunsProjection;
  details: Map<string, IssueDetail>;
  summaries: IssueSummary[];
}

function insideTmp(dir: string): boolean {
  try {
    const rel = path.relative(fs.realpathSync(os.tmpdir()), fs.realpathSync(dir));
    return Boolean(rel) && !rel.startsWith('..') && !path.isAbsolute(rel);
  } catch {
    return false;
  }
}

export class Model extends EventEmitter {
  readonly ws: Workspace;
  readonly ledger: LedgerReader;
  readonly opts: ModelOptions;
  private version = 1;
  private staticState: StaticState | null = null;
  private live: LiveState | null = null;
  private watchers: fs.FSWatcher[] = [];
  private debounce: NodeJS.Timeout | null = null;
  private health: HealthCheck[] | null = null;
  private healthAt = 0;
  private healthRunning: Promise<HealthCheck[]> | null = null;

  constructor(opts: ModelOptions) {
    super();
    this.opts = opts;
    this.ws = new Workspace(opts.root);
    this.ledger = new LedgerReader(opts.ledgerDir ? path.resolve(opts.ledgerDir) : path.join(this.ws.root, '.mars', 'ledger'));
    this.ledger.on('events', (evs) => this.emit('events', evs));
  }

  now(): Date {
    return this.opts.now ? this.opts.now() : new Date();
  }

  start(): void {
    this.ledger.start(500);
    if (this.opts.watch === false) return;
    // Only files the projection reads: evidence, definitions and the code model. Gate runs write a lot
    // of scratch output under .claude (worktrees, logs, work dirs); those must not rebuild every view.
    const RELEVANT: Record<string, RegExp | null> = {
      'docs/agent_output': null,
      '.claude/agents': /\.md$/,
      '.claude/skills': /^[0-9a-z-]+[\\/](SKILL\.md|scoring\.json|ranking-weights\.json|scripts[\\/][^\\/]+\.(js|mjs|py)|(catalog|knowledge|templates|schemas)[\\/][^\\/]+\.json)$/,
      '.claude/.pipeline-context': /^(artifacts\.json|context[\\/]descriptions\.json)$/,
      '.claude/settings.json': null,
    };
    for (const [t, keep] of Object.entries(RELEVANT)) {
      const abs = path.join(this.ws.root, t);
      if (!fs.existsSync(abs)) continue;
      try {
        const isDir = fs.statSync(abs).isDirectory();
        const w = fs.watch(abs, { recursive: isDir }, (_e, file) => {
          const f = String(file || '');
          if (/node_modules|\.lock$|\.tmp$|worktrees[\\/]/.test(f)) return;
          if (keep && f && !keep.test(f)) return;
          this.invalidate(`${t}${f ? `/${f.replace(/\\/g, '/')}` : ''}`);
        });
        w.on('error', () => undefined);
        this.watchers.push(w);
      } catch {
        /* watching is best-effort; the API still recomputes on request */
      }
    }
  }

  stop(): void {
    this.ledger.stop();
    for (const w of this.watchers) w.close();
    this.watchers = [];
    if (this.debounce) clearTimeout(this.debounce);
  }

  invalidate(reason: string): void {
    if (this.debounce) clearTimeout(this.debounce);
    this.debounce = setTimeout(() => {
      this.version += 1;
      this.staticState = null;
      this.live = null;
      this.emit('snapshot', { version: this.version, reason });
    }, 300);
  }

  getVersion(): number {
    return this.version;
  }

  decisionsEnabled(): { enabled: boolean; reason: string } {
    if (!this.opts.enableDecisions) return { enabled: false, reason: 'Recorded decisions are off. Start Mission Control with --enable-decisions to record human plan decisions; otherwise edit the plan\'s Status cell by hand, exactly as the MARS contract describes.' };
    // Same marker list as record-decision.js (AGENT_MARKER), so the UI never says "enabled" and then 403s.
    const markers = Object.keys(process.env).filter((k) => /^(CLAUDECODE|CLAUDE_PID|CLAUDE_CODE_(SESSION_ID|ENTRYPOINT|CHILD_SESSION|EXECPATH|SSE_PORT))$/.test(k));
    if (markers.length && !(this.opts.allowDecisionsInAgentSession && insideTmp(this.ws.root))) {
      return { enabled: false, reason: `This Mission Control server was started from inside an agent session (${markers.join(', ')}). Plan decisions are reserved for humans, so recording is refused. Start the server from your own terminal.` };
    }
    if (!this.ws.exists('.claude/scripts/record-decision.js')) return { enabled: false, reason: 'The MARS decision command (.claude/scripts/record-decision.js) is not present in this workspace.' };
    return { enabled: true, reason: `Decisions are recorded as ${this.actor() || 'the OS user'} (LOCALLY_ASSERTED — no identity provider is configured).` };
  }

  actor(): string | null {
    if (this.opts.actor) return this.opts.actor;
    if (process.env.MC_ACTOR) return process.env.MC_ACTOR;
    try {
      return os.userInfo().username || null;
    } catch {
      return null;
    }
  }

  state(): StaticState {
    if (this.staticState && this.staticState.version === this.version) return this.staticState;
    const ev = loadEvidence(this.ws);
    const reg = readRegister(this.ws);
    const decisions = loadDecisions(this.ws);
    const registry = loadRegistry(this.ws);
    const cm = loadCodeModel(this.ws);
    const scoringRel = '.claude/skills/07a-merge-arbiter/scoring.json';
    const scoring = this.ws.readJson<Scoring>(scoringRel) || (this.ws.exists(scoringRel) ? null : DEFAULT_SCORING);
    this.ledger.poll();
    const findings = [...runIntegrity(ev, reg.issues, decisions, scoring, cm), ...corroborateDecisions(decisions, this.ledger.all())];
    // Provenance is declared per artifact type; a report found to contain non-renderer (agent) text
    // (R6) is no longer purely computed, whatever its type says.
    for (const f of findings.filter((x) => x.rule === 'R6')) {
      for (const p of f.subjects) {
        const rec = ev.byPath.get(p);
        if (rec && rec.provenance === 'computed') {
          rec.provenance = 'mixed';
          rec.provenanceNote = `Declared computed by its renderer, but contains agent-inserted text (R6). ${rec.provenanceNote || ''}`.trim();
        }
      }
    }
    const settings = this.ws.read('.claude/settings.json') || '';
    this.staticState = {
      version: this.version, ev, register: reg.issues, registerError: reg.error, decisions, registry, cm, scoring,
      scoringSha: this.ws.sha256(scoringRel), findings, hooksConfigured: /mars-hook\.js/.test(settings), builtAt: this.now().toISOString(),
    };
    return this.staticState;
  }

  private liveState(): LiveState {
    this.ledger.poll();
    const s = this.state();
    const key = `${s.version}:${this.ledger.lastSeq()}:${Math.floor(this.now().getTime() / 15000)}`;
    if (this.live && this.live.key === key) return this.live;
    const runs = buildRuns(this.ledger.all(), s.ev, this.now());
    const details = new Map<string, IssueDetail>();
    const ctx = { ev: s.ev, register: s.register, decisions: s.decisions, findings: s.findings, scoring: s.scoring, cm: s.cm, openOps: runs.openOps, ledgerByIssue: runs.ledgerByIssue, decisionsEnabled: this.decisionsEnabled(), now: this.now() };
    for (const id of issueIds(s)) {
      const d = buildIssue(id, ctx);
      if (d.verdictDetail) d.verdictDetail.policySha256 = s.scoringSha;
      details.set(id, d);
    }
    const summaries = sortIssues(Array.from(details.values()).map(toSummary));
    for (const a of s.registry.agents) {
      const st = runs.agentStats.get(a.id);
      a.telemetry = st ? { runs: st.runs, lastRunAt: st.lastRunAt, failures: st.failures, avgDurationMs: st.durations.length ? Math.round(st.durations.reduce((x, y) => x + y, 0) / st.durations.length) : null, active: st.active } : null;
    }
    for (const sk of s.registry.skills) {
      const st = runs.skillStats.get(sk.id);
      sk.telemetry = st ? { executions: st.executions, lastAt: st.lastAt, failures: st.failures, active: st.active } : null;
    }
    this.live = { key, runs, details, summaries };
    return this.live;
  }

  workspaceInfo(): WorkspaceInfo {
    const s = this.state();
    const g = this.ws.gitHead();
    return {
      root: this.ws.root, name: path.basename(this.ws.root), branch: g.branch, head: g.head,
      runtimes: { claude: this.ws.exists('.claude/agents'), github: this.ws.exists('.github/agents') },
      hooksConfigured: s.hooksConfigured, decisions: { ...this.decisionsEnabled(), actor: this.actor() }, scoringSha256: s.scoringSha,
    };
  }

  issues(): IssueSummary[] {
    return this.liveState().summaries;
  }

  issue(id: string): IssueDetail | null {
    return this.liveState().details.get(id) || null;
  }

  runs(): RunsProjection {
    return this.liveState().runs;
  }

  registry(): Registry {
    this.liveState();
    return this.state().registry;
  }

  findings(): IntegrityFinding[] {
    return this.state().findings;
  }

  nowState(): NowState {
    const live = this.liveState();
    const events = this.ledger.all();
    const last = events[events.length - 1] || null;
    const s = this.state();
    type Ev = { path: string; time: string; issueId: string | null; stage: typeof s.ev.files[number]['stage'] };
    const candidates: Ev[] = s.ev.files
      .map((f) => ({ path: f.path, time: f.instants[f.instants.length - 1] || null, issueId: f.issueId, stage: f.stage }))
      .filter((x): x is Ev => Boolean(x.time));
    // The code model is evidence too (01a writes it); without it "last activity" can predate the newest file on disk.
    if (s.cm.generatedAt) candidates.push({ path: '.claude/.pipeline-context/artifacts.json', time: s.cm.generatedAt, issueId: null, stage: 'architecture' });
    const lastEvidence = candidates.sort((a, b) => b.time.localeCompare(a.time))[0] || null;
    const active = live.runs.active;
    const status: NowState['status'] = active.some((a) => a.waitingHuman) ? 'waiting_human' : active.length ? 'running' : 'idle';
    const lastActivityAt = [last?.time, lastEvidence?.time].filter(Boolean).sort().pop() || null;
    return {
      status, telemetry: events.length || s.hooksConfigured ? 'live' : 'none', ledgerEvents: events.length, activeRuns: active, lastEvent: last,
      lastActivityAt, lastActivitySource: last && (!lastEvidence || last.time >= lastEvidence.time) ? 'ledger' : lastEvidence ? 'evidence' : null, lastEvidence,
    };
  }

  overview(): Overview {
    const s = this.state();
    const issues = this.issues();
    const now = this.nowState();
    return {
      asOf: this.now().toISOString(), version: this.version, workspace: this.workspaceInfo(), now, attention: this.attention(issues, now), stages: STAGES,
      issues, summary: this.summary(issues, now), trust: this.trust(s),
    };
  }

  private summary(issues: IssueSummary[], now: NowState): BoardSummary {
    const blockedBy = new Map<string, { count: number; verified: boolean }>();
    for (const i of issues) {
      if (i.cells.verdict.state !== 'blocked' || !i.blocker) continue;
      const seen = new Set<string>();
      for (const c of i.blocker.causes) {
        const label = c.failureClass?.startsWith('security') ? 'Security verification' : c.failureClass === 'behavior.regression' ? 'Behavioural regression'
          : c.stage === 'build' ? 'Build gate' : c.stage === 'qa' ? 'QA gate' : c.kind === 'score' ? null : 'Other';
        if (!label || seen.has(label)) continue;
        seen.add(label);
        const cur = blockedBy.get(label) || { count: 0, verified: true };
        cur.count += 1;
        cur.verified = cur.verified && c.verified;
        blockedBy.set(label, cur);
      }
    }
    return {
      issues: issues.length,
      awaitingDecision: issues.filter((i) => i.cells.approval.state === 'awaiting_human').length,
      approvalsToReview: issues.filter((i) => {
        const ap = this.issue(i.id)?.approval;
        return Boolean(ap && (ap.state === 'approved' || ap.state === 'approved_unattributed') && (ap.plan?.reproposed || ap.implementation?.deviation));
      }).length,
      blocked: issues.filter((i) => i.cells.verdict.state === 'blocked').length,
      cleared: issues.filter((i) => i.cells.verdict.state === 'passed').length,
      published: 0,
      running: now.activeRuns.length,
      blockedBy: Array.from(blockedBy.entries()).map(([label, v]) => ({ label, ...v })).sort((a, b) => b.count - a.count),
      integrityFindings: this.state().findings.filter((f) => f.severity === 'critical' || f.severity === 'major').length,
    };
  }

  private attention(issues: IssueSummary[], now: NowState): AttentionItem[] {
    const items: AttentionItem[] = [];
    const findings = this.state().findings;
    for (const r of now.activeRuns.filter((a) => a.waitingHuman)) {
      items.push({ kind: 'running_human_wait', issueId: r.issues[0] || null, title: `${r.agentId || 'Session'} is waiting for a human`, detail: 'The agent runtime raised a permission or input prompt.', severity: 'critical', since: r.lastEventAt, link: `/runs/${encodeURIComponent(r.runId)}` });
    }
    for (const i of issues) {
      if (i.cells.approval.state === 'awaiting_human') {
        items.push({ kind: i.cells.approval.label.startsWith('Re-decision') ? 'invalidated_approval' : 'decision', issueId: i.id, title: `${i.id}: ${i.cells.approval.label}`, detail: i.title, severity: 'major', since: i.cells.plan.time, link: `/approvals/${i.id}` });
      }
      if (i.cells.publication.state === 'ready') items.push({ kind: 'publication', issueId: i.id, title: `${i.id}: Cleared — eligible for a publication request`, detail: 'Publication happens only on an explicit human request.', severity: 'minor', since: i.cells.verdict.time, link: `/issues/${i.id}` });
      const crit = findings.filter((f) => f.issueId === i.id && f.severity === 'critical');
      if (crit.length) {
        // Lead with the cause, not the consequence: when a report contradicts its own exit codes (R1),
        // a header-based replay that "clears" (R3) is a symptom of the altered header, not good news.
        const altered = crit.find((f) => f.rule === 'R1');
        const flips = crit.find((f) => f.rule === 'R3');
        const d = this.issue(i.id);
        const bodyAgrees = d?.verdictDetail?.bodyReplay?.matchesRecorded;
        items.push({
          kind: 'integrity', issueId: i.id,
          title: altered ? `${i.id}: gate reports contradict their own exit codes` : flips ? `${i.id}: recorded verdict is not reproducible from current evidence` : `${i.id}: evidence contradicts itself`,
          detail: altered
            ? `Report headers say Passed; the exit-code tables say Failed.${bodyAgrees ? ` Re-scoring from the exit codes reproduces the recorded ${d?.verdict?.decision || 'verdict'}.` : ''} Re-run 06 before relying on any verdict.`
            : flips ? flips.title : crit.map((f) => f.ruleTitle).filter((v, k, a) => a.indexOf(v) === k).join(' · '),
          severity: 'critical', since: null, link: `/issues/${i.id}?tab=verification`,
        });
      }
      // An approval that was carried over a re-proposal, or that the implementation departs from, no
      // longer covers what is on disk: a human should re-review it (proposal §20).
      const ap = this.issue(i.id)?.approval;
      if (ap && (ap.state === 'approved' || ap.state === 'approved_unattributed') && (ap.plan?.reproposed || ap.implementation?.deviation)) {
        items.push({
          kind: 'invalidated_approval', issueId: i.id,
          title: `${i.id}: approval does not cover the current plan or patch`,
          detail: [ap.plan?.reproposed ? 'The plan was re-proposed after it was approved; the Approved status was carried over.' : '', ap.implementation?.deviation ? `Implementation deviates: ${ap.implementation.deviation}` : ''].filter(Boolean).join(' '),
          severity: 'major', since: null, link: `/approvals/${i.id}`,
        });
      }
    }
    for (const f of findings.filter((x) => !x.issueId && x.severity === 'critical')) items.push({ kind: 'integrity', issueId: null, title: f.title, detail: f.detail, severity: 'critical', since: null, link: '/evidence?tab=integrity' });
    // Guard events need a human to look — for a day, deduplicated per rule and path, so one old event
    // does not hold the critical badge up forever (the full history stays in Audit and the run trace).
    const horizon = this.now().getTime() - 24 * 3600 * 1000;
    const recentGuards = new Map<string, LedgerEvent>();
    for (const e of this.ledger.all()) {
      if ((e.type !== 'guard.would_deny' && e.type !== 'guard.denied') || Date.parse(e.time) < horizon) continue;
      const a = (e.attrs || {}) as { rule?: string; path?: string };
      recentGuards.set(`${a.rule || ''}|${a.path || ''}|${e.type}`, e);
    }
    for (const e of recentGuards.values()) {
      const a = (e.attrs || {}) as { rule?: string; path?: string; tool?: string };
      const who = e.agent_id || 'A Claude session';
      const decisionCmd = a.rule === 'agent-ran-decision-command';
      const n = this.ledger.all().filter((x) => x.type === e.type && (x.attrs as { rule?: string } | undefined)?.rule === a.rule && (x.attrs as { path?: string } | undefined)?.path === a.path && Date.parse(x.time) >= horizon).length;
      items.push({
        kind: 'integrity', issueId: e.issue_ids?.[0] || null,
        title: decisionCmd ? 'An agent tried to run the human decision command' : e.type === 'guard.denied' ? `Agent edit of ${a.path || 'protected evidence'} was denied` : `An agent edited ${a.path || 'protected evidence'}`,
        detail: decisionCmd
          ? `${who} ran a command that executes record-decision.js; the hook denied it. Plan decisions are reserved for a human.${n > 1 ? ` ${n} times in the last 24 h.` : ''}`
          : `${who} used ${a.tool || 'an edit tool'} on rendered evidence${e.type === 'guard.would_deny' ? ' (guard in observe mode: the edit was allowed)' : ''}. Rendered evidence should only change through its renderer.${n > 1 ? ` ${n} times in the last 24 h.` : ''}`,
        severity: 'critical', since: e.time, link: e.run_id ? `/runs/${encodeURIComponent(e.run_id)}` : '/audit',
      });
    }
    const order = { critical: 0, major: 1, minor: 2, info: 3 };
    return items.sort((a, b) => order[a.severity] - order[b.severity]);
  }

  /** The evidence-guard mode Claude sessions in this workspace run with (settings env), default observe. */
  guardMode(): 'observe' | 'enforce' | 'off' {
    for (const f of ['.claude/settings.local.json', '.claude/settings.json']) {
      const v = String(this.ws.readJson<{ env?: Record<string, string> }>(f)?.env?.MARS_EVIDENCE_GUARD || '').toLowerCase();
      if (v === 'observe' || v === 'enforce' || v === 'off') return v;
    }
    return 'observe';
  }

  private trust(s: StaticState): TrustItem[] {
    const guardMode = () => this.guardMode();
    const crit = s.findings.filter((f) => f.severity === 'critical').length;
    const major = s.findings.filter((f) => f.severity === 'major').length;
    const unattributed = s.findings.filter((f) => f.rule === 'R12').length;
    const graph = s.findings.find((f) => f.rule === 'R11');
    const foreign = s.findings.filter((f) => f.rule === 'R7').length;
    const toolchain = this.health?.find((h) => h.id === 'toolchain');
    const items: TrustItem[] = [
      { id: 'integrity', label: crit || major ? `${crit} critical · ${major} major findings` : 'Evidence consistent', status: crit ? 'fail' : major ? 'warn' : 'ok', detail: `${crit} critical, ${major} major, ${s.findings.length - crit - major} minor/info integrity findings (${s.findings.length} total)`, link: '/evidence?tab=integrity' },
      { id: 'guard', label: `Evidence guard: ${guardMode()}`, status: guardMode() === 'enforce' ? 'ok' : 'warn', detail: guardMode() === 'enforce' ? 'Agent edits of rendered evidence, the register and decision records are denied.' : guardMode() === 'observe' ? 'Agent edits of rendered evidence are recorded but allowed (MARS_EVIDENCE_GUARD=observe). Set MARS_EVIDENCE_GUARD=enforce to deny them.' : 'Evidence guard is off (MARS_EVIDENCE_GUARD=off).', link: '/audit' },
      { id: 'attribution', label: unattributed ? `${unattributed} unattributed approvals` : 'Approvals attributed', status: unattributed ? 'warn' : 'ok', detail: 'Plan decisions with no record of who decided, when, or which version.', link: '/approvals' },
      { id: 'provenance', label: foreign ? 'Evidence from another checkout' : 'Evidence produced here', status: foreign ? 'warn' : 'ok', detail: foreign ? `${foreign} reports reference a different checkout and the former .architect data directory.` : '', link: '/evidence?tab=integrity' },
      { id: 'graph', label: graph ? 'Graph may be stale' : (s.cm.available ? 'Code model available' : 'No code model'), status: graph || !s.cm.available ? 'warn' : 'ok', detail: graph ? graph.title : `${s.cm.counts.types} types, ${s.cm.counts.methods} methods`, link: '/architecture' },
      { id: 'toolchain', label: toolchain ? (toolchain.status === 'pass' ? 'Toolchain matches target' : 'Toolchain mismatch') : 'Toolchain not checked', status: toolchain ? (toolchain.status === 'pass' ? 'ok' : 'warn') : 'unknown', detail: toolchain?.summary || 'Open Harness → Health to probe.', link: '/harness?tab=health' },
      { id: 'telemetry', label: s.hooksConfigured ? (this.ledger.all().length ? 'Live telemetry on' : 'Telemetry configured, no events yet') : 'Live telemetry off', status: s.hooksConfigured ? 'ok' : 'warn', detail: s.hooksConfigured ? `${this.ledger.all().length} ledger events` : 'Claude Code hooks are not configured; only evidence-derived state is shown.', link: '/runs' },
    ];
    return items;
  }

  audit(issueId?: string | null): AuditItem[] {
    const s = this.state();
    const out: AuditItem[] = [];
    const ids = issueId ? [issueId] : issueIds(s);
    for (const id of ids) {
      const d = this.issue(id);
      if (!d) continue;
      for (const t of d.timeline) out.push({ ...t, issueId: id, type: t.stage || 'event' });
    }
    if (!issueId) {
      if (s.cm.generatedAt) out.push({ time: s.cm.generatedAt, timeSource: 'reconstructed', stage: 'architecture', actorKind: 'script', actor: '01a-code-cartographer/scan', provenance: 'computed', title: 'Code model scanned (artifacts.json)', detail: `${s.cm.counts.types} types, ${s.cm.counts.methods} methods`, evidence: null, gap: false, issueId: null, type: 'architecture' });
      const desc = this.ws.readJson<{ generatedAt?: string; author?: string }>('.claude/.pipeline-context/context/descriptions.json');
      if (desc?.generatedAt) out.push({ time: desc.generatedAt, timeSource: 'reconstructed', stage: 'architecture', actorKind: 'agent', actor: desc.author || '01_architect', provenance: 'ai_authored', title: 'Semantic layer written (descriptions.json)', detail: `Author: ${desc.author || 'unknown'}`, evidence: null, gap: false, issueId: null, type: 'architecture' });
      for (const e of this.ledger.all().filter((x) => x.type.startsWith('guard.') || x.type === 'approval.recorded')) {
        if (e.issue_ids?.length) continue;
        out.push({ time: e.time, timeSource: 'observed', stage: (e.stage_id as AuditItem['stage']) || null, actorKind: (e.actor?.kind as AuditItem['actorKind']) || 'unknown', actor: e.actor?.id || e.agent_id || 'unknown', provenance: (e.provenance as AuditItem['provenance']) || (e.type.startsWith('guard.') ? 'computed' : 'unknown'), title: eventTitle(e), detail: e.type.startsWith('guard.') ? 'Recorded by the MARS hook (deterministic).' : null, evidence: null, gap: false, issueId: null, type: e.type });
      }
    }
    for (const row of out) {
      if (!row.evidence) continue;
      const n = s.findings.filter((f) => (f.severity === 'critical' || f.severity === 'major') && f.subjects.includes(row.evidence!.path)).length;
      if (n) row.conflicts = n;
    }
    return out.sort((a, b) => (b.time || '').localeCompare(a.time || ''));
  }

  ledgerStatus(): { events: number; malformed: number; lastEventAt: string | null; dir: string } {
    this.ledger.poll();
    const all = this.ledger.all();
    return { events: all.length, malformed: this.ledger.malformed, lastEventAt: all.length ? all[all.length - 1].time : null, dir: this.ledger.dir };
  }

  async healthChecks(force = false): Promise<HealthCheck[]> {
    if (!force && this.health && Date.now() - this.healthAt < 60000) return this.health;
    if (this.healthRunning) return this.healthRunning;
    const s = this.state();
    this.healthRunning = runHealth({ ws: this.ws, registry: s.registry, cm: s.cm, ev: s.ev, findings: s.findings, ledger: this.ledgerStatus(), hooksConfigured: s.hooksConfigured })
      .then((r) => {
        this.health = r;
        this.healthAt = Date.now();
        return r;
      })
      .finally(() => {
        this.healthRunning = null;
      });
    return this.healthRunning;
  }

  cachedHealth(): HealthCheck[] | null {
    return this.health;
  }
}
