import cytoscape, { type Core } from 'cytoscape';
import { Crosshair, Maximize2, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useGraph } from '../../api/queries';
import type { GraphNode, GraphView } from '../../api/types';
import { Button, EmptyState, ErrorState, KeyValues, LoadingState, Tag } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';

const HIGHLIGHTS = [
  { value: '', label: 'No highlight' },
  { value: 'FINDING', label: 'Findings' },
  { value: 'CHANGED', label: 'Changed by proposals' },
  { value: 'BLAST_RADIUS', label: 'Blast radius' },
  { value: 'MIGRATION_ISSUE', label: 'Migration issues' },
];

const HIGHLIGHT_COLOR: Record<string, string> = {
  FINDING: '#f05252',
  CHANGED: '#22d3ee',
  BLAST_RADIUS: '#f59e0b',
  MIGRATION_ISSUE: '#a78bfa',
};

function colorFor(type: string): string {
  const t = type.toUpperCase();
  if (t === 'FINDING') return '#f05252';
  if (t === 'FILE') return '#64748b';
  if (t === 'STATEMENT') return '#475569';
  if (t.includes('ENDPOINT')) return '#22c55e';
  if (t === 'MODULE' || t === 'REPOSITORY') return '#e2e8f0';
  if (t === 'LIBRARY') return '#8b5cf6';
  if (t.includes('CONTROLLER') || t.includes('BEAN') || t.includes('CONFIGURATION') || t === 'REPOSITORY') return '#38bdf8';
  return '#94a3b8';
}

