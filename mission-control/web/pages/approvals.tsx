import { useState } from 'react';
import { Link, useParams } from '@tanstack/react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Lock, UserRound } from 'lucide-react';
import type { ApprovalInfo } from '../../shared/types';
import { ApiError, decisionToken, getJson, postJson, useApproval, useApprovals, useSession } from '../api';
import { PageHeader } from '../components/shell';
import { EmptyState, ErrorState, HashChip, KV, Loading, Panel, Pill, ProvenanceBadge, SeverityBadge, StatusBadge, Time } from '../components/ui';
import type { CellState } from '../../shared/types';

const STATE_CELL: Record<ApprovalInfo['state'], CellState> = {
  pending: 'awaiting_human', approved: 'passed', rejected: 'failed', approved_unattributed: 'passed', rejected_unattributed: 'failed', invalidated: 'inconclusive', no_plan: 'waiting',
};

/** An approval that no longer covers the plan on disk or the patch is a warning, not a green pass. */
function needsReview(a: ApprovalInfo): boolean {
  return (a.state === 'approved' || a.state === 'approved_unattributed') && Boolean(a.plan?.reproposed || a.implementation?.deviation);
}
function approvalBadge(a: ApprovalInfo): { state: CellState; label: string } {
  return needsReview(a) ? { state: 'inconclusive', label: `${a.stateLabel} — needs re-review` } : { state: STATE_CELL[a.state], label: a.stateLabel };
}

export function ApprovalsPage() {
  const q = useApprovals();
  if (q.isLoading) return <Loading rows={5} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const pending = q.data.approvals.filter((a) => a.state === 'pending');
  const rest = q.data.approvals.filter((a) => a.state !== 'pending');
  return (
    <div>
      <PageHeader title="Approval Center" subtitle="The one human gate before implementation: a fix plan moves from Proposed to Approved or Rejected only by a human. Mission Control never approves anything by itself." />
      <div className="space-y-4 p-4 sm:p-6">
        <div className={`flex items-start gap-2 rounded border px-3 py-2 text-sm ${q.data.decisions.enabled ? 'border-human bg-human-tint' : 'border-line bg-surface-2'}`}>
          {q.data.decisions.enabled ? <UserRound size={16} className="mt-0.5 text-human" /> : <Lock size={16} className="mt-0.5 text-muted" />}
          <div><b>{q.data.decisions.enabled ? 'Decisions can be recorded from this console.' : 'Read-only.'}</b> {q.data.decisions.reason}</div>
        </div>
        <Panel title={`Awaiting a decision (${pending.length})`}>
          {pending.length === 0 ? <EmptyState title="No plan is waiting for a decision" body="Every fix plan on disk already has a decision (or there are none). A new plan appears here when 04_fix-generator Stage 1 writes Status: Proposed." /> : <ApprovalList items={pending} />}
        </Panel>
        <Panel title={`Decided (${rest.length})`} subtitle="Decisions written into the plan's Status cell, with or without a decision record.">
          {rest.length === 0 ? <EmptyState title="None" /> : <ApprovalList items={rest} />}
        </Panel>
      </div>
    </div>
  );
}

