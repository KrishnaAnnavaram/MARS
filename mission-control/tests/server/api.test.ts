/**
 * HTTP API and SSE stream, against a fixture workspace on an ephemeral port.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import type http from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../server/app';
import { Model } from '../../server/model';
import { ev, makeFixture, setPlanStatus, type Fixture } from '../helpers/fixture';

interface Ctx { f: Fixture; model: Model; server: http.Server; base: string }
const TOKEN = 'test-launch-token-0123456789abcdef';

async function boot(opts: { decisions?: boolean } = {}): Promise<Ctx> {
  const f = makeFixture();
  setPlanStatus(f, 'ISSUE-001', 'Proposed');
  const model = new Model({ root: f.root, ledgerDir: f.ledgerDir, watch: false, enableDecisions: opts.decisions, allowDecisionsInAgentSession: opts.decisions, actor: 'Dana Reviewer' });
  model.start();
  const server = createApp(model, { staticDir: null, heartbeatMs: 200, decisionToken: TOKEN });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return { f, model, server, base };
}
async function shutdown(c: Ctx): Promise<void> {
  c.model.stop();
  c.server.closeAllConnections?.();
  await new Promise((r) => c.server.close(() => r(null)));
  c.f.cleanup();
}

/** Minimal SSE reader over fetch: collects parsed messages until `until` returns true or timeout. */
async function sse(url: string, headers: Record<string, string>, until: (msgs: { event: string; id: string | null; data: unknown }[]) => boolean, onOpen?: () => void, timeoutMs = 8000) {
  const ac = new AbortController();
  const res = await fetch(url, { headers: { Accept: 'text/event-stream', ...headers }, signal: ac.signal });
  expect(res.headers.get('content-type')).toMatch(/text\/event-stream/);
  const reader = res.body!.getReader();
  const dec = new TextDecoder();
  const msgs: { event: string; id: string | null; data: unknown }[] = [];
  let buf = '';
  let opened = false;
  const deadline = Date.now() + timeoutMs;
  try {
    while (Date.now() < deadline) {
      const chunk = await Promise.race([reader.read(), new Promise<{ done: true; value: undefined }>((r) => setTimeout(() => r({ done: true, value: undefined }), Math.max(1, deadline - Date.now())))]);
      if (chunk.done) break;
      buf += dec.decode(chunk.value, { stream: true });
      let i: number;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        const m = { event: 'message', id: null as string | null, data: null as unknown };
        let data = '';
        for (const line of block.split('\n')) {
          if (line.startsWith('event: ')) m.event = line.slice(7);
          else if (line.startsWith('id: ')) m.id = line.slice(4);
          else if (line.startsWith('data: ')) data += line.slice(6);
        }
        if (!data) continue;
        m.data = JSON.parse(data);
        msgs.push(m);
        if (!opened && m.event === 'hello') {
          opened = true;
          onOpen?.();
        }
      }
      if (until(msgs)) break;
    }
  } finally {
    ac.abort();
  }
  return msgs;
}

