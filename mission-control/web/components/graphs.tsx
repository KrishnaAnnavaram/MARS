/**
 * Two graph experiences, kept separate (proposal §31):
 *  - PipelineGraph / LineageGraph: the runtime/evidence DAG (fixed topology, small) — React Flow + dagre.
 *  - ArchGraph: the application architecture (bounded ego network from the code model).
 * Every graph has a list twin elsewhere on its page for keyboard and screen-reader users.
 */
import { memo, useMemo } from 'react';
import { ReactFlow, Background, Controls, Handle, Position, MarkerType, type Edge, type Node, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import type { ArchitectureView, Cell, LineageGraph, StageDef, StageId } from '../../shared/types';
import { STATE, ROLE_CLASSES, PROVENANCE } from '../domain/status';
import { GlyphSvg, ModifierIcons } from './ui';

function layout<N extends Node>(nodes: N[], edges: Edge[], dir: 'LR' | 'TB', w: number, h: number): N[] {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: dir, nodesep: 14, ranksep: 36, marginx: 6, marginy: 6 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const n of nodes) g.setNode(n.id, { width: (n.data as { w?: number }).w || w, height: h });
  for (const e of edges) g.setEdge(e.source, e.target);
  dagre.layout(g);
  return nodes.map((n) => {
    const p = g.node(n.id);
    const nw = (n.data as { w?: number }).w || w;
    return { ...n, position: { x: p.x - nw / 2, y: p.y - h / 2 } };
  });
}

type StageNodeData = { stage: StageDef; cell: Cell | null; selected: boolean; w: number };

const StageNode = memo(({ data }: NodeProps<Node<StageNodeData>>) => {
  const c = data.cell;
  const state = c ? c.state : 'waiting';
  const s = STATE[state];
  const r = ROLE_CLASSES[s.role];
  const human = state === 'awaiting_human';
  return (
    <div className={`rounded-md border px-2 py-1.5 text-left shadow-sm ${data.selected ? 'ring-2 ring-accent' : ''} ${human ? `${r.bg} ${r.border} border-2` : 'border-line bg-surface'}`} style={{ width: data.w }}>
      <Handle type="target" position={Position.Left} className="!h-1.5 !w-1.5 !border-0 !bg-line-strong" />
      <div className="flex items-center gap-1.5">
        <span className={r.fg}><GlyphSvg glyph={s.glyph} size={14} /></span>
        <span className="truncate text-[12px] font-semibold">{data.stage.short}</span>
        {c && <ModifierIcons modifiers={c.modifiers} />}
      </div>
      <div className={`truncate text-[11px] ${r.fg}`}>{c ? c.label : 'Waiting'}</div>
      <Handle type="source" position={Position.Right} className="!h-1.5 !w-1.5 !border-0 !bg-line-strong" />
    </div>
  );
});
StageNode.displayName = 'StageNode';

export function PipelineGraph({ stages, cells, selected, onSelect, height = 260 }: { stages: StageDef[]; cells: Partial<Record<StageId, Cell>>; selected?: StageId | null; onSelect?: (s: StageId) => void; height?: number }) {
  const { nodes, edges } = useMemo(() => {
    const ns: Node<StageNodeData>[] = stages.map((st) => ({ id: st.id, type: 'stage', position: { x: 0, y: 0 }, data: { stage: st, cell: cells[st.id] || null, selected: selected === st.id, w: 128 }, ariaLabel: `${st.label}: ${cells[st.id]?.label || 'waiting'}` }));
    const es: Edge[] = [];
    for (const st of stages) for (const d of st.dependsOn) if (stages.some((x) => x.id === d)) {
      const done = cells[d]?.state === 'passed';
      es.push({ id: `${d}-${st.id}`, source: d, target: st.id, markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 }, style: { stroke: done ? 'var(--line-strong)' : 'var(--line)', strokeDasharray: done ? undefined : '4 3' } });
    }
    return { nodes: layout(ns, es, 'LR', 128, 46), edges: es };
  }, [stages, cells, selected]);
  return (
    <div style={{ height }} className="rounded border border-line">
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={{ stage: StageNode }} fitView fitViewOptions={{ padding: 0.08 }} nodesDraggable={false} nodesConnectable={false} elementsSelectable
        onNodeClick={(_e, n) => onSelect?.(n.id as StageId)} proOptions={{ hideAttribution: true }} minZoom={0.3} maxZoom={1.6}>
        <Background gap={16} size={1} />
        <Controls showInteractive={false} position="bottom-right" />
      </ReactFlow>
    </div>
  );
}