function ApprovalList({ items }: { items: ApprovalInfo[] }) {
  return (
    <ul className="divide-y divide-line">
      {items.map((a) => (
        <li key={a.issueId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
          <Link to="/approvals/$issueId" params={{ issueId: a.issueId }} className="mono font-semibold">{a.issueId}</Link>
          <SeverityBadge s={a.severity} />
          <span className="min-w-0 flex-1 truncate">{a.title}</span>
          <StatusBadge {...approvalBadge(a)} />
          {a.plan?.reproposed && <Pill tone="warn">carried over a re-proposal</Pill>}
          {a.implementation?.deviation && <Pill tone="human">implementation deviates</Pill>}
        </li>
      ))}
    </ul>
  );
}

export function ApprovalPage() {
  const { issueId } = useParams({ strict: false }) as { issueId?: string };
  const q = useApproval(issueId);
  const session = useSession();
  if (q.isLoading) return <Loading rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const { approval: a, issue } = q.data;
  const p = a.plan;
  return (
    <div>
      <PageHeader crumbs={<><Link to="/approvals">Approvals</Link> / {a.issueId}</>} title={<span className="flex flex-wrap items-center gap-2"><span className="mono">{a.issueId}</span><SeverityBadge s={a.severity} /><span className="text-base font-normal">{a.title}</span></span>}
        subtitle={<span>Decision packet. Everything below is read from the files on disk; the plan's hash is pinned so a decision can only apply to the version you read.</span>} />
      <div className="grid gap-4 p-4 sm:p-6 xl:grid-cols-[minmax(0,1fr)_400px]">
        <div className="space-y-4">
          {!p ? <EmptyState title="No plan" body="There is no fix plan for this issue." /> : (
            <Panel title="The plan you are deciding on" subtitle={<span className="flex items-center gap-2"><ProvenanceBadge p="ai_authored" actor="04_fix-generator" /> route {p.route} · confidence {p.confidence || '—'} · generated <Time iso={p.generatedAt} source="reconstructed" /></span>} actions={<HashChip sha={a.planSha256} />}>
              <div className="space-y-3 p-3 text-sm">
                {p.headline && <p className="font-medium">{p.headline}</p>}
                <KV items={[{ k: 'CWE', v: <span className="mono">{p.cwe}{p.cweName ? ` — ${p.cweName}` : ''}</span> }, { k: 'OWASP', v: p.owasp || '—' }, { k: 'Blast radius', v: a.priority || '—' }, { k: 'Services', v: a.services.join(', ') || '—' }, ...(p.dependency ? [{ k: 'Dependency', v: <span className="mono">{p.dependency}</span> }] : [])]} />
                {p.approach && <div><h4 className="text-xs font-semibold uppercase text-muted">Approach</h4><p>{p.approach}</p></div>}
                {p.plannedChanges.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">Files it will change</h4><ul className="mt-1 space-y-1">{p.plannedChanges.map((c, i) => <li key={i} className="rounded border border-line p-2"><div className="mono text-xs">{c.file}</div><div className="text-muted">{c.change}</div></li>)}</ul></div>}
                {p.alternatives.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">Alternatives considered</h4><ul className="list-disc pl-5 text-muted">{p.alternatives.map((r, i) => <li key={i}>{r}</li>)}</ul></div>}
                {p.risks.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">Risks</h4><ul className="list-disc pl-5 text-muted">{p.risks.map((r, i) => <li key={i}>{r}</li>)}</ul></div>}
                {p.verification.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">How the fix will be verified</h4><ol className="list-decimal pl-5 text-muted">{p.verification.map((r, i) => <li key={i}>{r}</li>)}</ol></div>}
                {p.evidence && <Link to="/evidence/view" search={{ path: p.evidence.path }} className="mono text-xs">{p.evidence.path}</Link>}
              </div>
            </Panel>
          )}
          {issue.rca && <Panel title="Why it is needed"><div className="space-y-1 p-3 text-sm"><p>{issue.rca.rootCause}</p><p className="mono text-xs text-muted">{issue.rca.where}</p></div></Panel>}
        </div>
        <div className="space-y-4">
          <Panel title="Decision">
            <div className="space-y-3 p-3 text-sm">
              <StatusBadge {...approvalBadge(a)} />
              {a.state === 'approved_unattributed' && <p className="rounded border border-warn bg-warn-tint p-2 text-xs">The Status cell reads Approved, but no decision record exists: MARS cannot say who approved it, when, or which version.</p>}
              {p?.reproposed && <p className="flex gap-1.5 rounded border border-warn bg-warn-tint p-2 text-xs"><AlertTriangle size={14} className="shrink-0" /><span><b>Approval carried over a re-proposal.</b> {p.reproposedNote}</span></p>}
              {a.implementation?.deviation && <p className="rounded border border-human bg-human-tint p-2 text-xs"><b>Implementation deviates from what was approved.</b> {a.implementation.deviation}</p>}
              {a.decisions.map((r) => (
                <div key={r.decisionId} className="rounded border border-line p-2 text-xs">
                  <div className="font-semibold">{r.decision} by {r.actor}</div>
                  <div className="text-muted">{r.actorAuthentication.replace('_', ' ').toLowerCase()} · via {r.channel} · <Time iso={r.timestamp} source="observed" /></div>
                  <div className="mt-1">{r.rationale}</div>
                  <div className="mt-1 flex items-center gap-1">plan <HashChip sha={r.shaBefore} len={8} />{r.appliesToCurrent ? <span className="text-pass">current version</span> : <span className="text-fail">earlier version</span>}</div>
                </div>
              ))}
              {a.state === 'pending' ? <DecisionForm a={a} actor={session.data?.actor ?? null} /> : <p className="text-xs text-muted">{a.actions.reason}</p>}
              {a.actions.unavailable.map((u) => <p key={u.action} className="text-xs text-subtle"><b>{u.action}</b> is not available: {u.reason}</p>)}
            </div>
          </Panel>
          {issue.findingList.length > 0 && <Panel title="Integrity findings on this issue"><ul className="divide-y divide-line">{issue.findingList.filter((f) => f.severity !== 'info').map((f) => <li key={f.id} className="px-3 py-1.5 text-xs"><span className="mono text-muted">{f.rule}</span> {f.title}</li>)}</ul></Panel>}
        </div>
      </div>
    </div>
  );
}

export function DecisionForm({ a, actor }: { a: ApprovalInfo; actor: string | null }) {
  const qc = useQueryClient();
  const [decision, setDecision] = useState<'APPROVED' | 'REJECTED' | null>(null);
  const [rationale, setRationale] = useState('');
  const [confirm, setConfirm] = useState('');
  const [stale, setStale] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: async () => {
      // Re-read the packet first: refuse locally if the plan changed since this page rendered.
      const fresh = await getJson<{ approval: ApprovalInfo }>(`/api/v1/approvals/${encodeURIComponent(a.issueId)}`);
      if (fresh.approval.planSha256 !== a.planSha256 || fresh.approval.state !== 'pending') {
        setStale('The plan changed since you opened it. Review the new version before deciding.');
        throw new ApiError(409, 'PLAN_CHANGED', 'The plan changed since you opened it.');
      }
      return postJson<{ ok: true; record: { decision_id: string }; path: string; next: string }>(`/api/v1/approvals/${encodeURIComponent(a.issueId)}/decisions`, { decision, rationale, expectedSha256: a.planSha256, confirmIssueId: confirm });
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['approval', a.issueId] });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
      void qc.invalidateQueries({ queryKey: ['overview'] });
    },
  });
  if (!a.actions.allowed) return <p className="flex gap-1.5 rounded border border-line bg-surface-2 p-2 text-xs"><Lock size={14} className="shrink-0" />{a.actions.reason}</p>;
  if (!decisionToken()) return <p className="flex gap-1.5 rounded border border-human bg-human-tint p-2 text-xs"><Lock size={14} className="shrink-0" />To record a decision, open Mission Control from the link printed in the terminal that started it (it carries this launch's token). This keeps other local programs from deciding on your behalf.</p>;
  if (m.isSuccess) return <div role="status" className="rounded border border-pass bg-pass-tint p-2 text-xs"><b>Recorded</b> as {m.data.record.decision_id}. {m.data.next}</div>;
  const valid = decision && rationale.trim().length >= 10 && confirm === a.issueId;
  return (
    <form className="space-y-3 rounded border-2 border-human p-3" onSubmit={(e) => { e.preventDefault(); if (valid) m.mutate(); }} aria-label="Record a decision">
      <fieldset>
        <legend className="mb-1 text-xs font-semibold uppercase text-muted">Your decision</legend>
        <div className="flex gap-2">
          {a.actions.available.map((d) => (
            <label key={d} className={`flex h-8 cursor-pointer items-center gap-1.5 rounded border px-3 text-sm ${decision === d ? (d === 'APPROVED' ? 'border-pass bg-pass-tint font-semibold' : 'border-fail bg-fail-tint font-semibold') : 'border-line'}`}>
              <input type="radio" name="decision" value={d} checked={decision === d} onChange={() => setDecision(d)} />{d === 'APPROVED' ? 'Approve' : 'Reject'}
            </label>
          ))}
        </div>
      </fieldset>
      <label className="block text-sm">Rationale (required, recorded verbatim)
        <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} rows={3} className="mt-1 w-full rounded border border-line bg-surface-2 p-2 text-sm" aria-describedby="rationale-help" />
        <span id="rationale-help" className="text-xs text-muted">At least 10 characters.</span>
      </label>
      <label className="block text-sm">Type <span className="mono font-semibold">{a.issueId}</span> to confirm
        <input value={confirm} onChange={(e) => setConfirm(e.target.value)} className="mono mt-1 h-8 w-full rounded border border-line bg-surface-2 px-2 text-sm" autoComplete="off" />
      </label>
      <div className="text-xs text-muted">Recorded as <b>{actor || 'unknown user'}</b> (locally asserted, not authenticated) against plan <HashChip sha={a.planSha256} len={8} />. This edits only the plan's Status cell and writes a decision record; it does not start the Fixer.</div>
      {(stale || m.error) && <div role="alert" className="rounded border border-fail bg-fail-tint p-2 text-xs">{stale || (m.error as Error).message}</div>}
      <button type="submit" disabled={!valid || m.isPending} className="h-9 rounded bg-human px-4 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50">{m.isPending ? 'Recording…' : decision ? `Record ${decision === 'APPROVED' ? 'approval' : 'rejection'}` : 'Choose a decision'}</button>
    </form>
  );
}
