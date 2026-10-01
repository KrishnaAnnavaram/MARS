#!/usr/bin/env node
'use strict';
/**
 * record-decision — the human plan decision, recorded.
 *
 * MARS's contract reserves the plan decision (Proposed -> Approved | Rejected) for a human. Until now
 * that decision was a hand edit of the plan's Status cell and left no record of who decided, when, or
 * which version of the plan they saw. This command performs EXACTLY the same edit a human would make
 * and additionally writes an append-only, hash-chained decision record. It is the only writer of
 * docs/agent_output/decisions/ and is used identically from a terminal and from Mission Control.
 *
 * It does not start the Fixer. 04_fix-generator Stage 2 still checks the Status cell itself.
 *
 * Refuses (non-zero exit, nothing written) when:
 *   - the plan no longer hashes to --expected-sha256           (exit 3, PLAN_CHANGED — stale view)
 *   - the plan's Status is not exactly "Proposed"               (exit 4, NOT_PROPOSED)
 *   - a Claude Code process marker is present (CLAUDECODE, …)   (exit 5, AGENT_SESSION_REFUSED)
 *   - the actor is a machine identity or the rationale is empty (exit 2, VALIDATION)
 *   - the issue has no plan                                     (exit 6, NOT_FOUND)
 *   - the CLI is not run in an interactive terminal, or the typed confirmation does not match
 *                                                               (exit 8, NOT_INTERACTIVE)
 *
 * Attribution is LOCALLY_ASSERTED. A process running as the same OS user can, with effort, defeat any
 * local check; these refusals are deterrence, and Mission Control's integrity rule R14 is detection
 * (unwitnessed or non-interactive decisions, or decisions concurrent with agent shell activity).
 *
 * Usage:
 *   node .claude/scripts/record-decision.js --issue ISSUE-001 --decision APPROVED|REJECTED \
 *        --expected-sha256 <hex> --actor "<your name>" --rationale "<why>" [--json]
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

let ledger = null;
try { ledger = require('./telemetry/ledger'); } catch (_) { ledger = null; }

const DEFAULT_ROOT = path.resolve(__dirname, '..', '..');
const MACHINE_ACTOR = /^(agent|claude|copilot|ci|bot|harness|llm|mars|system|automation|github-actions|jenkins)(?:$|[\s_.-])|\[(bot|agent)\]/i;
const STATUS_CELL = /(\|\s*\*\*Status\*\*\s*\|\s*)Proposed(\s*\|)/;
const ANY_STATUS = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/;

class DecisionError extends Error {
  constructor(code, exitCode, message) {
    super(message);
    this.code = code;
    this.exitCode = exitCode;
  }
}

function sha256(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

function canonical(obj) {
  if (Array.isArray(obj)) return `[${obj.map(canonical).join(',')}]`;
  if (obj && typeof obj === 'object') return `{${Object.keys(obj).sort().map((k) => `${JSON.stringify(k)}:${canonical(obj[k])}`).join(',')}}`;
  return JSON.stringify(obj);
}

function ulid() {
  if (ledger) return ledger.ulid();
  return `${Date.now().toString(36).toUpperCase()}${crypto.randomBytes(8).toString('hex').toUpperCase()}`;
}

function parseArgs(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i += 1) {
    const k = argv[i];
    const next = () => argv[++i];
    if (k === '--issue') a.issue = next();
    else if (k === '--decision') a.decision = String(next() || '').toUpperCase();
    else if (k === '--expected-sha256') a.expected = String(next() || '').toLowerCase();
    else if (k === '--actor') a.actor = next();
    else if (k === '--rationale') a.rationale = next();
    else if (k === '--channel') a.channel = next();
    else if (k === '--auth') a.auth = next();
    else if (k === '--root') a.root = next();
    else if (k === '--json') a.json = true;
    else if (k === '--help' || k === '-h') a.help = true;
  }
  return a;
}

function insideTmp(dir) {
  const rel = path.relative(fs.realpathSync(os.tmpdir()), fs.realpathSync(dir));
  return rel && !rel.startsWith('..') && !path.isAbsolute(rel);
}

/** Process markers of a Claude Code session; shared with Mission Control's decisionsEnabled(). */
const AGENT_MARKER = /^(CLAUDECODE|CLAUDE_PID|CLAUDE_CODE_(SESSION_ID|ENTRYPOINT|CHILD_SESSION|EXECPATH|SSE_PORT))$/;
// Set only by main() after a TTY confirmation; not exported, so a require()-ing caller cannot claim it.
const INTERACTIVE = Symbol('interactive');

/**
 * Decision logic; returns the record. Throws DecisionError on refusal. It always reads the real
 * process environment (no injectable env), and `interactive` is decided by main(), not by callers.
 */
