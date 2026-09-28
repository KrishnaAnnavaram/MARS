import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import type { DecisionRecorded, HumanAction, SessionView } from '../api/types';
import { useState } from 'react';
import { DecisionReceipt, GateDecision, ProposalDecision, RecentDecisionsProvider, useRecentDecisions } from './decisions';

const session: SessionView = {
  authenticated: true, username: 'approver', display_name: 'Dev Approver', roles: ['APPROVER'], auth_mode: 'dev',
  authentication: 'DEVELOPMENT_ASSERTED', permissions: ['read', 'decide', 'resume'],
};

const gateA: HumanAction = {
  id: 'GATE_A', gate: 'GATE_A', decision_type: 'EXECUTION_STRATEGY', title: 'Human Gate A: choose the execution strategy',
  why_stopped: 'Discovery is complete.', what_to_decide: 'Which work MARS may execute.',
  options: [
    { value: 'MIGRATE_FIRST', label: 'MIGRATE FIRST', consequence: 'Migration first.', recommended: true },
    { value: 'SECURITY_ONLY', label: 'SECURITY ONLY', consequence: 'Security only.', recommended: false },
  ],
  inspect: [], bound_to: 'combined assessment', bound_hash: 'a'.repeat(64), requires_rationale: true, can_decide: true,
  resumable: false, proposals: [], context: {}, manual_steps: [],
};

const recorded = (selected: string): DecisionRecorded => ({
  advancing: true, next: 'MARS is advancing the run',
  decision: {
    decision_id: 'DEC-01M3TEST', type: 'EXECUTION_STRATEGY', selected, finding_ids: [], actor: 'approver', role: 'APPROVER',
    actor_authentication: 'DEVELOPMENT_ASSERTED', authentication_note: 'Development identity', rationale: 'because',
    timestamp: '2026-09-27T10:00:00Z', integrity: 'VERIFIED', superseded: false, assessment_hash: 'a'.repeat(64),
  },
});

function wrap(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['session'], session);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

let fetchMock: MockInstance<typeof fetch>;
/** Answers the session query and returns `decision` (a fresh Response each time) for commands. */
function respond(status: number, body: unknown) {
  fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
    const url = String(input);
    const payload = url.endsWith('/api/v1/session') ? session : body;
    return new Response(JSON.stringify(payload), { status: url.endsWith('/api/v1/session') ? 200 : status,
      headers: { 'Content-Type': 'application/json' } });
  });
}
const commandCalls = () => fetchMock.mock.calls.filter((c) => !String(c[0]).endsWith('/api/v1/session')) as [string, RequestInit][];
beforeEach(() => {
  fetchMock = vi.spyOn(globalThis, 'fetch');
});
afterEach(() => vi.restoreAllMocks());

