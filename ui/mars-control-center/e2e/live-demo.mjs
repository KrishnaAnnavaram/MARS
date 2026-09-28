// Drives one real MARS workflow through the Control Center UI (real engine, real toolchain adapters)
// and captures the screens along the way. Usage: node e2e/live-demo.mjs <baseUrl> <outDir> [strategy]
import { chromium } from '@playwright/test';

const [base = 'http://127.0.0.1:18080', out = 'demo', strategy = 'MIGRATE_FIRST'] = process.argv.slice(2);
const log = (...a) => process.stdout.write(`[${new Date().toISOString()}] ${a.join(' ')}\n`);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
let shot = 0;
const snap = async (name, full = true) => {
  shot += 1;
  await page.screenshot({ path: `${out}/${String(shot).padStart(2, '0')}-${name}.png`, fullPage: full });
};

async function snapshot(runId) {
  const r = await page.request.get(`${base}/api/v1/runs/${runId}`);
  return r.ok() ? r.json() : undefined;
}

async function waitFor(runId, phases, minutes, onTick) {
  const deadline = Date.now() + minutes * 60_000;
  let last;
  while (Date.now() < deadline) {
    const s = await snapshot(runId);
    if (s && s.phase !== last) {
      log('phase', s.phase, s.liveness.state, s.current_activity?.title ?? '');
      last = s.phase;
    }
    if (s && phases.includes(s.phase) && s.liveness.state !== 'ADVANCING') return s;
    if (onTick) await onTick(s);
    await page.waitForTimeout(3000);
  }
  throw new Error(`timeout waiting for ${phases}`);
}

await page.goto(`${base}/login`);
await page.getByLabel('Development user').selectOption('admin');
await page.getByLabel('Password').fill('mars-dev');
await page.getByRole('button', { name: 'Sign in' }).click();
await page.waitForURL('**/runs');

await page.getByRole('button', { name: 'New run' }).click();
await page.getByRole('combobox').selectOption('composite/inventory-service');
await snap('new-run-dialog', false);
await page.getByRole('button', { name: 'Start analysis' }).click();
await page.waitForURL('**/pipeline');
const runId = page.url().split('/runs/')[1].split('/')[0];
log('run', runId);

let midShots = 0;
await waitFor(runId, ['WAITING_FOR_EXECUTION_DECISION', 'FAILED'], 20, async (s) => {
  if (midShots < 2 && s && s.liveness.state === 'ADVANCING') {
    midShots += 1;
    await page.waitForTimeout(1500);
    await snap(`pipeline-analysis-${midShots}`, false);
  }
});
await page.goto(`${base}/runs/${runId}/overview`);
await page.waitForTimeout(1500);
await snap('overview-gate-a');
await page.goto(`${base}/runs/${runId}/actions`);
await page.waitForTimeout(1500);
await snap('gate-a');

await page.locator(`input[type=radio][value=${strategy}]`).check();
await page.getByLabel(/Rationale/).fill('Boot 3.5 support has ended; the springdoc fix needs Boot 4. Migrate first, then remediate.');
await page.getByRole('button', { name: 'Review decision' }).click();
await snap('gate-a-confirm', false);
await page.getByRole('button', { name: 'Record decision' }).click();
await page.getByText('Decision recorded').waitFor({ timeout: 60_000 });
await snap('gate-a-recorded');

let migShots = 0;
const gateB = await waitFor(runId, ['WAITING_FOR_REMEDIATION_APPROVAL', 'NEEDS_HUMAN', 'COMPLETE', 'FAILED'], 45, async (s) => {
  if (s && s.phase === 'MIGRATION_RUNNING' && migShots < 2) {
    migShots += 1;
    await page.goto(`${base}/runs/${runId}/pipeline`);
    await page.waitForTimeout(2000);
    await snap(`pipeline-migration-${migShots}`, false);
  }
});
await page.goto(`${base}/runs/${runId}/migration`);
await page.waitForTimeout(1500);
await snap('migration');

if (gateB.phase === 'WAITING_FOR_REMEDIATION_APPROVAL') {
  await page.goto(`${base}/runs/${runId}/actions`);
  await page.waitForTimeout(1500);
  await snap('gate-b');
  const row = page.locator('div', { has: page.getByText('INV-101', { exact: true }) }).filter({ has: page.getByRole('button', { name: 'Approve' }) }).last();
  await row.getByRole('button', { name: 'Approve' }).click();
  await page.getByLabel(/Rationale/).fill('Parameterized query reviewed against the diff; scope limited to findByName.');
  await snap('gate-b-confirm', false);
  await page.getByRole('button', { name: 'Record decision' }).click();
  await page.getByText('Decision recorded').first().waitFor({ timeout: 60_000 });
  await snap('gate-b-recorded');
  const leave = page.getByLabel(/Leave the/);
  if (await leave.count()) await leave.check();
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await page.waitForTimeout(2000);
  await waitFor(runId, ['COMPLETE', 'NEEDS_HUMAN', 'FAILED', 'WAITING_FOR_POST_SECURITY_MIGRATION_DECISION'], 45);
}

for (const view of ['overview', 'pipeline', 'verdict', 'validation', 'security', 'changes', 'activity', 'evidence']) {
  await page.goto(`${base}/runs/${runId}/${view}`);
  await page.waitForTimeout(view === 'pipeline' ? 2500 : 1500);
  await snap(`final-${view}`, view !== 'pipeline');
}
const fix = page.locator('a[href*="/changes/PROP-"]').first();
await page.goto(`${base}/runs/${runId}/security`);
await page.waitForTimeout(1000);
const inv101 = page.getByRole('link', { name: 'INV-101' });
if (await inv101.count()) {
  await inv101.click();
  await page.waitForTimeout(1500);
  await snap('final-finding-inv101');
}
void fix;
log(JSON.stringify({ runId, errors }));
await browser.close();