function recordDecision(opts) {
  const env = process.env;
  const root = path.resolve(opts.root || DEFAULT_ROOT);
  // Any Claude Code process marker means an agent is (or launched) the caller. Unsetting CLAUDECODE alone
  // is not enough to pass; the PreToolUse hook also denies agent invocations of this command outright.
  const testMode = env.MARS_DECISION_TEST_MODE === '1' && insideTmp(root);
  const agentMarkers = Object.keys(env).filter((k) => AGENT_MARKER.test(k));
  if (agentMarkers.length && !testMode) {
    throw new DecisionError('AGENT_SESSION_REFUSED', 5, `Refusing: this command is running inside an agent session (${agentMarkers.join(', ')}). Plan decisions are reserved for a human; run it from your own terminal or from a Mission Control server you started yourself.`);
  }
  // A terminal decision needs a human at a terminal (agent tool shells have no TTY). Mission Control's
  // server channel instead requires the per-launch token the server checked on the request.
  const channel = opts.channel || 'cli';
  if (!testMode) {
    if (channel === 'mission-control') {
      if (!/^[A-Za-z0-9_-]{24,}$/.test(env.MC_DECISION_TOKEN || '')) throw new DecisionError('NOT_INTERACTIVE', 8, 'The mission-control channel is only available to a Mission Control server that verified its launch token.');
    } else if (opts[INTERACTIVE] !== true) {
      throw new DecisionError('NOT_INTERACTIVE', 8, 'Refusing: run this command yourself in an interactive terminal (it asks you to type the issue id to confirm). Non-interactive use is not allowed.');
    }
  }
  const issue = String(opts.issue || '');
  if (!/^[A-Za-z0-9_.-]{1,40}$/.test(issue)) throw new DecisionError('VALIDATION', 2, 'A valid --issue is required.');
  if (!['APPROVED', 'REJECTED'].includes(opts.decision)) throw new DecisionError('VALIDATION', 2, '--decision must be APPROVED or REJECTED. ("Request changes" is not part of the MARS contract: reject with a rationale instead.)');
  if (!/^[0-9a-f]{64}$/.test(opts.expected || '')) throw new DecisionError('VALIDATION', 2, '--expected-sha256 must be the 64-character sha256 of the plan you reviewed.');
  const actor = String(opts.actor || '').trim();
  if (!actor || actor.length > 80) throw new DecisionError('VALIDATION', 2, 'An --actor (the human deciding) is required.');
  if (MACHINE_ACTOR.test(actor)) throw new DecisionError('VALIDATION', 2, `"${actor}" looks like a machine identity. Only a human may decide a plan.`);
  const rationale = String(opts.rationale || '').trim();
  if (rationale.length < 10) throw new DecisionError('VALIDATION', 2, 'A --rationale of at least 10 characters is required.');

  const planRel = `docs/agent_output/04-remediation/fix_plan_${issue}.md`;
  const planAbs = path.join(root, planRel);
  if (!fs.existsSync(planAbs)) throw new DecisionError('NOT_FOUND', 6, `No plan at ${planRel}.`);
  const before = fs.readFileSync(planAbs);
  const shaBefore = sha256(before);
  if (shaBefore !== opts.expected) {
    throw new DecisionError('PLAN_CHANGED', 3, `The plan changed since it was reviewed (expected ${opts.expected.slice(0, 12)}…, found ${shaBefore.slice(0, 12)}…). Review the current version and decide again.`);
  }
  const text = before.toString('utf8');
  const current = (ANY_STATUS.exec(text) || [])[1];
  if (!current || current.trim() !== 'Proposed' || !STATUS_CELL.test(text)) {
    throw new DecisionError('NOT_PROPOSED', 4, `The plan's Status is "${current ? current.trim() : 'missing'}", not "Proposed". Only a Proposed plan can be decided.`);
  }
  const statusAfter = opts.decision === 'APPROVED' ? 'Approved' : 'Rejected';
  const after = text.replace(STATUS_CELL, `$1${statusAfter}$2`);
  const shaAfter = sha256(Buffer.from(after, 'utf8'));

  const dir = path.join(root, 'docs', 'agent_output', 'decisions');
  fs.mkdirSync(dir, { recursive: true });
  // One decision at a time: the hash chain's prev link and the plan rewrite must not interleave.
  const lockDir = path.join(dir, '.lock');
  const lockDeadline = Date.now() + 5000;
  for (;;) {
    try { fs.mkdirSync(lockDir); break; } catch (e) {
      if (e.code !== 'EEXIST') throw e;
      try { if (Date.now() - fs.statSync(lockDir).mtimeMs > 30000) { fs.rmSync(lockDir, { recursive: true, force: true }); continue; } } catch (_) { /* raced */ }
      if (Date.now() > lockDeadline) throw new DecisionError('BUSY', 7, 'Another decision is being recorded; try again.');
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 50);
    }
  }
  try {
    return writeDecision({ root, dir, planRel, planAbs, after, shaBefore, shaAfter, statusAfter, issue, actor, rationale, opts });
  } finally {
    try { fs.rmSync(lockDir, { recursive: true, force: true }); } catch (_) { /* ignore */ }
  }
}

