import { useState } from 'react';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { useArchitecture, useIssues, useOverview } from '../api';
import { STAGES } from '../../shared/stages';
import { PageHeader } from '../components/shell';
import { ArchGraph, OVERLAY_STYLE, PipelineGraph } from '../components/graphs';
import { Drawer, EmptyState, ErrorState, KV, Loading, Panel, Pill, Tabs } from '../components/ui';

export function ArchitecturePage() {
  const search = useSearch({ strict: false }) as { issue?: string; focus?: string; view?: 'app' | 'pipeline' };
  const nav = useNavigate();
  const view = search.view || 'app';
  const issues = useIssues();
  const ov = useOverview();
  const q = useArchitecture(search.issue || null, search.focus || null);
  const [sel, setSel] = useState<string | null>(null);
  const set = (patch: Record<string, string | undefined>) => nav({ to: '/architecture', search: { ...search, ...patch }, replace: true });
  const issueCells = search.issue ? ov.data?.issues.find((i) => i.id === search.issue)?.cells : undefined;
  const node = sel && q.data ? q.data.nodes.find((n) => n.id === sel) : null;
  return (
    <div>
      <PageHeader title="Architecture" subtitle="Two graphs, kept separate: the application under repair (from the Architect's code model) and the MARS pipeline that repairs it. Selecting an issue overlays where it lives in the code and how far it reaches." />
      <div className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-2 sm:px-6">
        <label className="text-sm">Issue overlay{' '}
          <select value={search.issue || ''} onChange={(e) => set({ issue: e.target.value || undefined, focus: undefined })} className="h-8 rounded border border-line bg-surface-2 px-2 text-sm">
            <option value="">None (overview)</option>
            {(issues.data?.issues || []).map((i) => <option key={i.id} value={i.id}>{i.id} — {i.title.slice(0, 60)}</option>)}
          </select>
        </label>
        {search.issue && <Link to="/issues/$issueId" params={{ issueId: search.issue }} search={{ tab: 'architecture' }} className="text-sm">Open {search.issue} →</Link>}
      </div>
      <div className="px-4 sm:px-6"><Tabs label="Graph" value={view} onChange={(t) => set({ view: t })} tabs={[{ id: 'app', label: 'Application graph' }, { id: 'pipeline', label: 'MARS pipeline graph' }]} /></div>
      <div className="p-4 sm:p-6" role="tabpanel">
        {view === 'pipeline' ? (
          <Panel title="MARS pipeline (runtime)" subtitle={search.issue ? `Stage states for ${search.issue}.` : 'Stage graph derived from agent and skill contracts. Select an issue to colour it.'}>
            <div className="p-3"><PipelineGraph stages={STAGES} cells={issueCells || {}} height={420} onSelect={() => search.issue && nav({ to: '/issues/$issueId', params: { issueId: search.issue }, search: { tab: 'overview' } })} /></div>
          </Panel>
        ) : q.isLoading ? <Loading rows={6} /> : q.error || !q.data ? <ErrorState error={q.error} retry={() => q.refetch()} /> : (
          <div className="grid gap-4 2xl:grid-cols-[minmax(0,1fr)_320px]">
            <Panel title="Application (code model)" subtitle={<span>{q.data.counts.modules} modules · {q.data.counts.types} types · {q.data.counts.methods} methods · {q.data.counts.endpoints} endpoints · {q.data.counts.calls} calls{q.data.truncated ? ' · view truncated' : ''}</span>}>
              <div className="p-3">{q.data.nodes.length ? <ArchGraph view={q.data} height={560} onSelect={setSel} selected={sel} /> : <EmptyState title="No code model" body="Run 01_architect to produce docs/agent_output/01-architecture/ (the AST model)." />}</div>
              <div className="border-t border-line px-3 py-2 text-xs text-muted">{q.data.note} {q.data.neo4j.note}</div>
            </Panel>
            <div className="space-y-4">
              <Panel title="Legend">
                <ul className="space-y-1 p-3 text-xs">{Object.entries(OVERLAY_STYLE).map(([k, s]) => <li key={k} className="flex items-start gap-2"><span className={`mt-0.5 inline-block h-3 w-5 shrink-0 rounded-sm border-2 ${s.cls}`} aria-hidden /><span><b>{s.label}</b> — {s.description}</span></li>)}</ul>
              </Panel>
              <Panel title="Nodes (list)" subtitle="Text twin of the graph.">
                <ul className="max-h-[420px] divide-y divide-line overflow-auto">
                  {q.data.nodes.map((n) => (
                    <li key={n.id}><button type="button" onClick={() => setSel(n.id)} className={`flex w-full items-center gap-2 px-3 py-1 text-left text-xs hover:bg-surface-2 ${sel === n.id ? 'bg-surface-3' : ''}`}><Pill>{n.kind}</Pill><span className="min-w-0 flex-1 truncate">{n.label}</span>{q.data.overlay[n.id] && <span className="text-[10px] font-semibold">{OVERLAY_STYLE[q.data.overlay[n.id]]?.label}</span>}</button></li>
                  ))}
                </ul>
              </Panel>
            </div>
          </div>
        )}
      </div>
      {node && q.data && (
        <Drawer open onClose={() => setSel(null)} title={node.label}>
          <div className="space-y-3 text-sm">
            <KV items={[{ k: 'Kind', v: node.kind }, { k: 'Module', v: node.module || '—' }, { k: 'File', v: node.file ? <span className="mono break-all text-xs">{node.file}{node.lines ? `:${node.lines[0]}–${node.lines[1]}` : ''}</span> : '—' }, { k: 'Overlay', v: q.data.overlay[node.id] ? OVERLAY_STYLE[q.data.overlay[node.id]]?.label : 'none' }, { k: 'Id', v: <span className="mono break-all text-[11px]">{node.id}</span> }]} />
            {node.ctx?.summary && <div><div className="text-xs font-semibold uppercase text-muted">Context ({node.ctx.author || 'Architect'}{node.ctx.confidence ? `, ${node.ctx.confidence}` : ''})</div><p className="text-muted">{node.ctx.summary}</p></div>}
            <button type="button" className="text-sm font-medium text-accent" onClick={() => set({ focus: node.id })}>Focus graph on this node</button>
            <div className="text-xs text-muted">Edges: {q.data.edges.filter((e) => e.from === node.id).length} out, {q.data.edges.filter((e) => e.to === node.id).length} in (visible).</div>
          </div>
        </Drawer>
      )}
    </div>
  );
}
