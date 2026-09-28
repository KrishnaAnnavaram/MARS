import { CheckCircle2, ShieldCheck, ShieldX } from 'lucide-react';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { ApiError, newIdempotencyKey } from '../api/client';
import { useDecision, useResume, useSession } from '../api/queries';
import type { DecisionRecorded, DecisionView, HumanAction, ProposalRow } from '../api/types';
import { Button, ErrorState, Hash, KeyValues, Modal, StatusBadge } from '../components/ui';
import { cn, formatDateTime } from '../lib/format';
import { lookup, proposalStatus } from '../lib/status';

interface RecentDecisions {
  items: DecisionRecorded[];
  add: (r: DecisionRecorded) => void;
}

const RecentDecisionsContext = createContext<RecentDecisions | undefined>(undefined);

/**
 * Receipts of the decisions recorded from this browser for the current run. A gate stops waiting
 * as soon as its decision is recorded, so its card goes away; the receipt must not go with it.
 */
export function RecentDecisionsProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<DecisionRecorded[]>([]);
  const add = useCallback((r: DecisionRecorded) => setItems((all) => [r, ...all.filter((x) =>
    x.decision.decision_id !== r.decision.decision_id)]), []);
  const value = useMemo(() => ({ items, add }), [items, add]);
  return <RecentDecisionsContext.Provider value={value}>{children}</RecentDecisionsContext.Provider>;
}

export function useRecentDecisions(): RecentDecisions | undefined {
  return useContext(RecentDecisionsContext);
}

/** What the server recorded. Shown only after the engine returned the decision. */
export function DecisionReceipt({ decision, next }: { decision: DecisionView; next?: string }) {
  const verified = decision.integrity === 'VERIFIED';
  return (
    <div role="status" className="rounded-md border border-success/40 bg-success-soft p-3">
      <div className="mb-2 flex items-center gap-2 font-semibold text-strong">
        <CheckCircle2 aria-hidden className="size-4 text-success" /> Decision recorded
      </div>
      <KeyValues rows={[
        ['Decision ID', <span key="d" className="mono">{decision.decision_id}</span>],
        ['Selected', <span key="s" className="font-medium">{decision.selected}</span>],
        ['Integrity', <span key="i" className={cn('inline-flex items-center gap-1', verified ? 'text-success' : 'text-danger')}>
          {verified ? <ShieldCheck aria-hidden className="size-3.5" /> : <ShieldX aria-hidden className="size-3.5" />}
          {verified ? 'verified (HMAC; not a signature)' : 'TAMPERED'}</span>],
        ...(decision.proposal_hash ? [['Proposal hash', <Hash key="p" value={decision.proposal_hash} label="proposal hash" />] as [string, ReactNode]] : []),
        ...(decision.assessment_hash ? [['Assessment hash', <Hash key="a" value={decision.assessment_hash} label="assessment hash" />] as [string, ReactNode]] : []),
        ...(decision.plan_hash ? [['Plan hash', <Hash key="ph" value={decision.plan_hash} label="plan hash" />] as [string, ReactNode]] : []),
        ['Actor', `${decision.actor} (${decision.role})`],
        ['Authentication', <span key="au" title={decision.authentication_note}>{decision.actor_authentication}</span>],
        ['Timestamp', formatDateTime(decision.timestamp)],
      ]} />
      <p className="mt-2 text-[12px] text-muted">{decision.authentication_note}</p>
      {next && <p className="mt-1 text-[12px] text-text">{next}</p>}
    </div>
  );
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    const hints: Record<string, string> = {
      STALE_ASSESSMENT: 'The assessment changed since you opened it. Reload and review it again.',
      PROPOSAL_HASH_MISMATCH: 'The proposal changed since you reviewed it. Reload and review the new version.',
      PLAN_HASH_MISMATCH: 'The plan changed since you reviewed it. Reload and review it again.',
      STALE_PROPOSAL: 'The proposal is no longer awaiting a decision.',
      DECISION_ALREADY_RECORDED: 'Someone already decided this. Reload to see their decision.',
      RUN_BUSY: 'MARS is busy with this run. Try again when it stops.',
      INVALID_STATE: 'The run is no longer waiting for this decision.',
    };
    return hints[e.code] ? `${hints[e.code]} (${e.message})` : e.message;
  }
  return (e as Error)?.message ?? String(e);
}

/**
 * Gate A, Gate A2 and migration-plan decisions: explicit choice (none preselected), a rationale,
 * a review step, then the command. The UI claims nothing until the server returns the decision.
 */
