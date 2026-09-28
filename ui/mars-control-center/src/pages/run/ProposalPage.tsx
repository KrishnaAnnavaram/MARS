import { ArrowLeft, ShieldCheck, ShieldX } from 'lucide-react';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useProposal, useRun } from '../../api/queries';
import type { MutationView, ProposalDetail } from '../../api/types';
import { DiffView } from '../../components/DiffView';
import { Markdown } from '../../components/Markdown';
import { EmptyState, Hash, KeyValues, Panel, QueryView, Table, Tabs, Tag, td, th } from '../../components/ui';
import { DecisionReceipt, ProposalDecision, ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { cn, formatDateTime } from '../../lib/format';
import { journeyStatus, lookup, toneClasses } from '../../lib/status';

/**
 * The Mutation Gateway's sequence for this proposal, with each step's outcome as the gateway
 * recorded it. Steps with no record are shown as not recorded, never as passed.
 */
function GatewayFlow({ m, decisionIds }: { m?: MutationView; decisionIds: string[] }) {
  const applied = m?.status === 'APPLIED';
  // the gateway's own record of this application (its MUTATION_APPLIED event); absent for runs that predate events
  const recorded = applied && (m?.checks.length ?? 0) > 0;
  const checks = new Set(m?.checks ?? []);
  const refused = m && !applied && m.status !== 'NOT_ATTEMPTED' && m.status !== 'STARTED';
  const step = (label: string, ok: boolean | undefined, detail?: string) => ({ label, ok, detail });
  const check = (name: string) => (recorded ? checks.has(name) : undefined);
  const steps = [
    step('Proposal registered', true, 'immutable file + PROPOSED ledger event'),
    step('Baseline sealed', check('BASELINE_SEALED')),
    step('Identity resolved (FILE_ID at current path)', check('IDENTITY_RESOLVED')),
    step('Authorization (exact-hash human approval, or the frozen plan for deterministic rules)', check('AUTHORIZED'),
      m?.authorized_by ?? (decisionIds.length ? decisionIds.join(', ') : undefined)),
    step('Approval integrity', check('APPROVAL_INTEGRITY')),
    step('Scope (files and symbols declared)', check('SCOPE')),
    step('Base hash (not stale)', check('BASE_HASH')),
    step('Containment and budget', recorded ? checks.has('CONTAINMENT') && checks.has('BUDGET') : undefined),
    step('Pre-batch checkpoint', recorded ? !!m?.pre_checkpoint : undefined, m?.pre_checkpoint),
    step('Write through Bootshift FileMutationGateway', applied ? true : refused ? false : undefined,
      m?.batch ?? (applied ? 'applied per the run record and lineage ledger' : undefined)),
    step('Identity synchronization', recorded ? m?.identity_sync === 'COMPLETE' : undefined, m?.identity_sync),
    step('Checkpoint recorded', recorded ? !!m?.checkpoint_id : undefined, m?.checkpoint_id),
    step('Bypass detection', recorded && m?.bypass_files !== undefined ? m.bypass_files === 0 : undefined,
      recorded && m?.bypass_files !== undefined ? (m.bypass_files ? `${m.bypass_files} file(s) changed outside the gateway`
        : 'no bypass') : undefined),
  ];
  return (
    <div>
      {applied && !recorded && (
        <p className="mb-2 rounded border border-border bg-panel-2 p-2 text-[12px] text-muted">
          Applied, according to the run record and the lineage ledger. This run predates the execution event stream, so the
          gateway's individual checks were not recorded here; they are shown as not recorded, never as passed.
        </p>
      )}
      {refused && (
        <div role="alert" className="mb-2 rounded border border-danger/40 bg-danger-soft p-2 text-text">
          Not applied ({m?.status}): <span className="mono">{m?.reason_code}</span> {m?.reason}
        </div>
      )}
      <ol className="space-y-1">
        {steps.map((s) => {
          const st = lookup(journeyStatus, s.ok === true ? 'DONE' : s.ok === false ? 'FAILED' : 'PENDING');
          const Icon = st.icon;
          return (
            <li key={s.label} className="flex items-start gap-2">
              <Icon aria-hidden className={cn('mt-0.5 size-4 shrink-0', toneClasses[st.tone].text)} />
              <span className="min-w-0">
                <span className="text-text">{s.label}</span>
                <span className={cn('ml-2 text-[11px]', toneClasses[st.tone].text)}>
                  {s.ok === true ? 'verified' : s.ok === false ? 'failed' : 'not recorded'}
                </span>
                {s.detail && <span className="mono ml-2 text-[11px] text-faint">{s.detail}</span>}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function Edits({ p }: { p: ProposalDetail }) {
  const [index, setIndex] = useState(0);
  const [view, setView] = useState<'split' | 'unified' | 'content'>('split');
  const edit = p.edits[index];
  if (!edit) return <EmptyState title={p.strategy_only ? 'Strategy-only proposal: no edits yet' : 'No edits'}>
    {p.strategy_only ? 'Approving it authorizes producing a concrete fix, which needs its own approval.' : null}</EmptyState>;
  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {p.edits.length > 1 && (
          <select aria-label="File" className="h-7 rounded border border-border-strong bg-panel-2 px-1.5 text-[12px]" value={index}
            onChange={(e) => setIndex(Number(e.target.value))}>
            {p.edits.map((e, i) => <option key={e.path + i} value={i}>{e.path}</option>)}
          </select>
        )}
        <span className="mono text-[12px] text-text">{edit.path}{edit.new_path ? ` → ${edit.new_path}` : ''}</span>
        <Tag>{edit.operation}</Tag>
        <span className="text-[12px] text-success">+{edit.added_lines}</span>
        <span className="text-[12px] text-danger">−{edit.removed_lines}</span>
        {edit.redacted && <Tag tone="warning">credential-like literals masked for display</Tag>}
        <div className="ml-auto">
          <Tabs label="Diff view" value={view} onChange={setView}
            options={[{ value: 'split', label: 'Side by side' }, { value: 'unified', label: 'Unified' }, { value: 'content', label: 'New content' }]} />
        </div>
      </div>
      <div className="rounded border border-border bg-panel-2">
        {view === 'content' ? (
          <pre className="mono max-h-[70vh] overflow-auto p-3 text-[12px] whitespace-pre">
            {edit.new_content ?? 'This edit has no replacement content (delete or rename only).'}
            {edit.new_content_truncated ? '\n… (truncated for display)' : ''}
          </pre>
        ) : (
          <DiffView diff={edit.unified_diff} path={edit.path} newPath={edit.new_path} viewType={view} />
        )}
      </div>
    </div>
  );
}

export function ProposalPage() {
  const runId = useRunId();
  const { proposalId = '' } = useParams();
  const proposal = useProposal(runId, proposalId);
  const run = useRun(runId).data;
  const mayDecide = run?.liveness.state !== 'ADVANCING';
  return (
    <div className="space-y-3 p-4">
      <Link to={`/runs/${runId}/changes`} className="inline-flex items-center gap-1 text-muted hover:text-text">
        <ArrowLeft aria-hidden className="size-4" /> Changes
      </Link>
      <QueryView query={proposal}>
        {(p) => (
          <>
            <header className="flex flex-wrap items-start gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="mono text-[16px] font-semibold text-strong">{p.proposal_id}</h1>
                  <ProposalStatusBadge status={p.status} />
                  <Tag>{p.capability} / {p.provider_type}</Tag>
                  {p.strategy_only && <Tag tone="warning">strategy only</Tag>}
                  {p.finding_ids.map((f, i) => (
                    <Link key={f} to={`/runs/${runId}/security/${f}`}><Tag tone="active">{p.finding_labels[i] ?? f}</Tag></Link>
                  ))}
                </div>
                {p.reason && <Markdown className="mt-1">{p.reason}</Markdown>}
                {p.expected_outcome && <p className="text-muted">Expected: {p.expected_outcome}</p>}
              </div>
              <div className="text-right text-[12px]">
                <div className="flex items-center justify-end gap-1">
                  {p.hash_verified ? <ShieldCheck aria-hidden className="size-3.5 text-success" /> : <ShieldX aria-hidden className="size-3.5 text-danger" />}
                  <span className={p.hash_verified ? 'text-success' : 'text-danger'}>{p.hash_verified ? 'hash matches its registration' : 'hash does not match its registration'}</span>
                </div>
                <Hash value={p.proposal_hash} length={24} label="proposal hash" />
                <div className={p.baseline_matches ? 'text-muted' : 'text-danger'}>{p.baseline_matches ? 'computed against the sealed baseline' : 'computed against another baseline'}</div>
              </div>
            </header>

            {(p.awaiting_decision || p.decidable) && (
              <Panel title="Decision" className="border-human/50">
                {p.decidable ? (
                  <ProposalDecision runId={runId} proposal={p} canDecide={mayDecide} cannotReason="MARS is advancing this run"
                    existingDecisionId={p.decisions.find((d) => !d.superseded)?.decision_id} />
                ) : <p className="text-muted">{p.decidable_reason}</p>}
              </Panel>
            )}
            {!p.decidable && p.decidable_reason && !p.awaiting_decision && (
              <p className="text-[12px] text-faint">{p.decidable_reason}</p>
            )}

            <Panel title="Before / after"><Edits p={p} /></Panel>

            <div className="grid gap-3 xl:grid-cols-2">
              <Panel title="Mutation Gateway">
                <GatewayFlow m={p.mutation} decisionIds={p.decisions.map((d) => d.decision_id)} />
                {p.mutation?.at && <p className="mt-2 text-[11px] text-faint">last gateway record {formatDateTime(p.mutation.at)}</p>}
              </Panel>
              <Panel title="Provenance and scope">
                <KeyValues rows={[
                  ['Provider', `${p.provider} ${p.provider_version ?? ''}`],
                  ['Rule', p.provenance?.rule_id],
                  ['Reference section', p.provenance?.reference_section],
                  ['Model', p.provenance?.model ? `${p.provenance.model} ${p.provenance.model_version ?? ''} (prompt ${p.provenance.prompt_hash ?? '?'})` : 'none (not model-authored)'],
                  ['Risk', p.risk],
                  ['Generated', formatDateTime(p.generated_at)],
                  ['Files', <span key="f" className="mono text-[11px]">{p.affected_file_ids.join(', ')}</span>],
                  ['Symbols', `${p.affected_symbol_ids.length} declared`],
                  ['Statements', `${p.affected_statement_ids.length} declared`],
                  ['Base hashes', <span key="b" className="mono text-[11px]">{Object.entries(p.base_hashes).map(([k, v]) => `${k.slice(-6)}:${v.slice(0, 10)}`).join(', ') || '—'}</span>],
                  ['Evidence', <span key="e" className="mono text-[11px]">{p.evidence_refs.join(', ') || '—'}</span>],
                  ['Knowledge', p.knowledge_refs.join(', ') || undefined],
                ]} />
              </Panel>
            </div>

            {p.decisions.length > 0 && (
              <Panel title="Decision history">
                <div className="grid gap-2 lg:grid-cols-2">
                  {p.decisions.map((d) => (
                    <div key={d.decision_id} className={cn(d.superseded && 'opacity-60')}>
                      {d.superseded && <div className="mb-1 text-[11px] text-faint">superseded by a later decision</div>}
                      <DecisionReceipt decision={d} />
                    </div>
                  ))}
                </div>
              </Panel>
            )}

            {p.mutation && p.mutation.lineage.length > 0 && (
              <Panel title="Lineage" bodyClassName="p-0">
                <Table label="Lineage">
                  <thead><tr>{['#', 'Kind', 'Change', 'File', 'Path', 'Hash before → after', 'Statements', 'Status'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>{p.mutation.lineage.map((l) => (
                    <tr key={l.sequence}>
                      <td className={`${td} mono`}>{l.sequence}</td><td className={td}>{l.kind}</td>
                      <td className={`${td} mono`}>{l.change_id ?? '—'}</td><td className={`${td} mono text-[11px]`}>{l.file_id ?? '—'}</td>
                      <td className={`${td} mono text-[11px]`}>{l.path_before}{l.path_after && l.path_after !== l.path_before ? ` → ${l.path_after}` : ''}</td>
                      <td className={`${td} mono text-[11px]`}>{l.hash_before?.slice(0, 8) ?? '—'} → {l.hash_after?.slice(0, 8) ?? '—'}</td>
                      <td className={`${td} text-[11px] text-muted`}>{l.statement_ids_changed.length} changed · {l.statement_ids_created.length} created · {l.statement_ids_deleted.length} deleted</td>
                      <td className={td}>{l.status}</td>
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
