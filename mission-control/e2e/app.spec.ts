/**
 * Rendered end-to-end checks over the real workspace at four viewports. Screenshots go to
 * e2e/screens/<project>/ (git-ignored) for visual review.
 */
import { expect, test, type Page } from '@playwright/test';

const shot = async (page: Page, name: string) => {
  await page.screenshot({ path: `e2e/screens/${test.info().project.name}/${name}.png`, fullPage: true });
};

const errors: string[] = [];
test.beforeEach(async ({ page }) => {
  errors.length = 0;
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
});
test.afterEach(() => {
  expect(errors, 'no console or page errors').toEqual([]);
});

test('Home answers "what is MARS doing right now" without fabricating activity', async ({ page }) => {
  await page.goto('/');
  // No MARS pipeline agent runs during the E2E; other Claude sessions may, and must not be counted as MARS.
  await expect(page.getByText('No MARS agent is running')).toBeVisible();
  await expect(page.getByText(/MARS is running/)).toHaveCount(0);
  await expect(page.getByText(/last activity .* \((observed|reconstructed from evidence)\)/).first()).toBeVisible();
  await expect(page.getByText(/Needs a human/i)).toBeVisible();
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible({ timeout: 10_000 });
  await shot(page, 'home');
});

test('Blocked issue end to end: board → issue → verification → verdict computation → evidence', async ({ page }) => {
  await page.goto('/issues');
  await expect(page.getByRole('link', { name: 'ISSUE-003' }).first()).toBeVisible();
  await shot(page, 'issues');
  await page.getByRole('link', { name: 'ISSUE-003' }).first().click();
  await expect(page.getByText('Verdict: Blocked')).toBeVisible();
  await page.getByRole('tab', { name: /Verification/ }).click();
  await expect(page.getByText('Report contradicts itself').first()).toBeVisible();
  await expect(page.getByText(/Not reproducible from current evidence/)).toBeVisible();
  await expect(page.getByText('TRIGGERED')).toBeVisible();
  await shot(page, 'issue-verification');
  await page.getByRole('tab', { name: /Evidence/ }).click();
  await page.getByRole('link', { name: /verdict_ISSUE-003\.md/ }).first().click();
  await expect(page.getByRole('heading', { name: /Ship Verdict — ISSUE-003/ })).toBeVisible();
  await expect(page.getByText(/Findings on this artifact/)).toBeVisible();
  await shot(page, 'artifact');
});

test('Approval Center is read-only by default and explains why', async ({ page }) => {
  await page.goto('/approvals/ISSUE-003');
  await expect(page.getByText('Approved (unattributed)').first()).toBeVisible();
  await expect(page.getByText(/Carried over a re-proposal|carried over a re-proposal/i).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /Record/ })).toHaveCount(0);
  await shot(page, 'approval');
});

test('Runs, audit, architecture and harness render with real data', async ({ page }) => {
  await page.goto('/runs');
  await expect(page.getByText(/reconstructed/).first()).toBeVisible();
  await shot(page, 'runs');
  await page.goto('/audit');
  // Table on md+, card list on narrow screens: whichever is visible must be there.
  await expect(page.locator('[aria-label="Audit trail"]:visible')).toBeVisible();
  await shot(page, 'audit');
  await page.goto('/architecture?issue=ISSUE-003');
  await expect(page.getByText('Defect site').first()).toBeVisible();
  await shot(page, 'architecture');
  await page.goto('/harness?tab=health');
  await expect(page.getByText('Pipeline contract lint', { exact: true })).toBeVisible({ timeout: 30_000 });
  await shot(page, 'health');
});

test('keyboard: command palette opens with Ctrl+K and navigates', async ({ page }, info) => {
  test.skip(info.project.name === 'narrow', 'palette shortcut is a desktop affordance');
  await page.goto('/');
  await page.keyboard.press('Control+k');
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await page.keyboard.type('ISSUE-002');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/issues\/ISSUE-002/);
});

test('missing artifact and unknown route show explanatory states', async ({ page }) => {
  await page.goto('/evidence/view?path=docs/agent_output/05-verify/rescan_ISSUE-999.md');
  await expect(page.getByText('Artifact missing')).toBeVisible();
  await page.goto('/definitely-not-a-page');
  await expect(page.getByText('Page not found')).toBeVisible();
  errors.length = 0; // the 404 fetch for the missing artifact is expected
});

// These run against the BUILT server (playwright webServer serves dist/), so a fix that exists only in
// source fails here.
test('built server: refuses non-loopback Host headers and does not serve source maps', async ({ baseURL }, info) => {
  test.skip(info.project.name !== 'desktop', 'server behaviour, viewport-independent');
  const http = await import('node:http');
  const u = new URL(baseURL!);
  const get = (p: string, host?: string) => new Promise<number>((resolve, reject) => {
    const req = http.request({ host: u.hostname, port: u.port, path: p, headers: host ? { Host: host } : {} }, (res) => { res.resume(); resolve(res.statusCode || 0); });
    req.on('error', reject);
    req.end();
  });
  expect(await get('/api/v1/overview', `evil.example:${u.port}`)).toBe(421);
  expect(await get('/api/v1/overview')).toBe(200);
  expect(await get('/assets/index.js.map')).toBe(404);
  expect(await get('/api/v1/issues/%E0%A4%A')).toBe(400);
});

test('board cells keep their text inside their own column (no collisions)', async ({ page }, info) => {
  test.skip(info.project.name === 'narrow', 'narrow uses the card layout');
  for (const p of ['/', '/issues']) {
    await page.goto(p);
    const table = page.getByRole('table', { name: 'Issue by stage board' });
    await expect(table).toBeVisible();
    const bad = await table.evaluate((t) => {
      const out: string[] = [];
      for (const td of Array.from(t.querySelectorAll('td'))) {
        const btn = td.querySelector('button');
        if (!btn) continue;
        const c = td.getBoundingClientRect();
        for (const el of Array.from(btn.querySelectorAll('span'))) {
          const r = el.getBoundingClientRect();
          if (r.width && (r.right > c.right + 1 || r.left < c.left - 1)) out.push(`${btn.getAttribute('aria-label')}: ${Math.round(r.right - c.right)}px`);
          if (el.scrollWidth > el.clientWidth + 1 && el.textContent && el.children.length === 0) out.push(`${btn.getAttribute('aria-label')}: clipped "${el.textContent}"`);
        }
      }
      return out;
    });
    expect(bad, `${p} at ${info.project.name}`).toEqual([]);
  }
});

test('no horizontal page overflow', async ({ page }) => {
  for (const p of ['/', '/issues', '/issues/ISSUE-003?tab=verification', '/approvals/ISSUE-003', '/runs', '/audit']) {
    await page.goto(p);
    await page.waitForLoadState('networkidle');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, p).toBeLessThanOrEqual(1);
  }
});
