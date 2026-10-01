import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from '@tanstack/react-router';
import { AlertTriangle, ArrowRight, Bot, ClipboardCheck, Clock, PauseCircle, PlayCircle, Radio, ShieldCheck } from 'lucide-react';
import type { ActiveRun, AttentionItem, LedgerEvent, NowState, Overview, TrustItem } from '../../shared/types';
import { STAGE_BY_ID } from '../../shared/stages';
import { eventTitle, isMarsAgent } from '../../shared/events';
import { useAudit, useOverview } from '../api';
import { useLive } from '../live';
import { RemediationBoard, StateLegend } from '../components/board';
import { HrefLink } from '../components/links';
import { EmptyState, ErrorState, Loading, Panel, Pill, ProvenanceBadge, StatusBadge, Time, duration, relTime, useTick } from '../components/ui';

function TrustStrip({ items }: { items: TrustItem[] }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-line bg-surface-2 px-4 py-1.5 text-xs sm:px-6" aria-label="Trust and health">
      <span className="font-semibold uppercase tracking-wide text-subtle">Trust</span>
      {items.map((t) => (
        <HrefLink key={t.id} href={t.link} title={t.detail} className={`inline-flex items-center gap-1 hover:no-underline ${t.status === 'fail' ? 'text-fail' : t.status === 'warn' ? 'text-tool' : t.status === 'ok' ? 'text-pass' : 'text-muted'}`}>
          <span aria-hidden>{t.status === 'fail' ? '▲' : t.status === 'warn' ? '◆' : t.status === 'ok' ? '●' : '○'}</span>
          {t.label}
        </HrefLink>
      ))}
    </div>
  );
}

function OpLine({ label, op, now }: { label: string; op: ActiveRun['current']; now: number }) {
  if (!op) return <div className="text-sm text-subtle">{label}: none observed</div>;
  const running = op.status === 'running';
  const elapsed = running ? now - Date.parse(op.startedAt) : op.durationMs;
  return (
    <div className="min-w-0">
      <div className="text-[11px] font-semibold uppercase tracking-wide text-subtle">{label}</div>
      <div className="flex flex-wrap items-center gap-2">
        <span className="mono truncate text-sm font-medium">{op.name}</span>
        <StatusBadge state={running ? 'running' : op.status === 'failed' ? 'failed' : op.status === 'refused' ? 'blocked' : 'passed'} label={running ? `Running ${duration(elapsed)}` : `${op.status} · ${duration(elapsed)}`} />
      </div>
      <div className="mt-0.5 flex flex-wrap gap-x-3 text-xs text-muted">
        {op.skill && <span>skill <Link to="/harness/skills/$skillId" params={{ skillId: op.skill }} className="mono">{op.skill}</Link></span>}
        {op.stage && <span>stage {STAGE_BY_ID[op.stage]?.label || op.stage}</span>}
        {op.stepKind && <span>step {op.stepKind}</span>}
        {op.issues.length > 0 && <span>issues {op.issues.map((i) => <Link key={i} to="/issues/$issueId" params={{ issueId: i }} className="mono mr-1">{i}</Link>)}</span>}
        {op.outcome && <span>reported {op.outcome}</span>}
      </div>
    </div>
  );
}

