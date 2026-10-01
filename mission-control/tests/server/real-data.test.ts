/**
 * Projection over the REAL MARS workspace (read-only). These assertions pin facts that are visible in
 * the evidence files themselves, so a regression in a parser shows up as a mismatch with the repo.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { beforeAll, describe, expect, it } from 'vitest';
import { Model } from '../../server/model';
import { REPO } from '../helpers/fixture';

let model: Model;
beforeAll(() => {
  // An empty ledger dir outside the repo: the real workspace is only read, never written.
  model = new Model({ root: REPO, ledgerDir: fs.mkdtempSync(path.join(os.tmpdir(), 'mc-noledger-')), watch: false });
});

describe('real workspace projection', () => {
  it('finds every issue the MARS register script lists (conformance)', () => {
    const out = execFileSync(process.execPath, [path.join(REPO, '.claude/skills/00-issue-register/scripts/list-register.js')], { cwd: REPO, encoding: 'utf8' });
    const ids = out.split(/\r?\n/).map((l) => /^(ISSUE-\d+)\s/.exec(l)?.[1]).filter((x): x is string => Boolean(x)).sort();
    expect(ids.length).toBeGreaterThan(0);
    const ours = model.issues().map((i) => i.id).sort();
    for (const id of ids) expect(ours).toContain(id);
  });

  it('shows every real issue as Blocked, matching the verdict files', () => {
    for (const i of model.issues()) {
      const v = model.state().ev.verdict.get(i.id);
      expect(v, i.id).toBeTruthy();
      expect(i.verdict?.decision).toBe(v!.decision);
      expect(i.cells.verdict.state).toBe('blocked');
    }
  });

  it('ISSUE-003 (scenario D/G/H): fix compile-failed, QA + build failed as compile errors with an unverified cause, Blocked 60/90', () => {
    const d = model.issue('ISSUE-003')!;
    expect(d.cells.fix.state).toBe('failed');
    expect(d.cells.fix.failureClass).toBe('compile.error');
    const qa = d.lanes.find((l) => l.check === 'qa')!;
    const build = d.lanes.find((l) => l.check === 'build')!;
    expect(qa.failureClass).toBe('compile.error');
    expect(build.failureClass).toBe('compile.error');
    expect(qa.causeVerified).toBe(false);
    expect(qa.compileErrors.length).toBeGreaterThan(0);
    expect(d.verdictDetail?.score).toBe(60);
    expect(d.verdictDetail?.threshold).toBe(90);
    expect(d.verdictDetail?.hardGates.find((g) => /build/i.test(g.name))?.triggered).toBe(true);
  });

  it('ISSUE-002 (scenario E): re-scan still vulnerable and red-team bypass are separate security failures', () => {
    const d = model.issue('ISSUE-002')!;
    expect(d.cells.rescan.failureClass).toBe('security.still_vulnerable');
    expect(d.cells.redteam.failureClass).toBe('security.bypass');
    expect(d.blocker?.summary).toMatch(/still present/i);
  });

  it('flags the gate report header contradicting its own exit-code table (R1)', () => {
    const r1 = model.findings().filter((f) => f.rule === 'R1');
    expect(r1.length).toBeGreaterThan(0);
    const qa = model.issue('ISSUE-003')!.lanes.find((l) => l.check === 'qa')!;
    expect(qa.headerStatus).toBe('Passed');
    expect(qa.bodyStatus).toBe('Failed');
  });

  it('replays the verdict: header-based replay disagrees, exit-code replay reproduces the record (R3)', () => {
    const v = model.issue('ISSUE-003')!.verdictDetail!;
    expect(v.replay?.matchesRecorded).toBe(false);
    expect(v.bodyReplay?.matchesRecorded).toBe(true);
    expect(model.findings().some((f) => f.rule === 'R3' && f.issueId === 'ISSUE-003')).toBe(true);
  });

  it('approvals are unattributed and the ISSUE-003 approval was carried over a re-proposal', () => {
    const a = model.issue('ISSUE-003')!.approval;
    expect(a.state).toBe('approved_unattributed');
    expect(a.plan?.reproposed).toBe(true);
    expect(a.implementation?.plannedNotChanged.some((f) => f.endsWith('EmployeeController.java'))).toBe(true);
  });

  it('never claims a running agent when there is no telemetry (no fabricated activity)', () => {
    const n = model.nowState();
    expect(n.activeRuns).toHaveLength(0);
    expect(n.status).toBe('idle');
    expect(n.lastActivitySource).toBe('evidence');
  });

  it('reads the full harness: 7 agents, 18 skills', () => {
    const r = model.registry();
    expect(r.agents).toHaveLength(7);
    expect(r.skills).toHaveLength(18);
    expect(r.skills.every((s) => s.telemetry === null || s.telemetry.executions >= 0)).toBe(true);
  });

  it('every reconstructed run is labelled reconstructed', () => {
    for (const r of model.runs().runs) if (r.kind === 'reconstructed') expect(r.source).toBe('reconstructed');
  });
});
