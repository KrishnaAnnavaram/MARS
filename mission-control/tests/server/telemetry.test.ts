/**
 * Additive MARS instrumentation (.claude/scripts/telemetry): ledger writer, command/path classifier,
 * hook adapter and gate-event failure classes. All writes go to temp ledger directories.
 */
import { execFile, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { afterEach, describe, expect, it } from 'vitest';
import { LedgerReader } from '../../server/sources/ledger';
import { REPO, makeFixture, type Fixture } from '../helpers/fixture';

const req = createRequire(import.meta.url);
const TEL = path.join(REPO, '.claude/scripts/telemetry');
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const ledger = req(path.join(TEL, 'ledger.js')) as any;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const classify = req(path.join(TEL, 'classify.js')) as any;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const gate = req(path.join(TEL, 'gate-events.js')) as any;

const tmp: string[] = [];
const fixtures: Fixture[] = [];
afterEach(() => {
  delete process.env.MARS_LEDGER_DIR;
  delete process.env.MARS_TELEMETRY;
  while (tmp.length) fs.rmSync(tmp.pop()!, { recursive: true, force: true });
  while (fixtures.length) fixtures.pop()!.cleanup();
});
function tdir(): string {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'mc-tel-'));
  tmp.push(d);
  return d;
}
function lines(dir: string): Record<string, unknown>[] {
  return fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl')).flatMap((f) => fs.readFileSync(path.join(dir, f), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)));
}

describe('ledger.js', () => {
  it('appends mars.event/1 envelopes with a monotonic per-workspace seq, even from concurrent processes', async () => {
    const dir = tdir();
    const code = `const l=require(${JSON.stringify(path.join(TEL, 'ledger.js'))});for(let i=0;i<10;i++)l.append({type:'operation.completed',status:'completed'});`;
    const run = promisify(execFile);
    await Promise.all([1, 2, 3, 4].map(() => run(process.execPath, ['-e', code], { env: { ...process.env, MARS_LEDGER_DIR: dir } })));
    const evs = lines(dir);
    expect(evs).toHaveLength(40);
    const seqs = evs.map((e) => e.seq as number).sort((a, b) => a - b);
    expect(new Set(seqs).size).toBe(40);
    expect(seqs[0]).toBe(1);
    expect(seqs[39]).toBe(40);
    for (const e of evs) {
      expect(e.schema).toBe('mars.event/1');
      expect(typeof e.event_id).toBe('string');
      expect(Date.parse(e.time as string)).not.toBeNaN();
    }
  });

  it('redacts secrets and never records raw values of secret-looking keys', () => {
    expect(ledger.redact('mvn -Dpassword=hunter2 -Dtoken="abc def" ok')).not.toMatch(/hunter2|abc def/);
    const clean = ledger.sanitizeAttrs({ api_key: 'sk-123', nested: { note: 'password=swordfish' }, n: 3 });
    expect(JSON.stringify(clean)).not.toMatch(/sk-123|swordfish/);
    expect(clean.n).toBe(3);
  });

  it('is a no-op when MARS_TELEMETRY=0 and never throws on an unwritable ledger', () => {
    const dir = tdir();
    process.env.MARS_LEDGER_DIR = dir;
    process.env.MARS_TELEMETRY = '0';
    expect(() => ledger.append({ type: 'x.y' })).not.toThrow();
    expect(lines(dir)).toHaveLength(0);
    delete process.env.MARS_TELEMETRY;
    const file = path.join(tdir(), 'not-a-dir');
    fs.writeFileSync(file, 'x');
    process.env.MARS_LEDGER_DIR = file;
    expect(() => ledger.append({ type: 'x.y' })).not.toThrow();
  });
});

