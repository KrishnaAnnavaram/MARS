#!/usr/bin/env node
'use strict';
/**
 * Claude Code hook adapter → MARS event ledger (mars.event/1).
 *
 * Configured in .claude/settings.json for SessionStart, SessionEnd, SubagentStart, SubagentStop,
 * PreToolUse, PostToolUse, PostToolUseFailure and Notification. It reads the hook's stdin JSON and
 * records ONLY allow-listed facts: event kind, agent type, tool name, MARS script id and sanitized
 * arguments, repo-relative file paths and their sha256, durations and exit status. It never records
 * prompts, model output, tool output, file contents or full shell commands.
 *
 * Evidence guard (PreToolUse on Write/Edit/MultiEdit/NotebookEdit):
 *   MARS_EVIDENCE_GUARD=observe (default) — records `guard.would_deny` when an agent edits rendered
 *                                            evidence, the register or decision records; allows it.
 *   MARS_EVIDENCE_GUARD=enforce            — denies the edit (renderers are unaffected: they write
 *                                            through Node, not through the Write/Edit tools).
 *   MARS_EVIDENCE_GUARD=off                — no guard.
 *
 * This script must never break a session: every path exits 0, and errors are swallowed.
 */
const fs = require('fs');
const path = require('path');

let ledger;
let classify;
try {
  ledger = require('./ledger');
  classify = require('./classify');
} catch (_) {
  process.exit(0);
}

const WRITE_TOOLS = new Set(['Write', 'Edit', 'MultiEdit', 'NotebookEdit']);