type LinNodeData = { label: string; provenance: string; exists: boolean; findings: number; w: number };
const LineageNode = memo(({ data }: NodeProps<Node<LinNodeData>>) => (
  <div className={`rounded border px-2 py-1 text-[11px] ${data.exists ? 'border-line bg-surface' : 'border-dashed border-line bg-surface-2 text-subtle'}`} style={{ width: data.w }}>
    <Handle type="target" position={Position.Left} className="!h-1 !w-1 !border-0 !bg-line-strong" />
    <div className="flex items-center justify-between gap-1">
      <span className="truncate font-semibold">{data.label}</span>
      {data.findings > 0 && <span className="rounded bg-fail-tint px-1 text-fail" title={`${data.findings} integrity finding(s)`}>⚠{data.findings}</span>}
    </div>
    <div className="text-subtle">{data.exists ? PROVENANCE[data.provenance as keyof typeof PROVENANCE]?.label : 'missing'}</div>
    <Handle type="source" position={Position.Right} className="!h-1 !w-1 !border-0 !bg-line-strong" />
  </div>
));
LineageNode.displayName = 'LineageNode';

export function LineageView({ graph, height = 360, onSelect }: { graph: LineageGraph; height?: number; onSelect?: (type: string) => void }) {
  const { nodes, edges } = useMemo(() => {
    const keep = new Set(graph.nodes.filter((n) => n.exists || ['decision'].includes(n.type)).map((n) => n.id));
    const ns: Node<LinNodeData>[] = graph.nodes.filter((n) => keep.has(n.id)).map((n) => ({ id: n.id, type: 'lin', position: { x: 0, y: 0 }, data: { label: n.label, provenance: n.provenance, exists: n.exists, findings: n.findings, w: 150 } }));
    const es: Edge[] = graph.edges.filter((e) => keep.has(e.from) && keep.has(e.to) && e.to !== 'audit_trail').map((e) => ({ id: `${e.from}-${e.to}`, source: e.from, target: e.to, style: { stroke: 'var(--line-strong)' }, markerEnd: { type: MarkerType.ArrowClosed, width: 12, height: 12 } }));
    return { nodes: layout(ns, es, 'LR', 150, 40), edges: es };
  }, [graph]);
  return (
    <div style={{ height }} className="rounded border border-line">
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={{ lin: LineageNode }} fitView fitViewOptions={{ padding: 0.04 }} nodesDraggable={false} nodesConnectable={false} onNodeClick={(_e, n) => onSelect?.(n.id)} proOptions={{ hideAttribution: true }} minZoom={0.2}>
        <Background gap={16} size={1} />
        <Controls showInteractive={false} position="bottom-right" />
      </ReactFlow>
    </div>
  );
}

