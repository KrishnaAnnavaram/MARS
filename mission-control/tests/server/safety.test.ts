/**
 * MARS safety boundaries Mission Control must not weaken.
 *  - Reading every API view never writes to the workspace (evidence is authority; MC is a projection).
 *  - The only write path is the human decision command, and it is off by default.
 *  - Instrumentation in MARS scripts is additive: guarded require, guarded call, no behavioural change.
 *  - The MARS pipeline lint still passes.
 */
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import type { AddressInfo } from 'node:net';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { createApp } from '../../server/app';
import { Model } from '../../server/model';
import { REPO, makeFixture } from '../helpers/fixture';

function snapshot(root: string, skip: RegExp): Map<string, string> {
  const out = new Map<string, string>();
  const walk = (dir: string) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, e.name);
      const rel = path.relative(root, abs).replace(/\\/g, '/');
      if (skip.test(rel)) continue;
      if (e.isDirectory()) walk(abs);
      else out.set(rel, crypto.createHash('sha256').update(fs.readFileSync(abs)).digest('hex'));
    }
  };
  walk(root);
  return out;
}

const INSTRUMENTED = [
  '.claude/skills/04b-fixer/scripts/verify-patch.js',
  '.claude/skills/04c-dependency-upgrader/scripts/apply-version-bump.js',
  '.claude/skills/06a-qa-runner/scripts/run-qa-gate.js',
  '.claude/skills/06b-build-gatekeeper/scripts/run-build-gate.js',
  '.claude/skills/07a-merge-arbiter/scripts/compute-score.js',
];

describe('read-only projection', () => {
  it('browsing every view (including health checks) leaves the workspace byte-identical', async () => {
    const f = makeFixture();
    try {
      const before = snapshot(f.root, /^\.mars(\/|$)/);
      const model = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false });
      model.start();
      const server = createApp(model, { staticDir: null });
      await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
      const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/api/v1/`;
      const paths = ['overview', 'issues', 'runs', 'registry', 'approvals', 'artifacts', 'integrity', 'audit', 'architecture', 'health?refresh=1', 'session'];
      for (const id of ['ISSUE-001', 'ISSUE-002', 'ISSUE-003', 'ISSUE-004']) paths.push(`issues/${id}`, `issues/${id}/lineage`, `approvals/${id}`, `architecture?issue=${id}`, `audit?issue=${id}`);
      const arts = (await (await fetch(`${base}artifacts`)).json()) as { artifacts: { path: string }[] };
      for (const a of arts.artifacts) paths.push(`artifacts/content?path=${encodeURIComponent(a.path)}`);
      for (const p of paths) expect((await fetch(base + p)).status, p).toBe(200);
      model.stop();
      server.closeAllConnections?.();
      await new Promise((r) => server.close(() => r(null)));
      const after = snapshot(f.root, /^\.mars(\/|$)/);
      expect([...after.keys()].sort()).toEqual([...before.keys()].sort());
      for (const [k, v] of before) expect(after.get(k), k).toBe(v);
    } finally {
      f.cleanup();
    }
  }, 120000);

  it('the server source contains no write path into the workspace other than the decision command', () => {
    const dir = path.join(REPO, 'mission-control/server');
    const files: string[] = [];
    const walk = (d: string) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : /\.ts$/.test(e.name) && files.push(path.join(d, e.name))));
    walk(dir);
    const writers = files.filter((f) => /\b(writeFileSync|appendFileSync|writeFile|appendFile|rmSync|unlinkSync|renameSync|mkdirSync|createWriteStream)\b/.test(fs.readFileSync(f, 'utf8')));
    expect(writers.map((f) => path.relative(dir, f))).toEqual([]);
    const app = fs.readFileSync(path.join(dir, 'app.ts'), 'utf8');
    expect((app.match(/execFile\(/g) || []).length).toBe(1);
    expect(app).toMatch(/record-decision\.js/);
  });
});

describe('additive instrumentation', () => {
  it.each(INSTRUMENTED)('%s: guarded optional require, guarded call, syntax OK', (rel) => {
    const text = fs.readFileSync(path.join(REPO, rel), 'utf8');
    expect(text).toMatch(/const marsTelemetry = \(\(\) => \{ try \{ return require\('\.\.\/\.\.\/\.\.\/scripts\/telemetry\/gate-events'\); \} catch \(_\) \{ return null; \} \}\)\(\);/);
    const calls = text.split('\n').filter((l) => l.includes('marsTelemetry.'));
    expect(calls.length).toBeGreaterThan(0);
    for (const c of calls) expect(c).toMatch(/if \(marsTelemetry\)/);
    execFileSync(process.execPath, ['--check', path.join(REPO, rel)]);
  });

  it('gate-event emitters never throw, even with malformed records', () => {
    const code = `const g=require(${JSON.stringify(path.join(REPO, '.claude/scripts/telemetry/gate-events.js'))});g.gateCompleted('qa',null,null);g.gateCompleted('build',{},{});g.fixVerified('x',undefined,undefined);g.verdictComputed({});console.log('ok')`;
    const tmp = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'mc-ge-'));
    try {
      const out = execFileSync(process.execPath, ['-e', code], { encoding: 'utf8', env: { ...process.env, MARS_LEDGER_DIR: tmp } });
      expect(out.trim()).toBe('ok');
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  });

  it('the MARS pipeline contract lint still passes', () => {
    const out = execFileSync(process.execPath, [path.join(REPO, '.claude/scripts/pipeline-lint.js')], { cwd: REPO, encoding: 'utf8' });
    expect(out).toMatch(/pass/i);
  });
});
