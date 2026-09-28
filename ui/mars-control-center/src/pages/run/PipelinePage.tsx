import { Background, Controls, Handle, MarkerType, Position, ReactFlow, type Edge, type Node, type NodeProps } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { X } from 'lucide-react';
import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { useRun } from '../../api/queries';
import type { ExecutionEvent, PipelineStage } from '../../api/types';
import { EventRow } from '../../components/run';
import { EmptyState, KeyValues, Panel, StatusBadge } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';
import { useRunEvents } from '../../live/LiveRun';
import { cn, formatDateTime, formatDuration } from '../../lib/format';
import { lookup, stageStatus, toneClasses } from '../../lib/status';

/** Where each real stage sits: the analysis spine, the two execution branches, and the final stages. */
const LAYOUT: Record<string, { x: number; y: number }> = {
  SNAPSHOT: { x: 330, y: 0 },
  INVENTORY: { x: 330, y: 80 },
  IDENTITY: { x: 330, y: 160 },
  GRAPH: { x: 330, y: 240 },
  BASELINE: { x: 330, y: 320 },
  DISCOVERY: { x: 330, y: 400 },
  GATE_A: { x: 330, y: 480 },
  EXECUTION_PLAN: { x: 330, y: 560 },
  MIGRATION_PLAN: { x: 40, y: 660 },
  MIGRATION_ROUNDS: { x: 40, y: 740 },
  MIGRATION_VALIDATION: { x: 40, y: 820 },
  REMEDIATION_PLANNING: { x: 620, y: 660 },
  GATE_B: { x: 620, y: 740 },
  MUTATION: { x: 620, y: 820 },
  FIX_VERIFICATION: { x: 620, y: 900 },
  GATE_A2: { x: 620, y: 990 },
  FINAL_VALIDATION: { x: 330, y: 1080 },
  VERDICT: { x: 330, y: 1160 },
};

type StageNodeData = { stage: PipelineStage; selected: boolean };

function StageNode({ data }: NodeProps<Node<StageNodeData>>) {
  const { stage, selected } = data;
  const st = lookup(stageStatus, stage.status);
  const tone = toneClasses[st.tone];
  return (
    <div
      className={cn('w-[240px] rounded-md border bg-panel px-2.5 py-1.5 text-left shadow-sm', tone.border,
        stage.status === 'ACTIVE' && 'mars-active', stage.status === 'PENDING' || stage.status === 'SKIPPED' ? 'opacity-60' : '',
        selected && 'ring-2 ring-primary')}
    >
      <Handle type="target" position={Position.Top} className="!bg-border-strong" />
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-[12px] font-semibold text-strong">{stage.label}</span>
        <StatusBadge status={st} compact />
      </div>
      <div className={cn('truncate text-[11px]', tone.text)}>
        {st.label}
        {stage.duration_ms !== undefined && <span className="text-faint"> · {formatDuration(stage.duration_ms)}</span>}
        {stage.entries > 1 && <span className="text-faint"> · entered {stage.entries}×</span>}
      </div>
      {stage.summary && <div className="truncate text-[11px] text-muted" title={stage.summary}>{stage.summary}</div>}
      <Handle type="source" position={Position.Bottom} className="!bg-border-strong" />
    </div>
  );
}

const nodeTypes = { stage: StageNode };

