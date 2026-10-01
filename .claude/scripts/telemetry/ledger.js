'use strict';
/**
 * MARS event ledger — `mars.event/1` writer.
 *
 * Events are WITNESSES, not authority: they record what happened and when. Evidence files and
 * decision records remain the source of truth (docs/mission-control/MARS-Mission-Control-Proposal.md §33, §35).
 *
 * Guarantees:
 *   - zero dependencies; never throws to the caller (every public function swallows its own errors);
 *   - append-only JSONL under <repo>/.mars/ledger/events-YYYY-MM.jsonl (override: MARS_LEDGER_DIR);
 *   - a gap-free per-file `seq`, assigned under a directory lock shared by every writer process;
 *   - allow-listed content only: no source, diffs, prompts, model output or environment values.
 *     Free text that does get recorded (short summaries) is passed through `redact()` and truncated.
 *
 * Disable entirely with MARS_TELEMETRY=0.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const SCHEMA = 'mars.event/1';
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');
const MAX_TEXT = 400;

function isDisabled() {
  return process.env.MARS_TELEMETRY === '0';
}

function ledgerDir(root) {
  if (process.env.MARS_LEDGER_DIR) return path.resolve(process.env.MARS_LEDGER_DIR);
  return path.join(root || REPO_ROOT, '.mars', 'ledger');
}

// ---------------------------------------------------------------------------------------------
// Identity helpers
// ---------------------------------------------------------------------------------------------

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** ULID: 48-bit millisecond time + 80 random bits, Crockford base32 (sortable by time). */
function ulid(now = Date.now()) {
  let time = '';
  let t = now;
  for (let i = 0; i < 10; i += 1) {
    time = CROCKFORD[t % 32] + time;
    t = Math.floor(t / 32);
  }
  const bytes = crypto.randomBytes(16);
  let rand = '';
  for (let i = 0; i < 16; i += 1) rand += CROCKFORD[bytes[i] % 32];
  return time + rand;
}

function sha256(data) {
  return crypto.createHash('sha256').update(data).digest('hex');
}

function fileSha256(file) {
  try {
    return sha256(fs.readFileSync(file));
  } catch (_) {
    return null;
  }
}

/** Deterministic hex id of `len` characters from the given parts (W3C trace id = 32, span id = 16). */
function deterministicId(len, ...parts) {
  return sha256(parts.map((p) => String(p == null ? '' : p)).join('|')).slice(0, len);
}

/** Current commit of the repository, read from .git without spawning git. */
function headCommit(root) {
  try {
    const gitDir = path.join(root || REPO_ROOT, '.git');
    const head = fs.readFileSync(path.join(gitDir, 'HEAD'), 'utf8').trim();
    if (!head.startsWith('ref:')) return head.slice(0, 40);
    const ref = head.slice(4).trim();
    const loose = path.join(gitDir, ...ref.split('/'));
    if (fs.existsSync(loose)) return fs.readFileSync(loose, 'utf8').trim().slice(0, 40);
    const packed = fs.readFileSync(path.join(gitDir, 'packed-refs'), 'utf8');
    const line = packed.split(/\r?\n/).find((l) => l.endsWith(` ${ref}`));
    return line ? line.slice(0, 40) : null;
  } catch (_) {
    return null;
  }
}

function workspaceId(root) {
  return `ws_${sha256(path.resolve(root || REPO_ROOT).toLowerCase()).slice(0, 12)}`;
}

function repoRelative(file, root) {
  if (!file) return file;
  const abs = path.resolve(file);
  const rel = path.relative(root || REPO_ROOT, abs);
  if (!rel || rel.startsWith('..') || path.isAbsolute(rel)) return '<outside-workspace>';
  return rel.replace(/\\/g, '/');
}

// ---------------------------------------------------------------------------------------------
// Redaction (ported from .github/skills/04d-version-migration/scripts/lib/migration.js)
// ---------------------------------------------------------------------------------------------

const SECRET_ENV_KEY = /(password|passwd|secret|token|api[-_]?key|access[-_]?key|credential|auth)/i;
const SECRET_ASSIGNMENT = /([\w.-]*(?:password|passwd|secret|token|api[-_]?key|access[-_]?key)[\w.-]*\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;]+)/gi;

