import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { ColumnDef } from '@tanstack/react-table';
import { Bot, Cog, FileText, Layers } from 'lucide-react';
import type { CellState, LedgerEvent, RunSummary, SpanView } from '../../shared/types';
import { STAGE_BY_ID } from '../../shared/stages';
import { useRun, useRuns } from '../api';
import { useLive } from '../live';
import { PageHeader } from '../components/shell';
import { DataTable, Drawer, EmptyState, ErrorState, HashChip, KV, Loading, Panel, Pill, ProvenanceBadge, StatusBadge, StatusGlyph, Tabs, Time, duration, useTick } from '../components/ui';
import { JsonView } from '../components/content';

const RUN_STATE: Record<RunSummary['status'], CellState> = { running: 'running', completed: 'passed', failed: 'failed', stale: 'inconclusive', unknown: 'unknown' };
const SPAN_STATE: Record<SpanView['status'], CellState> = { running: 'running', completed: 'passed', failed: 'failed', refused: 'blocked', info: 'not_applicable' };

export function RunsPage() {
  const q = useRuns();
  const nav = useNavigate();
  const [src, setSrc] = useState<'all' | 'observed' | 'reconstructed'>('all');
  const rows = useMemo(() => (q.data?.runs || []).filter((r) => src === 'all' || r.source === src), [q.data, src]);
  const cols = useMemo<ColumnDef<RunSummary, unknown>[]>(() => [
    { id: 'status', header: 'Status', accessorKey: 'status', cell: (c) => <StatusBadge state={RUN_STATE[c.row.original.status]} label={c.row.original.source === 'reconstructed' ? 'not observed' : c.row.original.status} /> },
    { id: 'label', header: 'Run', accessorKey: 'label', cell: (c) => <Link to="/runs/$runId" params={{ runId: c.row.original.runId }} className="font-medium" onClick={(e) => e.stopPropagation()}>{c.row.original.label}</Link> },
    { id: 'agent', header: 'Agent', accessorFn: (r) => r.agentId || '', cell: (c) => c.row.original.agentId ? <span className="mono text-xs">{c.row.original.agentId}</span> : <span className="text-subtle">—</span> },
    { id: 'source', header: 'Source', accessorKey: 'source', cell: (c) => <Pill tone={c.row.original.source === 'observed' ? 'run' : 'neutral'}>{c.row.original.source}</Pill> },
    { id: 'issues', header: 'Issues', accessorFn: (r) => r.issues.join(','), cell: (c) => <span className="mono text-xs">{c.row.original.issues.join(', ') || '—'}</span> },
    { id: 'started', header: 'Started', accessorKey: 'startedAt', cell: (c) => <Time iso={c.row.original.startedAt} mode="both" source={c.row.original.source === 'observed' ? 'observed' : 'reconstructed'} /> },
    { id: 'dur', header: 'Duration', accessorFn: (r) => r.durationMs ?? -1, cell: (c) => {
      const r = c.row.original;
      // A reconstructed run is bounded by report timestamps only: a single timestamp has no measurable duration.
      if (r.source === 'reconstructed') return <span className="tnum text-muted" title="Approximate: span between the first and last report timestamps of this run">{r.durationMs && r.durationMs >= 1000 ? `≈ ${duration(r.durationMs)}` : 'not measurable'}</span>;
      return <span className="tnum">{duration(r.durationMs)}</span>;
    } },
    { id: 'ops', header: 'Ops', accessorKey: 'operations', cell: (c) => <span className="tnum">{c.row.original.operations}{c.row.original.failures ? <span className="ml-1 text-fail">({c.row.original.failures} failed{c.row.original.source === 'reconstructed' ? ' per evidence' : ''})</span> : ''}</span> },
  ], []);
  if (q.isLoading) return <Loading rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const led = q.data.ledger;
  return (
    <div>
      <PageHeader title="Runs" subtitle="Observed runs come from the event ledger (hook and script witnesses). Reconstructed runs are inferred from timestamps inside reports and are approximate." />
      <div className="space-y-4 p-4 sm:p-6">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="text-muted">Ledger: <b className="text-fg">{led.events}</b> events{led.malformed ? <span className="text-tool"> · {led.malformed} malformed lines skipped</span> : ''} · last <Time iso={led.lastEventAt} mode="rel" /></span>
          <div role="group" aria-label="Source" className="ml-auto flex gap-1">
            {(['all', 'observed', 'reconstructed'] as const).map((s) => <button key={s} type="button" aria-pressed={src === s} onClick={() => setSrc(s)} className={`h-7 rounded border px-2 text-xs ${src === s ? 'border-accent bg-surface-3 font-semibold' : 'border-line'}`}>{s}</button>)}
          </div>
        </div>
        {led.events === 0 && <div className="rounded border border-line bg-surface-2 px-3 py-2 text-sm">No observed runs yet. Telemetry starts when Claude Code loads the hooks in <span className="mono">.claude/settings.json</span> (restart the session) or when an instrumented gate script runs.</div>}
        <Panel>
          <DataTable label="Runs" data={rows} columns={cols} rowKey={(r) => r.runId} onRowClick={(r) => nav({ to: '/runs/$runId', params: { runId: r.runId } })} initialSort={[{ id: 'started', desc: true }]} empty={<EmptyState title="No runs" body="Nothing has been observed or reconstructed for this filter." />} />
        </Panel>
      </div>
    </div>
  );
}

