// Continues a real run waiting at Gate B through the UI: approve the INV-101 fix, continue, follow to the verdict.
// Usage: node e2e/continue-demo.mjs <baseUrl> <runId> <outDir>
import { chromium } from '@playwright/test';

const [base, runId, out] = process.argv.slice(2);
const log = (...a) => process.stdout.write(`[${new Date().toISOString()}] ${a.join(' ')}\n`);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
let n = 20;
const snap = async (name, full = true) => page.screenshot({ path: `${out}/${n++}-${name}.png`, fullPage: full });
const snapshot = async () => (await page.request.get(`${base}/api/v1/runs/${runId}`)).json();

await page.goto(`${base}/login`);
await page.getByLabel('Development user').selectOption('admin');
await page.getByLabel('Password').fill('mars-dev');
await page.getByRole('button', { name: 'Sign in' }).click();
await page.waitForURL('**/runs');

await page.goto(`${base}/runs/${runId}/overview`);
await page.waitForTimeout(1500);
await snap('overview-gate-b');
await page.goto(`${base}/runs/${runId}/actions`);
await page.waitForTimeout(1500);
await snap('gate-b');

// review the INV-101 fix itself before approving: its exact diff, bound hash, gateway record
const changes = await (await page.request.get(`${base}/api/v1/runs/${runId}/proposals`)).json();
const fix = changes.proposals.find((p) => p.finding_labels.includes('INV-101') && !p.strategy_only);
await page.goto(`${base}/runs/${runId}/changes/${fix.proposal_id}`);
await page.waitForTimeout(2000);
await snap('proposal-before-approval');
await page.getByRole('button', { name: 'Approve' }).click();
await page.getByLabel(/Rationale/).fill('Parameterized query reviewed against the diff; scope limited to findByName.');
await snap('proposal-approve-confirm', false);
await page.getByRole('button', { name: 'Record decision' }).click();
await page.getByText('Decision recorded').first().waitFor({ timeout: 60_000 });
await snap('proposal-approved');

await page.goto(`${base}/runs/${runId}/actions`);
await page.waitForTimeout(1500);
const leave = page.getByLabel(/Leave the/);
if (await leave.count()) await leave.check();
await snap('gate-b-continue');
await page.getByRole('button', { name: 'Continue', exact: true }).click();
await page.waitForTimeout(2500);
await page.goto(`${base}/runs/${runId}/pipeline`);
await page.waitForTimeout(2500);
await snap('pipeline-after-continue', false);

const deadline = Date.now() + 40 * 60_000;
let last;
let mid = 0;
for (;;) {
  const s = await snapshot();
  if (s.phase !== last) {
    log('phase', s.phase, s.liveness.state, s.current_activity?.title ?? '');
    last = s.phase;
  }
  if (s.liveness.state === 'ADVANCING' && mid < 1 && s.current_activity) {
    mid++;
    await page.goto(`${base}/runs/${runId}/overview`);
    await page.waitForTimeout(1500);
    await snap('overview-while-verifying');
  }
  if (['COMPLETE', 'NEEDS_HUMAN', 'FAILED', 'WAITING_FOR_POST_SECURITY_MIGRATION_DECISION'].includes(s.phase)
    && s.liveness.state !== 'ADVANCING') break;
  if (Date.now() > deadline) throw new Error('timeout');
  await page.waitForTimeout(3000);
}
for (const view of ['overview', 'pipeline', 'verdict', 'validation', 'security', 'activity', 'evidence', 'migration']) {
  await page.goto(`${base}/runs/${runId}/${view}`);
  await page.waitForTimeout(view === 'pipeline' ? 2500 : 1500);
  await snap(`final-${view}`, view !== 'pipeline' && view !== 'activity');
}
await page.goto(`${base}/runs/${runId}/changes/${fix.proposal_id}`);
await page.waitForTimeout(2000);
await snap('final-proposal-applied');
log(JSON.stringify({ runId, errors }));
await browser.close();
