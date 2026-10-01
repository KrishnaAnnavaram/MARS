/**
 * Parity: Mission Control's verdict replay (server/projection/scoring.ts) must agree with the MARS
 * arbiter's own compute-score.js on the same evidence. compute-score runs for real inside a fixture
 * copy (it writes its score records under the fixture's .claude/.pipeline-context), so a drift in
 * either implementation fails here instead of silently misreporting "replay disagrees".
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Model } from '../../server/model';
import { REPO, makeFixture, type Fixture } from '../helpers/fixture';

let f: Fixture;
beforeAll(() => {
  f = makeFixture();
  for (const s of ['07a-merge-arbiter', '00-issue-register']) {
    fs.cpSync(path.join(REPO, '.claude/skills', s), path.join(f.root, '.claude/skills', s), { recursive: true, filter: (src) => !/node_modules|[\\/]work[\\/]/.test(src) });
  }
});
afterAll(() => f.cleanup());

function computeScore(id: string): { score: number; threshold: number; computedDecision: string; gates: { gate: string }[] } {
  const r = spawnSync(process.execPath, [path.join(f.root, '.claude/skills/07a-merge-arbiter/scripts/compute-score.js'), '--issue', id], {
    cwd: f.root, encoding: 'utf8', env: { ...process.env, MARS_LEDGER_DIR: f.ledgerDir, MARS_PIPELINE_CONTEXT: '' },
  });
  expect(r.status, r.stderr).toBe(0);
  const dir = path.join(f.root, '.claude/.pipeline-context');
  const file = [path.join(dir, 'arbiter', `${id}.score.json`), path.join(dir, `${id}.score.json`)].find((p) => fs.existsSync(p))
    || walk(dir).find((p) => p.endsWith(`${id}.score.json`));
  expect(file, 'score record written').toBeTruthy();
  return JSON.parse(fs.readFileSync(file!, 'utf8'));
}
function walk(d: string): string[] {
  if (!fs.existsSync(d)) return [];
  return fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]));
}

describe('verdict replay parity with compute-score.js', () => {
  it.each(['ISSUE-001', 'ISSUE-002', 'ISSUE-003', 'ISSUE-004'])('%s: same decision, score, threshold and hard gates', (id) => {
    const real = computeScore(id);
    const m = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false });
    const replay = m.issue(id)!.verdictDetail!.replay!;
    expect(replay.decision).toBe(real.computedDecision);
    expect(replay.score).toBe(real.score);
    expect(replay.threshold).toBe(real.threshold);
    expect([...replay.gates].sort()).toEqual(real.gates.map((g) => g.gate).sort());
  });
});
