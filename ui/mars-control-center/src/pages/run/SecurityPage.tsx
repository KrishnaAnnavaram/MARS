import { Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { useFindings } from '../../api/queries';
import { EmptyState, Panel, QueryView, Stat, StatusBadge, Table, Tag, td, th } from '../../components/ui';
import { ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { lookup, severityStatus } from '../../lib/status';

const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO', 'UNKNOWN'];

export function SecurityPage() {
  const runId = useRunId();
  const view = useFindings(runId);
  const [q, setQ] = useState('');
  const [severity, setSeverity] = useState('');

  return (
    <div className="space-y-3 p-4">
      <QueryView query={view}>
        {(v) => {
          const s = v.summary;
          return (
            <>
              <div className="grid grid-cols-2 gap-3 md:grid-cols-5 xl:grid-cols-10">
                <Stat label="Findings" value={s.total} />
                {SEVERITY_ORDER.filter((k) => s.by_severity[k]).map((k) => (
                  <Stat key={k} label={k.toLowerCase()} value={s.by_severity[k]} tone={lookup(severityStatus, k).tone} />
                ))}
                <Stat label="Analysed" value={`${s.analysed}/${s.total}`} sub="RCA + blast radius" />
                <Stat label="Planned" value={s.planned} />
                <Stat label="Awaiting" value={s.proposals_awaiting} tone={s.proposals_awaiting ? 'human' : undefined} sub="decision" />
                <Stat label="Applied" value={s.applied} />
                <Stat label="Cleared" value={s.cleared} tone={s.cleared ? 'success' : undefined} sub="arbiter" />
                <Stat label="Blocked" value={s.blocked} tone={s.blocked ? 'danger' : undefined} sub="arbiter" />
                <Stat label="Deferred" value={s.deferred} />
                <Stat label="Platform-blocked" value={s.blocked_by_platform} tone={s.blocked_by_platform ? 'warning' : undefined} />
              </div>
              {v.gaps.length > 0 && (
                <Panel title="Discovery gaps" className="border-warning/40">
                  <ul className="list-disc space-y-1 pl-5 text-muted">{v.gaps.map((g) => <li key={g}>{g}</li>)}</ul>
                </Panel>
              )}
              <FindingsTable runId={runId} v={v} q={q} setQ={setQ} severity={severity} setSeverity={setSeverity} />
            </>
          );
        }}
      </QueryView>
    </div>
  );
}

function FindingsTable({ runId, v, q, setQ, severity, setSeverity }: {
  runId: string; v: NonNullable<ReturnType<typeof useFindings>['data']>; q: string; setQ: (s: string) => void;
  severity: string; setSeverity: (s: string) => void;
}) {
  const rows = useMemo(() => v.findings
    .filter((f) => !severity || f.severity === severity)
    .filter((f) => !q || `${f.source_finding_id} ${f.title} ${f.cwe.join(' ')} ${f.location?.path}`.toLowerCase().includes(q.toLowerCase()))
    .sort((a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity)), [v.findings, q, severity]);

  if (!v.discovered) {
    return <EmptyState title="Security discovery has not run yet">Findings appear once read-only discovery completes.</EmptyState>;
  }
  if (v.findings.length === 0) return <EmptyState title="No findings detected" />;
  return (
    <Panel title={`Findings (${rows.length})`} bodyClassName="p-0"
      actions={
        <div className="flex items-center gap-2">
          <select aria-label="Severity filter" value={severity} onChange={(e) => setSeverity(e.target.value)}
            className="h-7 rounded border border-border-strong bg-panel-2 px-1.5 text-[12px]">
            <option value="">All severities</option>
            {SEVERITY_ORDER.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
          <label className="relative">
            <Search aria-hidden className="absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
            <span className="sr-only">Search findings</span>
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="ID, CWE, title, file"
              className="h-7 w-56 rounded border border-border-strong bg-panel-2 pl-7 pr-2 text-[12px]" />
          </label>
        </div>
      }>
      <Table label="Findings">
        <thead>
          <tr>{['ID', 'Severity', 'CWE', 'Title', 'Location', 'Source', 'Route', 'Blast radius', 'Proposal', 'Decision', 'Verification', 'Outcome']
            .map((h) => <th key={h} className={th}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((f) => (
            <tr key={f.finding_id} className="hover:bg-panel-2">
              <td className={td}>
                <Link className="mono text-primary hover:underline" to={`/runs/${runId}/security/${f.finding_id}`}>{f.source_finding_id}</Link>
              </td>
              <td className={td}><StatusBadge status={lookup(severityStatus, f.severity)} /></td>
              <td className={`${td} mono`}>{f.cwe.join(', ')}</td>
              <td className={`${td} max-w-[260px]`}>{f.title}</td>
              <td className={`${td} mono max-w-[240px] truncate text-muted`} title={f.location?.path}>
                {f.location ? `${f.location.path.split('/').pop()}:${f.location.line_start}` : '—'}
              </td>
              <td className={`${td} text-[11px] text-muted`}>{f.source}</td>
              <td className={td}>{f.route ? <Tag>{f.route}</Tag> : <span className="text-faint">not planned</span>}
                {f.blocked_by_platform && <Tag tone="warning" className="ml-1">platform</Tag>}</td>
              <td className={`${td} text-muted`}>{f.blast_radius_scope ?? '—'}</td>
              <td className={td}>{f.proposal_id ? <Link to={`/runs/${runId}/changes/${f.proposal_id}`}><ProposalStatusBadge status={f.proposal_status} /></Link> : '—'}</td>
              <td className={td}>{f.decision ?? '—'}</td>
              <td className={td}>{f.verification ?? '—'}</td>
              <td className={`${td} mono text-[11px]`}>{f.item_status ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </Table>
    </Panel>
  );
}
