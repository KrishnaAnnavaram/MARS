/**
 * LiveClient: SSE connection state, dedupe, gap backfill, reconnect backoff, watchdog and
 * debounced query invalidation — with a fake EventSource and manual timers (no network).
 */
import { describe, expect, it } from 'vitest';
import { LiveClient, type LiveDeps } from '../../web/live';
import type { LedgerEvent } from '../../shared/types';

class FakeES {
  static all: FakeES[] = [];
  url: string;
  readyState = 0;
  onerror: (() => void) | null = null;
  private l = new Map<string, ((m: { data: string }) => void)[]>();
  closed = false;
  constructor(url: string) {
    this.url = url;
    FakeES.all.push(this);
  }
  addEventListener(t: string, fn: (m: { data: string }) => void) {
    if (!this.l.has(t)) this.l.set(t, []);
    this.l.get(t)!.push(fn);
  }
  emit(t: string, data: unknown) {
    for (const fn of this.l.get(t) || []) fn({ data: JSON.stringify(data) });
  }
  close() {
    this.closed = true;
    this.readyState = 2;
  }
  fail() {
    this.readyState = 2;
    this.onerror?.();
  }
}

function harness(fetchImpl?: (url: string) => Promise<unknown>) {
  FakeES.all = [];
  const timers: { fn: () => void; ms: number; id: number; done: boolean }[] = [];
  let id = 0;
  const invalidations: (unknown[][] | 'all')[] = [];
  const fetched: string[] = [];
  const deps: LiveDeps = {
    EventSourceImpl: FakeES as unknown as typeof EventSource,
    fetchJson: async <T,>(url: string) => {
      fetched.push(url);
      return (fetchImpl ? await fetchImpl(url) : { events: [], oldestSeq: 1 }) as T;
    },
    invalidate: (k) => invalidations.push(k),
    setTimer: (fn, ms) => {
      id += 1;
      timers.push({ fn, ms, id, done: false });
      return id;
    },
    clearTimer: (t) => {
      const x = timers.find((y) => y.id === t);
      if (x) x.done = true;
    },
    watchdogMs: 40_000,
  };
  const c = new LiveClient(deps);
  const run = (pred: (ms: number) => boolean) => {
    for (const t of timers.filter((x) => !x.done && pred(x.ms))) {
      t.done = true;
      t.fn();
    }
  };
  return { c, timers, invalidations, fetched, run, es: () => FakeES.all[FakeES.all.length - 1] };
}

const E = (gseq: number, extra: Partial<LedgerEvent> = {}): LedgerEvent => ({ schema: 'mars.event/1', event_id: `e${gseq}`, seq: gseq, gseq, time: new Date(0).toISOString(), type: 'operation.completed', ...extra });

