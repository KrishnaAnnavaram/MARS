import { Ban } from 'lucide-react';
import { Link } from 'react-router';
import { useMigration } from '../../api/queries';
import type { AssessmentView, DimensionView, RoundView } from '../../api/types';
import { EmptyState, Hash, KeyValues, Panel, QueryView, StatusBadge, Table, Tag, td, th } from '../../components/ui';
import { ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { cn, formatDuration, humanize } from '../../lib/format';
import { dimensionStatus, lookup, trafficLight } from '../../lib/status';

function outcomeTone(outcome?: string) {
  return outcome === 'passed' ? 'text-success' : outcome ? 'text-danger' : 'text-muted';
}

export function Dimensions({ dims, empty }: { dims: DimensionView[]; empty: string }) {
  if (dims.length === 0) return <EmptyState title={empty} />;
  return (
    <Table label="Validation dimensions">
      <thead><tr>{['Dimension', 'Status', 'Mandatory', 'Detail', 'Summary', 'Producer', 'Evidence'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
      <tbody>
        {dims.map((d) => (
          <tr key={d.dimension}>
            <td className={`${td} mono`}>{d.dimension}</td>
            <td className={td}><StatusBadge status={lookup(dimensionStatus, d.status)} /></td>
            <td className={td}>{d.mandatory ? <Tag tone="warning">mandatory</Tag> : '—'}</td>
            <td className={`${td} mono text-[11px] text-muted`}>{d.detail ?? '—'}</td>
            <td className={`${td} max-w-[420px] text-muted`}>{d.summary ?? '—'}</td>
            <td className={`${td} text-[11px] text-faint`}>{d.producer ?? '—'}</td>
            <td className={`${td} mono text-[11px] text-faint`}>{d.evidence_refs.length || '—'}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function Assessment({ a, title }: { a: AssessmentView; title: string }) {
  return (
    <Panel title={title}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-2">
          {a.traffic_light && <StatusBadge status={lookup(trafficLight, a.traffic_light)} />}
          {a.traffic_light_rationale && <p className="text-muted">{a.traffic_light_rationale}</p>}
          <KeyValues rows={[
            ['Need', humanize(a.need)],
            ['Priority', humanize(a.priority)],
            ['Complexity', humanize(a.complexity)],
            ['Effort score', a.effort_score !== undefined ? `${a.effort_score}/100 (${a.effort_weights_version ?? 'policy weights'}; not a probability)` : undefined],
            ['Evidence confidence', humanize(a.evidence_confidence)],
            ['Current', a.current ? `${a.current.framework ?? ''} ${a.current.framework_version ?? ''} · Java ${a.current.java_version ?? '?'} · ${a.current.build_system ?? ''}` : undefined],
            ['Lifecycle', a.current ? `${a.current.lifecycle_quality ?? '?'} evidence; support ends ${a.current.support_ends ?? '?'}${a.current.end_of_life ? ' (END OF LIFE)' : ''}` : undefined],
            ['Target', a.recommended_target ? `${a.recommended_target.framework} ${a.recommended_target.version} · Java ${a.recommended_target.java_version ?? '?'}` : undefined],
            ['Target basis', a.recommended_target?.basis],
            ['Reference pack', a.reference_pack_id ? <span key="r">{a.reference_pack_id} <Hash value={a.reference_pack_sha256} label="pack hash" /></span> : 'none'],
          ]} />
        </div>
        <div>
          <div className="mb-1 text-[11px] uppercase tracking-wide text-faint">Effort factors (decomposed, policy-weighted)</div>
          <Table label="Effort factors">
            <thead><tr>{['Factor', 'Observed', 'Weight', 'Points'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
            <tbody>
              {a.effort_factors.map((f) => (
                <tr key={f.name}>
                  <td className={`${td} text-[12px]`}>{humanize(f.name)}{!f.known && <Tag className="ml-1">unknown</Tag>}</td>
                  <td className={`${td} mono text-[12px]`}>{f.raw_value} / {f.saturation}</td>
                  <td className={`${td} mono text-[12px]`}>{f.weight}</td>
                  <td className={`${td} mono text-[12px]`}>{f.contribution}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        </div>
      </div>
      {(a.blockers.length > 0 || a.unknowns.length > 0) && (
        <div className="mt-3 space-y-1">
          {a.blockers.map((b) => <div key={b.id}><Tag tone="danger">{b.id}</Tag> <span className="text-muted">{b.description}</span></div>)}
          {a.unknowns.map((u) => <div key={u.id + u.description}><Tag>UNKNOWN {u.id}</Tag> <span className="text-muted">{u.description}</span></div>)}
        </div>
      )}
      {a.objectives.length > 0 && (
        <div className="mt-3">
          <div className="mb-1 text-[11px] uppercase tracking-wide text-faint">Objectives weighed ({a.objectives.filter((o) => o.requires_migration).length} require migration)</div>
          <ul className="space-y-0.5 text-[12px]">
            {a.objectives.map((o) => (
              <li key={o.objective_id} className="text-muted">
                {o.requires_migration ? <Tag tone="danger">requires {o.required_platform}</Tag> : <Tag>independent</Tag>} {o.description}
              </li>
            ))}
          </ul>
        </div>
      )}
      {a.issues.length > 0 && <p className="mt-2 text-[12px] text-muted">{a.issues.length} migration issue(s) observed in the code ({a.issues.filter((i) => i.mandatory).length} mandatory).</p>}
    </Panel>
  );
}

function Round({ r, runId }: { r: RoundView; runId: string }) {
  return (
    <li className="rounded-md border border-border bg-panel-2 p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mono font-semibold text-strong">Round {String(r.round).padStart(2, '0')}</span>
        <span className="text-muted">{r.label}</span>
        <span className={cn('mono font-medium', outcomeTone(r.outcome))}>{r.outcome}</span>
        <span className="mono text-[11px] text-faint">{r.intent} · exit {r.exit_code} · {formatDuration(r.duration_ms)}{r.jdk ? ` · JDK ${r.jdk}` : ''}</span>
        {r.log_ref && (
          <Link className="ml-auto text-[12px] text-primary hover:underline" to={`/runs/${runId}/logs?path=${encodeURIComponent(r.log_ref)}`}>
            build log
          </Link>
        )}
      </div>
      {r.applied_rules.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">{r.applied_rules.map((rule) => <Tag key={rule} tone="active">{rule}</Tag>)}</div>
      )}
      {Object.keys(r.errors_by_category).length > 0 && (
        <div className="mt-1 text-[12px] text-muted">
          errors: {Object.entries(r.errors_by_category).map(([k, v]) => `${v} ${k}`).join(', ')}
        </div>
      )}
      {r.tests && <div className="text-[12px] text-muted">tests: {r.tests.run} run, {r.tests.failures} failed, {r.tests.errors} errors, {r.tests.skipped} skipped</div>}
      {r.diagnosis && <div className="mt-1 text-[12px] text-text">{r.diagnosis}</div>}
      {r.errors.length > 0 && (
        <details className="mt-1">
          <summary className="cursor-pointer text-[12px] text-faint">{r.error_count} compiler/build error(s){r.errors_truncated ? ' (truncated)' : ''}</summary>
          <ul className="mono mt-1 max-h-48 space-y-0.5 overflow-auto text-[11px] text-muted">
            {r.errors.map((e, i) => <li key={i}>{e.file ? `${e.file}:${e.line ?? ''} ` : ''}[{e.category}] {e.message}</li>)}
          </ul>
        </details>
      )}
    </li>
  );
}

export function MigrationPage() {
  const runId = useRunId();
  const view = useMigration(runId);
  return (
    <div className="space-y-3 p-4">
      <QueryView query={view}>
        {(m) => (
          <>
            <header className="flex flex-wrap items-center gap-3">
              <h1 className="text-[15px] font-semibold text-strong">Migration</h1>
              <Tag tone={m.status === 'GREEN' ? 'success' : m.status === 'BLOCKED' || m.status === 'FAILED' ? 'danger' : m.status === 'NEEDS_HUMAN' ? 'human' : 'muted'}>{m.status}</Tag>
              <span className="text-muted">{m.status_detail}</span>
              {m.item_status && <span className="ml-auto text-muted">verdict item: <span className="mono">{m.item_status}</span></span>}
            </header>
            {m.status === 'BLOCKED' && m.blocker && (
              <div role="alert" className="flex gap-3 rounded-md border border-danger/50 bg-danger-soft p-3">
                <Ban aria-hidden className="mt-0.5 size-5 shrink-0 text-danger" />
                <div>
                  <div className="font-semibold text-strong">MIGRATION BLOCKED</div>
                  <p className="text-text">{m.blocker}</p>
                  <p className="text-[12px] text-muted">MARS will not improvise an unsupported migration.</p>
                </div>
              </div>
            )}
            {m.status === 'NEEDS_HUMAN' && m.blocker && (
              <div role="alert" className="rounded-md border border-human/50 bg-human-soft p-3">
                <div className="font-semibold text-strong">The rounds stopped for a human</div>
                <p className="text-text">{m.blocker}</p>
                <Link to={`/runs/${runId}/actions`} className="text-primary">Human Action Center</Link>
              </div>
            )}
            {!m.selected && m.status !== 'BLOCKED' && (
              <p className="rounded border border-border bg-panel-2 p-2 text-muted">
                Migration was not executed for this run ({m.status_detail ?? m.status}).
              </p>
            )}
            {m.assessment ? <Assessment a={m.assessment} title="Assessment (read-only; never an authorization)" />
              : <EmptyState title="Not assessed yet">The migration assessment is produced during discovery.</EmptyState>}
            {m.refreshed_assessment && <Assessment a={m.refreshed_assessment} title="Refreshed assessment (after code changes)" />}
            {m.post_security && (
              <Panel title="Post-security reassessment">
                <KeyValues rows={[
                  ['Traffic light', `${m.post_security.previous_traffic_light} → ${m.post_security.current_traffic_light}`],
                  ['Effort', `${m.post_security.previous_effort_score ?? '?'} → ${m.post_security.current_effort_score ?? '?'}`],
                  ['What changed', m.post_security.what_changed.join('; ') || 'nothing'],
                  ['Why', m.post_security.why],
                ]} />
              </Panel>
            )}
            {m.plan && (
              <Panel title="Frozen migration plan">
                <KeyValues rows={[
                  ['Plan', <span key="p" className="mono">{m.plan.plan_id}</span>],
                  ['Plan hash', <Hash key="h" value={m.plan.plan_hash} label="plan hash" />],
                  ['From → to', `${m.plan.framework} ${m.plan.from_version} → ${m.plan.to_version} (Java ${m.plan.from_java ?? '?'} → ${m.plan.to_java ?? '?'})`],
                  ['Engine / pack', `${m.plan.engine ?? ''} · ${m.plan.reference_pack_id ?? ''}`],
                  ['Round limit', m.plan.max_rounds],
                  ['Allowed rules', m.plan.allowed_rule_ids.join(', ')],
                  ['Stop conditions', m.plan.stop_conditions.length ? m.plan.stop_conditions.join('; ') : 'none'],
                ]} />
              </Panel>
            )}
            {m.execution && (
              <Panel title={`Rounds (${m.execution.rounds.length}; limit ${m.plan?.max_rounds ?? '?'})`}>
                <ol className="space-y-2">{m.execution.rounds.map((r) => <Round key={r.round} r={r} runId={runId} />)}</ol>
                {m.execution.unmatched_errors.length > 0 && (
                  <div className="mt-3">
                    <div className="text-[11px] uppercase tracking-wide text-faint">Failures no pack rule matches</div>
                    <ul className="mono text-[11px] text-muted">{m.execution.unmatched_errors.map((e) => <li key={e}>{e}</li>)}</ul>
                  </div>
                )}
              </Panel>
            )}
            {m.execution?.behaviour && (
              <Panel title="Behaviour comparison (probes, round 0 vs final)">
                <p className="mb-2 text-muted">{m.execution.behaviour.overall} · verdict {m.execution.behaviour.verdict}</p>
                <Table label="Probes">
                  <thead><tr>{['Probe', 'Verdict', 'Before', 'After', 'Note'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>{m.execution.behaviour.probes.map((p) => (
                    <tr key={p.name}><td className={`${td} mono`}>{p.name}</td><td className={td}>{p.verdict}</td>
                      <td className={`${td} mono`}>{p.before_status ?? '—'}</td><td className={`${td} mono`}>{p.after_status ?? '—'}</td>
                      <td className={`${td} text-muted`}>{p.note ?? '—'}</td></tr>
                  ))}</tbody>
                </Table>
                {m.execution.behaviour.explanations.length > 0 && (
                  <ul className="mt-2 list-disc pl-5 text-[12px] text-muted">{m.execution.behaviour.explanations.map((e) => <li key={e}>{e}</li>)}</ul>
                )}
              </Panel>
            )}
            {m.execution?.tests && (
              <Panel title="Tests compared with round 0">
                <KeyValues rows={[
                  ['Verdict', m.execution.tests.verdict],
                  ['New failures', m.execution.tests.new_failures.join(', ') || 'none'],
                  ['Pre-existing (recorded, not fixed)', m.execution.tests.pre_existing_failures.join(', ') || 'none'],
                ]} />
              </Panel>
            )}
            <Panel title="Migration validation"><Dimensions dims={m.validation} empty="Migration validation has not run" /></Panel>
            {m.proposals.length > 0 && (
              <Panel title={`Migration changes (${m.proposals.length})`} bodyClassName="p-0">
                <Table label="Migration proposals">
                  <thead><tr>{['Proposal', 'Rule', 'Files', 'Status'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>{m.proposals.map((p) => (
                    <tr key={p.proposal_id}>
                      <td className={td}><Link className="mono text-primary hover:underline" to={`/runs/${runId}/changes/${p.proposal_id}`}>{p.proposal_id}</Link></td>
                      <td className={`${td} mono`}>{p.rule_id ?? '—'}</td>
                      <td className={`${td} mono text-[11px] text-muted`}>{p.files.join(', ')}</td>
                      <td className={td}><ProposalStatusBadge status={p.status} /></td>
                    </tr>
                  ))}</tbody>
                </Table>
              </Panel>
            )}
          </>
        )}
      </QueryView>
    </div>
  );
}
