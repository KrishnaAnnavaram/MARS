import { defineConfig } from '@playwright/test';
import path from 'node:path';

/**
 * End-to-end tests against a real Control Center: the packaged API (real MARS engine, real
 * adapters) serving the built UI, over a throwaway runs root. Build both first:
 *   mvn -pl apps/control-center-api -am install -DskipTests   (from the repository root)
 *   npm run build
 */
const root = path.resolve(import.meta.dirname, '..', '..');
const runs = path.resolve(import.meta.dirname, 'e2e', '.runs');
const port = Number(process.env.MARS_E2E_PORT ?? 18090);

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  timeout: 15 * 60_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './e2e/global-setup.ts',
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    viewport: { width: 1600, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: [
      'java', '-jar', JSON.stringify(path.join(root, 'apps/control-center-api/target/control-center-api-1.0.0-exec.jar')),
      `--server.port=${port}`,
      `--mars.control-center.harness-root=${JSON.stringify(root)}`,
      `--mars.control-center.runs-root=${JSON.stringify(runs)}`,
      `--mars.control-center.ui-dir=${JSON.stringify(path.resolve(import.meta.dirname, 'dist'))}`,
    ].join(' '),
    url: `http://127.0.0.1:${port}/actuator/health`,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