// A JS runtime in command position (start, after ; & | ( ` " ' or $( , optionally behind sudo/env and
// VAR=value prefixes) whose same command segment names the decision script. Mentions of the file
// (grep, find -name "*.tsx" … record-decision.js) do not match.
const RUNS_DECISION_COMMAND = /(?:^|[;&|(`"']|\$\()\s*(?:sudo\s+)?(?:env(?:\s+-\S+(?:\s+[A-Za-z_]\w*)?)*\s+)?(?:[A-Za-z_]\w*=\S*\s+)*(?:node|nodejs|bun|deno|tsx|npx\s+tsx)(?:\.exe)?\s[^;&|\n]*record-decision(?:\.js)?\b/i;

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch (_) {
    return '';
  }
}

function projectRoot(input) {
  const fromEnv = process.env.CLAUDE_PROJECT_DIR;
  if (fromEnv && fs.existsSync(fromEnv)) return path.resolve(fromEnv);
  return ledger.REPO_ROOT || path.resolve(input.cwd || process.cwd());
}

function pendingDir(root) {
  return path.join(ledger.ledgerDir(root), 'pending');
}

function pendingKey(input) {
  if (input.tool_use_id) return String(input.tool_use_id).replace(/[^A-Za-z0-9_-]/g, '_');
  return ledger.deterministicId(24, input.session_id, input.tool_name, JSON.stringify(input.tool_input || {}));
}

function rememberStart(root, input, extra) {
  try {
    fs.mkdirSync(pendingDir(root), { recursive: true });
    fs.writeFileSync(path.join(pendingDir(root), `${pendingKey(input)}.json`), JSON.stringify({ t: Date.now(), ...extra }));
  } catch (_) { /* ignore */ }
}

function recallStart(root, input) {
  try {
    const file = path.join(pendingDir(root), `${pendingKey(input)}.json`);
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    fs.rmSync(file, { force: true });
    return data;
  } catch (_) {
    return null;
  }
}

/** Drop start markers of this session (and any older than a day): their completion will never arrive. */
function purgePending(root, sessionId) {
  try {
    const dir = pendingDir(root);
    for (const f of fs.readdirSync(dir)) {
      const file = path.join(dir, f);
      try {
        const data = JSON.parse(fs.readFileSync(file, 'utf8'));
        if ((sessionId && data.session_id === sessionId) || Date.now() - (data.t || 0) > 24 * 3600 * 1000) fs.rmSync(file, { force: true });
      } catch (_) { fs.rmSync(file, { force: true }); }
    }
  } catch (_) { /* ignore */ }
}

/** Which agent run this hook belongs to. Inside a subagent, Claude Code supplies agent_id/agent_type. */
function runContext(root, input) {
  const runtimeAgentId = input.agent_id || null;
  if (runtimeAgentId) {
    try {
      const f = path.join(ledger.ledgerDir(root), 'active', `agent-${String(runtimeAgentId).replace(/[^A-Za-z0-9_.-]/g, '_')}.json`);
      if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf8'));
    } catch (_) { /* ignore */ }
  }
  return { run_id: input.session_id ? `session_${String(input.session_id).slice(0, 36)}` : null, agent_id: input.agent_type || null, kind: runtimeAgentId ? 'subagent' : 'session' };
}

function base(root, input, ctx) {
  const traceId = ctx.trace_id || ledger.deterministicId(32, ledger.workspaceId(root), ctx.run_id || input.session_id || 'session');
  return {
    source: { emitter: 'hook', id: input.hook_event_name, runtime: 'claude-code' },
    session_id: input.session_id || undefined,
    run_id: ctx.run_id || undefined,
    trace_id: traceId,
    agent_id: ctx.agent_id || input.agent_type || undefined,
  };
}

function isMarsAgent(name) {
  return typeof name === 'string' && /^0\d_[a-z0-9-]+$/i.test(name);
}

function relPath(root, file) {
  if (!file) return null;
  const abs = path.isAbsolute(file) ? file : path.resolve(root, file);
  return ledger.repoRelative(abs, root);
}

function guardMode() {
  const v = String(process.env.MARS_EVIDENCE_GUARD || 'observe').toLowerCase();
  return ['off', 'observe', 'enforce'].includes(v) ? v : 'observe';
}

function parseReported(text) {
  // MARS gate/score scripts print one summary line per issue: "ISSUE-003 — FAIL (complete)",
  // "ISSUE-003 — score 60/100 (threshold 90) -> Blocked". We keep only those structured facts.
  const out = [];
  const re = /^(ISSUE-[A-Za-z0-9-]+|SAMPLE-[A-Za-z0-9-]+) — (?:(PASS|FAIL|REFUSED)\b(?: \(([a-z-]+)\))?|score (\d+)\/100 \(threshold (\d+)\) -> (Cleared|Blocked))/gm;
  let m;
  const s = String(text || '');
  while ((m = re.exec(s)) && out.length < 50) {
    out.push(m[2] ? { issue_id: m[1], result: m[2], stage: m[3] || null } : { issue_id: m[1], score: Number(m[4]), threshold: Number(m[5]), decision: m[6] });
  }
  return out;
}

function responseText(resp) {
  if (!resp) return '';
  if (typeof resp === 'string') return resp;
  return [resp.stdout, resp.stderr, resp.output].filter((x) => typeof x === 'string').join('\n');
}

function exitCodeOf(resp) {
  if (!resp || typeof resp !== 'object') return null;
  for (const k of ['exit_code', 'exitCode', 'code', 'returnCode']) if (Number.isInteger(resp[k])) return resp[k];
  return null;
}

function handle(input) {
  const root = projectRoot(input);
  const evt = input.hook_event_name;
  const ctx = runContext(root, input);
  const b = base(root, input, ctx);

  switch (evt) {
    case 'SessionStart':
      ledger.append({ ...b, type: 'session.started', status: 'started', actor: { kind: 'system', id: 'claude-code' }, attrs: { start_type: input.source || null, permission_mode: input.permission_mode || null } }, { root });
      return null;
    case 'SessionEnd':
      ledger.append({ ...b, type: 'session.ended', status: 'completed', actor: { kind: 'system', id: 'claude-code' }, attrs: { reason: input.reason || null } }, { root });
      purgePending(root, input.session_id);
      return null;
    case 'SubagentStart': {
      const agentType = input.agent_type || input.subagent_type || 'unknown';
      const runId = `run_${ledger.ulid()}`;
      const traceId = ledger.deterministicId(32, ledger.workspaceId(root), runId);
      const run = { run_id: runId, agent_id: agentType, runtime_agent_id: input.agent_id || null, trace_id: traceId, span_id: ledger.deterministicId(16, traceId, 'root'), started: Date.now(), mars_agent: isMarsAgent(agentType) };
      if (input.agent_id) {
        try {
          const dir = path.join(ledger.ledgerDir(root), 'active');
          fs.mkdirSync(dir, { recursive: true });
          fs.writeFileSync(path.join(dir, `agent-${String(input.agent_id).replace(/[^A-Za-z0-9_.-]/g, '_')}.json`), JSON.stringify(run));
        } catch (_) { /* ignore */ }
      }
      if (input.session_id && run.mars_agent) ledger.setActiveRun(input.session_id, run, root);
      ledger.append({ ...b, run_id: runId, trace_id: traceId, span_id: run.span_id, agent_id: agentType, type: 'agent_run.started', status: 'started', actor: { kind: 'agent', id: agentType }, provenance: 'ai_authored', attrs: { mars_agent: run.mars_agent, runtime_agent_id: input.agent_id || null } }, { root });
      return null;
    }
    case 'SubagentStop': {
      const runFile = input.agent_id ? path.join(ledger.ledgerDir(root), 'active', `agent-${String(input.agent_id).replace(/[^A-Za-z0-9_.-]/g, '_')}.json`) : null;
      let run = null;
      try { run = runFile && fs.existsSync(runFile) ? JSON.parse(fs.readFileSync(runFile, 'utf8')) : null; } catch (_) { run = null; }
      const agentType = (run && run.agent_id) || input.agent_type || 'unknown';
      ledger.append({ ...b, run_id: run ? run.run_id : b.run_id, trace_id: run ? run.trace_id : b.trace_id, span_id: run ? run.span_id : undefined, agent_id: agentType, type: 'agent_run.completed', status: 'completed', actor: { kind: 'agent', id: agentType }, provenance: 'ai_authored', duration_ms: run ? Date.now() - run.started : undefined, attrs: { mars_agent: isMarsAgent(agentType) } }, { root });
      try { if (runFile) fs.rmSync(runFile, { force: true }); } catch (_) { /* ignore */ }
      if (input.session_id && run && run.mars_agent) ledger.clearActiveRun(input.session_id, root);
      return null;
    }
    case 'Notification': {
      const kind = input.notification_type || (/permission/i.test(input.message || '') ? 'permission_prompt' : 'notification');
      ledger.append({ ...b, type: 'human.waiting', status: 'waiting', actor: { kind: 'system', id: 'claude-code' }, attrs: { kind } }, { root });
      return null;
    }
    case 'PreToolUse':
      return preTool(root, input, b);
    case 'PostToolUse':
    case 'PostToolUseFailure':
      postTool(root, input, b, evt === 'PostToolUseFailure');
      return null;
    default:
      return null;
  }
}

function preTool(root, input, b) {
  const tool = input.tool_name;
  const ti = input.tool_input || {};
  if (tool === 'Skill' && ti.skill) {
    ledger.append({ ...b, type: 'skill.loaded', status: 'completed', skill_id: String(ti.skill), actor: { kind: 'agent', id: b.agent_id || 'session' }, attrs: { via: 'skill-tool' } }, { root });
    return null;
  }
  if (tool === 'Read' && ti.file_path) {
    const rel = relPath(root, ti.file_path);
    const m = /^(?:\.claude|\.github)\/skills\/([0-9a-z-]+)\/SKILL\.md$/.exec(rel || '');
    if (m) ledger.append({ ...b, type: 'skill.loaded', status: 'completed', skill_id: m[1], actor: { kind: 'agent', id: b.agent_id || 'session' }, attrs: { via: 'read' } }, { root });
    return null;
  }
  if (tool === 'Bash' || tool === 'PowerShell') {
    const cmd = ti.command || '';
    // The plan decision is reserved for a human. An agent (or any Claude session) must never run the
    // decision command, whatever the guard mode: unsetting CLAUDECODE inside a tool call would
    // otherwise let it mint a human-attributed decision record.
    if (RUNS_DECISION_COMMAND.test(cmd)) {
      ledger.append({ ...b, type: 'guard.denied', status: 'refused', actor: { kind: 'agent', id: b.agent_id || 'session' }, attrs: { tool, rule: 'agent-ran-decision-command' } }, { root });
      return {
        hookSpecificOutput: {
          hookEventName: 'PreToolUse',
          permissionDecision: 'deny',
          permissionDecisionReason: 'MARS: plan decisions (record-decision.js) are reserved for a human. Ask the user to run it from their own terminal, or to record the decision in Mission Control.',
        },
      };
    }
    const background = Boolean(ti.run_in_background);
    const c = classify.classifyCommand(cmd, input.cwd);
    if (c) {
      const spanId = ledger.deterministicId(16, b.trace_id, pendingKey(input));
      rememberStart(root, input, { span_id: spanId, script_id: c.script_id, session_id: input.session_id || null });
      ledger.append({ ...b, span_id: spanId, type: 'operation.started', status: 'started', skill_id: c.skill_id || undefined, script_id: c.script_id, step_kind: c.step_kind, stage_id: c.stage_id || undefined, issue_ids: c.issue_ids, actor: { kind: 'agent', id: b.agent_id || 'session' }, provenance: 'computed', attrs: { tool, scope: c.scope, args: c.args, script_runtime: c.runtime, background: background || undefined } }, { root });
    } else {
      const cc = classify.commandClass(cmd);
      const spanId = ledger.deterministicId(16, b.trace_id, pendingKey(input));
      rememberStart(root, input, { span_id: spanId, session_id: input.session_id || null });
      ledger.append({ ...b, span_id: spanId, type: 'operation.started', status: 'started', actor: { kind: 'agent', id: b.agent_id || 'session' }, attrs: { tool, ...cc, background: background || undefined } }, { root });
    }
    return null;
  }
  if (WRITE_TOOLS.has(tool)) {
    const rel = relPath(root, ti.file_path || ti.notebook_path);
    const cls = classify.classifyPath(rel);
    // Paths of non-MARS files are never persisted, not even in the transient start marker.
    rememberStart(root, input, { area: cls.area, path: cls.area === 'other' || cls.area === 'source' ? undefined : rel, session_id: input.session_id || null });
    if (cls.protected || cls.area === 'decision' || cls.area === 'register') {
      const mode = guardMode();
      if (mode !== 'off') {
        ledger.append({ ...b, type: mode === 'enforce' ? 'guard.denied' : 'guard.would_deny', status: mode === 'enforce' ? 'refused' : 'completed', issue_ids: cls.issue_id ? [cls.issue_id] : undefined, stage_id: cls.stage_id || undefined, actor: { kind: 'agent', id: b.agent_id || 'session' }, attrs: { tool, path: rel, artifact_type: cls.artifact_type, rule: 'agent-edit-of-rendered-evidence' } }, { root });
      }
      if (mode === 'enforce') {
        return {
          hookSpecificOutput: {
            hookEventName: 'PreToolUse',
            permissionDecision: 'deny',
            permissionDecisionReason: `MARS evidence guard: ${rel} is rendered pipeline evidence (or a decision record / the issue register). Only MARS render scripts and the human decision command may write it. Re-run the owning stage's renderer instead.`,
          },
        };
      }
    }
    return null;
  }
  return null;
}