export function GateDecision({ runId, action, endpoint, field, hashField }: {
  runId: string; action: HumanAction; endpoint: string; field: 'strategy' | 'choice' | 'verdict';
  hashField: 'expected_assessment_hash' | 'expected_plan_hash';
}) {
  const [choice, setChoice] = useState<string>();
  const [rationale, setRationale] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [key, setKey] = useState(newIdempotencyKey);
  const [recorded, setRecorded] = useState<DecisionRecorded>();
  const session = useSession();
  const recent = useRecentDecisions();
  const mutation = useDecision<Record<string, string>>(runId, endpoint, recent?.add);
  const option = action.options.find((o) => o.value === choice);

  if (recorded) return <DecisionReceipt decision={recorded.decision} next={recorded.next} />;
  const disabled = !action.can_decide;

  return (
    <div className="space-y-3">
      <fieldset disabled={disabled} aria-describedby={`${action.id}-hint`}>
        <legend className="mb-2 font-medium text-strong">{action.what_to_decide}</legend>
        <div className="grid gap-2 md:grid-cols-2">
          {action.options.map((o) => (
            <label key={o.value}
              className={cn('flex cursor-pointer gap-2 rounded-md border p-2.5', choice === o.value
                ? 'border-primary bg-primary-soft' : 'border-border bg-panel-2 hover:border-border-strong', disabled && 'cursor-not-allowed')}>
              <input type="radio" name={`${action.id}-choice`} value={o.value} checked={choice === o.value}
                onChange={() => setChoice(o.value)} className="mt-1" />
              <span className="min-w-0">
                <span className="flex items-center gap-2">
                  <span className="mono font-semibold text-strong">{o.value}</span>
                  {o.recommended && <span className="rounded bg-panel-3 px-1.5 text-[10px] uppercase tracking-wide text-muted">MARS advice</span>}
                </span>
                <span className="block text-[12px] text-muted">{o.consequence}</span>
              </span>
            </label>
          ))}
        </div>
        <label className="mt-3 block">
          <span className="mb-1 block text-muted">Rationale (required: an empty rationale is not a decision)</span>
          <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} rows={3} maxLength={4000}
            className="w-full rounded border border-border-strong bg-panel-2 p-2" />
        </label>
      </fieldset>
      <p id={`${action.id}-hint`} className="text-[12px] text-faint">
        {disabled ? action.cannot_decide_reason
          : `Recorded as ${session.data?.username} (${session.data?.authentication}). The decision is bound to the ${action.bound_to} hash shown.`}
      </p>
      <div className="flex items-center gap-3">
        <Button variant="primary" disabled={disabled || !choice || !rationale.trim()} onClick={() => setConfirming(true)}>
          Review decision
        </Button>
        <span className="text-[12px] text-faint">Bound to <Hash value={action.bound_hash} label="bound hash" /></span>
      </div>
      <Modal open={confirming} onOpenChange={setConfirming} title="Confirm decision"
        description="This records an immutable decision through the MARS approval store. A changed mind is a new decision, never an edit.">
        <div className="space-y-3">
          <KeyValues rows={[
            ['Gate', action.title],
            ['Decision', <span key="c" className="mono font-semibold">{choice}</span>],
            ['What happens next', option?.consequence],
            ['Rationale', rationale],
            ['Bound to', <Hash key="h" value={action.bound_hash} length={20} label="bound hash" />],
            ['Recorded as', `${session.data?.username} · ${session.data?.authentication}`],
          ]} />
          {mutation.error && <div role="alert" className="rounded border border-danger/40 bg-danger-soft p-2 text-text">{errorText(mutation.error)}</div>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setConfirming(false)}>Back</Button>
            <Button variant="primary" loading={mutation.isPending}
              onClick={() => mutation.mutate({ key, body: { [field]: choice!, rationale: rationale.trim(), [hashField]: action.bound_hash ?? '' } }, {
                onSuccess: (r) => {
                  setRecorded(r);
                  setConfirming(false);
                },
                onError: (e) => {
                  // a definite refusal: the next attempt is a new request
                  if (e instanceof ApiError && e.status !== 0) setKey(newIdempotencyKey());
                },
              })}>
              {mutation.isPending ? 'Recording…' : 'Record decision'}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

const VERDICTS = [
  { value: 'APPROVED', label: 'Approve', variant: 'primary' as const },
  { value: 'REJECTED', label: 'Reject', variant: 'danger' as const },
  { value: 'DEFERRED', label: 'Defer', variant: 'default' as const },
];

/** Gate B for one exact proposal, bound to the hash the reviewer inspected. */
export function ProposalDecision({ runId, proposal, canDecide, cannotReason, existingDecisionId, compact }: {
  runId: string; proposal: Pick<ProposalRow, 'proposal_id' | 'proposal_hash' | 'strategy_only'>; canDecide: boolean;
  cannotReason?: string; existingDecisionId?: string; compact?: boolean;
}) {
  const [verdict, setVerdict] = useState<string>();
  const [rationale, setRationale] = useState('');
  const [supersede, setSupersede] = useState(false);
  const [key, setKey] = useState(newIdempotencyKey);
  const [recorded, setRecorded] = useState<DecisionRecorded>();
  const session = useSession();
  const recent = useRecentDecisions();
  const mutation = useDecision<Record<string, string | undefined>>(runId,
    `/proposals/${encodeURIComponent(proposal.proposal_id)}/decision`, recent?.add);

  if (recorded) return <DecisionReceipt decision={recorded.decision} next={recorded.next} />;
  if (existingDecisionId && !supersede) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-[12px] text-muted">
        <span>Decided in <span className="mono">{existingDecisionId}</span>.</span>
        {canDecide && <Button size="sm" variant="ghost" onClick={() => setSupersede(true)}>Supersede with a new decision…</Button>}
      </div>
    );
  }

  return (
    <div>
      <div className={cn('flex flex-wrap items-center gap-2', compact && 'justify-end')}>
        {VERDICTS.map((v) => (
          <Button key={v.value} size="sm" variant={v.variant} disabled={!canDecide} onClick={() => setVerdict(v.value)}
            title={!canDecide ? cannotReason : undefined}>
            {v.label}
          </Button>
        ))}
      </div>
      {!canDecide && cannotReason && !compact && <p className="mt-1 text-[12px] text-faint">{cannotReason}</p>}
      <Modal open={!!verdict} onOpenChange={(o) => !o && setVerdict(undefined)} title={`${VERDICTS.find((v) => v.value === verdict)?.label} ${proposal.proposal_id}`}
        description={verdict === 'APPROVED'
          ? proposal.strategy_only
            ? 'This proposal is strategy only: approving authorizes producing a concrete fix, which needs its own approval. Nothing is applied.'
            : 'Approval authorizes exactly this proposal. The Mutation Gateway applies it when the run continues, after re-checking scope, identity, base hashes and this approval.'
          : 'The proposal will not be applied. The verdict reports it as decided by you.'}>
        <div className="space-y-3">
          <KeyValues rows={[
            ['Proposal', <span key="p" className="mono">{proposal.proposal_id}</span>],
            ['Bound to hash', <Hash key="h" value={proposal.proposal_hash} length={24} label="proposal hash" />],
            ['Supersedes', existingDecisionId ?? 'nothing (first decision)'],
            ['Recorded as', `${session.data?.username} · ${session.data?.authentication}`],
          ]} />
          <label className="block">
            <span className="mb-1 block text-muted">Rationale (required)</span>
            <textarea value={rationale} onChange={(e) => setRationale(e.target.value)} rows={3} maxLength={4000}
              className="w-full rounded border border-border-strong bg-panel-2 p-2" />
          </label>
          {mutation.error && <div role="alert" className="rounded border border-danger/40 bg-danger-soft p-2 text-text">{errorText(mutation.error)}</div>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setVerdict(undefined)}>Cancel</Button>
            <Button variant="primary" disabled={!rationale.trim()} loading={mutation.isPending}
              onClick={() => mutation.mutate({
                key,
                body: { verdict: verdict!, rationale: rationale.trim(), expected_proposal_hash: proposal.proposal_hash,
                  supersedes_decision_id: existingDecisionId },
              }, {
                onSuccess: (r) => {
                  setRecorded(r);
                  setVerdict(undefined);
                },
                onError: (e) => {
                  if (e instanceof ApiError && e.status !== 0) setKey(newIdempotencyKey());
                },
              })}>
              {mutation.isPending ? 'Recording…' : 'Record decision'}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}

