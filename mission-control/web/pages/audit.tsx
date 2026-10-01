import { useMemo, useState } from 'react';
import { Link } from '@tanstack/react-router';
import { Bot, Cog, UserRound } from 'lucide-react';
import type { AuditItem } from '../../shared/types';
import { STAGE_BY_ID } from '../../shared/stages';
import { useAudit, useIssues } from '../api';
import { PageHeader } from '../components/shell';
import { EmptyState, ErrorState, Loading, Panel, Pill, ProvenanceBadge, Time } from '../components/ui';

type Kind = 'all' | 'human' | 'agent' | 'script' | 'gaps';

const KIND_LABEL: Record<Kind, string> = { all: 'All', human: 'Human decisions', agent: 'AI-authored', script: 'Deterministic', gaps: 'Gaps in the record' };

function ActorIcon({ k }: { k: AuditItem['actorKind'] }) {
  if (k === 'human') return <UserRound size={14} className="text-human" aria-label="Human" />;
  if (k === 'agent') return <Bot size={14} className="text-run" aria-label="Agent" />;
  if (k === 'script') return <Cog size={14} className="text-muted" aria-label="Script" />;
  return <span className="inline-block h-3.5 w-3.5" aria-label="System" />;
}

export function AuditPage() {
  const [issue, setIssue] = useState<string>('');
  const [kind, setKind] = useState<Kind>('all');
  const [src, setSrc] = useState<'all' | 'observed' | 'reconstructed'>('all');
  const q = useAudit(issue || null);
  const issues = useIssues();
  const items = useMemo(() => (q.data?.items || []).filter((i) => {
    if (kind === 'gaps') return i.gap;
    if (kind !== 'all' && i.actorKind !== kind) return false;
    if (src !== 'all' && i.timeSource !== src) return false;
    return true;
  }), [q.data, kind, src]);
  const counts = useMemo(() => {
    const all = q.data?.items || [];
    return { all: all.length, human: all.filter((i) => i.actorKind === 'human').length, agent: all.filter((i) => i.actorKind === 'agent').length, script: all.filter((i) => i.actorKind === 'script').length, gaps: all.filter((i) => i.gap).length };
  }, [q.data]);
  return (
    <div>
      <PageHeader title="Audit" subtitle="Who decided what, on which evidence, and when. Deterministic script results, AI-authored judgements and human decisions are kept visibly separate; gaps are things MARS does not record." />
      <div className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-2 sm:px-6">
        <label className="text-sm">Issue{' '}
          <select value={issue} onChange={(e) => setIssue(e.target.value)} className="h-8 rounded border border-line bg-surface-2 px-2 text-sm">
            <option value="">All issues</option>
            {(issues.data?.issues || []).map((i) => <option key={i.id} value={i.id}>{i.id}</option>)}
          </select>
        </label>
        <div role="group" aria-label="Actor" className="flex flex-wrap gap-1">{(Object.keys(KIND_LABEL) as Kind[]).map((k) => <button key={k} type="button" aria-pressed={kind === k} onClick={() => setKind(k)} className={`h-7 rounded border px-2 text-xs ${kind === k ? 'border-accent bg-surface-3 font-semibold' : 'border-line'}`}>{KIND_LABEL[k]} ({counts[k]})</button>)}</div>
        <div role="group" aria-label="Time source" className="flex gap-1">{(['all', 'observed', 'reconstructed'] as const).map((s) => <button key={s} type="button" aria-pressed={src === s} onClick={() => setSrc(s)} className={`h-7 rounded border px-2 text-xs ${src === s ? 'border-accent bg-surface-3 font-semibold' : 'border-line'}`}>{s}</button>)}</div>
      </div>
      <div className="p-4 sm:p-6">
        {q.isLoading ? <Loading rows={8} /> : q.error ? <ErrorState error={q.error} retry={() => q.refetch()} /> : (
          <Panel>
            {items.length === 0 ? <EmptyState title="Nothing matches" body="Try another filter." /> : (
              <>
              <ul className="divide-y divide-line md:hidden" aria-label="Audit trail">
                {items.map((i, n) => (
                  <li key={n} className={`px-3 py-2 text-sm ${i.gap ? 'bg-tool-tint' : i.actorKind === 'human' ? 'bg-human-tint/40' : ''}`}>
                    <div className="flex flex-wrap items-center gap-1.5">{i.gap && <Pill tone="tool">gap</Pill>}{i.conflicts ? <Pill tone="fail">⚠ {i.conflicts} conflict{i.conflicts > 1 ? 's' : ''}</Pill> : null}<span className="font-medium">{i.title}</span></div>
                    <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
                      <Time iso={i.time} source={i.timeSource} />
                      <span className="inline-flex items-center gap-1"><ActorIcon k={i.actorKind} />{i.actor}</span>
                      <ProvenanceBadge p={i.provenance} />
                      {i.issueId && <Link to="/issues/$issueId" params={{ issueId: i.issueId }} search={{ tab: 'timeline' }} className="mono whitespace-nowrap">{i.issueId}</Link>}
                      {i.evidence && <Link to="/evidence/view" search={{ path: i.evidence.path }} className="mono break-all text-[11px]">{i.evidence.path.split('/').pop()}</Link>}
                    </div>
                    {i.detail && <div className="mt-0.5 text-xs text-muted">{i.detail}</div>}
                  </li>
                ))}
              </ul>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[820px] border-collapse text-sm" aria-label="Audit trail">
                  <thead><tr className="bg-surface-2 text-left text-xs uppercase text-muted"><th className="px-3 py-1.5" scope="col">When</th><th scope="col">Who</th><th scope="col">Kind</th><th scope="col">Issue</th><th scope="col">What</th><th scope="col">Evidence</th></tr></thead>
                  <tbody>
                    {items.map((i, n) => (
                      <tr key={n} className={`border-t border-line align-top ${i.gap ? 'bg-tool-tint' : i.actorKind === 'human' ? 'bg-human-tint/40' : ''}`}>
                        <td className="whitespace-nowrap px-3 py-1.5 text-xs"><Time iso={i.time} source={i.timeSource} /></td>
                        <td className="py-1.5 text-xs"><span className="inline-flex items-center gap-1"><ActorIcon k={i.actorKind} />{i.actor}</span></td>
                        <td className="py-1.5"><ProvenanceBadge p={i.provenance} /></td>
                        <td className="py-1.5">{i.issueId ? <Link to="/issues/$issueId" params={{ issueId: i.issueId }} search={{ tab: 'timeline' }} className="mono whitespace-nowrap text-xs">{i.issueId}</Link> : '—'}</td>
                        <td className="py-1.5 pr-2"><div className="flex flex-wrap items-center gap-1.5">{i.gap && <Pill tone="tool">gap</Pill>}{i.conflicts ? <Pill tone="fail">⚠ {i.conflicts} conflict{i.conflicts > 1 ? 's' : ''} in this evidence</Pill> : null}<span>{i.title}</span>{i.stage && <span className="text-xs text-subtle">{STAGE_BY_ID[i.stage]?.short}</span>}</div>{i.detail && <div className="text-xs text-muted">{i.detail}</div>}</td>
                        <td className="py-1.5 pr-3">{i.evidence ? <Link to="/evidence/view" search={{ path: i.evidence.path }} className="mono text-[11px]">{i.evidence.path.split('/').pop()}</Link> : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              </>
            )}
          </Panel>
        )}
      </div>
    </div>
  );
}
