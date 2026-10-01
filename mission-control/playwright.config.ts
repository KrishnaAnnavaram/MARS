import { defineConfig } from '@playwright/test';

/**
 * E2E against the built app served by the Mission Control server over the REAL workspace (read-only).
 * Uses an installed Edge or Chrome (no browser download): set PW_CHANNEL=chrome to switch.
 * Build first: npm run build
 */
const port = Number(process.env.MC_E2E_PORT || 7461);

export default defineConfig({
  testDir: 'e2e',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    channel: process.env.PW_CHANNEL || 'msedge',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'large', use: { viewport: { width: 1920, height: 1080 } } },
    { name: 'desktop', use: { viewport: { width: 1440, height: 900 } } },
    { name: 'laptop', use: { viewport: { width: 1280, height: 720 } } },
    { name: 'narrow', use: { viewport: { width: 390, height: 844 } } },
  ],
  webServer: {
    command: `node dist/server/server/index.js --workspace .. --port ${port}`,
    url: `http://127.0.0.1:${port}/api/v1/healthz`,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
