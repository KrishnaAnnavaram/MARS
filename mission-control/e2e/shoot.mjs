// Ad-hoc screenshot helper: node e2e/shoot.mjs <outDir> <baseUrl> [route ...]
import { chromium } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
const [outDir, base = 'http://127.0.0.1:7440', ...routes] = process.argv.slice(2);
const VIEWPORTS = { desktop: [1440, 900], large: [1920, 1080], laptop: [1280, 720], narrow: [390, 844] };
const vpNames = (process.env.VP || 'desktop').split(',');
fs.mkdirSync(outDir, { recursive: true });
let browser;
for (const channel of ['msedge', 'chrome', undefined]) {
  try { browser = await chromium.launch({ channel }); break; } catch { /* try next */ }
}
const errors = [];
for (const vp of vpNames) {
  const ctx = await browser.newContext({ viewport: { width: VIEWPORTS[vp][0], height: VIEWPORTS[vp][1] }, colorScheme: process.env.SCHEME || 'light' });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`[${vp}] ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`[${vp}] PAGEERROR ${e.message}`));
  for (const r of routes.length ? routes : ['/']) {
    await page.goto(base + r, { waitUntil: 'networkidle' }).catch((e) => errors.push(`${r}: ${e.message}`));
    await page.waitForTimeout(900);
    const name = `${vp}_${r.replace(/[^a-z0-9]+/gi, '_').replace(/^_|_$/g, '') || 'home'}.png`;
    await page.screenshot({ path: path.join(outDir, name), fullPage: process.env.FULL === '1' });
  }
  await ctx.close();
}
await browser.close();
console.log(errors.length ? errors.join('\n') : 'no console errors');