export const OVERLAY_STYLE: Record<string, { label: string; cls: string; description: string }> = {
  defect: { label: 'Defect site', cls: 'border-fail bg-fail-tint text-fail border-2', description: 'Where the root cause report locates the defect.' },
  path: { label: 'On the call path', cls: 'border-tool bg-tool-tint', description: 'Methods between the entry point and the defect.' },
  reported: { label: 'Reported symbol', cls: 'border-warn bg-warn-tint', description: 'Named in the issue register.' },
  broken: { label: 'Broken', cls: 'border-fail bg-fail-tint', description: 'Blast radius: requests through this fail.' },
  degraded: { label: 'Degraded', cls: 'border-tool bg-tool-tint', description: 'Blast radius: works with missing or stale data.' },
  at_risk: { label: 'At risk', cls: 'border-warn bg-warn-tint', description: 'Blast radius: shares infrastructure.' },
  changed: { label: 'Changed by patch', cls: 'border-run bg-run-tint', description: 'File touched by the fix diff.' },
  planned_unchanged: { label: 'Planned, not changed', cls: 'border-human bg-human-tint border-dashed', description: 'The approved plan targets this file; the diff does not change it.' },
  endpoint: { label: 'Endpoint', cls: 'border-line-strong bg-surface', description: 'A REST endpoint exposed by a service (no issue role).' },
  module: { label: 'Service', cls: 'border-line-strong bg-surface-2', description: 'A Maven module / microservice (no issue role).' },
};

type ArchNodeData = { label: string; kind: string; overlay: string | null; sub: string | null; w: number };
const ArchNodeView = memo(({ data }: NodeProps<Node<ArchNodeData>>) => {
  const st = data.overlay ? OVERLAY_STYLE[data.overlay] : null;
  return (
    <div className={`overflow-hidden rounded border px-2 py-1 text-[11px] ${st ? st.cls : data.kind === 'module' ? 'border-line-strong bg-surface-3' : 'border-line bg-surface'}`} style={{ width: data.w, height: 58 }} title={st ? st.label : data.kind}>
      <Handle type="target" position={Position.Left} className="!h-1 !w-1 !border-0 !bg-line-strong" />
      <div className="text-[9px] uppercase tracking-wide text-subtle">{data.kind}{st ? ` · ${st.label}` : ''}</div>
      <div className={`truncate ${data.kind === 'endpoint' ? 'mono' : 'font-semibold'}`}>{data.label}</div>
      {data.sub && <div className="truncate text-subtle">{data.sub}</div>}
      <Handle type="source" position={Position.Right} className="!h-1 !w-1 !border-0 !bg-line-strong" />
    </div>
  );
});
ArchNodeView.displayName = 'ArchNodeView';

export function ArchGraph({ view, height = 520, onSelect, selected }: { view: ArchitectureView; height?: number; onSelect?: (id: string) => void; selected?: string | null }) {
  const { nodes, edges } = useMemo(() => {
    const ns: Node<ArchNodeData>[] = view.nodes.map((n) => ({
      id: n.id, type: 'arch', position: { x: 0, y: 0 }, selected: selected === n.id,
      data: { label: n.kind === 'module' ? n.label : n.label, kind: n.kind, overlay: view.overlay[n.id] || null, sub: n.kind === 'method' || n.kind === 'type' ? (n.module || null) : n.role, w: n.kind === 'endpoint' ? 210 : 200 },
    }));
    const es: Edge[] = view.edges.map((e, i) => ({
      id: `${e.from}-${e.to}-${i}`, source: e.from, target: e.to, animated: false,
      style: { stroke: e.kind === 'http' ? 'var(--tool)' : e.kind === 'calls' ? 'var(--line-strong)' : 'var(--line)', strokeDasharray: e.kind === 'http' ? '5 3' : e.kind === 'contains' ? '2 3' : undefined },
      label: e.kind === 'http' ? 'HTTP' : undefined, markerEnd: { type: MarkerType.ArrowClosed, width: 10, height: 10 },
    }));
    // Arch nodes render up to three lines (kind · overlay, label, module): lay them out at that height.
    return { nodes: layout(ns, es, 'LR', 200, 64), edges: es };
  }, [view, selected]);
  return (
    <div style={{ height }} className="rounded border border-line">
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={{ arch: ArchNodeView }} fitView fitViewOptions={{ padding: 0.04 }} nodesDraggable={false} nodesConnectable={false} onNodeClick={(_e, n) => onSelect?.(n.id)} proOptions={{ hideAttribution: true }} minZoom={0.15}>
        <Background gap={16} size={1} />
        <Controls showInteractive={false} position="bottom-right" />
      </ReactFlow>
    </div>
  );
}
