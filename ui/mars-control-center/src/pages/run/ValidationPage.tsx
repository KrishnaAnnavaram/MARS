import { useValidation } from '../../api/queries';
import { EmptyState, KeyValues, Panel, QueryView, StatusBadge, Table, Tag, td, th } from '../../components/ui';
import { ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { formatDateTime } from '../../lib/format';
import { dimensionStatus, lookup } from '../../lib/status';
import { Dimensions } from './MigrationPage';

export function ValidationPage() {
  const runId = useRunId();
  const view = useValidation(runId);
  return (
    <div className="space-y-3 p-4">
      <QueryView query={view}>
        {(v) => (
          <>
            <header className="flex flex-wrap items-center gap-3">
              <h1 className="text-[15px] font-semibold text-strong">Validation</h1>
              <StatusBadge status={lookup(dimensionStatus, v.status === 'PASSED' ? 'PASS' : v.status === 'FAILED' ? 'FAIL'
                : v.status === 'NOT_RUN' ? 'NOT_RUN' : 'INSUFFICIENT_EVIDENCE')} />
              {v.generated_at && <span className="text-muted">{v.validation_id} · {formatDateTime(v.generated_at)}</span>}
              <span className="ml-auto text-[12px] text-faint">Each dimension is reported separately. Unknown or unexecuted dimensions never count as passed.</span>
            </header>
            <Panel title="Final (unified) validation" bodyClassName="p-0">
              <Dimensions dims={v.final_validation} empty="Final validation has not run yet" />
            </Panel>
            {v.not_run_dimensions.length > 0 && (
              <Panel title="Dimensions with no result">
                <div className="flex flex-wrap gap-1">{v.not_run_dimensions.map((d) => <Tag key={d}>{d}: not run</Tag>)}</div>
              </Panel>
            )}
            <div className="grid gap-3 xl:grid-cols-2">
              <Panel title="Migration validation" bodyClassName="p-0">
                <Dimensions dims={v.migration_validation} empty="Migration validation did not run" />
              </Panel>
              <Panel title="Baseline (round 0)">
                {v.baseline ? (
                  <KeyValues rows={[
                    ['Seal', <span key="s" className="mono text-[11px] break-all">{v.baseline.seal}</span>],
                    ['Sealed', formatDateTime(v.baseline.sealed_at)],
                    ['Build', v.baseline.build_outcome],
                    ['Pre-existing failures', v.baseline.pre_existing_failures.join(', ') || 'none (recorded, never fixed)'],
                    ['Runtime started', v.baseline.runtime_started === undefined ? 'not run' : String(v.baseline.runtime_started)],
                  ]} />
                ) : <EmptyState title="No baseline yet" />}
              </Panel>
            </div>
            <Panel title="Security verification (per applied fix)" bodyClassName="p-0">
              {v.security_verification.length === 0 ? <EmptyState title="No fix has been verified" /> : (
                <Table label="Security verification">
                  <thead><tr>{['Plan', 'Fix', 'Re-scan', 'Red-team', 'Behaviour', 'QA', 'Build', 'Score', 'Arbiter'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>{v.security_verification.map((s) => (
                    <tr key={s.plan_id}>
                      <td className={`${td} mono text-[11px]`}>{s.plan_id}</td><td className={td}>{s.fix_status}</td>
                      <td className={td}>{s.rescan}</td><td className={td}>{s.redteam}</td><td className={td}>{s.behavior}</td>
                      <td className={td}>{s.qa}</td><td className={td}>{s.build}</td>
                      <td className={`${td} mono`}>{s.score}/{s.threshold}</td>
                      <td className={td}><Tag tone={s.decision === 'Cleared' ? 'success' : 'danger'}>{s.decision}</Tag></td>
                    </tr>
                  ))}</tbody>
                </Table>
              )}
            </Panel>
            <Panel title="Proposal validation" bodyClassName="p-0">
              {v.proposal_validation.length === 0 ? <EmptyState title="No applied proposal yet" /> : (
                <Table label="Proposal validation">
                  <thead><tr>{['Proposal', 'Capability', 'Status', 'Findings'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>{v.proposal_validation.map((p) => (
                    <tr key={p.proposal_id}>
                      <td className={`${td} mono text-[11px]`}>{p.proposal_id}</td><td className={td}>{p.capability}</td>
                      <td className={td}>{p.status === 'NOT_YET_VALIDATED' ? <Tag>applied, not yet validated</Tag> : <ProposalStatusBadge status={p.status} />}</td>
                      <td className={`${td} mono text-[11px]`}>{p.finding_ids.join(', ') || '—'}</td>
                    </tr>
                  ))}</tbody>
                </Table>
              )}
            </Panel>
          </>
        )}
      </QueryView>
    </div>
  );
}