function Canvas({ view, onSelect, focus }: { view: GraphView; onSelect: (n?: GraphNode) => void; focus?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const cy = useRef<Core>(null);
  const byId = useMemo(() => new Map(view.nodes.map((n) => [n.id, n])), [view.nodes]);

  useEffect(() => {
    if (!ref.current) return;
    const instance = cytoscape({
      container: ref.current,
      elements: [
        ...view.nodes.map((n) => ({
          data: { id: n.id, label: n.name ?? n.id, color: colorFor(n.type), mark: n.highlights[0] ? HIGHLIGHT_COLOR[n.highlights[0]] : '' },
          classes: [n.highlights.length ? 'marked' : '', n.id === focus ? 'focus' : ''].join(' '),
        })),
        ...view.edges.map((e, i) => ({ data: { id: `e${i}`, source: e.from, target: e.to, label: e.type } })),
      ],
      style: [
        { selector: 'node', style: { 'background-color': 'data(color)', label: 'data(label)', color: '#cbd5e1', 'font-size': 8,
          'text-valign': 'bottom', 'text-margin-y': 3, width: 14, height: 14, 'text-max-width': '120px', 'text-wrap': 'ellipsis' } },
        { selector: 'node.marked', style: { 'border-width': 3, 'border-color': 'data(mark)' } },
        { selector: 'node.focus', style: { width: 22, height: 22, 'border-width': 3, 'border-color': '#f1f5f9' } },
        { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#67e8f9' } },
        { selector: 'edge', style: { width: 1, 'line-color': '#334155', 'target-arrow-color': '#334155', 'target-arrow-shape': 'triangle',
          'curve-style': 'bezier', 'arrow-scale': 0.6 } },
      ],
      layout: { name: view.nodes.length > 600 ? 'grid' : 'cose', animate: false, padding: 20 } as cytoscape.LayoutOptions,
      wheelSensitivity: 0.3,
    });
    instance.on('tap', 'node', (evt) => onSelect(byId.get(evt.target.id())));
    instance.on('tap', (evt) => {
      if (evt.target === instance) onSelect(undefined);
    });
    cy.current = instance;
    return () => instance.destroy();
  }, [view, byId, onSelect, focus]);

  return (
    <div className="relative h-full">
      <div ref={ref} className="h-full w-full" role="img" aria-label={`Canonical graph: ${view.nodes.length} nodes, ${view.edges.length} edges`} />
      <div className="absolute right-2 top-2 flex gap-1">
        <Button size="sm" onClick={() => cy.current?.fit(undefined, 20)} aria-label="Fit graph to view"><Maximize2 aria-hidden className="size-3.5" /></Button>
      </div>
    </div>
  );
}

export function GraphPage() {
  const runId = useRunId();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [focus, setFocus] = useState<string>();
  const [depth, setDepth] = useState(1);
  const [highlight, setHighlight] = useState('');
  const [types, setTypes] = useState<string[]>([]);
  const [limit, setLimit] = useState(300);
  const [selected, setSelected] = useState<GraphNode>();
  const graph = useGraph(runId, { focus, depth, q: search, highlight, types: types.join(','), limit });
  const view = graph.data;

  return (
    <div className="flex h-full min-h-[600px]">
      <div className="flex min-w-0 flex-1 flex-col">
        <form className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2" onSubmit={(e) => { e.preventDefault(); setSearch(q); }}>
          <label className="sr-only" htmlFor="graph-search">Search nodes</label>
          <input id="graph-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search by name, FQN or identity"
            className="h-7 w-64 rounded border border-border-strong bg-panel-2 px-2 text-[12px]" />
          <Button size="sm" type="submit">Search</Button>
          <label className="flex items-center gap-1 text-[12px] text-muted">Highlight
            <select value={highlight} onChange={(e) => setHighlight(e.target.value)} className="h-7 rounded border border-border-strong bg-panel-2 px-1">
              {HIGHLIGHTS.map((h) => <option key={h.value} value={h.value}>{h.label}</option>)}
            </select>
          </label>
          <label className="flex items-center gap-1 text-[12px] text-muted">Depth
            <select value={depth} onChange={(e) => setDepth(Number(e.target.value))} className="h-7 rounded border border-border-strong bg-panel-2 px-1">
              {[0, 1, 2, 3].map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </label>
          <label className="flex items-center gap-1 text-[12px] text-muted">Limit
            <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} className="h-7 rounded border border-border-strong bg-panel-2 px-1">
              {[100, 300, 800, 2000].map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </label>
          {(focus || search || highlight || types.length > 0) && (
            <Button size="sm" variant="ghost" onClick={() => { setFocus(undefined); setSearch(''); setQ(''); setHighlight(''); setTypes([]); }}>
              <X aria-hidden className="size-3.5" /> Reset
            </Button>
          )}
          {view && (
            <span className="ml-auto text-[12px] text-muted">
              showing {view.nodes.length} of {view.total_nodes} nodes · {view.edges.length} edges · {view.graph_source}
            </span>
          )}
        </form>
        {view && Object.keys(view.node_types).length > 0 && (
          <div className="flex flex-wrap gap-1 border-b border-border px-3 py-1.5" role="group" aria-label="Node type filter">
            {Object.entries(view.node_types).map(([t, n]) => {
              const on = types.includes(t);
              return (
                <button key={t} type="button" aria-pressed={on} onClick={() => setTypes(on ? types.filter((x) => x !== t) : [...types, t])}
                  className={`rounded border px-1.5 py-0.5 text-[11px] ${on ? 'border-primary bg-primary-soft text-primary' : 'border-border text-muted hover:text-text'}`}>
                  {t} <span className="text-faint">{n}</span>
                </button>
              );
            })}
          </div>
        )}
        {view?.truncated && (
          <div role="status" className="border-b border-warning/40 bg-warning-soft px-3 py-1 text-[12px] text-text">
            The filter matched more than {view.limit} nodes; this view is partial. Search, focus a node or filter by type to narrow it.
          </div>
        )}
        <div className="min-h-0 flex-1">
          {graph.isLoading && !view && <LoadingState label="Querying the canonical graph…" />}
          {graph.error && <ErrorState error={graph.error} />}
          {view && !view.available && (
            <EmptyState title="Graph is not available yet">The canonical graph is built once identity is sealed.</EmptyState>
          )}
          {view && view.available && view.nodes.length === 0 && <EmptyState title="No node matches this view" />}
          {view && view.available && view.nodes.length > 0 && <Canvas view={view} onSelect={setSelected} focus={focus} />}
        </div>
      </div>
      {selected && (
        <aside className="w-[360px] shrink-0 overflow-y-auto border-l border-border bg-panel p-3" aria-label="Node details">
          <div className="mb-2 flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="text-[11px] uppercase text-faint">{selected.type} · {selected.origin}</div>
              <div className="break-all font-semibold text-strong">{selected.name ?? selected.id}</div>
            </div>
            <button type="button" onClick={() => setSelected(undefined)} className="rounded p-1 text-muted hover:text-text" aria-label="Close node details">
              <X aria-hidden className="size-4" />
            </button>
          </div>
          <div className="mb-2 flex flex-wrap gap-1">{selected.highlights.map((h) => <Tag key={h} tone="warning">{h}</Tag>)}</div>
          <div className="mb-3 flex gap-2">
            <Button size="sm" onClick={() => { setFocus(selected.id); setDepth(1); }}><Crosshair aria-hidden className="size-3.5" /> Focus</Button>
            <Button size="sm" onClick={() => { setFocus(selected.id); setDepth((d) => Math.min(3, d + 1)); }}>Expand neighbours</Button>
          </div>
          <KeyValues rows={[
            ['Node ID', <span key="i" className="mono break-all text-[11px]">{selected.id}</span>],
            ['Identity', <span key="id" className="mono text-[11px]">{selected.identity_id}</span>],
            ['FILE_ID', <span key="f" className="mono text-[11px]">{selected.file_id}</span>],
            ['FQN', <span key="q" className="mono break-all text-[11px]">{selected.fqn}</span>],
            ['Degree', selected.degree],
            ...Object.entries(selected.properties).slice(0, 20).map(([k, v]) => [k, <span key={k} className="mono break-all text-[11px]">{String(v)}</span>] as [string, ReactNode]),
          ]} />
        </aside>
      )}
    </div>
  );
}
