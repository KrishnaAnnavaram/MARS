import { expect, test } from '@playwright/test';
import { signIn, startRun, waitForPhase } from './helpers';

/**
 * One real MARS run, driven entirely from the browser: Gate A, a Gate B approval through the
 * exact proposal, continuation through the Mutation Gateway, Gate A2 and the verdict. Nothing
 * here is mocked; the assertions compare the UI with the API's authoritative snapshot.
 */
test.describe.serial('a MARS run through the Control Center', () => {
  let runId = '';

  test('an operator starts a run and watches it stop at Gate A', async ({ page }) => {
    await signIn(page, 'operator');
    runId = await startRun(page);
    expect(runId).toMatch(/^RUN-/);
    await expect(page.getByLabel('Run pipeline')).toBeVisible();
    await waitForPhase(page.request, runId, ['WAITING_FOR_EXECUTION_DECISION']);
    await page.reload();
    await expect(page.getByRole('alert').filter({ hasText: 'ACTION REQUIRED' })).toBeVisible();
    await page.goto(`/runs/${runId}/actions`);
    const gate = page.getByRole('region', { name: /Human Gate A/ }).or(page.locator('section', { hasText: 'Human Gate A' })).first();
    await expect(gate).toBeVisible();
    // an operator sees the gate but cannot decide it
    await expect(page.getByRole('radio').first()).toBeDisabled();
  });

  test('an approver decides Gate A: nothing preselected, receipt only after the server records it', async ({ page }) => {
    await signIn(page, 'approver');
    await page.goto(`/runs/${runId}/actions`);
    const radios = page.getByRole('radio');
    await expect(radios).toHaveCount(6);
    for (const radio of await radios.all()) await expect(radio).not.toBeChecked();
    await expect(page.getByText('MARS advice (not a decision)')).toBeVisible();
    await page.locator('input[type=radio][value=SECURITY_ONLY]').check();
    await page.getByLabel(/Rationale/).fill('Remediate on the current platform this quarter');
    await page.getByRole('button', { name: 'Review decision' }).click();
    await expect(page.getByRole('dialog', { name: 'Confirm decision' })).toContainText('DEVELOPMENT_ASSERTED');
    await page.getByRole('button', { name: 'Record decision' }).click();
    const receipt = page.getByRole('status').filter({ hasText: 'Decision recorded' }).first();
    await expect(receipt).toBeVisible();
    await expect(receipt).toContainText(/DEC-[0-9A-Z]{26}/);
    await expect(receipt).toContainText('approver (APPROVER)');
    const decisions = await (await page.request.get(`/api/v1/runs/${runId}/decisions`)).json();
    expect(decisions[0]).toMatchObject({ type: 'EXECUTION_STRATEGY', selected: 'SECURITY_ONLY', actor: 'approver',
      actor_authentication: 'DEVELOPMENT_ASSERTED', integrity: 'VERIFIED' });
  });

  test('Gate B: the approver reviews the exact diff and approves the fix; the gateway applies it on continue', async ({ page }) => {
    await signIn(page, 'approver');
    await waitForPhase(page.request, runId, ['WAITING_FOR_REMEDIATION_APPROVAL']);
    const changes = await (await page.request.get(`/api/v1/runs/${runId}/proposals`)).json();
    const fix = changes.proposals.find((p: { finding_labels: string[]; strategy_only: boolean }) =>
      p.finding_labels.includes('INV-101') && !p.strategy_only);
    await page.goto(`/runs/${runId}/changes/${fix.proposal_id}`);
    await expect(page.getByText('hash matches its registration')).toBeVisible();
    await expect(page.locator('.diff')).toContainText('queryForList(sql, name)');
    await page.getByRole('tab', { name: 'Unified' }).click();
    await expect(page.locator('.diff-unified')).toBeVisible();
    // not applied just because it is reviewed
    await expect(page.getByText('not recorded').first()).toBeVisible();
    await page.getByRole('button', { name: 'Approve' }).click();
    await page.getByLabel(/Rationale/).fill('Bind parameter; scope limited to findByName');
    await page.getByRole('button', { name: 'Record decision' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Decision recorded' }).first()).toBeVisible();

    await page.goto(`/runs/${runId}/actions`);
    await page.getByLabel(/Leave the \d+ undecided/).check();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await waitForPhase(page.request, runId, ['WAITING_FOR_POST_SECURITY_MIGRATION_DECISION', 'COMPLETE', 'NEEDS_HUMAN']);
    const applied = await (await page.request.get(`/api/v1/runs/${runId}/proposals/${fix.proposal_id}`)).json();
    expect(['APPLIED', 'VALIDATED', 'FAILED_VALIDATION']).toContain(applied.status);
    await page.goto(`/runs/${runId}/changes/${fix.proposal_id}`);
    await expect(page.getByText('Write through Bootshift FileMutationGateway')).toBeVisible();
    await expect(page.locator('li', { hasText: 'Base hash (not stale)' })).toContainText('verified');
  });

  test('Gate A2 and the verdict come from the engine', async ({ page }) => {
    await signIn(page, 'approver');
    let snapshot = await waitForPhase(page.request, runId, ['WAITING_FOR_POST_SECURITY_MIGRATION_DECISION', 'COMPLETE', 'NEEDS_HUMAN']);
    if (snapshot.phase === 'WAITING_FOR_POST_SECURITY_MIGRATION_DECISION') {
      await page.goto(`/runs/${runId}/actions`);
      await page.locator('input[type=radio][value=SKIP]').check();
      await page.getByLabel(/Rationale/).fill('Not this quarter');
      await page.getByRole('button', { name: 'Review decision' }).click();
      await page.getByRole('button', { name: 'Record decision' }).click();
      await expect(page.getByRole('status').filter({ hasText: 'Decision recorded' }).first()).toBeVisible();
      snapshot = await waitForPhase(page.request, runId, ['COMPLETE', 'NEEDS_HUMAN']);
    }
    const verdict = await (await page.request.get(`/api/v1/runs/${runId}/verdict`)).json();
    await page.goto(`/runs/${runId}/verdict`);
    await expect(page.getByRole('region', { name: 'Verdict' })).toBeVisible();
    await expect(page.getByText('The Control Center shows it as recorded; it computes nothing.')).toBeVisible();
    await expect(page.getByRole('table', { name: 'Verdict items' }).getByRole('row')).toHaveCount(verdict.items.length + 1);
    // the undecided proposals were never applied and the verdict says so
    await expect(page.getByRole('table', { name: 'Verdict items' })).toContainText('PENDING_APPROVAL');
    if (snapshot.phase === 'NEEDS_HUMAN') {
      await page.goto(`/runs/${runId}/actions`);
      await expect(page.getByText('MARS needs a human: the verdict is NEEDS_HUMAN')).toBeVisible();
      await expect(page.getByText('Not resumable')).toBeVisible();
      await expect(page.getByRole('button', { name: /Continue anyway/i })).toHaveCount(0);
    }
  });

  test('the activity feed and the pipeline reflect the persisted history', async ({ page }) => {
    await signIn(page, 'viewer');
    const events = await (await page.request.get(`/api/v1/runs/${runId}/events/history?after=0&limit=5000`)).json();
    await page.goto(`/runs/${runId}/activity`);
    await expect(page.getByText(`${events.length} of ${events.length} event(s)`)).toBeVisible();
    await page.getByRole('tab', { name: 'MUTATION' }).click();
    await expect(page.getByRole('log')).toContainText('applied');
    await page.goto(`/runs/${runId}/pipeline`);
    await expect(page.getByLabel('Run pipeline')).toContainText('Human Gate A');
    // a viewer can read everything but cannot act
    await page.goto(`/runs/${runId}/actions`);
    await expect(page.getByRole('button', { name: 'Record decision' })).toHaveCount(0);
  });

  test('reports read as documents: tables are tables and the executed flow is drawn', async ({ page }) => {
    await signIn(page, 'viewer');
    await page.goto(`/runs/${runId}/verdict`);
    await page.getByText('reports/final-report.md').first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { level: 1 })).toContainText('Final Evidence Report');
    await expect(dialog.getByRole('table').first().getByRole('columnheader').first()).toBeVisible();
    await expect(dialog.getByText('|---|')).toHaveCount(0);
    const diagram = dialog.getByRole('figure', { name: 'Diagram' });
    await expect(diagram.locator('svg')).toBeVisible({ timeout: 20_000 });
    await expect(diagram.locator('svg')).toContainText('CREATED');
    // the source stays one click away, verbatim
    await dialog.getByRole('tab', { name: 'Markdown source' }).click();
    await expect(dialog.locator('pre')).toContainText('```mermaid');
    // no absolute server path reaches the browser
    await expect(dialog.locator('pre')).not.toContainText(/runs[\\/]RUN-/);
  });
});
