// Development aid: signs in and captures every screen of one run, for visual review.
// Usage: node e2e/screens.mjs <baseUrl> <runId> <outDir>
import { chromium } from '@playwright/test';

const [base = 'http://127.0.0.1:18080', runId, out = 'screens'] = process.argv.slice(2);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errors = [];
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
page.on('pageerror', (e) => errors.push(String(e)));

await page.goto(`${base}/login`);
await page.getByLabel('Development user').selectOption('admin');
await page.getByLabel('Password').fill('mars-dev');
await page.getByRole('button', { name: 'Sign in' }).click();
await page.waitForURL('**/runs');
await page.waitForTimeout(800);
await page.screenshot({ path: `${out}/00-runs.png`, fullPage: true });

const id = runId ?? (await page.locator('a[href*="/runs/RUN-"]').first().textContent());
for (const view of ['overview', 'pipeline', 'actions', 'activity', 'security', 'migration', 'changes', 'graph', 'evidence', 'validation', 'verdict', 'logs']) {
  await page.goto(`${base}/runs/${id}/${view}`);
  await page.waitForTimeout(view === 'graph' || view === 'pipeline' ? 2500 : 1200);
  await page.screenshot({ path: `${out}/${view}.png`, fullPage: view !== 'pipeline' && view !== 'graph' });
}
const finding = page.locator('a[href*="/security/FINDING-"]').first();
await page.goto(`${base}/runs/${id}/security`);
await page.waitForTimeout(800);
if (await finding.count()) {
  await finding.click();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `${out}/finding.png`, fullPage: true });
}
await page.goto(`${base}/runs/${id}/changes`);
await page.waitForTimeout(800);
const proposal = page.locator('a[href*="/changes/PROP-"]').first();
if (await proposal.count()) {
  await proposal.click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${out}/proposal.png`, fullPage: true });
}
console.log(JSON.stringify({ runId: id, errors }, null, 2));
await browser.close();
