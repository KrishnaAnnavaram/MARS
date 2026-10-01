/**
 * The human decision command (.claude/scripts/record-decision.js) — run as a real process against
 * fixture workspaces in the OS temp directory. This suite runs inside an agent session (CLAUDECODE=1),
 * so success paths use MARS_DECISION_TEST_MODE=1, which the script honours ONLY for temp workspaces.
 */
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { Model } from '../../server/model';
import { REPO, makeFixture, setPlanStatus, type Fixture } from '../helpers/fixture';

const fixtures: Fixture[] = [];
afterEach(() => {
  while (fixtures.length) fixtures.pop()!.cleanup();
});

const PLAN = (id: string) => `docs/agent_output/04-remediation/fix_plan_${id}.md`;
const sha = (f: Fixture, rel: string) => crypto.createHash('sha256').update(fs.readFileSync(f.file(rel))).digest('hex');

function proposed(): Fixture {
  const f = makeFixture();
  fixtures.push(f);
  setPlanStatus(f, 'ISSUE-001', 'Proposed');
  return f;
}

function run(f: Fixture | string, args: string[], env: Record<string, string | undefined> = {}) {
  const root = typeof f === 'string' ? f : f.root;
  const script = typeof f === 'string' ? path.join(REPO, '.claude/scripts/record-decision.js') : path.join(f.root, '.claude/scripts/record-decision.js');
  const r = spawnSync(process.execPath, [script, '--root', root, '--json', ...args], { encoding: 'utf8', env: { ...process.env, CLAUDECODE: '1', MARS_LEDGER_DIR: '', ...env } });
  const last = (r.stdout || '').trim().split('\n').pop() || '{}';
  return { status: r.status, out: JSON.parse(last) as { ok: boolean; code?: string; record?: Record<string, unknown>; path?: string } };
}

const TEST = { MARS_DECISION_TEST_MODE: '1' };