describe('classify.js', () => {
  it('identifies MARS skill scripts, their stage and issue, including cd-prefixed forms', () => {
    const a = classify.classifyCommand('node .claude/skills/06b-build-gatekeeper/scripts/run-build-gate.js --issue ISSUE-002', REPO);
    expect(a.skill_id).toBe('06b-build-gatekeeper');
    expect(a.script_id).toBe('06b-build-gatekeeper/run-build-gate');
    expect(a.stage_id).toBe('build');
    expect(a.issue_ids).toEqual(['ISSUE-002']);
    const b = classify.classifyCommand('cd .claude/skills/07a-merge-arbiter && node scripts/compute-score.js --all', REPO);
    expect(b.skill_id).toBe('07a-merge-arbiter');
    expect(b.stage_id).toBe('verdict');
    expect(classify.classifyCommand('git status', REPO)).toBeNull();
  });

  it('names ordinary commands by program only, through quoted cd prefixes and env assignments', () => {
    expect(classify.commandClass('cd "/e/Virtusa Projects/MARS" && git status')).toEqual({ program: 'git', command_class: 'vcs' });
    expect(classify.commandClass('MSYS_NO_PATHCONV=1 VP=desktop node e2e/shoot.mjs out')).toEqual({ program: 'node', command_class: 'runtime' });
    expect(classify.commandClass("cd 'a b' ; npx vitest run")).toEqual({ program: 'npx', command_class: 'package' });
    expect(classify.commandClass('./weird-tool --x').program).toBe('other');
  });

  it('keeps only allow-listed arguments (no free text, no secrets)', () => {
    const c = classify.classifyCommand('node .claude/skills/04b-fixer/scripts/verify-patch.js --issue ISSUE-001 --password hunter2 "some free text"', REPO);
    expect(JSON.stringify(c.args)).not.toMatch(/hunter2|free text/);
  });

  it('marks rendered evidence, the register and decision records as protected', () => {
    expect(classify.classifyPath('docs/agent_output/07-ship/verdict_ISSUE-001.md').protected).toBe(true);
    expect(classify.classifyPath('docs/agent_output/00-issues/issue-register.xlsx').protected).toBe(true);
    expect(classify.classifyPath('docs/agent_output/decisions/DEC-1.json').protected).toBe(true);
    expect(classify.classifyPath('src/employee-service/src/main/java/A.java').protected).toBeFalsy();
  });
});

