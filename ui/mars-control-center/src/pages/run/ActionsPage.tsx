import { ExternalLink, Terminal, UserRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { useHumanActions, useRun } from '../../api/queries';
import type { HumanAction, HumanLink } from '../../api/types';
import { ArtifactViewer } from '../../components/ArtifactViewer';
import { Markdown } from '../../components/Markdown';
import { EmptyState, KeyValues, Panel, QueryView, StatusBadge, Tag } from '../../components/ui';
import { ContinueExecution, DecisionReceipt, GateDecision, ProposalDecision, ProposalStatusBadge, useRecentDecisions } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { humanize } from '../../lib/format';
import { lookup, trafficLight } from '../../lib/status';

function Inspect({ runId, links }: { runId: string; links: HumanLink[] }) {
  const [artifact, setArtifact] = useState<string>();
  return (
    <div>
      <div className="mb-1 text-[11px] uppercase tracking-wide text-faint">Inspect before deciding</div>
      <div className="flex flex-wrap gap-2">
        {links.map((l) => l.kind === 'VIEW' ? (
          <Link key={l.id + l.label} to={`/runs/${runId}/${l.id}`} className="inline-flex items-center gap-1 rounded border border-border px-2 py-1 text-[12px] text-primary hover:bg-panel-2">
            {l.label} <ExternalLink aria-hidden className="size-3" />
          </Link>
        ) : (
          <button key={l.id} type="button" onClick={() => setArtifact(l.id)}
            className="inline-flex items-center gap-1 rounded border border-border px-2 py-1 text-[12px] text-primary hover:bg-panel-2">
            {l.label} <span className="mono text-faint">{l.id}</span>
          </button>
        ))}
      </div>
      <ArtifactViewer runId={runId} path={artifact} onClose={() => setArtifact(undefined)} />
    </div>
  );
}

function GateAContext({ ctx }: { ctx: Record<string, unknown> }) {
  const sec = ctx.security as { total: number; critical: number; high: number; medium: number; low: number;
    platform_constrained: number; unanchored: number } | undefined;
  const interactions = (ctx.interactions as { kind: string; description: string }[] | undefined) ?? [];
  const blockers = (ctx.blockers as string[] | undefined) ?? [];
  const unknowns = (ctx.unknowns as string[] | undefined) ?? [];
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      <Panel title="Migration assessment">
        <KeyValues rows={[
          ['Assessment', ctx.traffic_light ? <StatusBadge key="t" status={lookup(trafficLight, String(ctx.traffic_light))} /> : undefined],
          ['Need', humanize(String(ctx.migration_need ?? ''))],
          ['Current', String(ctx.current_version ?? '—')],
          ['Target', String(ctx.target_version ?? '—')],
          ['Complexity', humanize(String(ctx.complexity ?? ''))],
          ['Effort score', `${ctx.effort_score ?? '—'}/100 (deterministic; not a probability)`],
          ['Evidence confidence', humanize(String(ctx.evidence_confidence ?? ''))],
          ['Reference pack', String(ctx.reference_pack ?? 'none')],
        ]} />
        {blockers.length > 0 && (
          <div className="mt-2">{blockers.map((b) => <Tag key={b} tone="danger" className="mr-1">{b}</Tag>)}</div>
        )}
      </Panel>
      <Panel title="Security summary">
        {sec ? (
          <KeyValues rows={[
            ['Open findings', sec.total],
            ['Critical / high', `${sec.critical} / ${sec.high}`],
            ['Medium / low', `${sec.medium} / ${sec.low}`],
            ['Need the target platform', sec.platform_constrained],
            ['Unanchored', sec.unanchored],
          ]} />
        ) : <p className="text-muted">No security summary.</p>}
      </Panel>
      {(interactions.length > 0 || unknowns.length > 0) && (
        <Panel title="How migration and security interact" className="lg:col-span-2">
          <ul className="space-y-1">
            {interactions.map((i, n) => <li key={n}><Tag tone="warning">{i.kind}</Tag> <span className="text-muted">{i.description}</span></li>)}
            {unknowns.map((u) => <li key={u}><Tag>UNKNOWN</Tag> <span className="text-muted">{u}</span></li>)}
          </ul>
        </Panel>
      )}
    </div>
  );
}