describe('record-decision.js', () => {
  it('refuses inside an agent session unless in test mode on a temp workspace (exit 5)', () => {
    const f = proposed();
    const r = run(f, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', 'Dana Reviewer', '--rationale', 'Reviewed the plan and approve it.']);
    expect(r.status).toBe(5);
    expect(r.out.code).toBe('AGENT_SESSION_REFUSED');
  });

  it.each([['CLAUDE_CODE_SESSION_ID'], ['CLAUDE_CODE_ENTRYPOINT'], ['CLAUDE_PID']])('refuses when %s is set even with CLAUDECODE unset (agent forging a human decision)', (marker) => {
    const f = proposed();
    const env: Record<string, string | undefined> = {};
    for (const k of Object.keys(process.env)) if (/^CLAUDE/.test(k)) env[k] = undefined;
    const r = spawnSync(process.execPath, [path.join(f.root, '.claude/scripts/record-decision.js'), '--root', f.root, '--json', '--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', 'Krishna Annavaram', '--rationale', 'Looks fine to me overall.'], {
      encoding: 'utf8', env: Object.fromEntries(Object.entries({ ...process.env, ...env, [marker]: 'x' }).filter(([, v]) => v !== undefined)) as NodeJS.ProcessEnv,
    });
    expect(r.status).toBe(5);
    expect(f.read(PLAN('ISSUE-001'))).toMatch(/\*\*Status\*\* \| Proposed/);
  });

  function cleanEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
    return Object.fromEntries(Object.entries({ ...process.env, ...extra }).filter(([k]) => !/^CLAUDE/.test(k) && k !== 'MARS_DECISION_TEST_MODE')) as NodeJS.ProcessEnv;
  }

  it('outside an agent session, a non-interactive CLI call is refused (exit 8): a human must type the confirmation on a TTY', () => {
    const f = proposed();
    const r = spawnSync(process.execPath, [path.join(f.root, '.claude/scripts/record-decision.js'), '--root', f.root, '--json', '--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', 'Krishna Annavaram', '--rationale', 'Looks fine to me overall.'], { encoding: 'utf8', env: cleanEnv() });
    expect(r.status).toBe(8);
    expect(f.read(PLAN('ISSUE-001'))).toMatch(/\*\*Status\*\* \| Proposed/);
  });

  it('the mission-control channel is refused without a launch token (exit 8)', () => {
    const f = proposed();
    const r = spawnSync(process.execPath, [path.join(f.root, '.claude/scripts/record-decision.js'), '--root', f.root, '--json', '--channel', 'mission-control', '--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', 'Krishna Annavaram', '--rationale', 'Looks fine to me overall.'], { encoding: 'utf8', env: cleanEnv() });
    expect(r.status).toBe(8);
  });

  it('a well-formed record forged by writing the file directly is flagged as uncorroborated (R14)', () => {
    const f = proposed();
    // SYNTHETIC forgery: a same-user process writes a valid, hash-chained record and edits the Status cell.
    const req = createRequire(import.meta.url);
    const { canonical } = req(path.join(f.root, '.claude/scripts/record-decision.js')) as { canonical: (o: unknown) => string };
    const plan = f.read(PLAN('ISSUE-001'));
    const after = plan.replace(/(\|\s*\*\*Status\*\*\s*\|\s*)Proposed/, '$1Approved');
    const rec: Record<string, unknown> = { schema: 'mars.decision/1', decision_id: 'DEC-FORGED', type: 'PLAN_DECISION', decision: 'APPROVED', issue_id: 'ISSUE-001',
      subject: { path: PLAN('ISSUE-001'), sha256_before: sha(f, PLAN('ISSUE-001')), sha256_after: crypto.createHash('sha256').update(after).digest('hex'), status_before: 'Proposed', status_after: 'Approved' },
      actor: 'Krishna Annavaram', actor_authentication: 'LOCALLY_ASSERTED', channel: 'cli', presence: 'tty-confirmation', rationale: 'Approve it please.', timestamp: new Date().toISOString(), effect: '', prev_record_sha256: null };
    rec.record_sha256 = crypto.createHash('sha256').update(canonical(rec)).digest('hex');
    f.write('docs/agent_output/decisions/DEC-FORGED.json', JSON.stringify(rec, null, 2));
    f.write(PLAN('ISSUE-001'), after);
    const m = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false });
    expect(m.findings().some((x) => x.rule === 'R13')).toBe(false); // the hash chain is valid…
    const r14 = m.findings().find((x) => x.rule === 'R14')!;
    expect(r14.severity).toBe('critical'); // …but nothing witnessed it
    expect(r14.detail).toMatch(/no matching approval\.recorded witness/);
  });

  it('test mode never applies to the real workspace', () => {
    const r = run(REPO, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', '0'.repeat(64), '--actor', 'Dana Reviewer', '--rationale', 'Reviewed the plan and approve it.'], TEST);
    expect(r.status).toBe(5);
  });

  it('refuses a plan that is not Proposed (exit 4)', () => {
    const f = makeFixture();
    fixtures.push(f);
    const r = run(f, ['--issue', 'ISSUE-003', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-003')), '--actor', 'Dana Reviewer', '--rationale', 'Reviewed the plan and approve it.'], TEST);
    expect(r.status).toBe(4);
    expect(r.out.code).toBe('NOT_PROPOSED');
  });

  it('refuses when the plan changed after review (stale hash, exit 3) and leaves the plan untouched', () => {
    const f = proposed();
    const before = f.read(PLAN('ISSUE-001'));
    const r = run(f, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', 'a'.repeat(64), '--actor', 'Dana Reviewer', '--rationale', 'Reviewed the plan and approve it.'], TEST);
    expect(r.status).toBe(3);
    expect(r.out.code).toBe('PLAN_CHANGED');
    expect(f.read(PLAN('ISSUE-001'))).toBe(before);
  });

  it.each([['claude'], ['04_fix-generator-bot[bot]'], ['agent-07'], ['github-actions']])('refuses machine actor %s (exit 2)', (actor) => {
    const f = proposed();
    const r = run(f, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', actor, '--rationale', 'Reviewed the plan and approve it.'], TEST);
    expect(r.status).toBe(2);
  });

  it('refuses "request changes" and short rationales (exit 2)', () => {
    const f = proposed();
    const h = sha(f, PLAN('ISSUE-001'));
    expect(run(f, ['--issue', 'ISSUE-001', '--decision', 'CHANGES', '--expected-sha256', h, '--actor', 'Dana', '--rationale', 'Please change it a lot.'], TEST).status).toBe(2);
    expect(run(f, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', h, '--actor', 'Dana', '--rationale', 'ok'], TEST).status).toBe(2);
  });

  it('records an attributable decision, edits only the Status cell, and appends a ledger witness', () => {
    const f = proposed();
    const rel = PLAN('ISSUE-001');
    const before = f.read(rel);
    const h = sha(f, rel);
    const r = run(f, ['--issue', 'ISSUE-001', '--decision', 'APPROVED', '--expected-sha256', h, '--actor', 'Dana Reviewer', '--rationale', 'Parameterised query approach is right; risks acknowledged.'], TEST);
    expect(r.status).toBe(0);
    expect(r.out.ok).toBe(true);
    const after = f.read(rel);
    // Exactly one line differs: the Status cell.
    const a = before.split('\n');
    const b = after.split('\n');
    expect(b.length).toBe(a.length);
    const changed = a.map((l, i) => (l === b[i] ? null : [l, b[i]])).filter(Boolean) as [string, string][];
    expect(changed).toHaveLength(1);
    expect(changed[0][0]).toMatch(/\*\*Status\*\* \| Proposed/);
    expect(changed[0][1]).toMatch(/\*\*Status\*\* \| Approved/);

    const dir = f.file('docs/agent_output/decisions');
    const recs = fs.readdirSync(dir).filter((x) => x.endsWith('.json'));
    expect(recs).toHaveLength(1);
    const rec = JSON.parse(fs.readFileSync(path.join(dir, recs[0]), 'utf8'));
    expect(rec.actor).toBe('Dana Reviewer');
    expect(rec.actor_authentication).toBe('LOCALLY_ASSERTED');
    expect(rec.subject.sha256_before).toBe(h);

    const month = new Date().toISOString().slice(0, 7);
    const ledgerLines = fs.readFileSync(path.join(f.ledgerDir, `events-${month}.jsonl`), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    expect(ledgerLines.some((e) => e.type === 'approval.recorded' && e.issue_ids?.includes('ISSUE-001'))).toBe(true);

    // Mission Control now shows an attributed approval and verifies the record's hash.
    const m = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false });
    const ap = m.issue('ISSUE-001')!.approval;
    expect(ap.state).toBe('approved');
    expect(ap.decisions[0].actor).toBe('Dana Reviewer');
    expect(ap.decisions[0].appliesToCurrent).toBe(true);
    expect(m.findings().some((x) => x.rule === 'R13')).toBe(false);

    // A second decision on the same plan is refused: it is no longer Proposed.
    expect(run(f, ['--issue', 'ISSUE-001', '--decision', 'REJECTED', '--expected-sha256', sha(f, rel), '--actor', 'Dana Reviewer', '--rationale', 'Changing my mind after all.'], TEST).status).toBe(4);
  });

  it('a tampered decision record is caught by Mission Control (R13)', () => {
    const f = proposed();
    run(f, ['--issue', 'ISSUE-001', '--decision', 'REJECTED', '--expected-sha256', sha(f, PLAN('ISSUE-001')), '--actor', 'Dana Reviewer', '--rationale', 'Approach does not cover the export path.'], TEST);
    const dir = f.file('docs/agent_output/decisions');
    const file = path.join(dir, fs.readdirSync(dir)[0]);
    const rec = JSON.parse(fs.readFileSync(file, 'utf8'));
    rec.actor = 'Someone Else';
    fs.writeFileSync(file, JSON.stringify(rec, null, 2));
    const m = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false });
    expect(m.findings().some((x) => x.rule === 'R13')).toBe(true);
  });
});