function postTool(root, input, b, failed) {
  const tool = input.tool_name;
  const ti = input.tool_input || {};
  const started = recallStart(root, input);
  const duration = started ? Date.now() - started.t : undefined;
  if (tool === 'Bash' || tool === 'PowerShell') {
    const c = classify.classifyCommand(ti.command || '', input.cwd);
    const exitCode = exitCodeOf(input.tool_response);
    const text = responseText(input.tool_response);
    const interrupted = Boolean(input.tool_response && input.tool_response.interrupted);
    const reported = c ? parseReported(text) : [];
    // A MARS gate/score script that printed its per-issue result lines did its job even when it exits
    // non-zero (gates exit 1 when a gate FAILs). That is a gate outcome, not a tool failure.
    const status = interrupted ? 'failed' : reported.length ? 'completed' : failed || (exitCode != null && exitCode !== 0) ? 'failed' : 'completed';
    ledger.append({
      ...b,
      span_id: started ? started.span_id : undefined,
      type: status === 'failed' ? 'operation.failed' : 'operation.completed',
      status,
      skill_id: c ? c.skill_id || undefined : undefined,
      script_id: c ? c.script_id : undefined,
      step_kind: c ? c.step_kind : undefined,
      stage_id: c && c.stage_id ? c.stage_id : undefined,
      issue_ids: c ? Array.from(new Set([...(c.issue_ids || []), ...reported.map((r) => r.issue_id)])) : undefined,
      actor: { kind: 'agent', id: b.agent_id || 'session' },
      provenance: c ? 'computed' : undefined,
      duration_ms: duration,
      failure: status === 'failed' ? { class: interrupted ? 'infrastructure' : 'unclassified', code: interrupted ? 'INTERRUPTED' : (exitCode != null ? `EXIT_${exitCode}` : 'TOOL_FAILURE'), summary: '' } : undefined,
      attrs: { tool, exit_code: exitCode, reported: reported.length ? reported : undefined, ...(c ? { scope: c.scope, args: c.args } : classify.commandClass(ti.command || '')) },
    }, { root });
    return;
  }
  if (WRITE_TOOLS.has(tool)) {
    const rel = relPath(root, ti.file_path || ti.notebook_path);
    const cls = classify.classifyPath(rel);
    if (cls.area === 'other' || cls.area === 'source') {
      // Writes outside MARS areas are recorded by area only: no path, no content.
      ledger.append({ ...b, type: failed ? 'operation.failed' : 'artifact.written', status: failed ? 'failed' : 'completed', actor: { kind: 'agent', id: b.agent_id || 'session' }, provenance: 'ai_authored', duration_ms: duration, attrs: { tool, area: cls.area } }, { root });
      return;
    }
    const abs = path.resolve(root, rel);
    ledger.append({
      ...b,
      type: failed ? 'operation.failed' : 'artifact.written',
      status: failed ? 'failed' : 'completed',
      stage_id: cls.stage_id || undefined,
      issue_ids: cls.issue_id ? [cls.issue_id] : undefined,
      step_kind: cls.area === 'intermediate' && cls.artifact_type === 'judgement' ? 'author' : (cls.area === 'intermediate' && cls.artifact_type === 'draft_diff' ? 'author' : undefined),
      actor: { kind: 'agent', id: b.agent_id || 'session' },
      provenance: 'ai_authored',
      duration_ms: duration,
      outputs: [{ path: rel, sha256: failed ? null : ledger.fileSha256(abs), artifact_type: cls.artifact_type }],
      attrs: { tool, area: cls.area },
    }, { root });
  }
}

function main() {
  let input = {};
  try {
    const raw = readStdin();
    input = raw ? JSON.parse(raw) : {};
  } catch (_) {
    process.exit(0);
  }
  let output = null;
  try {
    output = handle(input);
  } catch (_) {
    output = null;
  }
  if (output) process.stdout.write(JSON.stringify(output));
  process.exit(0);
}

if (require.main === module) main();

module.exports = { handle, parseReported, guardMode };
