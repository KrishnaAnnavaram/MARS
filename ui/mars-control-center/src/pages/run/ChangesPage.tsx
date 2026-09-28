import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useProposals } from '../../api/queries';
import { EmptyState, Panel, QueryView, Stat, Table, Tabs, Tag, td, th } from '../../components/ui';
import { ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { formatTime } from '../../lib/format';

export function ChangesPage() {
  const runId = useRunId();
  const view = useProposals(runId);
  const [capability, setCapability] = useState('');
  const [awaiting, setAwaiting] = useState(false);
  return (
    <div className="space-y-3 p-4">
      <QueryView query={view}>
        {(v) => (
          <>
            <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
              <Stat label="Proposals" value={v.summary.total} />
              {Object.entries(v.summary.by_capability).map(([k, n]) => <Stat key={k} label={k.toLowerCase()} value={n} />)}
              {Object.entries(v.summary.by_status).map(([k, n]) => <Stat key={k} label={k.toLowerCase().replace(/_/g, ' ')} value={n} />)}
            </div>
            <ChangesTable runId={runId} rows={v.proposals} capability={capability} setCapability={setCapability}
              awaiting={awaiting} setAwaiting={setAwaiting} />
          </>
        )}
      </QueryView>
    </div>
  );
}

function ChangesTable({ runId, rows, capability, setCapability, awaiting, setAwaiting }: {
  runId: string; rows: NonNullable<ReturnType<typeof useProposals>['data']>['proposals']; capability: string;
  setCapability: (c: string) => void; awaiting: boolean; setAwaiting: (a: boolean) => void;
}) {
  const shown = useMemo(() => rows.filter((r) => (!capability || r.capability === capability) && (!awaiting || r.awaiting_decision)),
    [rows, capability, awaiting]);
  if (rows.length === 0) {
    return <EmptyState title="No proposals have been generated">Proposals appear once migration or remediation planning runs.</EmptyState>;
  }
  return (
    <Panel title={`Change proposals (${shown.length})`} bodyClassName="p-0"
      actions={
        <div className="flex items-center gap-3">
          <Tabs label="Capability" value={capability} onChange={setCapability}
            options={[{ value: '', label: 'All' }, { value: 'MIGRATION', label: 'Migration' }, { value: 'SECURITY', label: 'Security' }, { value: 'MANUAL', label: 'Manual' }]} />
          <label className="flex items-center gap-1 text-[12px] text-muted">
            <input type="checkbox" checked={awaiting} onChange={(e) => setAwaiting(e.target.checked)} /> awaiting a decision
          </label>
        </div>
      }>
      <Table label="Change proposals">
        <thead>
          <tr>{['Proposal', 'Capability', 'Findings / rule', 'Files', 'Operation', 'Decision', 'Status', 'Mutation', 'Validation', 'Proposed']
            .map((h) => <th key={h} className={th}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {shown.map((p) => (
            <tr key={p.proposal_id} className="hover:bg-panel-2">
              <td className={td}>
                <Link to={`/runs/${runId}/changes/${p.proposal_id}`} className="mono text-primary hover:underline">{p.proposal_id}</Link>
                {p.strategy_only && <Tag tone="warning" className="ml-1">strategy</Tag>}
              </td>
              <td className={`${td} text-[12px]`}>{p.capability}<div className="text-[11px] text-faint">{p.provider_type}</div></td>
              <td className={`${td} mono text-[12px]`}>{p.finding_labels.join(', ') || p.rule_id || '—'}</td>
              <td className={`${td} mono max-w-[260px] text-[11px] text-muted`}>{p.files.join(', ') || '—'}</td>
              <td className={`${td} text-[12px]`}>{p.operations.join(', ') || '—'}</td>
              <td className={td}>{p.decision ?? (p.awaiting_decision ? <span className="text-human">awaiting</span> : '—')}</td>
              <td className={td}><ProposalStatusBadge status={p.status} /></td>
              <td className={`${td} text-[12px]`}>{p.mutation ?? '—'}</td>
              <td className={`${td} text-[12px]`}>{p.validation ?? '—'}</td>
              <td className={`${td} mono text-[11px] text-faint`}>{formatTime(p.generated_at)}</td>
            </tr>
          ))}
        </tbody>
      </Table>
    </Panel>
  );
}