function ActiveRunCard({ run }: { run: ActiveRun }) {
  const now = useTick(1000);
  return (
    <article className={`rounded-md border p-3 ${run.waitingHuman ? 'border-2 border-human bg-human-tint' : 'border-run bg-surface'}`} aria-label={`Active run ${run.agentId || 'session'}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Bot size={16} className="text-run" />
          {run.agentId && run.isMarsAgent ? <Link to="/harness/agents/$agentId" params={{ agentId: run.agentId }} className="font-semibold">{run.agentId}</Link> : <span className="font-semibold">{run.agentId || 'Interactive session'}</span>}
          {!run.isMarsAgent && <Pill tone={run.skill ? 'run' : 'neutral'}>{run.skill ? 'interactive session running a MARS skill' : 'not a MARS agent'}</Pill>}
          {run.waitingHuman && <Pill tone="human">waiting for a human</Pill>}
        </div>
        <div className="flex items-center gap-3 text-xs text-muted">
          <span className="inline-flex items-center gap-1"><Clock size={12} /> elapsed <b className="tnum text-fg">{duration(now - Date.parse(run.startedAt))}</b></span>
          <span>last event {relTime(run.lastEventAt, now)}</span>
          <Link to="/runs/$runId" params={{ runId: run.runId }} className="font-medium">Run explorer →</Link>
        </div>
      </div>
      <div className="mt-3 grid gap-3 lg:grid-cols-3">
        <OpLine label="Now" op={run.current} now={now} />
        <OpLine label="Previous" op={run.previous} now={now} />
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wide text-subtle">Next expected</div>
          <div className="text-sm">{run.nextExpected || <span className="text-subtle">{run.isMarsAgent ? 'not predictable from the current step' : 'not applicable — not a MARS pipeline agent'}</span>}</div>
          {run.nextExpectedBasis && <div className="mt-0.5 text-[11px] text-subtle">{run.nextExpectedBasis}</div>}
        </div>
      </div>
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 border-t border-line pt-2 text-xs text-muted">
        <span>stage <b className="text-fg">{run.stage ? STAGE_BY_ID[run.stage]?.label : '—'}</b></span>
        <span>skill <b className="mono text-fg">{run.skill || '—'}</b></span>
        <span>issues <b className="text-fg">{run.issues.join(', ') || '—'}</b></span>
        <span className={run.failures ? 'text-fail' : ''}>failures <b className="tnum">{run.failures}</b></span>
        <span>artifacts <b className="tnum text-fg">{run.artifacts.length}</b>{run.artifacts.length ? `: ${run.artifacts.slice(-3).map((a) => a.path.split('/').pop()).join(', ')}` : ''}</span>
      </div>
    </article>
  );
}

function NowPanel({ now, overview }: { now: NowState; overview: Overview }) {
  const tick = useTick(15000);
  const next = overview.issues.filter((i) => i.nextAction).slice(0, 4);
  // Only MARS pipeline agents (01_…07_) count as "MARS running"; other Claude sessions in the
  // workspace are shown separately so ordinary editing is never presented as pipeline work.
  const agents = now.activeRuns.filter((r) => r.isMarsAgent);
  // A MARS skill's scripts run from an interactive session (e.g. 04d invoked directly) are MARS work too.
  const skillWork = now.activeRuns.filter((r) => !r.isMarsAgent && r.skill);
  const mars = [...agents, ...skillWork];
  const others = now.activeRuns.filter((r) => !r.isMarsAgent && !r.skill);
  const waiting = mars.some((r) => r.waitingHuman);
  const headline = waiting ? 'MARS is waiting for a human'
    : agents.length === 1 && !skillWork.length ? `MARS is running ${agents[0].agentId}`
    : agents.length ? `MARS is running ${agents.length} agent${agents.length > 1 ? 's' : ''}${skillWork.length ? ` and ${skillWork.length} skill session${skillWork.length > 1 ? 's' : ''}` : ''}`
    : skillWork.length === 1 ? `MARS skill ${skillWork[0].skill} is running in an interactive session`
    : skillWork.length ? `${skillWork.length} interactive sessions are running MARS skills`
    : 'No MARS agent is running';
  return (
    <Panel title="Now" id="now" subtitle={now.telemetry === 'none' ? 'Live telemetry is not configured — state below is derived from evidence on disk.' : `Live telemetry on · ${now.ledgerEvents} events in the ledger`}>
      <div className="space-y-3 p-3">
        <div className="flex flex-wrap items-center gap-2">
          <Radio size={18} className={mars.length ? 'text-run' : 'text-subtle'} />
          <h2 className="text-base font-semibold">{headline}</h2>
          {now.lastActivityAt && <span className="text-xs text-muted">· last activity {relTime(now.lastActivityAt, tick)} ({now.lastActivitySource === 'ledger' ? 'observed' : 'reconstructed from evidence'})</span>}
        </div>
        {mars.map((r) => <ActiveRunCard key={r.runId} run={r} />)}
        {others.length > 0 && (
          <details className="rounded border border-line bg-surface-2 text-sm" open={!mars.length && others.some((r) => r.waitingHuman)}>
            <summary className="cursor-pointer px-3 py-1.5 text-xs text-muted">
              {others.length} other Claude session{others.length > 1 ? 's' : ''} active in this workspace — not MARS pipeline agents{others.some((r) => r.waitingHuman) ? ' · one is waiting for a human' : ''}
            </summary>
            <div className="space-y-2 p-2">{others.map((r) => <ActiveRunCard key={r.runId} run={r} />)}</div>
          </details>
        )}
        {!mars.length && (
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="rounded border border-line bg-surface-2 p-3 text-sm">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-subtle">Most recent work</div>
              {now.lastEvidence ? (
                <div className="mt-1">
                  <Link to="/evidence/view" search={{ path: now.lastEvidence.path }} className="mono break-all text-xs">{now.lastEvidence.path}</Link>
                  <div className="mt-1 text-xs text-muted"><Time iso={now.lastEvidence.time} source="reconstructed" /> · {now.lastEvidence.stage ? STAGE_BY_ID[now.lastEvidence.stage]?.label : ''} {now.lastEvidence.issueId}</div>
                </div>
              ) : <div className="text-muted">No evidence timestamps found.</div>}
              {now.lastEvent && <div className="mt-2 text-xs text-muted">Last ledger event: <span className="mono">{now.lastEvent.type}</span> <Time iso={now.lastEvent.time} mode="rel" /></div>}
              <div className="mt-2 text-xs text-subtle">{now.telemetry === 'live' ? 'Agent runs will appear here as soon as Claude Code hooks report them.' : 'Enable the hooks in .claude/settings.json to see runs live.'}</div>
            </div>
            <div className="rounded border border-line bg-surface-2 p-3">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-subtle">What runs next (derived from evidence)</div>
              {next.length ? (
                <ul className="mt-1 space-y-1.5 text-sm">
                  {next.map((i) => (
                    <li key={i.id} className="flex gap-2">
                      <Link to="/issues/$issueId" params={{ issueId: i.id }} className="mono shrink-0 text-xs font-semibold">{i.id}</Link>
                      <span className="min-w-0"><span className={i.nextAction!.ownerKind === 'human' ? 'font-medium text-human' : ''}>{i.nextAction!.text}</span> <span className="text-xs text-subtle">— {i.nextAction!.owner}</span></span>
                    </li>
                  ))}
                </ul>
              ) : <div className="mt-1 text-sm text-muted">Nothing is ready to run.</div>}
            </div>
          </div>
        )}
      </div>
    </Panel>
  );
}

function AttentionBand({ items }: { items: AttentionItem[] }) {
  if (!items.length) return <div className="rounded-md border border-line bg-surface px-3 py-2 text-sm text-muted"><ShieldCheck size={14} className="mr-1 inline text-pass" />No decisions waiting and no evidence conflicts.</div>;
  return (
    <section aria-labelledby="attention" className="rounded-md border-2 border-human bg-surface">
      <header className="flex items-center justify-between gap-2 border-b border-line px-3 py-2">
        <h2 id="attention" className="flex items-center gap-2 text-[13px] font-semibold uppercase tracking-wide text-human"><ClipboardCheck size={15} /> Needs a human ({items.length})</h2>
        <span className="text-xs text-muted">Decisions, conflicts and waits that automation must not resolve on its own</span>
      </header>
      <ul className="divide-y divide-line">
        {items.map((a, i) => (
          <li key={i} className="flex flex-wrap items-start gap-3 px-3 py-2">
            <span className={a.severity === 'critical' ? 'text-fail' : a.kind === 'decision' ? 'text-human' : 'text-tool'} aria-hidden>{a.kind === 'decision' || a.kind === 'invalidated_approval' ? <ClipboardCheck size={16} /> : <AlertTriangle size={16} />}</span>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium">{a.title}</div>
              <div className="text-xs text-muted">{a.detail}</div>
            </div>
            <span className={`rounded px-1.5 py-px text-[11px] font-semibold uppercase ${a.severity === 'critical' ? 'bg-fail-tint text-fail' : 'bg-surface-3 text-muted'}`}>{a.kind.replace('_', ' ')}</span>
            <HrefLink href={a.link} className="inline-flex items-center gap-1 text-sm font-medium">Review <ArrowRight size={14} /></HrefLink>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function ActivityFeed({ events, fallback }: { events: LedgerEvent[]; fallback?: { time: string | null; title: string; actor: string; provenance: import('../../shared/types').Provenance; issueId: string | null }[] }) {
  const [paused, setPaused] = useState(false);
  const [frozen, setFrozen] = useState<LedgerEvent[] | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const shown = paused && frozen ? frozen : events;
  const newer = paused && frozen ? events.length - frozen.length : 0;
  useEffect(() => {
    if (!paused) setFrozen(null);
  }, [paused]);
  const pause = () => {
    if (!paused) {
      setFrozen(events);
      setPaused(true);
    }
  };
  const rows = useMemo(() => [...shown].reverse().slice(0, 60), [shown]);
  const announce = useMemo(() => [...events].reverse().find((e) => /^(gate|verdict|approval|guard|human|fix)\./.test(e.type) || (isMarsAgent(e.agent_id) && e.type.startsWith('agent_run.'))), [events]);
  return (
    <Panel title="Activity" id="activity" actions={
      <button type="button" className="inline-flex items-center gap-1 text-xs text-muted hover:text-fg" onClick={() => (paused ? setPaused(false) : pause())} aria-pressed={paused}>
        {paused ? <PlayCircle size={14} /> : <PauseCircle size={14} />}{paused ? 'Resume' : 'Pause'}
      </button>}>
      {newer > 0 && <button type="button" onClick={() => setPaused(false)} className="w-full bg-run-tint py-1 text-xs font-medium text-run">{newer} new event{newer > 1 ? 's' : ''} — show</button>}
      {/* Screen readers hear only pipeline-significant events, not every tool call. */}
      <div className="sr-only" aria-live="polite">{announce ? eventTitle(announce) : ''}</div>
      <div ref={listRef} role="log" aria-live="off" aria-label="Live activity" className="max-h-[560px] overflow-y-auto"
        onMouseEnter={pause} onMouseLeave={() => setPaused(false)} onFocus={pause} onScroll={(e) => { if ((e.target as HTMLDivElement).scrollTop > 4) pause(); }}>
        {rows.length > 0 ? (
          <ul className="divide-y divide-line">
            {rows.map((e) => (
              <li key={e.gseq} className="px-3 py-1.5 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className={`truncate font-medium ${e.type.endsWith('.failed') || e.type === 'guard.denied' ? 'text-fail' : ''}`} title={e.type}>{eventTitle(e)}</span>
                  <span className="shrink-0"><Time iso={e.time} mode="rel" /></span>
                </div>
                <div className="flex items-center gap-1.5 text-muted">
                  <span className="truncate">{e.agent_id ? (isMarsAgent(e.agent_id) ? e.agent_id : `${e.agent_id} (not MARS)`) : 'session'}</span>
                  {(e.issue_ids || []).map((i) => <span key={i} className="mono shrink-0 whitespace-nowrap">{i}</span>)}
                </div>
              </li>
            ))}
          </ul>
        ) : fallback && fallback.length ? (
          <>
            <div className="px-3 py-1.5 text-[11px] text-subtle">No ledger events yet. Most recent evidence (reconstructed from report timestamps):</div>
            <ul className="divide-y divide-line">
              {fallback.slice(0, 14).map((f, i) => (
                <li key={i} className="px-3 py-1.5 text-xs">
                  <div className="flex items-center justify-between gap-2"><span className="truncate font-medium">{f.title}</span><Time iso={f.time} mode="rel" /></div>
                  <div className="flex items-center gap-1.5 text-muted"><ProvenanceBadge p={f.provenance} />{f.issueId && <span className="mono shrink-0 whitespace-nowrap">{f.issueId}</span>}<span className="truncate">{f.actor}</span></div>
                </li>
              ))}
            </ul>
          </>
        ) : <EmptyState title="No activity yet" body="Nothing has been recorded by the ledger and no evidence timestamps were found." />}
      </div>
    </Panel>
  );
}

export function HomePage() {
  const q = useOverview();
  const live = useLive();
  const audit = useAudit(null);
  if (q.isLoading) return <Loading rows={8} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const o = q.data;
  const s = o.summary;
  const fallback = (audit.data?.items || []).filter((x) => x.time && x.time.length > 10).slice(0, 14).map((x) => ({ time: x.time, title: x.title, actor: x.actor, provenance: x.provenance, issueId: x.issueId }));
  return (
    <div>
      <TrustStrip items={o.trust} />
      <div className="grid gap-4 p-4 sm:p-6 min-[1600px]:grid-cols-[minmax(0,1fr)_340px]">
        <div className="min-w-0 space-y-4">
          <NowPanel now={o.now} overview={o} />
          <AttentionBand items={o.attention} />
          <Panel title="Remediation board" id="board" subtitle={
            <span>
              <b className="text-fg">{s.issues}</b> issues · <b className={s.awaitingDecision ? 'text-human' : 'text-fg'}>{s.awaitingDecision}</b> awaiting decision
              {s.approvalsToReview > 0 && <> · <b className="text-human">{s.approvalsToReview}</b> approvals to re-review</>}
              {' '}· <b className="text-fg">{s.blocked}</b> Blocked
              {s.blockedBy.length > 0 && <> ({s.blockedBy.map((b) => `${b.label} ${b.count}${b.verified ? '' : ', cause unverified'}`).join(' · ')})</>}
              {' '}· <b className="text-fg">{s.cleared}</b> Cleared · publication not recorded by MARS · <b className={s.integrityFindings ? 'text-fail' : 'text-fg'}>{s.integrityFindings}</b> critical/major findings
            </span>}>
            <RemediationBoard issues={o.issues} stages={o.stages} />
            <div className="border-t border-line px-3 py-2"><StateLegend /></div>
          </Panel>
        </div>
        <div className="min-w-0 space-y-4">
          <ActivityFeed events={live.events} fallback={fallback} />
        </div>
      </div>
    </div>
  );
}