describe('mars-hook.js', () => {
  function hook(f: Fixture, payload: Record<string, unknown>, env: Record<string, string> = {}) {
    return spawnSync(process.execPath, [path.join(f.root, '.claude/scripts/telemetry/mars-hook.js')], {
      input: typeof payload === 'string' ? payload : JSON.stringify({ cwd: f.root, session_id: 'sess-h', ...payload }),
      env: { ...process.env, CLAUDE_PROJECT_DIR: f.root, MARS_LEDGER_DIR: f.ledgerDir, ...env },
      encoding: 'utf8',
    });
  }
  function fx(): Fixture {
    const f = makeFixture({ empty: true });
    fixtures.push(f);
    return f;
  }

  it('always exits 0, even on garbage input', () => {
    const f = fx();
    const r = spawnSync(process.execPath, [path.join(f.root, '.claude/scripts/telemetry/mars-hook.js')], { input: '{not json', env: { ...process.env, MARS_LEDGER_DIR: f.ledgerDir }, encoding: 'utf8' });
    expect(r.status).toBe(0);
  });

  it('observe mode records guard.would_deny for an agent editing rendered evidence and allows it', () => {
    const f = fx();
    const r = hook(f, { hook_event_name: 'PreToolUse', agent_id: 'a', agent_type: '07_audit-and-pr', tool_name: 'Edit', tool_input: { file_path: path.join(f.root, 'docs/agent_output/06-test-gate/qa_ISSUE-003.md'), old_string: 'Failed', new_string: 'Passed' } });
    expect(r.status).toBe(0);
    expect(r.stdout.trim()).toBe('');
    const evs = new LedgerReader(f.ledgerDir);
    evs.poll();
    const g = evs.all().find((e) => e.type === 'guard.would_deny')!;
    expect(g).toBeTruthy();
    expect(JSON.stringify(g)).not.toMatch(/old_string|Passed/);
  });

  it('enforce mode denies the edit with a reason', () => {
    const f = fx();
    const r = hook(f, { hook_event_name: 'PreToolUse', tool_name: 'Write', tool_input: { file_path: path.join(f.root, 'docs/agent_output/07-ship/verdict_ISSUE-001.md'), content: 'Cleared' } }, { MARS_EVIDENCE_GUARD: 'enforce' });
    expect(r.status).toBe(0);
    const out = JSON.parse(r.stdout);
    expect(out.hookSpecificOutput.permissionDecision).toBe('deny');
  });

  it.each([
    'node .claude/scripts/record-decision.js --issue ISSUE-001 --decision APPROVED --actor "Krishna Annavaram"',
    'env -u CLAUDECODE node .claude/scripts/record-decision.js --issue ISSUE-001',
    'cd "/e/x y" && CLAUDECODE= node ./.claude/scripts/record-decision --issue ISSUE-001',
  ])('always denies an agent running the human decision command: %s', (command) => {
    const f = fx();
    const r = hook(f, { hook_event_name: 'PreToolUse', agent_type: '04_fix-generator', agent_id: 'a', tool_name: 'Bash', tool_input: { command } }, { MARS_EVIDENCE_GUARD: 'off' });
    expect(r.status).toBe(0);
    expect(JSON.parse(r.stdout).hookSpecificOutput.permissionDecision).toBe('deny');
    const evs = new LedgerReader(f.ledgerDir);
    evs.poll();
    expect(evs.all().some((e) => e.type === 'guard.denied' && (e.attrs as { rule?: string }).rule === 'agent-ran-decision-command')).toBe(true);
  });

  it.each([
    'grep -n record-decision .claude/README.md',
    'find mission-control -name "*.tsx" | xargs wc -l .claude/scripts/record-decision.js',
    'git diff -- .claude/scripts/record-decision.js',
  ])('does not deny merely mentioning the decision command: %s', (command) => {
    const f = fx();
    const r = hook(f, { hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_input: { command } });
    expect(r.stdout.trim()).toBe('');
  });

  it.each([
    'bash -c "node .claude/scripts/record-decision.js --issue ISSUE-001"',
    'sudo env -i PATH=/usr/bin nodejs .claude/scripts/record-decision.js',
    'echo hi; npx tsx .claude/scripts/record-decision.js',
  ])('denies wrapped invocations too: %s', (command) => {
    const f = fx();
    const r = hook(f, { hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_input: { command } });
    expect(JSON.parse(r.stdout).hookSpecificOutput.permissionDecision).toBe('deny');
  });

  it('records background commands and writes outside MARS areas without their path', () => {
    const f = fx();
    hook(f, { hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_use_id: 'bg1', tool_input: { command: 'npm run dev', run_in_background: true } });
    hook(f, { hook_event_name: 'PostToolUse', tool_name: 'Write', tool_use_id: 'w1', tool_input: { file_path: path.join(f.root, 'notes/private-plan.txt'), content: 'x' }, tool_response: {} });
    const evs = new LedgerReader(f.ledgerDir);
    evs.poll();
    expect(evs.all().find((e) => e.type === 'operation.started')?.attrs?.background).toBe(true);
    const w = evs.all().find((e) => e.type === 'artifact.written')!;
    expect(JSON.stringify(w)).not.toContain('private-plan');
  });

  it('never stores the raw shell command or tool output', () => {
    const f = fx();
    hook(f, { hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_use_id: 'x1', tool_input: { command: 'curl -H "Authorization: Bearer SECRET123" https://example.com' } });
    hook(f, { hook_event_name: 'PostToolUse', tool_name: 'Bash', tool_use_id: 'x1', tool_input: { command: 'curl -H "Authorization: Bearer SECRET123" https://example.com' }, tool_response: { stdout: 'private output body', exit_code: 0 } });
    const text = fs.readdirSync(f.ledgerDir).filter((x) => x.endsWith('.jsonl')).map((x) => fs.readFileSync(path.join(f.ledgerDir, x), 'utf8')).join('');
    expect(text).toContain('operation.completed');
    expect(text).not.toMatch(/SECRET123|private output body|example\.com/);
  });
});

describe('gate-events failure classes', () => {
  it('differentiates infrastructure, patch-apply, compile, test and build failures', () => {
    expect(gate.failureClass({ passed: true }, 'qa')).toBeNull();
    expect(gate.failureClass({ refused: true }, 'qa').class).toBe('refused');
    expect(gate.failureClass({ stage: 'worktree-create' }, 'qa').class).toBe('infrastructure');
    expect(gate.failureClass({ stage: 'apply-fix' }, 'build').class).toBe('patch.apply');
    expect(gate.failureClass({ stage: 'complete', output: ['[ERROR] /x/A.java:[30,9] cannot find symbol'] }, 'qa').class).toBe('compile.error');
    expect(gate.failureClass({ stage: 'complete', output: ['Tests run: 1, Failures: 1'] }, 'qa').class).toBe('qa.test_failed');
    expect(gate.failureClass({ stage: 'complete', steps: [{ output: 'BUILD FAILURE: enforcer' }] }, 'build').class).toBe('build.failed');
  });
});