function ActionCard({ runId, action }: { runId: string; action: HumanAction }) {
  return (
    <section className="rounded-md border border-human/50 bg-panel" aria-labelledby={`${action.id}-title`}>
      <header className="flex items-start gap-3 border-b border-border px-4 py-3">
        <UserRound aria-hidden className="mt-0.5 size-5 shrink-0 text-human" />
        <div className="min-w-0">
          <h2 id={`${action.id}-title`} className="text-[15px] font-semibold text-strong">{action.title}</h2>
          <p className="text-muted"><span className="font-medium text-text">Why MARS stopped: </span>{action.why_stopped}</p>
        </div>
        <Tag tone="human" className="ml-auto shrink-0">{action.gate}</Tag>
      </header>
      <div className="space-y-4 p-4">
        {action.gate === 'GATE_A' && <GateAContext ctx={action.context} />}
        {action.gate === 'GATE_A2' && (
          <Panel title="Post-security reassessment">
            <KeyValues rows={[
              ['Before', action.context.previous_traffic_light as string],
              ['Now', action.context.current_traffic_light as string],
              ['Why', action.context.why as string],
            ]} />
          </Panel>
        )}
        {action.gate === 'MIGRATION_PLAN' && (
          <Panel title="Frozen migration plan">
            <KeyValues rows={Object.entries(action.context).map(([k, v]) => [humanize(k), Array.isArray(v) ? v.join(', ') : String(v)])} />
          </Panel>
        )}
        {action.recommendation && (
          <div className="rounded border border-border bg-panel-2 p-3">
            <div className="text-[11px] uppercase tracking-wide text-faint">MARS advice (not a decision)</div>
            <div className="mono font-semibold text-strong">{action.recommendation.value}</div>
            {action.recommendation_rationale && <p className="text-muted">{action.recommendation_rationale}</p>}
          </div>
        )}
        <Inspect runId={runId} links={action.inspect} />

        {action.gate === 'GATE_A' && (
          <GateDecision runId={runId} action={action} endpoint="/decisions/execution" field="strategy" hashField="expected_assessment_hash" />
        )}
        {action.gate === 'GATE_A2' && (
          <GateDecision runId={runId} action={action} endpoint="/decisions/post-security" field="choice" hashField="expected_assessment_hash" />
        )}
        {action.gate === 'MIGRATION_PLAN' && (
          <GateDecision runId={runId} action={action} endpoint="/decisions/migration-plan" field="verdict" hashField="expected_plan_hash" />
        )}

        {(action.gate === 'GATE_B' || action.proposals.length > 0) && (
          <Panel title={`Proposals (${action.proposals.length})`} bodyClassName="p-0">
            {action.proposals.length === 0 && <EmptyState title="No proposal awaits a decision" />}
            {action.proposals.map((p) => (
              <div key={p.proposal_id} className="flex flex-wrap items-start gap-3 border-b border-border/60 px-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Link to={`/runs/${runId}/changes/${p.proposal_id}`} className="mono text-primary hover:underline">{p.proposal_id}</Link>
                    <ProposalStatusBadge status={p.status} />
                    <Tag>{p.capability}/{p.provider_type}</Tag>
                    {p.strategy_only && <Tag tone="warning">strategy only</Tag>}
                    {p.finding_labels.map((f) => <Tag key={f} tone="active">{f}</Tag>)}
                  </div>
                  {p.reason && <Markdown className="mt-0.5 text-muted">{p.reason}</Markdown>}
                  <p className="mono text-[11px] text-faint">{p.files.join(', ')}</p>
                </div>
                <ProposalDecision runId={runId} proposal={p} canDecide={action.can_decide} cannotReason={action.cannot_decide_reason}
                  existingDecisionId={p.decision_id} compact />
              </div>
            ))}
          </Panel>
        )}

        {action.gate === 'GATE_B' && (
          <ContinueExecution runId={runId} undecided={Number(action.context.undecided ?? 0)} note={action.resume_note} />
        )}

        {action.gate === 'NEEDS_HUMAN' && (
          <div className="space-y-3">
            <p className="text-text">{action.what_to_decide}</p>
            {action.manual_steps.length > 0 && (
              <Panel title="What resolves it">
                <ol className="list-decimal space-y-1 pl-5">
                  {action.manual_steps.map((s) => (
                    <li key={s} className="text-muted">
                      {s.startsWith('harness') ? <code className="mono inline-flex items-center gap-1 text-text"><Terminal aria-hidden className="size-3" />{s}</code> : s}
                    </li>
                  ))}
                </ol>
              </Panel>
            )}
            {action.resumable ? (
              <ContinueExecution runId={runId} undecided={0} note={action.resume_note} />
            ) : (
              <p className="rounded border border-border bg-panel-2 p-2 text-[12px] text-muted">{action.resume_note ?? 'Not resumable yet.'}</p>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

export function ActionsPage() {
  const runId = useRunId();
  const actions = useHumanActions(runId);
  const run = useRun(runId).data!;
  const recent = useRecentDecisions();
  return (
    <div className="space-y-3 p-4">
      <header>
        <h1 className="text-[15px] font-semibold text-strong">Human Action Center</h1>
        <p className="text-muted">
          MARS proposes; only a recorded human decision authorizes. Decisions are recorded by the engine through its
          approval store, bound to the exact assessment, plan or proposal shown here.
        </p>
      </header>
      {recent && recent.items.length > 0 && (
        <Panel title="Recorded from this browser">
          <div className="grid gap-2 lg:grid-cols-2">
            {recent.items.map((r) => <DecisionReceipt key={r.decision.decision_id} decision={r.decision} next={r.next} />)}
          </div>
        </Panel>
      )}
      <QueryView query={actions}>
        {(view) => view.actions.length === 0 ? (
          <EmptyState title="No human action is currently required">
            The run is at <span className="mono">{run.phase}</span>. {run.liveness.explanation}
          </EmptyState>
        ) : (
          <div className="space-y-4">{view.actions.map((a) => <ActionCard key={a.id} runId={runId} action={a} />)}</div>
        )}
      </QueryView>
    </div>
  );
}