function writeDecision({ root, dir, planRel, planAbs, after, shaBefore, shaAfter, statusAfter, issue, actor, rationale, opts }) {
  // Re-check under the lock: a concurrent decision may have changed the plan since validation.
  if (sha256(fs.readFileSync(planAbs)) !== shaBefore) throw new DecisionError('PLAN_CHANGED', 3, 'The plan changed while this decision was being recorded. Review the current version and decide again.');
  const existing = fs.readdirSync(dir).filter((f) => /^DEC-.+\.json$/.test(f)).sort();
  let prev = null;
  if (existing.length) {
    try { prev = JSON.parse(fs.readFileSync(path.join(dir, existing[existing.length - 1]), 'utf8')).record_sha256 || null; } catch (_) { prev = null; }
  }
  const decisionId = `DEC-${ulid()}`;
  const record = {
    schema: 'mars.decision/1',
    decision_id: decisionId,
    type: 'PLAN_DECISION',
    decision: opts.decision,
    issue_id: issue,
    subject: { path: planRel, sha256_before: shaBefore, sha256_after: shaAfter, status_before: 'Proposed', status_after: statusAfter },
    actor,
    actor_authentication: opts.auth || 'LOCALLY_ASSERTED',
    channel: opts.channel || 'cli',
    // How the human was established at decision time (deterrence evidence, not authentication).
    presence: opts.channel === 'mission-control' ? 'launch-token' : opts[INTERACTIVE] === true ? 'tty-confirmation' : 'test-mode',
    rationale,
    timestamp: new Date().toISOString(),
    effect: 'Status cell updated. The Fixer is NOT started; 04_fix-generator Stage 2 re-checks the Status cell when an operator runs it.',
    prev_record_sha256: prev,
  };
  record.record_sha256 = sha256(canonical(record));

  // Order: the record lands first, then the plan is replaced atomically. If the plan write fails the
  // record is removed; if the process dies in between, the record's sha256_after does not match the
  // plan on disk, which Mission Control reports (the decision is never silently implied).
  const finalPath = path.join(dir, `${decisionId}.json`);
  const tmpPath = `${finalPath}.tmp`;
  const planTmp = `${planAbs}.decision.tmp`;
  fs.writeFileSync(tmpPath, `${JSON.stringify(record, null, 2)}\n`);
  fs.renameSync(tmpPath, finalPath);
  try {
    fs.writeFileSync(planTmp, after);
    fs.renameSync(planTmp, planAbs);
  } catch (err) {
    try { fs.rmSync(planTmp, { force: true }); } catch (_) { /* ignore */ }
    try { fs.rmSync(finalPath, { force: true }); } catch (_) { /* ignore */ }
    throw err;
  }

  if (ledger) {
    ledger.append({
      type: 'approval.recorded',
      source: { emitter: 'command', id: 'harness/record-decision', runtime: record.channel },
      issue_ids: [issue],
      stage_id: 'approval',
      actor: { kind: 'human', id: actor, authentication: record.actor_authentication },
      provenance: 'human',
      status: 'completed',
      outcome: statusAfter,
      inputs: [{ path: planRel, sha256: shaBefore }],
      outputs: [{ path: planRel, sha256: shaAfter }, { path: `docs/agent_output/decisions/${decisionId}.json`, sha256: sha256(fs.readFileSync(finalPath)) }],
      attrs: { decision_id: decisionId, decision: opts.decision, channel: record.channel, presence: record.presence, rationale_present: true },
    }, { root, force: true }); // the decision witness is not optional telemetry: MARS_TELEMETRY=0 does not skip it
  }
  return { record, path: path.relative(root, finalPath).replace(/\\/g, '/') };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('node .claude/scripts/record-decision.js --issue <ID> --decision APPROVED|REJECTED --expected-sha256 <hex> --actor "<name>" --rationale "<why>" [--json]');
    return;
  }
  try {
    // Interactive confirmation for terminal use: a human types the issue id on a real TTY.
    if ((args.channel || 'cli') !== 'mission-control' && process.stdin.isTTY && process.stdout.isTTY && !args.json) {
      process.stdout.write(`Record ${String(args.decision || '').toUpperCase()} for ${args.issue} as "${args.actor}". Type the issue id to confirm: `);
      const buf = Buffer.alloc(256);
      let typed = '';
      try {
        const n = fs.readSync(0, buf, 0, buf.length, null);
        typed = buf.slice(0, n).toString('utf8').trim();
      } catch (_) { typed = ''; }
      if (typed !== args.issue) throw new DecisionError('NOT_INTERACTIVE', 8, 'Confirmation did not match the issue id; nothing was recorded.');
      args[INTERACTIVE] = true;
    }
    const out = recordDecision(args);
    if (args.json) process.stdout.write(`${JSON.stringify({ ok: true, ...out })}\n`);
    else console.log(`${out.record.decision_id}: ${args.issue} ${out.record.subject.status_after} by ${out.record.actor} → ${out.path}`);
  } catch (err) {
    const code = err instanceof DecisionError ? err.code : 'INTERNAL';
    const exitCode = err instanceof DecisionError ? err.exitCode : 1;
    if (args.json) process.stdout.write(`${JSON.stringify({ ok: false, code, message: err.message })}\n`);
    else console.error(`${code}: ${err.message}`);
    process.exitCode = exitCode;
  }
}

if (require.main === module) main();

module.exports = { recordDecision, DecisionError, canonical, AGENT_MARKER };
