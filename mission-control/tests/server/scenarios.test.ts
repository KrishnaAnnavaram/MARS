/**
 * Scenarios A–M over fixture workspaces (copies of the real evidence in the OS temp directory).
 * Scenarios the real data already contains (D, E, G, H) also run against the unmodified copy.
 * Scenarios it does not contain are produced by a minimal, labelled mutation:
 *   C (Proposed plan), F (a real test failure, not a compile error), I (SYNTHETIC Cleared verdict),
 *   K (missing artifact), L (malformed artifact / ledger line), M (empty workspace).
 */
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { Model } from '../../server/model';
import { ev, makeFixture, setPlanStatus, type Fixture } from '../helpers/fixture';

const fixtures: Fixture[] = [];
afterEach(() => {
  while (fixtures.length) fixtures.pop()!.cleanup();
});
function fx(opts?: { empty?: boolean }): Fixture {
  const f = makeFixture(opts);
  fixtures.push(f);
  return f;
}
function modelOf(f: Fixture, extra: Partial<ConstructorParameters<typeof Model>[0]> = {}): Model {
  return new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false, ...extra });
}

/** Run the fixture's own copy of the MARS hook adapter with a Claude Code hook payload. */
function hook(f: Fixture, payload: Record<string, unknown>): void {
  execFileSync(process.execPath, [path.join(f.root, '.claude/scripts/telemetry/mars-hook.js')], {
    input: JSON.stringify({ cwd: f.root, session_id: 'sess-test-1', ...payload }),
    env: { ...process.env, CLAUDE_PROJECT_DIR: f.root, MARS_LEDGER_DIR: f.ledgerDir, MARS_RUN_ID: '' },
    encoding: 'utf8',
  });
}

/** Replace a gate report's collapsed output block (fixture only). */
function replaceOutput(f: Fixture, rel: string, log: string): void {
  f.edit(rel, (t) => t.replace(/<details>[\s\S]*?<\/details>/, `<details><summary>Per-test output</summary>\n\n\`\`\`\n${log}\n\`\`\`\n\n</details>`).replace(/\*\*Why this is environmental[^\n]*\n/, ''));
}