describe('LiveClient', () => {
  it('connects, becomes live on hello, and applies events in order', () => {
    const h = harness();
    h.c.start();
    expect(h.c.getState().status).toBe('connecting');
    h.es().emit('hello', { version: 3, lastSeq: 0 });
    expect(h.c.getState().status).toBe('live');
    h.es().emit('event', E(1));
    h.es().emit('event', E(2));
    expect(h.c.getState().lastSeq).toBe(2);
    expect(h.c.getState().events.map((e) => e.gseq)).toEqual([1, 2]);
  });

  it('drops duplicates (same gseq delivered twice after a replay)', () => {
    const h = harness();
    h.c.start();
    h.es().emit('hello', {});
    h.c.ingest([E(1), E(2)]);
    h.c.ingest([E(2), E(1), E(3)]);
    expect(h.c.getState().events.map((e) => e.gseq)).toEqual([1, 2, 3]);
  });

  it('backfills a gap once over JSON and holds later events until it is filled', async () => {
    let release!: (v: unknown) => void;
    const h = harness((url) => new Promise((r) => { release = r; void url; }));
    h.c.start();
    h.es().emit('hello', {});
    h.c.ingest([E(1)]);
    h.c.ingest([E(5)]);
    h.c.ingest([E(6)]);
    expect(h.fetched).toEqual(['/api/v1/events?after=1&limit=5000']);
    release({ events: [E(2), E(3), E(4)], oldestSeq: 1 });
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    expect(h.c.getState().events.map((e) => e.gseq)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(h.c.getState().gaps).toBe(1);
  });

  it('detects a gap in the middle of a batch, not only at its start', async () => {
    const h = harness(async () => ({ events: [E(4)], oldestSeq: 1 }));
    h.c.start();
    h.es().emit('hello', {});
    h.c.ingest([E(1), E(2), E(3), E(5), E(6)]);
    expect(h.fetched).toEqual(['/api/v1/events?after=3&limit=5000']);
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    expect(h.c.getState().events.map((e) => e.gseq)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('reconnects with exponential backoff after the stream closes, resuming after lastSeq', () => {
    const h = harness();
    h.c.start();
    h.es().emit('hello', {});
    h.c.ingest([E(1), E(2)]);
    h.es().fail();
    expect(h.c.getState().status).toBe('reconnecting');
    const first = h.timers.filter((t) => !t.done && t.ms === 500);
    expect(first).toHaveLength(1);
    h.run((ms) => ms === 500);
    expect(h.es().url).toBe('/api/v1/events/stream?after=2');
    h.es().fail();
    expect(h.timers.some((t) => !t.done && t.ms === 1000)).toBe(true);
    h.run((ms) => ms === 1000);
    h.es().emit('hello', { gap: false });
    expect(h.c.getState().status).toBe('live');
    expect(h.c.getState().reconnects).toBe(2);
    // A reconnect re-reads snapshots once: events may have been missed while offline.
    expect(h.invalidations).toContain('all');
  });

  it('the watchdog forces a reconnect when the stream goes silent (no heartbeat)', () => {
    const h = harness();
    h.c.start();
    h.es().emit('hello', {});
    const before = FakeES.all.length;
    h.run((ms) => ms === 40_000);
    expect(h.c.getState().status).toBe('reconnecting');
    h.run((ms) => ms === 500);
    expect(FakeES.all.length).toBe(before + 1);
  });

  it('debounces query invalidation and targets the affected issue and run', () => {
    const h = harness();
    h.c.start();
    h.es().emit('hello', {});
    h.c.ingest([E(1, { run_id: 'r1', issue_ids: ['ISSUE-003'] }), E(2, { run_id: 'r1', issue_ids: ['ISSUE-003'], type: 'approval.recorded' })]);
    expect(h.invalidations).toHaveLength(0);
    h.run((ms) => ms === 400);
    const keys = (h.invalidations[0] as unknown[][]).map((k) => JSON.stringify(k));
    expect(keys).toEqual(expect.arrayContaining(['["overview"]', '["run","r1"]', '["issue","ISSUE-003"]', '["approvals"]']));
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('seeds recent ledger history on first connect and applies live events that arrive meanwhile, in order', async () => {
    let release!: (v: unknown) => void;
    const h = harness(() => new Promise((r) => { release = r; }));
    h.c.start();
    h.es().emit('hello', { lastSeq: 11 });
    expect(h.fetched).toEqual(['/api/v1/events?after=0&limit=5000']);
    h.es().emit('event', E(12));
    release({ events: Array.from({ length: 11 }, (_, i) => E(i + 1)), oldestSeq: 1 });
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
    expect(h.c.getState().events.map((e) => e.gseq)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
    expect(h.c.getState().lastSeq).toBe(12);
  });

  it('a snapshot message invalidates everything; stop() goes offline and closes the stream', () => {
    const h = harness();
    h.c.start();
    h.es().emit('snapshot', { version: 9 });
    expect(h.invalidations).toContain('all');
    h.c.stop();
    expect(h.c.getState().status).toBe('offline');
    expect(h.es().closed).toBe(true);
  });
});