describe('read API', () => {
  let c: Ctx;
  beforeAll(async () => { c = await boot(); });
  afterAll(async () => { await shutdown(c); });

  it.each(['healthz', 'session', 'overview', 'issues', 'issues/ISSUE-003', 'issues/ISSUE-003/lineage', 'runs', 'events', 'registry', 'registry/agents/04_fix-generator', 'registry/skills/06a-qa-runner', 'approvals', 'approvals/ISSUE-001', 'artifacts', 'integrity', 'audit', 'audit?issue=ISSUE-002', 'architecture', 'architecture?issue=ISSUE-003'])('GET /api/v1/%s → 200 JSON with security headers', async (p) => {
    const r = await fetch(`${c.base}/api/v1/${p}`);
    expect(r.status).toBe(200);
    expect(r.headers.get('content-type')).toMatch(/application\/json/);
    expect(r.headers.get('x-content-type-options')).toBe('nosniff');
    expect(r.headers.get('content-security-policy')).toMatch(/frame-ancestors 'none'/);
    await r.json();
  });

  it('typed 404s for unknown issues, runs, agents and routes', async () => {
    for (const p of ['issues/NOPE-1', 'runs/nope', 'registry/agents/nope', 'architecture?issue=NOPE', 'nothing-here']) {
      const r = await fetch(`${c.base}/api/v1/${p}`);
      expect(r.status, p).toBe(404);
      const body = await r.json();
      expect(typeof body.code).toBe('string');
      expect(typeof body.correlationId).toBe('string');
    }
  });

  it('serves only allow-listed evidence paths (no traversal, no source files, no secrets)', async () => {
    for (const p of ['../../.git/config', '.claude/settings.local.json', 'src/employee-service/pom.xml', '.claude/skills/01c-graph-forge/.env', 'docs/agent_output/../../package.json']) {
      const r = await fetch(`${c.base}/api/v1/artifacts/content?path=${encodeURIComponent(p)}`);
      expect(r.status, p).toBe(403);
    }
    const ok = await fetch(`${c.base}/api/v1/artifacts/content?path=${encodeURIComponent('docs/agent_output/07-ship/verdict_ISSUE-003.md')}`);
    expect(ok.status).toBe(200);
    const body = await ok.json();
    expect(body.kind).toBe('markdown');
    expect(body.sha256).toBe(crypto.createHash('sha256').update(fs.readFileSync(c.f.file('docs/agent_output/07-ship/verdict_ISSUE-003.md'))).digest('hex'));
    const missing = await fetch(`${c.base}/api/v1/artifacts/content?path=${encodeURIComponent('docs/agent_output/07-ship/verdict_ISSUE-999.md')}`);
    expect(missing.status).toBe(404);
  });

  it('refuses non-loopback Host headers (DNS-rebinding guard)', async () => {
    const http = await import('node:http');
    const u = new URL(c.base);
    const status = await new Promise<number>((resolve, reject) => {
      const req = http.request({ host: u.hostname, port: u.port, path: '/api/v1/overview', headers: { Host: `evil.example:${u.port}` } }, (res) => {
        res.resume();
        resolve(res.statusCode || 0);
      });
      req.on('error', reject);
      req.end();
    });
    expect(status).toBe(421);
  });

  it('decisions are disabled by default and the API says so', async () => {
    const s = await (await fetch(`${c.base}/api/v1/session`)).json();
    expect(s.decisions.enabled).toBe(false);
    const r = await fetch(`${c.base}/api/v1/approvals/ISSUE-001/decisions`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-MC-Request': '1' }, body: '{}' });
    expect(r.status).toBe(409);
    expect((await r.json()).code).toBe('DECISIONS_DISABLED');
  });
});

describe('decision API (enabled on a temp workspace)', () => {
  let c: Ctx;
  beforeAll(async () => {
    process.env.MARS_DECISION_TEST_MODE = '1';
    c = await boot({ decisions: true });
  });
  afterAll(async () => {
    delete process.env.MARS_DECISION_TEST_MODE;
    await shutdown(c);
  });
  const post = (body: unknown, headers: Record<string, string> = { 'Content-Type': 'application/json', 'X-MC-Request': '1', 'X-MC-Token': TOKEN }) =>
    fetch(`${c.base}/api/v1/approvals/ISSUE-001/decisions`, { method: 'POST', headers, body: JSON.stringify(body) });

  it('requires the launch token: a local process that did not see the terminal link cannot decide', async () => {
    const r = await post({ decision: 'APPROVED', rationale: 'Looks right to me overall.', expectedSha256: 'a'.repeat(64), confirmIssueId: 'ISSUE-001' }, { 'Content-Type': 'application/json', 'X-MC-Request': '1' });
    expect(r.status).toBe(403);
    expect((await r.json()).code).toBe('TOKEN_REQUIRED');
    const wrong = await post({ decision: 'APPROVED', rationale: 'Looks right to me overall.', expectedSha256: 'a'.repeat(64), confirmIssueId: 'ISSUE-001' }, { 'Content-Type': 'application/json', 'X-MC-Request': '1', 'X-MC-Token': `${TOKEN.slice(0, -1)}x` });
    expect(wrong.status).toBe(403);
  });

  it('requires the CSRF header, JSON, same origin and typed confirmation', async () => {
    expect((await post({}, { 'Content-Type': 'application/json', 'X-MC-Token': TOKEN })).status).toBe(403);
    expect((await post({}, { 'Content-Type': 'text/plain', 'X-MC-Request': '1', 'X-MC-Token': TOKEN })).status).toBe(403);
    expect((await post({}, { 'Content-Type': 'application/json', 'X-MC-Request': '1', 'X-MC-Token': TOKEN, Origin: 'http://evil.example' })).status).toBe(403);
    expect((await post({}, { 'Content-Type': 'application/json', 'X-MC-Request': '1', 'X-MC-Token': TOKEN, Origin: 'null' })).status).toBe(403);
    const r = await post({ decision: 'APPROVED', rationale: 'Looks right to me overall.', expectedSha256: 'a'.repeat(64), confirmIssueId: 'ISSUE-00' });
    expect(r.status).toBe(422);
  });

  it('a stale plan hash is refused with 409 PLAN_CHANGED', async () => {
    const r = await post({ decision: 'APPROVED', rationale: 'Looks right to me overall.', expectedSha256: 'a'.repeat(64), confirmIssueId: 'ISSUE-001' });
    expect(r.status).toBe(409);
    expect((await r.json()).code).toBe('PLAN_CHANGED');
  });

  it('records a decision, then refuses a rapid duplicate, then refuses a re-decision', async () => {
    await new Promise((r) => setTimeout(r, 3100));
    const ap = await (await fetch(`${c.base}/api/v1/approvals/ISSUE-001`)).json();
    expect(ap.approval.state).toBe('pending');
    expect(ap.approval.actions.allowed).toBe(true);
    const r = await post({ decision: 'APPROVED', rationale: 'Parameterised query approach is right.', expectedSha256: ap.approval.planSha256, confirmIssueId: 'ISSUE-001' });
    expect(r.status).toBe(201);
    const body = await r.json();
    expect(body.next).toMatch(/does not start the Fixer/);
    const dup = await post({ decision: 'APPROVED', rationale: 'Parameterised query approach is right.', expectedSha256: ap.approval.planSha256, confirmIssueId: 'ISSUE-001' });
    expect(dup.status).toBe(429);
    await new Promise((res) => setTimeout(res, 3100));
    const again = await post({ decision: 'REJECTED', rationale: 'Second thoughts on this one.', expectedSha256: ap.approval.planSha256, confirmIssueId: 'ISSUE-001' });
    expect(again.status).toBe(409);
    await new Promise((res) => setTimeout(res, 400));
    const after = await (await fetch(`${c.base}/api/v1/approvals/ISSUE-001`)).json();
    expect(after.approval.state).toBe('approved');
    expect(after.approval.decisions[0].actor).toBe('Dana Reviewer');
  }, 20000);
});

describe('SSE stream', () => {
  let c: Ctx;
  beforeAll(async () => { c = await boot(); });
  afterAll(async () => { await shutdown(c); });

  it('sends hello, then live events with gseq ids, and heartbeats', async () => {
    const msgs = await sse(`${c.base}/api/v1/events/stream`, {}, (m) => m.some((x) => x.event === 'event') && m.some((x) => x.event === 'heartbeat'), () => {
      c.f.appendLedger([ev('agent_run.started', { run_id: 'run_sse_1', agent_id: '05_existing-app-test-agent', session_id: 's-sse' })]);
    });
    const hello = msgs.find((m) => m.event === 'hello')!;
    expect((hello.data as { lastSeq: number }).lastSeq).toBe(0);
    const e = msgs.find((m) => m.event === 'event')!;
    expect(e.id).toBe('1');
    expect((e.data as { type: string; gseq: number }).type).toBe('agent_run.started');
    expect(msgs.some((m) => m.event === 'heartbeat')).toBe(true);
  });

  it('replays missed events after a disconnect using Last-Event-ID (scenario J)', async () => {
    c.f.appendLedger([ev('operation.started', { run_id: 'run_sse_1', span_id: 'sp2' }), ev('operation.completed', { run_id: 'run_sse_1', span_id: 'sp2', status: 'completed' })]);
    await new Promise((r) => setTimeout(r, 700));
    const msgs = await sse(`${c.base}/api/v1/events/stream`, { 'Last-Event-ID': '1' }, (m) => m.filter((x) => x.event === 'event').length >= 2);
    const hello = msgs.find((m) => m.event === 'hello')!.data as { replayed: number; gap: boolean };
    expect(hello.replayed).toBe(2);
    expect(hello.gap).toBe(false);
    expect(msgs.filter((m) => m.event === 'event').map((m) => m.id)).toEqual(['2', '3']);
  });

  it('?after= backfill over plain JSON matches the stream', async () => {
    const r = await (await fetch(`${c.base}/api/v1/events?after=1`)).json();
    expect(r.events.map((e: { gseq: number }) => e.gseq)).toEqual([2, 3]);
    expect(r.lastSeq).toBe(3);
  });

  it('pushes a snapshot invalidation when evidence changes', async () => {
    const msgs = await sse(`${c.base}/api/v1/events/stream`, {}, (m) => m.some((x) => x.event === 'snapshot'), () => c.model.invalidate('test'));
    const snap = msgs.find((m) => m.event === 'snapshot')!.data as { version: number };
    expect(snap.version).toBeGreaterThan(1);
  });
});