function redact(text, env = process.env) {
  if (text === null || text === undefined) return text;
  let out = String(text);
  for (const [key, value] of Object.entries(env || {})) {
    if (SECRET_ENV_KEY.test(key) && typeof value === 'string' && value.length >= 6) out = out.split(value).join('***');
  }
  out = out.replace(/(\b[a-z][a-z0-9+.-]*:\/\/)[^/\s:@]+:[^/\s@]+@/gi, '$1***:***@');
  out = out.replace(SECRET_ASSIGNMENT, '$1***');
  out = out.replace(/(Authorization\s*:\s*\w+\s+)[^\s"']+/gi, '$1***');
  return out;
}

function clip(text) {
  if (typeof text !== 'string') return text;
  const r = redact(text);
  return r.length > MAX_TEXT ? `${r.slice(0, MAX_TEXT)}…` : r;
}

/** Attributes may only hold scalars, short scalar arrays, or one level of scalar objects. */
const SECRET_ATTR_KEY = /(password|passwd|secret|token|api[-_]?key|access[-_]?key|credential|private[-_]?key)/i;

function sanitizeAttrs(attrs, depth = 0) {
  if (!attrs || typeof attrs !== 'object') return {};
  const out = {};
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined) continue;
    if (SECRET_ATTR_KEY.test(k) && v !== null && typeof v !== 'boolean') out[k] = '***';
    else if (v === null || typeof v === 'number' || typeof v === 'boolean') out[k] = v;
    else if (typeof v === 'string') out[k] = clip(v);
    else if (Array.isArray(v)) {
      out[k] = v.slice(0, 50).map((x) => (typeof x === 'string' ? clip(x) : (typeof x === 'object' && x !== null && depth < 1 ? sanitizeAttrs(x, depth + 1) : x)));
    } else if (typeof v === 'object' && depth < 1) out[k] = sanitizeAttrs(v, depth + 1);
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------------------------

const ENVELOPE_KEYS = [
  'type', 'source', 'session_id', 'run_id', 'trace_id', 'span_id', 'parent_span_id', 'links',
  'issue_ids', 'stage_id', 'attempt', 'agent_id', 'skill_id', 'script_id', 'step_kind', 'actor',
  'provenance', 'status', 'outcome', 'failure', 'inputs', 'outputs', 'code_refs', 'duration_ms', 'attrs', 'summary',
];

function normalize(event, root) {
  const e = {};
  for (const key of ENVELOPE_KEYS) if (event[key] !== undefined) e[key] = event[key];
  const record = {
    schema: SCHEMA,
    event_id: event.event_id || `evt_${ulid()}`,
    seq: event.seq == null ? null : event.seq,
    time: event.time || new Date().toISOString(),
    workspace_id: workspaceId(root),
    harness: { sha: headCommit(root) },
    ...e,
  };
  if (typeof record.summary === 'string') record.summary = clip(record.summary);
  if (record.failure && typeof record.failure === 'object') {
    record.failure = { class: record.failure.class || 'unclassified', code: record.failure.code || null, summary: clip(record.failure.summary || '') };
  }
  if (record.attrs) record.attrs = sanitizeAttrs(record.attrs);
  for (const list of ['inputs', 'outputs']) {
    if (Array.isArray(record[list])) record[list] = record[list].slice(0, 50).map((r) => ({ path: r.path, sha256: r.sha256 || null, ...(r.artifact_type ? { artifact_type: r.artifact_type } : {}) }));
  }
  if (Array.isArray(record.issue_ids)) record.issue_ids = record.issue_ids.filter(Boolean).slice(0, 100);
  return record;
}

// ---------------------------------------------------------------------------------------------
// Append with a cross-process directory lock
// ---------------------------------------------------------------------------------------------

function sleep(ms) {
  try {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
  } catch (_) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
  }
}

function acquireLock(dir, timeoutMs = 2000) {
  const lock = path.join(dir, '.lock');
  const started = Date.now();
  for (;;) {
    try {
      fs.mkdirSync(lock);
      return lock;
    } catch (err) {
      if (err.code !== 'EEXIST') return null;
      try {
        if (Date.now() - fs.statSync(lock).mtimeMs > 10000) {
          fs.rmdirSync(lock); // stale lock from a crashed writer
          continue;
        }
      } catch (_) { /* raced with another writer */ }
      if (Date.now() - started > timeoutMs) return null;
      sleep(5);
    }
  }
}

function countLines(file) {
  try {
    const text = fs.readFileSync(file, 'utf8');
    return text.split('\n').filter((l) => l.trim()).length;
  } catch (_) {
    return 0;
  }
}

/** Appends one event. Returns the written record, or null if telemetry is off or anything failed. */
function append(event, opts = {}) {
  // `force` is for witnesses that are part of a MARS control (the human decision), not optional telemetry.
  if (isDisabled() && !opts.force) return null;
  try {
    const dir = ledgerDir(opts.root);
    fs.mkdirSync(dir, { recursive: true });
    const now = new Date();
    const file = path.join(dir, `events-${now.toISOString().slice(0, 7)}.jsonl`);
    const lock = acquireLock(dir);
    try {
      let seq = null;
      if (lock) {
        const seqFile = `${file}.seq`;
        let last = 0;
        try { last = parseInt(fs.readFileSync(seqFile, 'utf8'), 10) || 0; } catch (_) { last = countLines(file); }
        seq = last + 1;
        const record = normalize({ ...event, seq, time: event.time || now.toISOString() }, opts.root);
        fs.appendFileSync(file, `${JSON.stringify(record)}\n`);
        fs.writeFileSync(seqFile, String(seq));
        return record;
      }
      // Lock timeout: still record the event, flagged, rather than lose it.
      const record = normalize({ ...event, seq, time: event.time || now.toISOString(), attrs: { ...(event.attrs || {}), lock_timeout: true } }, opts.root);
      fs.appendFileSync(file, `${JSON.stringify(record)}\n`);
      return record;
    } finally {
      if (lock) {
        try { fs.rmdirSync(lock); } catch (_) { /* ignore */ }
      }
    }
  } catch (_) {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------
// Run attribution: the hook adapter records which agent run is active per session so that
// scripts invoked from that run can attribute themselves. Attribution is "inferred", and says so.
// ---------------------------------------------------------------------------------------------

function activeDir(root) {
  return path.join(ledgerDir(root), 'active');
}

function setActiveRun(sessionId, run, root) {
  try {
    if (isDisabled() || !sessionId) return;
    fs.mkdirSync(activeDir(root), { recursive: true });
    fs.writeFileSync(path.join(activeDir(root), `${safeName(sessionId)}.json`), JSON.stringify(run));
  } catch (_) { /* ignore */ }
}

function clearActiveRun(sessionId, root) {
  try {
    if (!sessionId) return;
    fs.rmSync(path.join(activeDir(root), `${safeName(sessionId)}.json`), { force: true });
  } catch (_) { /* ignore */ }
}

function currentRun(root) {
  try {
    const sessionId = process.env.CLAUDE_CODE_SESSION_ID || process.env.MARS_SESSION_ID || null;
    if (process.env.MARS_RUN_ID) return { run_id: process.env.MARS_RUN_ID, session_id: sessionId, attribution: 'explicit' };
    if (!sessionId) return { run_id: null, session_id: null, attribution: 'none' };
    const file = path.join(activeDir(root), `${safeName(sessionId)}.json`);
    if (!fs.existsSync(file)) return { run_id: null, session_id: sessionId, attribution: 'session-only' };
    const run = JSON.parse(fs.readFileSync(file, 'utf8'));
    return { ...run, session_id: sessionId, attribution: 'inferred' };
  } catch (_) {
    return { run_id: null, session_id: null, attribution: 'none' };
  }
}

function safeName(s) {
  return String(s).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 120);
}

// ---------------------------------------------------------------------------------------------
// Script helper: one call at start, one at the end. Durations from a monotonic clock.
// ---------------------------------------------------------------------------------------------

function startScript(meta, opts = {}) {
  const t0 = process.hrtime.bigint();
  const run = currentRun(opts.root);
  const traceId = run.trace_id || deterministicId(32, workspaceId(opts.root), run.run_id || run.session_id || 'standalone');
  const spanId = deterministicId(16, traceId, meta.script_id, (meta.issue_ids || []).join(','), Date.now(), process.pid);
  const base = {
    source: { emitter: 'script', id: meta.script_id, runtime: process.env.CLAUDECODE === '1' ? 'claude-code' : 'cli' },
    session_id: run.session_id || undefined,
    run_id: run.run_id || undefined,
    trace_id: traceId,
    span_id: spanId,
    parent_span_id: run.span_id || undefined,
    agent_id: run.agent_id || undefined,
    skill_id: meta.skill_id,
    script_id: meta.script_id,
    step_kind: meta.step_kind,
    stage_id: meta.stage_id,
    issue_ids: meta.issue_ids,
    actor: { kind: 'script', id: meta.script_id },
    provenance: 'computed',
  };
  append({ ...base, type: 'script.started', status: 'started', attrs: { ...(meta.attrs || {}), run_attribution: run.attribution } }, opts);
  return {
    base,
    elapsedMs() {
      return Number((process.hrtime.bigint() - t0) / 1000000n);
    },
    emit(event) {
      return append({ ...base, ...event, attrs: { ...(event.attrs || {}) } }, opts);
    },
    complete(outcome, extra = {}) {
      return append({
        ...base,
        type: extra.failed ? 'script.failed' : 'script.completed',
        status: extra.failed ? 'failed' : 'completed',
        outcome,
        duration_ms: Number((process.hrtime.bigint() - t0) / 1000000n),
        ...extra.event,
        attrs: { ...(extra.attrs || {}), exit_code: extra.exitCode == null ? undefined : extra.exitCode },
      }, opts);
    },
  };
}

module.exports = {
  SCHEMA,
  REPO_ROOT,
  append,
  normalize,
  ulid,
  sha256,
  fileSha256,
  deterministicId,
  headCommit,
  workspaceId,
  repoRelative,
  redact,
  sanitizeAttrs,
  ledgerDir,
  setActiveRun,
  clearActiveRun,
  currentRun,
  startScript,
  isDisabled,
};