describe('GateDecision', () => {
  it('preselects nothing, not even the recommendation, and needs a rationale', async () => {
    render(wrap(<GateDecision runId="RUN-X" action={gateA} endpoint="/decisions/execution" field="strategy"
      hashField="expected_assessment_hash" />));
    const radios = screen.getAllByRole('radio');
    radios.forEach((r) => expect(r).not.toBeChecked());
    expect(screen.getByText('MARS advice')).toBeInTheDocument();
    const review = screen.getByRole('button', { name: 'Review decision' });
    expect(review).toBeDisabled();
    await userEvent.click(radios[1]);
    expect(review).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Rationale/), 'Security first this quarter');
    expect(review).toBeEnabled();
  });

  it('claims a decision only after the server returns it, bound to the reviewed hash', async () => {
    respond(200, recorded('SECURITY_ONLY'));
    render(wrap(<GateDecision runId="RUN-X" action={gateA} endpoint="/decisions/execution" field="strategy"
      hashField="expected_assessment_hash" />));
    await userEvent.click(screen.getAllByRole('radio')[1]);
    await userEvent.type(screen.getByLabelText(/Rationale/), 'Security first');
    await userEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    expect(screen.queryByText('Decision recorded')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(screen.getByText('Decision recorded')).toBeInTheDocument());
    expect(screen.getByText('DEC-01M3TEST')).toBeInTheDocument();
    expect(screen.getByText(/verified \(HMAC; not a signature\)/)).toBeInTheDocument();
    const [url, init] = commandCalls()[0];
    expect(url).toBe('/api/v1/runs/RUN-X/decisions/execution');
    const body = JSON.parse(String(init.body));
    expect(body).toEqual({ strategy: 'SECURITY_ONLY', rationale: 'Security first', expected_assessment_hash: 'a'.repeat(64) });
    expect(body).not.toHaveProperty('actor');
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy();
  });

  it('explains a stale assessment instead of pretending the decision was taken', async () => {
    respond(409, { code: 'STALE_ASSESSMENT', message: 'The assessment changed' });
    render(wrap(<GateDecision runId="RUN-X" action={gateA} endpoint="/decisions/execution" field="strategy"
      hashField="expected_assessment_hash" />));
    await userEvent.click(screen.getAllByRole('radio')[0]);
    await userEvent.type(screen.getByLabelText(/Rationale/), 'Migrate');
    await userEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('The assessment changed since you opened it'));
    expect(screen.queryByText('Decision recorded')).not.toBeInTheDocument();
  });

  it('disables deciding for roles that cannot decide, and says why', () => {
    render(wrap(<GateDecision runId="RUN-X" action={{ ...gateA, can_decide: false, cannot_decide_reason: 'Your role cannot record decisions' }}
      endpoint="/decisions/execution" field="strategy" hashField="expected_assessment_hash" />));
    screen.getAllByRole('radio').forEach((r) => expect(r).toBeDisabled());
    expect(screen.getByText('Your role cannot record decisions')).toBeInTheDocument();
  });
});

describe('receipts', () => {
  /** A gate that disappears as soon as its decision is recorded, like the real Human Action Center. */
  function ClosingGate() {
    const [open, setOpen] = useState(true);
    const recent = useRecentDecisions();
    return (
      <>
        {open && <GateDecision runId="RUN-X" action={gateA} endpoint="/decisions/execution" field="strategy"
          hashField="expected_assessment_hash" />}
        <button type="button" onClick={() => setOpen(false)}>close gate</button>
        {recent?.items.map((r) => <DecisionReceipt key={r.decision.decision_id} decision={r.decision} />)}
      </>
    );
  }

  it('keeps the receipt after the decided gate goes away', async () => {
    respond(200, recorded('MIGRATE_FIRST'));
    render(wrap(<RecentDecisionsProvider><ClosingGate /></RecentDecisionsProvider>));
    await userEvent.click(screen.getAllByRole('radio')[0]);
    await userEvent.type(screen.getByLabelText(/Rationale/), 'Migrate first');
    await userEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(screen.getAllByText('DEC-01M3TEST').length).toBeGreaterThan(0));
    await userEvent.click(screen.getByRole('button', { name: 'close gate' }));
    expect(screen.getByText('DEC-01M3TEST')).toBeInTheDocument();
  });
});

describe('ProposalDecision', () => {
  const proposal = { proposal_id: 'PROP-1', proposal_hash: 'f'.repeat(64), strategy_only: false };

  it('sends the proposal hash the reviewer saw and supersedes only explicitly', async () => {
    respond(200, { ...recorded('REJECTED'), advancing: false });
    render(wrap(<ProposalDecision runId="RUN-X" proposal={proposal} canDecide existingDecisionId="DEC-OLD" />));
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Supersede/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await userEvent.type(screen.getByLabelText(/Rationale/), 'Breaks the report export');
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }));
    await waitFor(() => expect(screen.getByText('Decision recorded')).toBeInTheDocument());
    const body = JSON.parse(String(commandCalls()[0][1].body));
    expect(body).toMatchObject({ verdict: 'REJECTED', expected_proposal_hash: 'f'.repeat(64), supersedes_decision_id: 'DEC-OLD' });
  });

  it('explains that approving a strategy-only proposal applies nothing', async () => {
    render(wrap(<ProposalDecision runId="RUN-X" proposal={{ ...proposal, strategy_only: true }} canDecide />));
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(screen.getByText(/strategy only: approving authorizes producing a concrete fix/)).toBeInTheDocument();
  });
});
