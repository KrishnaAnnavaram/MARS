import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import { useOverview } from '../api';
import type { IssueSummary, StageId } from '../../shared/types';
import { STAGE_BY_ID, VERIFICATION_CHECKS } from '../../shared/stages';
import { PageHeader } from '../components/shell';
import { RemediationBoard, StateLegend, StatusCell } from '../components/board';
import { EmptyState, ErrorState, FailureTag, Loading, Panel, SeverityBadge, StatusBadge, Tabs } from '../components/ui';
import { severityRank } from '../domain/status';

type Filter = 'all' | 'blocked' | 'awaiting' | 'cleared' | 'conflicts';

export function IssuesPage() {
  const search = useSearch({ strict: false }) as { tab?: 'board' | 'matrix'; filter?: Filter };
  const nav = useNavigate();
  const tab = search.tab || 'board';
  const filter: Filter = search.filter || 'all';
  const q = useOverview();
  const [text, setText] = useState('');
  const issues = useMemo(() => {
    const all = q.data?.issues || [];
    const t = text.trim().toLowerCase();
    return all.filter((i) => {
      if (t && !`${i.id} ${i.title} ${i.cwe || ''} ${i.services.join(' ')}`.toLowerCase().includes(t)) return false;
      if (filter === 'blocked') return i.verdict?.decision === 'Blocked';
      if (filter === 'cleared') return i.verdict?.decision === 'Cleared';
      if (filter === 'awaiting') return i.cells.approval.state === 'awaiting_human';
      if (filter === 'conflicts') return i.findings > 0;
      return true;
    });
  }, [q.data, text, filter]);
  if (q.isLoading) return <Loading rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const set = (patch: Record<string, string>) => nav({ to: '/issues', search: { tab, filter, ...patch }, replace: true });
  return (
    <div>
      <PageHeader title="Issues" subtitle="Every issue in the register and every issue with pipeline evidence. Cells are derived from the evidence files each stage writes." />
      <div className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-2 sm:px-6">
        <label className="flex items-center gap-2 text-sm">
          <span className="sr-only">Search issues</span>
          <input type="search" value={text} onChange={(e) => setText(e.target.value)} placeholder="Search id, title, CWE, service" className="h-8 w-64 max-w-full rounded border border-line bg-surface-2 px-2 text-sm" />
        </label>
        <div role="group" aria-label="Filter" className="flex flex-wrap gap-1">
          {(['all', 'blocked', 'awaiting', 'cleared', 'conflicts'] as Filter[]).map((f) => (
            <button key={f} type="button" aria-pressed={filter === f} onClick={() => set({ filter: f })} className={`h-7 rounded border px-2 text-xs ${filter === f ? 'border-accent bg-surface-3 font-semibold' : 'border-line'}`}>
              {{ all: 'All', blocked: 'Blocked', awaiting: 'Awaiting decision', cleared: 'Cleared', conflicts: 'Evidence conflicts' }[f]}
            </button>
          ))}
        </div>
        <span className="ml-auto text-xs text-muted">{issues.length} of {q.data.issues.length}</span>
      </div>
      <div className="px-4 sm:px-6">
        <Tabs label="Issue views" value={tab} onChange={(t) => set({ tab: t })} tabs={[{ id: 'board', label: 'Board' }, { id: 'matrix', label: 'Verification matrix' }]} />
      </div>
      <div className="space-y-3 p-4 sm:p-6" role="tabpanel">
        {issues.length === 0 ? <EmptyState title="No issues match" body={q.data.issues.length ? 'Clear the search or filter.' : 'The register has no issues and no pipeline evidence exists yet.'} /> : tab === 'board' ? (
          <Panel><RemediationBoard issues={issues} stages={q.data.stages} /><div className="border-t border-line px-3 py-2"><StateLegend /></div></Panel>
        ) : <Matrix issues={issues} />}
      </div>
    </div>
  );
}

function Matrix({ issues }: { issues: IssueSummary[] }) {
  const sorted = [...issues].sort((a, b) => severityRank(a.severity) - severityRank(b.severity));
  const checks: readonly StageId[] = VERIFICATION_CHECKS;
  return (
    <Panel title="Verification matrix" subtitle="Five independent checks per fix. Each is shown separately; a failing gate is never collapsed into a single 'failed'. Cause verified = the failure is attributable to the patch, not inferred.">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[900px] border-collapse text-sm" aria-label="Verification matrix">
          <thead><tr className="bg-surface-2 text-left text-xs uppercase text-muted">
            <th className="px-3 py-1.5" scope="col">Issue</th><th className="px-1" scope="col">Fix</th>
            {checks.map((c) => <th key={c} className="px-1" scope="col">{STAGE_BY_ID[c].short}</th>)}
            <th className="px-2" scope="col">Verdict</th><th className="px-2" scope="col">Why</th>
          </tr></thead>
          <tbody>
            {sorted.map((i) => (
              <tr key={i.id} className="border-t border-line align-top">
                <th scope="row" className="px-3 py-2 text-left font-normal"><Link to="/issues/$issueId" params={{ issueId: i.id }} search={{ tab: 'verification' }} className="mono font-semibold">{i.id}</Link> <SeverityBadge s={i.severity} /></th>
                <td className="px-0.5 py-2"><StatusCell stage={STAGE_BY_ID.fix} cell={i.cells.fix} /></td>
                {checks.map((c) => <td key={c} className="px-0.5 py-2"><StatusCell stage={STAGE_BY_ID[c]} cell={i.cells[c]} />{i.cells[c].failureClass && <div className="mt-0.5"><FailureTag cls={i.cells[c].failureClass} verified={i.cells[c].causeVerified} /></div>}</td>)}
                <td className="px-2 py-2">{i.verdict ? <StatusBadge state={i.cells.verdict.state} label={`${i.verdict.decision} ${i.verdict.score ?? ''}/${i.verdict.threshold ?? ''}`} /> : '—'}{i.verdict && i.verdict.replayMatches === false && <div className="mt-0.5 text-[11px] text-fail">replay disagrees: {i.verdict.replayDecision}</div>}</td>
                <td className="px-2 py-2 text-xs">{i.blocker?.summary || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