describe('A — normal completed stages', () => {
  it('upstream stages with evidence read as passed with computed or mixed provenance', () => {
    const m = modelOf(fx());
    const d = m.issue('ISSUE-001')!;
    for (const s of ['intake', 'rca', 'blast_radius', 'plan'] as const) expect(d.cells[s].state, s).toBe('passed');
    expect(d.cells.rca.evidence?.sha256).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe('B — running agent (driven through the real hook adapter)', () => {
  it('shows the agent, skill, script, stage and issue while the gate runs, then completes', () => {
    const f = fx();
    hook(f, { hook_event_name: 'SubagentStart', agent_id: 'ag-1', agent_type: '06_additional-test-execution' });
    hook(f, { hook_event_name: 'PreToolUse', agent_id: 'ag-1', agent_type: '06_additional-test-execution', tool_name: 'Bash', tool_use_id: 'tu-1', tool_input: { command: 'node .claude/skills/06a-qa-runner/scripts/run-qa-gate.js --issue ISSUE-003' } });
    let m = modelOf(f);
    const now = m.nowState();
    expect(now.status).toBe('running');
    expect(now.activeRuns).toHaveLength(1);
    const run = now.activeRuns[0];
    expect(run.agentId).toBe('06_additional-test-execution');
    expect(run.isMarsAgent).toBe(true);
    expect(run.current?.script).toMatch(/run-qa-gate/);
    expect(run.current?.skill).toBe('06a-qa-runner');
    expect(run.stage).toBe('qa');
    expect(run.issues).toContain('ISSUE-003');
    expect(m.issue('ISSUE-003')!.cells.qa.modifiers).toContain('live');

    hook(f, { hook_event_name: 'PostToolUse', agent_id: 'ag-1', agent_type: '06_additional-test-execution', tool_name: 'Bash', tool_use_id: 'tu-1', tool_input: { command: 'node .claude/skills/06a-qa-runner/scripts/run-qa-gate.js --issue ISSUE-003' }, tool_response: { stdout: 'ISSUE-003 — FAIL (complete)\n', stderr: '', exit_code: 1 } });
    hook(f, { hook_event_name: 'SubagentStop', agent_id: 'ag-1', agent_type: '06_additional-test-execution' });
    m = modelOf(f);
    expect(m.nowState().activeRuns).toHaveLength(0);
    const observed = m.runs().runs.find((r) => r.source === 'observed' && r.agentId === '06_additional-test-execution')!;
    expect(observed.status).toBe('completed');
    const detail = m.runs().details.get(observed.runId)!;
    const op = detail.spans.find((s) => s.kind === 'operation')!;
    expect(op.status).toBe('completed');
    expect(op.attrs.reported).toEqual([{ issue_id: 'ISSUE-003', result: 'FAIL', stage: 'complete' }]);
    // Privacy: the raw command and tool output are not in the ledger.
    expect(JSON.stringify(detail.events)).not.toContain('FAIL (complete)\\n');
  });

  it('a human-wait notification marks the run as waiting on a human', () => {
    const f = fx();
    hook(f, { hook_event_name: 'SubagentStart', agent_id: 'ag-2', agent_type: '04_fix-generator' });
    hook(f, { hook_event_name: 'Notification', agent_id: 'ag-2', notification_type: 'permission_prompt', message: 'Claude needs your permission' });
    const n = modelOf(f).nowState();
    expect(n.status).toBe('waiting_human');
    expect(n.activeRuns[0].waitingHuman).toBe(true);
  });
});

describe('B2 — operations whose completion never arrived', () => {
  it('are not shown as "running now" once later work completed, and background commands stay running', () => {
    const f = fx();
    const t0 = Date.now() - 10 * 60 * 1000;
    const at = (min: number) => new Date(t0 + min * 60 * 1000);
    f.appendLedger([
      ev('operation.started', { session_id: 's-orph', span_id: 'lost', attrs: { tool: 'Bash', program: 'node', command_class: 'runtime' } }, at(0)),
      ev('operation.started', { session_id: 's-orph', span_id: 'bg', attrs: { tool: 'Bash', program: 'npm', command_class: 'package', background: true } }, at(1)),
      ev('operation.started', { session_id: 's-orph', span_id: 'ok', attrs: { tool: 'Bash', program: 'git', command_class: 'vcs' } }, at(5)),
      ev('operation.completed', { session_id: 's-orph', span_id: 'ok', status: 'completed', attrs: { tool: 'Bash', program: 'git', command_class: 'vcs' } }, at(5.1)),
    ]);
    const m = modelOf(f);
    const run = m.nowState().activeRuns.find((r) => r.sessionId === 's-orph')!;
    expect(run.current?.name).toMatch(/npm/); // the background command, not the lost one
    const spans = m.runs().details.get('session_s-orph')!.spans;
    const lost = spans.find((s) => s.id === 'op:lost')!;
    expect(lost.status).toBe('info');
    expect(lost.outcome).toBe('unknown');
    expect(lost.detail).toMatch(/No completion was recorded/);
  });

  it('a stray agent_run.completed (SubagentStop without an observed start) does not end a live session', () => {
    const f = fx();
    f.appendLedger([
      ev('session.started', { session_id: 's-live', run_id: 'session_s-live' }),
      ev('agent_run.completed', { session_id: 's-live', run_id: 'session_s-live', agent_id: 'general-purpose' }),
      ev('operation.started', { session_id: 's-live', run_id: 'session_s-live', span_id: 'm1', skill_id: '04d-version-migration', script_id: '04d-version-migration/run-migration-build', attrs: { tool: 'Bash' } }),
    ]);
    const m = modelOf(f);
    expect(m.runs().runs.find((r) => r.runId === 'session_s-live')!.status).toBe('running');
    const run = m.nowState().activeRuns.find((r) => r.runId === 'session_s-live')!;
    expect(run.skill).toBe('04d-version-migration');
    expect(m.registry().skills.find((s) => s.id === '04d-version-migration')?.telemetry?.active).toBe(true);
  });

  it('a session is not relabelled by a subagent whose start was not observed', () => {
    const f = fx();
    f.appendLedger([
      ev('session.started', { session_id: 's-x', run_id: 'session_s-x' }),
      ev('operation.started', { session_id: 's-x', run_id: 'session_s-x', agent_id: 'general-purpose', span_id: 'o1', attrs: { tool: 'Bash', program: 'git', command_class: 'vcs' } }),
    ]);
    const r = modelOf(f).runs().runs.find((x) => x.runId === 'session_s-x')!;
    expect(r.agentId).toBeNull();
    expect(r.label).toBe('Interactive session');
  });

  it('only MARS agents are counted as MARS activity on Home (non-MARS sessions are separate)', () => {
    const f = fx();
    f.appendLedger([ev('session.started', { session_id: 's-y', run_id: 'session_s-y' })]);
    const n = modelOf(f).nowState();
    expect(n.activeRuns.every((r) => !r.isMarsAgent)).toBe(true);
  });
});

describe('C — awaiting human approval', () => {
  it('a Proposed plan with no fix is a human decision, and nothing downstream claims to have run', () => {
    const f = fx();
    setPlanStatus(f, 'ISSUE-001', 'Proposed');
    f.edit('docs/agent_output/04-remediation/fix_plan_ISSUE-001.md', (t) => t.replace(/^_This plan was re-proposed[^\n]*\n/m, ''));
    for (const rel of ['04-remediation/fix_ISSUE-001.md', '04-remediation/fix_ISSUE-001.diff', '05-verify/rescan_ISSUE-001.md', '05-verify/redteam_ISSUE-001.md', '05-verify/behavior_ISSUE-001.md', '06-test-gate/qa_ISSUE-001.md', '06-test-gate/build_ISSUE-001.md', '07-ship/verdict_ISSUE-001.md', '07-ship/pr_ISSUE-001.md', '07-ship/audit_ISSUE-001.md']) f.remove(`docs/agent_output/${rel}`);
    const m = modelOf(f);
    const d = m.issue('ISSUE-001')!;
    expect(d.cells.approval.state).toBe('awaiting_human');
    expect(d.approval.state).toBe('pending');
    expect(d.nextAction?.ownerKind).toBe('human');
    for (const s of ['fix', 'rescan', 'qa', 'build', 'verdict'] as const) expect(['waiting', 'blocked_upstream'], s).toContain(d.cells[s].state);
    expect(m.overview().attention.some((a) => a.kind === 'decision' && a.issueId === 'ISSUE-001')).toBe(true);
    expect(m.overview().summary.awaitingDecision).toBe(1);
    // Decisions are off by default: the packet explains instead of offering a button.
    expect(d.approval.actions.allowed).toBe(false);
  });
});

describe('D/G/H — compile failure, build failure, Blocked (real data copy)', () => {
  it('differentiates the compile failure from test and build failures and keeps the cause unverified', () => {
    const d = modelOf(fx()).issue('ISSUE-004')!;
    expect(d.cells.fix.outcome).toMatch(/Compile Failed/);
    expect(d.cells.build.failureClass).toBe('compile.error');
    expect(d.cells.build.causeVerified).toBe(false);
    expect(d.verdict?.decision).toBe('Blocked');
    expect(d.blocker?.causes.some((c) => c.kind === 'hard_gate')).toBe(true);
  });
});

describe('E — verification failure (real data copy)', () => {
  it('ISSUE-002 is Blocked by the re-scan hard gate, not by score', () => {
    const d = modelOf(fx()).issue('ISSUE-002')!;
    expect(d.cells.rescan.state).toBe('failed');
    expect(d.verdictDetail?.hardGates.find((g) => /re-?scan/i.test(g.name))?.triggered).toBe(true);
  });
});

describe('F — QA test failure (mutated: a real assertion failure, no compile errors)', () => {
  it('classifies as a failed test, not a compile error', () => {
    const f = fx();
    replaceOutput(f, 'docs/agent_output/06-test-gate/qa_ISSUE-003.md', '[INFO] Running com.aura.vihanga.employeeservice.repository.EmployeeSearchRepositoryInjectionTest\n[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n[ERROR] searchEmployees_bindsPayloadAsLiteral  Time elapsed: 0.2 s  <<< FAILURE!\norg.opentest4j.AssertionFailedError: expected: <true> but was: <false>');
    const qa = modelOf(f).issue('ISSUE-003')!.lanes.find((l) => l.check === 'qa')!;
    expect(qa.state).toBe('failed');
    expect(qa.failureClass).toBe('qa.test_failed');
    expect(qa.compileErrors).toHaveLength(0);
  });
});

describe('I — Cleared verdict (SYNTHETIC: no Cleared verdict exists in the real data)', () => {
  it('a verdict that reads Cleared and replays to Cleared is shown as Cleared and publication-eligible only on explicit request', () => {
    const f = fx();
    const id = 'ISSUE-003';
    f.edit(`docs/agent_output/04-remediation/fix_${id}.md`, (t) => t.replace('| **Status** | Compile Failed |', '| **Status** | Compiled |'));
    for (const g of ['qa', 'build']) {
      f.edit(`docs/agent_output/06-test-gate/${g}_${id}.md`, (t) => t.replace(/\| (FAIL|1) \|(\s*\d+\s*\|)?\s*$/gm, (row) => row.replace('FAIL', 'PASS').replace(/\| 1 \|\s*$/, '| 0 |')).replace(/\| 1 \|$/gm, '| 0 |'));
      replaceOutput(f, `docs/agent_output/06-test-gate/${g}_${id}.md`, '[INFO] BUILD SUCCESS');
    }
    f.edit(`docs/agent_output/07-ship/verdict_${id}.md`, (t) => t
      .replace('> ⛔ Blocked — not safe to ship.', '> ✅ Cleared — safe to ship.')
      .replace('| **Decision** | Blocked |', '| **Decision** | Cleared |')
      .replace('| **Score** | 60 / 100 |', '| **Score** | 100 / 100 |')
      .replace('| **Hard gates triggered** | build-gatekeeper |', '| **Hard gates triggered** | none |')
      .replace('| QA gate | Failed | 0 / 40 |', '| QA gate | Passed | 40 / 40 |')
      .replace('| **Total** | | **60 / 100** |', '| **Total** | | **100 / 100** |')
      .replace('| Build failure blocks merge | **TRIGGERED** |', '| Build failure blocks merge | clear |'));
    const d = modelOf(f).issue(id)!;
    expect(d.verdict?.decision).toBe('Cleared');
    expect(d.cells.verdict.state).toBe('passed');
    expect(d.blocker).toBeNull();
    // PR creation is never automatic: it needs an explicit user request to 07_audit-and-pr.
    expect(d.cells.publication.state).not.toBe('passed');
    expect(d.nextAction?.text).toMatch(/explicit/i);
  });
});

describe('Attention ranking and wording (judge P1 findings)', () => {
  it('names altered gate reports as the cause instead of suggesting the patch should be Cleared', () => {
    const o = modelOf(fx()).overview();
    const a = o.attention.find((x) => x.issueId === 'ISSUE-003' && x.kind === 'integrity')!;
    expect(a.title).toMatch(/gate reports contradict their own exit codes/);
    expect(`${a.title} ${a.detail}`).not.toMatch(/gives Cleared/);
    expect(a.detail).toMatch(/reproduces the recorded Blocked/);
  });

  it('approvals carried over a re-proposal or deviating implementations need a human re-review', () => {
    const o = modelOf(fx()).overview();
    expect(o.attention.some((x) => x.kind === 'invalidated_approval' && x.issueId === 'ISSUE-003')).toBe(true);
    expect(o.summary.approvalsToReview).toBeGreaterThan(0);
  });

  it('an agent edit of rendered evidence is a critical attention item', () => {
    const f = fx();
    f.appendLedger([ev('guard.would_deny', { session_id: 's-g', agent_id: '07_audit-and-pr', issue_ids: ['ISSUE-003'], attrs: { tool: 'Edit', path: 'docs/agent_output/06-test-gate/qa_ISSUE-003.md' } })]);
    const a = modelOf(f).overview().attention.find((x) => /edited docs\/agent_output\/06-test-gate\/qa_ISSUE-003\.md/.test(x.title))!;
    expect(a.severity).toBe('critical');
  });

  it('a denied decision-command attempt is labelled as such, and guard items expire after 24 h', () => {
    const f = fx();
    f.appendLedger([
      ev('guard.denied', { session_id: 's-d', agent_id: '04_fix-generator', attrs: { tool: 'Bash', rule: 'agent-ran-decision-command' } }),
      ev('guard.would_deny', { session_id: 's-d', attrs: { tool: 'Edit', path: 'docs/agent_output/07-ship/verdict_ISSUE-001.md' } }, new Date(Date.now() - 30 * 3600 * 1000)),
    ]);
    const att = modelOf(f).overview().attention;
    expect(att.some((a) => a.title === 'An agent tried to run the human decision command')).toBe(true);
    expect(att.some((a) => /verdict_ISSUE-001/.test(a.title))).toBe(false); // older than 24 h
    expect(att.some((a) => /undefined/.test(`${a.title} ${a.detail}`))).toBe(false);
  });

  it('a Cleared verdict with critical integrity findings is "Cleared, untrusted", never "Eligible"', () => {
    const f = fx();
    // SYNTHETIC: flip the ISSUE-003 verdict to Cleared but leave the contradicting gate reports in place.
    f.edit('docs/agent_output/07-ship/verdict_ISSUE-003.md', (t) => t.replace('| **Decision** | Blocked |', '| **Decision** | Cleared |').replace('> ⛔ Blocked — not safe to ship.', '> ✅ Cleared — safe to ship.'));
    const d = modelOf(f).issue('ISSUE-003')!;
    expect(d.verdict?.decision).toBe('Cleared');
    expect(d.cells.publication.state).toBe('inconclusive');
    expect(d.cells.publication.label).toBe('Cleared, untrusted');
    expect(d.publication.eligible).toBe(false);
  });
});

describe('K — missing artifact', () => {
  it('a missing re-scan report is shown as missing and the verdict is not trusted silently', () => {
    const f = fx();
    f.remove('docs/agent_output/05-verify/rescan_ISSUE-001.md');
    const m = modelOf(f);
    const d = m.issue('ISSUE-001')!;
    // The fix exists, so the re-scan could run now ("ready"); it has no evidence.
    expect(['missing', 'ready', 'waiting']).toContain(d.cells.rescan.state);
    expect(d.lanes.find((l) => l.check === 'rescan')!.evidence).toBeNull();
    // The verdict that was computed from it is flagged instead of trusted silently (R9).
    expect(m.findings().some((x) => x.rule === 'R9' && x.issueId === 'ISSUE-001')).toBe(true);
  });
});

describe('L — malformed inputs', () => {
  it('a corrupted verdict file does not crash the projection and is reported as unparsed', () => {
    const f = fx();
    f.write('docs/agent_output/07-ship/verdict_ISSUE-001.md', '\u0000\u0000 garbage {{{ not a verdict\n| broken | table');
    const m = modelOf(f);
    const d = m.issue('ISSUE-001')!;
    expect(d).toBeTruthy();
    expect(d.verdict?.decision ?? null).not.toBe('Cleared');
    expect(m.overview().issues.length).toBe(4);
  });

  it('torn and malformed ledger lines are skipped and counted', () => {
    const f = fx();
    f.appendLedger([ev('session.started', { session_id: 's9' })]);
    const month = new Date().toISOString().slice(0, 7);
    f.write(`.mars/ledger/events-${month}.jsonl`, f.read(`.mars/ledger/events-${month}.jsonl`) + '{"not json\n{"schema":"mars.event/1","time":"bad"');
    const m = modelOf(f);
    const st = m.ledgerStatus();
    expect(st.events).toBe(1);
    expect(st.malformed).toBeGreaterThanOrEqual(1);
  });
});

describe('M — empty workspace / no active runs', () => {
  it('renders an idle state with no issues and no fabricated activity', () => {
    const m = modelOf(fx({ empty: true }));
    const o = m.overview();
    expect(o.issues).toHaveLength(0);
    expect(o.now.status).toBe('idle');
    expect(o.now.activeRuns).toHaveLength(0);
    expect(o.now.lastActivityAt).toBeNull();
    expect(m.runs().runs).toHaveLength(0);
  });
});
