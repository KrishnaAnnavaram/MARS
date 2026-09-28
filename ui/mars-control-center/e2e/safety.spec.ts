import { expect, test } from '@playwright/test';
import { signIn, startRun, waitForPhase } from './helpers';

test('two approvers on one proposal: the second is told, never silently overwrites', async ({ browser }) => {
  const first = await browser.newPage();
  await signIn(first, 'admin');
  const runId = await startRun(first);
  await waitForPhase(first.request, runId, ['WAITING_FOR_EXECUTION_DECISION']);
  const combined = await (await first.request.get(`/api/v1/runs/${runId}/human-actions`)).json();
  const hash = combined.actions[0].bound_hash;
  const csrf = (await first.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
  const gateA = await first.request.post(`/api/v1/runs/${runId}/decisions/execution`, {
    headers: { 'X-XSRF-TOKEN': csrf },
    data: { strategy: 'SECURITY_ONLY', rationale: 'Security only', expected_assessment_hash: hash },
  });
  expect(gateA.ok()).toBeTruthy();
  await waitForPhase(first.request, runId, ['WAITING_FOR_REMEDIATION_APPROVAL']);

  const second = await browser.newPage();
  await signIn(second, 'approver');
  const changes = await (await first.request.get(`/api/v1/runs/${runId}/proposals`)).json();
  const target = changes.proposals.find((p: { awaiting_decision: boolean; strategy_only: boolean }) => p.awaiting_decision && !p.strategy_only);
  // both reviewers open the same proposal
  await first.goto(`/runs/${runId}/changes/${target.proposal_id}`);
  await second.goto(`/runs/${runId}/changes/${target.proposal_id}`);
  await expect(second.getByRole('button', { name: 'Approve' })).toBeVisible();
  await first.getByRole('button', { name: 'Reject' }).click();
  await first.getByLabel(/Rationale/).fill('Out of scope for this change window');
  await first.getByRole('button', { name: 'Record decision' }).click();
  await expect(first.getByRole('status').filter({ hasText: 'Decision recorded' }).first()).toBeVisible();

  // the second reviewer's page learns about it live, from the event stream
  await expect(second.getByText(/Decided in DEC-/)).toBeVisible({ timeout: 30_000 });
  await expect(second.getByRole('button', { name: 'Approve' })).toHaveCount(0);
  // and the server refuses a decision that does not explicitly supersede the recorded one
  const secondCsrf = (await second.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
  const late = await second.request.post(`/api/v1/runs/${runId}/proposals/${target.proposal_id}/decision`, {
    headers: { 'X-XSRF-TOKEN': secondCsrf },
    data: { verdict: 'APPROVED', rationale: 'Looks right to me', expected_proposal_hash: target.proposal_hash },
  });
  expect(late.status()).toBe(409);
  expect((await late.json()).code).toBe('DECISION_ALREADY_RECORDED');
  const decisions = await (await first.request.get(`/api/v1/runs/${runId}/decisions`)).json();
  const onProposal = decisions.filter((d: { proposal_id?: string }) => d.proposal_id === target.proposal_id);
  expect(onProposal).toHaveLength(1);
  expect(onProposal[0].selected).toBe('REJECTED');
});

test('a disconnected event stream is shown as such and recovers without implying the run stopped', async ({ page }) => {
  await signIn(page, 'viewer');
  const runs = await (await page.request.get('/api/v1/runs')).json();
  test.skip(runs.runs.length === 0, 'needs a run from the other specs');
  const runId = runs.runs[0].run_id;
  // the stream cannot connect: the page says so, and still shows the run from its snapshot
  await page.route('**/api/v1/runs/*/events', (route) => route.abort('connectionrefused'));
  await page.goto(`/runs/${runId}/overview`);
  await expect(page.getByText(/RECONNECTING|OFFLINE/)).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('Current state')).toBeVisible();
  // the stream comes back and resumes after the last delivered event
  await page.unroute('**/api/v1/runs/*/events');
  await expect(page.getByText('LIVE', { exact: true })).toBeVisible({ timeout: 60_000 });
});

test('roles are enforced by the server, not only hidden by the UI', async ({ page }) => {
  await signIn(page, 'viewer');
  await expect(page.getByRole('button', { name: 'New run' })).toHaveCount(0);
  const csrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
  const response = await page.request.post('/api/v1/runs', { headers: { 'X-XSRF-TOKEN': csrf },
    data: { repository: 'composite/inventory-service' } });
  expect(response.status()).toBe(403);
  const forged = await page.request.post('/api/v1/runs', { data: { repository: 'composite/inventory-service' } });
  expect(forged.status()).toBe(403);
});
