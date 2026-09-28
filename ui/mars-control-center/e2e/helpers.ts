import { expect, type APIRequestContext, type Page } from '@playwright/test';

export async function signIn(page: Page, user: 'viewer' | 'operator' | 'approver' | 'admin') {
  await page.goto('/login');
  await expect(page.getByRole('note')).toContainText('not authentication');
  await page.getByLabel('Development user').selectOption(user);
  await page.getByLabel('Password').fill('mars-dev');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL('**/runs');
}

export interface Snapshot {
  phase: string;
  liveness: { state: string };
  verdict?: string;
}

/** Polls the authoritative snapshot until the run is in one of `phases` and nothing advances it. */
export async function waitForPhase(request: APIRequestContext, runId: string, phases: string[], minutes = 12): Promise<Snapshot> {
  const deadline = Date.now() + minutes * 60_000;
  let last: Snapshot | undefined;
  while (Date.now() < deadline) {
    const response = await request.get(`/api/v1/runs/${runId}`);
    if (response.ok()) {
      last = (await response.json()) as Snapshot;
      if (phases.includes(last.phase) && last.liveness.state !== 'ADVANCING') return last;
    }
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error(`run ${runId} did not reach ${phases.join('|')}; last ${last?.phase} ${last?.liveness.state}`);
}

/** Starts a run through the UI (skip-build keeps the analysis fast; builds still run where MARS needs them). */
export async function startRun(page: Page): Promise<string> {
  await page.goto('/runs');
  await page.getByRole('button', { name: 'New run' }).click();
  await page.getByRole('combobox').selectOption('composite/inventory-service');
  await page.getByLabel(/Skip the baseline build/).check();
  await page.getByRole('button', { name: 'Start analysis' }).click();
  await page.waitForURL('**/pipeline');
  return page.url().split('/runs/')[1].split('/')[0];
}