function orderSpans(spans: SpanView[]): { span: SpanView; depth: number }[] {
  const kids = new Map<string | null, SpanView[]>();
  const ids = new Set(spans.map((s) => s.id));
  for (const s of spans) {
    const p = s.parentId && ids.has(s.parentId) ? s.parentId : null;
    if (!kids.has(p)) kids.set(p, []);
    kids.get(p)!.push(s);
  }
  for (const list of kids.values()) list.sort((a, b) => a.startedAt.localeCompare(b.startedAt));
  const out: { span: SpanView; depth: number }[] = [];
  const walk = (p: string | null, depth: number) => {
    for (const s of kids.get(p) || []) {
      out.push({ span: s, depth });
      walk(s.id, depth + 1);
    }
  };
  walk(null, 0);
  return out;
}

const KIND_ICON = { run: Bot, skill: Layers, operation: Cog, write: FileText, event: Cog } as const;

function Waterfall({ spans, run, onSelect, selected }: { spans: SpanView[]; run: RunSummary; onSelect: (s: SpanView) => void; selected: string | null }) {
  const now = useTick(run.status === 'running' ? 1000 : 600000);
  const rows = useMemo(() => orderSpans(spans), [spans]);
  const t0 = Date.parse(run.startedAt);
  const t1 = Math.max(run.endedAt ? Date.parse(run.endedAt) : now, ...spans.map((s) => (s.endedAt ? Date.parse(s.endedAt) : s.status === 'running' ? now : Date.parse(s.startedAt))));
  const span = Math.max(1, t1 - t0);
  const parent = useRef<HTMLDivElement>(null);
  const v = useVirtualizer({ count: rows.length, getScrollElement: () => parent.current, estimateSize: () => 30, overscan: 12 });
  if (!rows.length) return <EmptyState title="No spans" />;
  return (
    <div>
      <div className="grid grid-cols-[minmax(220px,40%)_1fr] border-b border-line bg-surface-2 px-3 py-1 text-[11px] font-semibold uppercase text-muted"><span>Span</span><span className="flex justify-between"><span>0</span><span>{duration(span)}</span></span></div>
      <div ref={parent} className="max-h-[560px] overflow-auto" role="tree" aria-label="Run trace">
        <div style={{ height: v.getTotalSize(), position: 'relative' }}>
          {v.getVirtualItems().map((it) => {
            const { span: s, depth } = rows[it.index];
            const st = Date.parse(s.startedAt) - t0;
            const en = (s.endedAt ? Date.parse(s.endedAt) : s.status === 'running' ? now : Date.parse(s.startedAt)) - t0;
            const left = Math.max(0, (st / span) * 100);
            const width = Math.max(0.4, ((en - st) / span) * 100);
            const Icon = KIND_ICON[s.kind];
            const bar = s.status === 'failed' ? 'bg-fail' : s.status === 'refused' ? 'bg-fail' : s.status === 'running' ? 'bg-run' : s.kind === 'skill' ? 'bg-accent/40' : s.kind === 'write' ? 'bg-line-strong' : 'bg-pass/70';
            return (
              <button key={it.key} type="button" role="treeitem" aria-level={depth + 1} aria-selected={selected === s.id} onClick={() => onSelect(s)}
                className={`absolute left-0 grid w-full grid-cols-[minmax(220px,40%)_1fr] items-center border-b border-line px-3 text-left text-xs hover:bg-surface-2 ${selected === s.id ? 'bg-surface-3' : ''}`}
                style={{ top: it.start, height: it.size }}>
                <span className="flex min-w-0 items-center gap-1.5" style={{ paddingLeft: depth * 14 }}>
                  <StatusGlyph state={SPAN_STATE[s.status]} size={12} />
                  <Icon size={12} className="shrink-0 text-subtle" aria-hidden />
                  <span className="truncate">{s.name}</span>
                  {s.issues.length > 0 && <span className="mono shrink-0 text-[10px] text-subtle">{s.issues.join(',')}</span>}
                </span>
                <span className="relative h-3">
                  <span className={`absolute top-0 h-3 rounded-sm ${bar}`} style={{ left: `${left}%`, width: `${Math.min(width, 100 - left)}%` }} aria-hidden />
                  <span className="sr-only">{s.status}, {duration(s.durationMs)}</span>
                  <span className="absolute right-0 top-0 text-[10px] text-subtle" aria-hidden>{s.durationMs != null ? duration(s.durationMs) : s.status === 'running' ? 'running' : ''}</span>
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function SpanDetail({ s, onClose }: { s: SpanView; onClose: () => void }) {
  return (
    <Drawer open onClose={onClose} title={s.name}>
      <div className="space-y-3 text-sm">
        <div className="flex flex-wrap items-center gap-2"><StatusBadge state={SPAN_STATE[s.status]} label={s.status} /><ProvenanceBadge p={s.provenance} /><Pill>{s.kind}</Pill></div>
        {s.detail && <p className="text-muted">{s.detail}</p>}
        <KV items={[
          { k: 'Stage', v: s.stage ? STAGE_BY_ID[s.stage]?.label || s.stage : '—' },
          { k: 'Skill', v: s.skill ? <Link to="/harness/skills/$skillId" params={{ skillId: s.skill }} className="mono text-xs">{s.skill}</Link> : '—' },
          { k: 'Script', v: s.script ? <span className="mono text-xs">{s.script}</span> : '—' },
          { k: 'Step kind', v: s.stepKind || '—' },
          { k: 'Issues', v: s.issues.length ? s.issues.map((i) => <Link key={i} to="/issues/$issueId" params={{ issueId: i }} className="mono mr-2 text-xs">{i}</Link>) : '—' },
          { k: 'Started', v: <Time iso={s.startedAt} source="observed" /> },
          { k: 'Ended', v: <Time iso={s.endedAt} source="observed" /> },
          { k: 'Duration', v: duration(s.durationMs) },
          { k: 'Outcome', v: s.outcome ? <span className="mono">{s.outcome}</span> : '—' },
        ]} />
        {s.failure && <div className="rounded border border-fail bg-fail-tint p-2 text-xs"><b>{s.failure.class}</b>{s.failure.code ? ` (${s.failure.code})` : ''} {s.failure.summary}</div>}
        {(s.inputs.length > 0 || s.outputs.length > 0) && (
          <div className="space-y-1">
            {s.inputs.map((f) => <div key={`i${f.path}`} className="flex flex-wrap items-center gap-1 text-xs"><span className="text-muted">in</span><Link to="/evidence/view" search={{ path: f.path }} className="mono">{f.path}</Link><HashChip sha={f.sha256} len={8} /></div>)}
            {s.outputs.map((f) => <div key={`o${f.path}`} className="flex flex-wrap items-center gap-1 text-xs"><span className="text-muted">out</span><Link to="/evidence/view" search={{ path: f.path }} className="mono">{f.path}</Link><HashChip sha={f.sha256} len={8} /></div>)}
          </div>
        )}
        {Object.keys(s.attrs).length > 0 && <details><summary className="cursor-pointer text-xs font-medium">Attributes (sanitised)</summary><JsonView text={JSON.stringify(s.attrs, null, 2)} /></details>}
        <p className="text-[11px] text-subtle">Mission Control records what an agent did (tools, scripts, files, timings), never what it was thinking.</p>
      </div>
    </Drawer>
  );
}

function EventLog({ events }: { events: LedgerEvent[] }) {
  if (!events.length) return <EmptyState title="No ledger events" body="This run is reconstructed from report timestamps; there are no events to show." />;
  return (
    <ol className="divide-y divide-line text-xs" role="log" aria-label="Run events">
      {events.map((e) => (
        <li key={e.event_id} className="grid grid-cols-[70px_150px_1fr] gap-2 px-3 py-1">
          <span className="mono text-subtle">#{e.gseq}</span>
          <span className="mono">{e.type}</span>
          <span className="min-w-0 truncate">{e.summary || e.script_id || e.skill_id || ''} <span className="text-subtle"><Time iso={e.time} /></span></span>
        </li>
      ))}
    </ol>
  );
}

export function RunPage() {
  const { runId } = useParams({ strict: false }) as { runId?: string };
  const search = useSearch({ strict: false }) as { tab?: 'trace' | 'events' };
  const nav = useNavigate();
  const q = useRun(runId);
  const live = useLive();
  const [sel, setSel] = useState<SpanView | null>(null);
  if (q.isLoading) return <Loading rows={8} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const r = q.data;
  const tab = search.tab || 'trace';
  const liveCount = live.events.filter((e) => e.run_id === r.runId).length;
  return (
    <div>
      <PageHeader crumbs={<><Link to="/runs">Runs</Link> / {r.label}</>} title={r.label}
        subtitle={<span className="flex flex-wrap items-center gap-2"><StatusBadge state={RUN_STATE[r.status]} label={r.source === 'reconstructed' ? 'not observed' : r.status} /><Pill tone={r.source === 'observed' ? 'run' : 'neutral'}>{r.source}</Pill>{r.agentId && <Link to="/harness/agents/$agentId" params={{ agentId: r.agentId }} className="mono">{r.agentId}</Link>}<span>started <Time iso={r.startedAt} mode="both" /></span><span>{duration(r.durationMs)}</span>{r.status === 'running' && liveCount > 0 && <span className="text-run">{liveCount} live events this session</span>}</span>} />
      {r.note && <div className="mx-4 mt-4 rounded border border-line bg-surface-2 px-3 py-2 text-sm sm:mx-6">{r.note}</div>}
      <div className="px-4 sm:px-6"><Tabs label="Run views" value={tab} onChange={(t) => nav({ to: '/runs/$runId', params: { runId: r.runId }, search: { tab: t }, replace: true })} tabs={[{ id: 'trace', label: 'Trace' }, { id: 'events', label: 'Events', count: r.events.length }]} /></div>
      <div className="p-4 sm:p-6" role="tabpanel">
        {tab === 'trace' ? <Panel title="Trace" subtitle="Run → skill → operation → artifact write. Click a span for details."><Waterfall spans={r.spans} run={r} onSelect={setSel} selected={sel?.id ?? null} /></Panel> : <Panel title="Events"><EventLog events={r.events} /></Panel>}
      </div>
      {sel && <SpanDetail s={sel} onClose={() => setSel(null)} />}
    </div>
  );
}
