/**
 * Mission Control HTTP API (node:http, no framework). Read endpoints are pure projections. The single
 * state-changing endpoint delegates to MARS's own decision command and never writes evidence itself.
 */
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import type { LedgerEvent, SessionInfo } from '../shared/types.js';
import type { Model } from './model.js';
import { architectureView } from './projection/architecture.js';
import { artifactContent, artifactList, lineage } from './projection/artifacts.js';

export interface AppOptions {
  staticDir?: string | null;
  heartbeatMs?: number;
  /** Extra Host header names to serve (besides loopback), e.g. when bound to a LAN address on purpose. */
  allowedHosts?: string[];
  /**
   * Per-launch secret required (X-MC-Token) to record a decision. It is printed only to the terminal
   * that started the server, inside a one-time link; local processes that did not see it cannot POST.
   */
  decisionToken?: string | null;
}

type Handler = (req: http.IncomingMessage, res: http.ServerResponse, params: Record<string, string>, url: URL) => void | Promise<void>;

const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
};

function send(res: http.ServerResponse, status: number, body: unknown, extra: Record<string, string> = {}): void {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...SECURITY_HEADERS, ...extra });
  res.end(json);
}

function error(res: http.ServerResponse, status: number, code: string, message: string, details?: unknown): void {
  send(res, status, { code, message, details: details ?? null, correlationId: crypto.randomUUID() });
}