/** Resume: the CLI's `harness resume`. It advances by the recorded decisions only. */
export function ContinueExecution({ runId, undecided, note }: { runId: string; undecided: number; note?: string }) {
  const session = useSession();
  const resume = useResume(runId);
  const [acceptPending, setAcceptPending] = useState(false);
  const may = session.data?.permissions.includes('resume');
  return (
    <div className="space-y-2 rounded-md border border-border bg-panel-2 p-3">
      <div className="font-medium text-strong">Continue execution</div>
      {note && <p className="text-[12px] text-muted">{note}</p>}
      {undecided > 0 && (
        <label className="flex items-start gap-2 text-[12px]">
          <input type="checkbox" checked={acceptPending} onChange={(e) => setAcceptPending(e.target.checked)} className="mt-0.5" />
          <span>
            Leave the {undecided} undecided proposal(s) pending and continue. They stay unapproved, are never applied, and
            are reported as pending in the verdict.
          </span>
        </label>
      )}
      <div className="flex items-center gap-2">
        <Button variant="primary" disabled={!may} loading={resume.isPending} onClick={() => resume.mutate(acceptPending)}
          title={!may ? 'Requires the OPERATOR, APPROVER or ADMIN role' : undefined}>
          Continue
        </Button>
        {resume.data && <span className="text-[12px] text-success">{resume.data.message}</span>}
      </div>
      {resume.error && <ErrorState error={resume.error} title="The run was not resumed" />}
    </div>
  );
}

export function ProposalStatusBadge({ status }: { status?: string }) {
  return <StatusBadge status={lookup(proposalStatus, status)} />;
}
