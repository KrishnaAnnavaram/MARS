import { useState } from 'react';
import { useRun, useVerdict } from '../../api/queries';
import { ArtifactViewer } from '../../components/ArtifactViewer';
import { EmptyState, Panel, QueryView, Stat, StatusBadge, Table, Tag, td, th } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';
import { formatDateTime } from '../../lib/format';
import { lookup, verdictStatus } from '../../lib/status';

const ITEM_TONE: Record<string, 'success' | 'danger' | 'warning' | 'human' | 'muted'> = {
  FIXED: 'success', MIGRATED: 'success', STILL_VULNERABLE: 'danger', FAILED: 'danger', BLOCKED_BY_PLATFORM: 'warning',
  DEFERRED_BY_DEVELOPER: 'warning', REJECTED_BY_DEVELOPER: 'warning', PENDING_APPROVAL: 'human', NEEDS_HUMAN: 'human',
  INSUFFICIENT_EVIDENCE: 'warning', MIGRATION_INCOMPLETE: 'danger', MIGRATION_DECLINED: 'muted', NOT_COMPARED: 'warning',
};

export function VerdictPage() {
  const runId = useRunId();
  const view = useVerdict(runId);
  const run = useRun(runId).data!;
  const [report, setReport] = useState<string>();
  return (
    <div className="space-y-3 p-4">
      <QueryView query={view}>
        {(v) => !v.available ? (
          <EmptyState title="The verdict has not been computed">
            MARS computes the verdict after final validation. The run is at <span className="mono">{run.phase}</span>.
          </EmptyState>
        ) : (
          <>
            <section className="flex flex-wrap items-center gap-4 rounded-md border border-border bg-panel p-4" aria-label="Verdict">
              <div>
                <div className="text-[11px] uppercase tracking-wide text-faint">Final verdict</div>
                <StatusBadge status={lookup(verdictStatus, v.outcome)} className="mt-1 px-2 py-1 text-[15px]" />
              </div>
              <div className="text-[12px] text-muted">
                Computed by the MARS verdict calculator at {formatDateTime(v.generated_at)} under policy {v.policy_version}. The
                Control Center shows it as recorded; it computes nothing.
              </div>
            </section>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
              {Object.entries(v.items_by_status).map(([k, n]) => <Stat key={k} label={k.toLowerCase().replace(/_/g, ' ')} value={n} tone={ITEM_TONE[k]} />)}
            </div>
            <div className="grid gap-3 xl:grid-cols-2">
              <Panel title="Why">
                {v.reasons.length ? <ul className="list-disc space-y-1 pl-5">{v.reasons.map((r) => <li key={r}>{r}</li>)}</ul> : <p className="text-muted">No reasons recorded.</p>}
                {v.hard_failures.length > 0 && (
                  <div className="mt-2"><div className="text-[11px] uppercase text-danger">Hard failures (never overridden)</div>
                    <ul className="list-disc pl-5 text-danger">{v.hard_failures.map((r) => <li key={r}>{r}</li>)}</ul></div>
                )}
              </Panel>
              <Panel title="Outstanding">
                <div className="space-y-2">
                  <div><span className="text-muted">Unknown dimensions: </span>{v.unknown_dimensions.length ? v.unknown_dimensions.map((d) => <Tag key={d} tone="warning" className="mr-1">{d}</Tag>) : 'none'}</div>
                  <div><span className="text-muted">Pending decisions: </span>{v.pending_decisions.length ? v.pending_decisions.join('; ') : 'none'}</div>
                  <div><span className="text-muted">Proposals: </span>{Object.entries(v.proposals_by_status).map(([k, n]) => `${n} ${k.toLowerCase()}`).join(', ') || 'none'}</div>
                </div>
              </Panel>
            </div>
            <Panel title={`Items (${v.items.length})`} bodyClassName="p-0">
              <Table label="Verdict items">
                <thead><tr>{['Item', 'Kind', 'Status', 'Capability verdict', 'Reason'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                <tbody>{v.items.map((i) => (
                  <tr key={i.item_id}>
                    <td className={`${td} max-w-[320px]`}>{i.label}</td>
                    <td className={`${td} text-muted`}>{i.kind}</td>
                    <td className={td}><Tag tone={ITEM_TONE[i.status] ?? 'muted'}>{i.status}</Tag></td>
                    <td className={`${td} text-[12px] text-muted`}>{i.legacy_verdict ?? '—'}</td>
                    <td className={`${td} max-w-[520px] text-[12px] text-muted`}>{i.reason ?? '—'}</td>
                  </tr>
                ))}</tbody>
              </Table>
            </Panel>
            <Panel title="Evidence package">
              <div className="flex flex-wrap gap-2">
                {v.reports.map((r) => (
                  <button key={r} type="button" onClick={() => setReport(r)} className="mono rounded border border-border px-2 py-1 text-[12px] text-primary hover:bg-panel-2">
                    {r}
                  </button>
                ))}
              </div>
            </Panel>
          </>
        )}
      </QueryView>
      <ArtifactViewer runId={runId} path={report} onClose={() => setReport(undefined)} />
    </div>
  );
}