function StageDrawer({ stage, events, onClose, stages }: {
  stage: PipelineStage; events: ExecutionEvent[]; onClose: () => void; stages: PipelineStage[];
}) {
  const related = events.filter((e) => e.phase && stage.phases.includes(e.phase) && e.type !== 'STATE_TRANSITION');
  const failures = related.filter((e) => e.status === 'FAILED');
  const evidence = [...new Set(related.flatMap((e) => e.evidence_refs))];
  const artifacts = [...new Set(related.flatMap((e) => e.artifact_refs))];
  const label = (id: string) => stages.find((s) => s.id === id)?.label ?? id;
  return (
    <aside className="flex w-[420px] shrink-0 flex-col border-l border-border bg-panel" aria-label={`${stage.label} details`}>
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <div>
          <div className="font-semibold text-strong">{stage.label}</div>
          <StatusBadge status={lookup(stageStatus, stage.status)} />
        </div>
        <button type="button" className="rounded p-1 text-muted hover:text-text" onClick={onClose} aria-label="Close details">
          <X aria-hidden className="size-4" />
        </button>
      </div>
      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        <KeyValues rows={[
          ['States', <span key="p" className="mono">{stage.phases.join(', ')}</span>],
          ['Entered', formatDateTime(stage.entered_at)],
          ['Left', formatDateTime(stage.exited_at)],
          ['Duration', formatDuration(stage.duration_ms)],
          ['Entries', stage.entries],
          ['Transition reason', stage.reason],
          ['Summary', stage.summary],
          ['Next possible', stage.next.length ? stage.next.map(label).join(', ') : 'none (end of the lifecycle)'],
        ]} />
        {failures.length > 0 && (
          <Panel title="Errors" className="border-danger/40" bodyClassName="p-0">
            {failures.map((e) => <EventRow key={e.sequence} event={e} />)}
          </Panel>
        )}
        <Panel title={`Activity (${related.length})`} bodyClassName="p-0">
          {related.length === 0 ? (
            <EmptyState title="No events for this stage">
              The stage is derived from the state history; events exist only for runs recorded with the event stream.
            </EmptyState>
          ) : (
            related.map((e) => <EventRow key={e.sequence} event={e} />)
          )}
        </Panel>
        {(evidence.length > 0 || artifacts.length > 0) && (
          <Panel title="Evidence and artifacts">
            <div className="space-y-1">
              {evidence.map((id) => <div key={id} className="mono text-[11px] text-muted">{id}</div>)}
              {artifacts.map((a) => <div key={a} className="mono text-[11px] text-primary">{a}</div>)}
            </div>
          </Panel>
        )}
      </div>
    </aside>
  );
}

export function PipelinePage() {
  const runId = useRunId();
  const run = useRun(runId).data!;
  const events = useRunEvents();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get('stage');
  const stages = run.pipeline;

  const path = run.pipeline_path;
  const { nodes, edges } = useMemo(() => {
    const byId = new Map(stages.map((s) => [s.id, s]));
    const hops = new Set(path.slice(1).map((to, i) => `${path[i]}->${to}`));
    const nodes: Node<StageNodeData>[] = stages.map((s) => ({
      id: s.id,
      type: 'stage',
      position: LAYOUT[s.id] ?? { x: 0, y: 0 },
      data: { stage: s, selected: s.id === selectedId },
      draggable: false,
    }));
    const edges: Edge[] = [];
    for (const s of stages) {
      for (const n of s.next) {
        const target = byId.get(n);
        if (!target) continue;
        // an edge is "taken" only when the persisted history moved the run from one stage straight to the other
        const taken = hops.has(`${s.id}->${n}`);
        edges.push({
          id: `${s.id}->${n}`,
          source: s.id,
          target: n,
          animated: taken && target.status === 'ACTIVE',
          style: { stroke: taken ? 'var(--primary)' : 'var(--border-strong)', strokeWidth: taken ? 2 : 1, opacity: taken ? 0.9 : 0.5 },
          markerEnd: { type: MarkerType.ArrowClosed, color: taken ? 'var(--primary)' : 'var(--border-strong)' },
        });
      }
    }
    return { nodes, edges };
  }, [stages, selectedId, path]);

  const selected = stages.find((s) => s.id === selectedId);

  return (
    <div className="flex h-full min-h-[600px]">
      <div className="relative min-w-0 flex-1">
        <div className="absolute left-3 top-3 z-10 max-w-md rounded border border-border bg-panel/95 px-3 py-2 text-[12px] text-muted">
          Stages and their order come from the MARS state machine; statuses from this run's persisted transitions.
          Highlighted edges are the path this run took.
        </div>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={{ padding: 0.15 }}
          nodesConnectable={false}
          elementsSelectable
          onNodeClick={(_, node) => setParams({ stage: node.id })}
          proOptions={{ hideAttribution: true }}
          colorMode="dark"
          aria-label="Run pipeline"
        >
          <Background color="var(--border)" gap={24} />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
      {selected && (
        <StageDrawer stage={selected} events={events} stages={stages} onClose={() => setParams({})} />
      )}
    </div>
  );
}