function readBody(req: http.IncomingMessage, limit = 32 * 1024): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => {
      size += c.length;
      if (size > limit) {
        reject(new Error('BODY_TOO_LARGE'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

export function createApp(model: Model, opts: AppOptions = {}): http.Server {
  const routes: { method: string; re: RegExp; keys: string[]; h: Handler }[] = [];
  const add = (method: string, pattern: string, h: Handler) => {
    const keys: string[] = [];
    const re = new RegExp(`^${pattern.replace(/:([a-zA-Z]+)/g, (_m, k) => {
      keys.push(k);
      return '([^/]+)';
    })}$`);
    routes.push({ method, re, keys, h });
  };
  const sseClients = new Set<http.ServerResponse>();
  const recentDecisions = new Map<string, number>();

  // ---------------------------------------------------------------- read endpoints
  add('GET', '/api/v1/healthz', (_q, res) => send(res, 200, { ok: true, version: model.getVersion() }));
  add('GET', '/api/v1/session', (_q, res) => {
    const d = model.decisionsEnabled();
    const body: SessionInfo = { actor: model.actor(), authentication: 'LOCALLY_ASSERTED', role: d.enabled ? 'approver' : 'viewer', decisions: d };
    send(res, 200, body);
  });
  add('GET', '/api/v1/overview', (_q, res) => send(res, 200, model.overview()));
  add('GET', '/api/v1/issues', (_q, res) => send(res, 200, { asOf: new Date().toISOString(), issues: model.issues() }));
  add('GET', '/api/v1/issues/:id', (_q, res, p) => {
    const d = model.issue(decodeURIComponent(p.id));
    if (!d) return error(res, 404, 'ISSUE_NOT_FOUND', `No issue ${p.id} in the register or the evidence.`);
    send(res, 200, d);
  });
  add('GET', '/api/v1/issues/:id/lineage', (_q, res, p) => {
    const id = decodeURIComponent(p.id);
    if (!model.issue(id)) return error(res, 404, 'ISSUE_NOT_FOUND', `No issue ${id}.`);
    const s = model.state();
    send(res, 200, lineage(s.ev, id, s.decisions, s.findings));
  });
  add('GET', '/api/v1/runs', (_q, res) => send(res, 200, { runs: model.runs().runs, ledger: model.ledgerStatus() }));
  add('GET', '/api/v1/runs/:id', (_q, res, p) => {
    const r = model.runs().details.get(decodeURIComponent(p.id));
    if (!r) return error(res, 404, 'RUN_NOT_FOUND', `No run ${p.id}.`);
    send(res, 200, r);
  });
  add('GET', '/api/v1/events', (_q, res, _p, url) => {
    const afterRaw = Number(url.searchParams.get('after') || 0);
    const limitRaw = Number(url.searchParams.get('limit') || 500);
    if (!Number.isFinite(afterRaw) || afterRaw < 0 || !Number.isFinite(limitRaw) || limitRaw < 1) return error(res, 400, 'BAD_QUERY', '`after` must be ≥ 0 and `limit` ≥ 1.');
    const after = Math.floor(afterRaw);
    const limit = Math.min(Math.floor(limitRaw), 5000);
    model.ledger.poll();
    send(res, 200, { events: model.ledger.after(after, limit), lastSeq: model.ledger.lastSeq(), oldestSeq: model.ledger.oldestSeq() });
  });
  add('GET', '/api/v1/registry', (_q, res) => send(res, 200, model.registry()));
  add('GET', '/api/v1/registry/agents/:id', (_q, res, p) => {
    const reg = model.registry();
    const a = reg.agents.find((x) => x.id === decodeURIComponent(p.id));
    if (!a) return error(res, 404, 'AGENT_NOT_FOUND', `No agent ${p.id}.`);
    const runs = model.runs().runs.filter((r) => r.agentId === a.id).slice(0, 50);
    send(res, 200, { agent: a, skills: reg.skills.filter((s) => a.skills.includes(s.id)), runs });
  });
  add('GET', '/api/v1/registry/skills/:id', (_q, res, p) => {
    const reg = model.registry();
    const s = reg.skills.find((x) => x.id === decodeURIComponent(p.id));
    if (!s) return error(res, 404, 'SKILL_NOT_FOUND', `No skill ${p.id}.`);
    const ops = model.ledger.all().filter((e) => e.skill_id === s.id && /^(operation|gate|fix|verdict|skill)\./.test(e.type)).slice(-100);
    const md = model.ws.read(s.skillMdPath);
    send(res, 200, { skill: s, skillMd: md, recent: ops });
  });
  add('GET', '/api/v1/approvals', (_q, res) => send(res, 200, { approvals: model.issues().map((i) => model.issue(i.id)!.approval), decisions: model.decisionsEnabled() }));
  add('GET', '/api/v1/approvals/:id', (_q, res, p) => {
    const d = model.issue(decodeURIComponent(p.id));
    if (!d) return error(res, 404, 'ISSUE_NOT_FOUND', `No issue ${p.id}.`);
    send(res, 200, { approval: d.approval, issue: { id: d.id, title: d.title, severity: d.severity, priority: d.priority, rca: d.rca, blast: d.blast, codeRefs: d.codeRefs, fix: d.fix, findingList: d.findingList.filter((f) => f.stage === 'approval' || f.stage === 'plan') } });
  });
  add('GET', '/api/v1/artifacts', (_q, res) => {
    const s = model.state();
    send(res, 200, { artifacts: artifactList(s.ev, s.findings), findings: s.findings });
  });
  add('GET', '/api/v1/artifacts/content', (_q, res, _p, url) => {
    const r = artifactContent(model.ws, url.searchParams.get('path') || '');
    if ('error' in r) return error(res, r.status, r.status === 403 ? 'PATH_NOT_ALLOWED' : 'ARTIFACT_NOT_FOUND', r.error);
    send(res, 200, r);
  });
  add('GET', '/api/v1/integrity', (_q, res) => send(res, 200, { findings: model.findings() }));
  add('GET', '/api/v1/audit', (_q, res, _p, url) => send(res, 200, { items: model.audit(url.searchParams.get('issue')) }));
  add('GET', '/api/v1/architecture', (_q, res, _p, url) => {
    const s = model.state();
    const issueId = url.searchParams.get('issue');
    const issue = issueId ? model.issue(issueId) : null;
    if (issueId && !issue) return error(res, 404, 'ISSUE_NOT_FOUND', `No issue ${issueId}.`);
    send(res, 200, architectureView(s.cm, issue, { focus: url.searchParams.get('focus'), depth: Number(url.searchParams.get('depth') || 2) }, s.ev.architecture.recordedGraphCounts, model.ws.exists('.claude/skills/01c-graph-forge/.env')));
  });
  add('GET', '/api/v1/health', async (_q, res, _p, url) => send(res, 200, { checks: await model.healthChecks(url.searchParams.get('refresh') === '1') }));

  // ---------------------------------------------------------------- the one command
  add('POST', '/api/v1/approvals/:id/decisions', async (req, res, p) => {
    const id = decodeURIComponent(p.id);
    if (req.headers['x-mc-request'] !== '1' || !/application\/json/.test(String(req.headers['content-type'] || ''))) return error(res, 403, 'CSRF', 'Missing X-MC-Request header or JSON content type.');
    const origin = req.headers.origin;
    if (origin) {
      let originHost: string | null = null;
      try {
        originHost = new URL(origin).host;
      } catch {
        originHost = null; // "null" (sandboxed/file origins) or malformed: never same-origin
      }
      if (originHost !== req.headers.host) return error(res, 403, 'ORIGIN', 'Cross-origin requests are refused.');
    }
    const d = model.decisionsEnabled();
    if (!d.enabled) return error(res, 409, 'DECISIONS_DISABLED', d.reason);
    const presented = Buffer.from(String(req.headers['x-mc-token'] || ''));
    const expected = Buffer.from(String(opts.decisionToken || ''));
    if (!expected.length || presented.length !== expected.length || !crypto.timingSafeEqual(presented, expected)) {
      return error(res, 403, 'TOKEN_REQUIRED', 'Recording a decision needs this launch\'s token. Open Mission Control from the link printed in the terminal that started it.');
    }
    let body: { decision?: string; rationale?: string; expectedSha256?: string; confirmIssueId?: string; idempotencyKey?: string };
    try {
      body = JSON.parse(await readBody(req));
    } catch (e) {
      return error(res, (e as Error).message === 'BODY_TOO_LARGE' ? 413 : 400, 'BAD_REQUEST', 'Body must be JSON under 32 KB.');
    }
    if (body.confirmIssueId !== id) return error(res, 422, 'CONFIRMATION_REQUIRED', `Type ${id} to confirm.`);
    // Throttle only after a decision was actually recorded, so a corrected resubmission is not blocked.
    const last = recentDecisions.get(id) || 0;
    if (Date.now() - last < 3000) return error(res, 429, 'TOO_FAST', 'A decision for this issue was just recorded.');
    const actor = model.actor();
    if (!actor) return error(res, 422, 'NO_ACTOR', 'Cannot determine the local user; set MC_ACTOR.');
    const args = [model.ws.resolve('.claude/scripts/record-decision.js'), '--root', model.ws.root, '--issue', id, '--decision', String(body.decision || ''), '--expected-sha256', String(body.expectedSha256 || ''),
      '--actor', actor, '--rationale', String(body.rationale || ''), '--channel', 'mission-control', '--auth', 'LOCALLY_ASSERTED', '--json'];
    execFile(process.execPath, args, { cwd: model.ws.root, timeout: 15000, windowsHide: true, env: { ...process.env, MC_DECISION_TOKEN: String(opts.decisionToken) } }, (_err, stdout) => {
      let out: { ok: boolean; code?: string; message?: string } = { ok: false, code: 'INTERNAL', message: 'No output from record-decision.js' };
      try {
        out = JSON.parse(String(stdout || '').trim().split('\n').pop() || '{}');
      } catch {
        /* keep default */
      }
      if (!out.ok) {
        const status = out.code === 'PLAN_CHANGED' || out.code === 'NOT_PROPOSED' || out.code === 'BUSY' ? 409 : out.code === 'VALIDATION' ? 422 : out.code === 'AGENT_SESSION_REFUSED' ? 403 : out.code === 'NOT_FOUND' ? 404 : 500;
        return error(res, status, out.code || 'INTERNAL', out.message || 'Decision refused.');
      }
      recentDecisions.set(id, Date.now());
      model.invalidate(`decision:${id}`);
      send(res, 201, { ...out, next: 'Recorded. This does not start the Fixer: an operator runs 04_fix-generator Stage 2, which re-checks the plan Status.' });
    });
  });

  // ---------------------------------------------------------------- live stream
  add('GET', '/api/v1/events/stream', (req, res, _p, url) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive', 'X-Accel-Buffering': 'no', ...SECURITY_HEADERS });
    model.ledger.poll();
    const header = req.headers['last-event-id'];
    const fromQuery = url.searchParams.get('after');
    const cursor = Number(header ?? fromQuery ?? model.ledger.lastSeq());
    const write = (event: string, data: unknown, id?: number) => {
      res.write(`${id != null ? `id: ${id}\n` : ''}event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };
    res.write('retry: 2000\n\n');
    const missed = Number.isFinite(cursor) ? model.ledger.after(cursor, 5000) : [];
    const gap = Number.isFinite(cursor) && cursor > 0 && cursor < model.ledger.oldestSeq() - 1;
    write('hello', { version: model.getVersion(), lastSeq: model.ledger.lastSeq(), replayed: missed.length, replayFrom: cursor, gap, serverTime: new Date().toISOString() });
    for (const e of missed) write('event', e, e.gseq);
    sseClients.add(res);
    const onEvents = (evs: LedgerEvent[]) => {
      for (const e of evs) write('event', e, e.gseq);
    };
    const onSnapshot = (s: { version: number; reason: string }) => write('snapshot', s);
    model.on('events', onEvents);
    model.on('snapshot', onSnapshot);
    const hb = setInterval(() => write('heartbeat', { serverTime: new Date().toISOString(), lastSeq: model.ledger.lastSeq(), version: model.getVersion() }), opts.heartbeatMs ?? 15000);
    req.on('close', () => {
      clearInterval(hb);
      model.off('events', onEvents);
      model.off('snapshot', onSnapshot);
      sseClients.delete(res);
    });
  });

  // ---------------------------------------------------------------- static web app
  const staticDir = opts.staticDir && fs.existsSync(opts.staticDir) ? path.resolve(opts.staticDir) : null;
  const TYPES: Record<string, string> = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.png': 'image/png', '.ico': 'image/x-icon', '.json': 'application/json' };
  const serveStatic = (res: http.ServerResponse, pathname: string): boolean => {
    if (!staticDir) return false;
    let file = path.resolve(staticDir, `.${decodeURIComponent(pathname)}`);
    const rel = path.relative(staticDir, file);
    if (rel.startsWith('..') || path.isAbsolute(rel)) return false;
    // Source maps are not served unless explicitly asked for (they expose the full source tree).
    if (file.endsWith('.map') && process.env.MC_SOURCEMAPS !== '1') return false;
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(staticDir, 'index.html');
    if (!fs.existsSync(file)) return false;
    const ext = path.extname(file);
    res.writeHead(200, { 'Content-Type': TYPES[ext] || 'application/octet-stream', 'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=31536000, immutable', ...SECURITY_HEADERS });
    fs.createReadStream(file).pipe(res);
    return true;
  };

  const allowedHosts = new Set(['127.0.0.1', 'localhost', '[::1]', ...(opts.allowedHosts || [])]);
  const server = http.createServer(async (req, res) => {
    // DNS-rebinding guard: a page on another domain that resolves to 127.0.0.1 is same-origin to the
    // browser, so the Origin check alone cannot stop it. Only loopback Host headers are served.
    const hostName = String(req.headers.host || '').replace(/:\d+$/, '').toLowerCase();
    if (!allowedHosts.has(hostName)) return error(res, 421, 'HOST_NOT_ALLOWED', `Host "${hostName}" is not served. Mission Control only answers on loopback addresses.`);
    let url: URL;
    try {
      url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    } catch {
      return error(res, 400, 'BAD_URL', 'Malformed request URL.');
    }
    // HEAD is answered like GET (node:http omits the body); never for the event stream.
    const method = req.method === 'HEAD' && url.pathname !== '/api/v1/events/stream' ? 'GET' : req.method;
    try {
      for (const r of routes) {
        if (r.method !== method) continue;
        const m = r.re.exec(url.pathname);
        if (!m) continue;
        const params: Record<string, string> = {};
        r.keys.forEach((k, i) => (params[k] = m[i + 1]));
        await r.h(req, res, params, url);
        return;
      }
      if (url.pathname.startsWith('/api/')) return error(res, 404, 'NOT_FOUND', `No route ${req.method} ${url.pathname}`);
      if (method === 'GET' && serveStatic(res, url.pathname)) return;
      error(res, 404, 'NOT_FOUND', 'Not found');
    } catch (err) {
      if (err instanceof URIError) {
        if (!res.headersSent) error(res, 400, 'BAD_URL', 'Malformed percent-encoding in the request path.');
        else res.end();
        return;
      }
      if (!res.headersSent) error(res, 500, 'INTERNAL', (err as Error).message);
      else res.end();
    }
  });
  server.on('close', () => {
    for (const c of sseClients) c.end();
  });
  return server;
}
