/**
 * Live client: snapshot + delta over Server-Sent Events.
 *   1. Screens fetch authoritative snapshots (TanStack Query).
 *   2. This client subscribes to /api/v1/events/stream and applies deltas:
 *      - ledger events are de-duplicated by gseq; a skipped gseq is a gap → backfilled from
 *        /api/v1/events?after=…; if the server's window no longer covers it, snapshots are re-read;
 *      - `snapshot` messages (evidence changed on disk) invalidate cached snapshots;
 *      - after any reconnect, snapshots are re-read once.
 *   3. A watchdog reconnects (backoff 0.5 s → 15 s) when no message or heartbeat arrives for 40 s.
 * Events are witnesses: they never become UI state directly, they only trigger re-reads.
 */
import { useSyncExternalStore } from 'react';
import type { LedgerEvent } from '../shared/types';

export type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

export interface LiveState {
  status: LiveStatus;
  lastSeq: number;
  version: number | null;
  lastMessageAt: number | null;
  reconnects: number;
  gaps: number;
  events: LedgerEvent[];
}

export interface LiveDeps {
  EventSourceImpl: typeof EventSource;
  fetchJson: <T>(url: string) => Promise<T>;
  invalidate: (keys: unknown[][] | 'all') => void;
  now?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (t: unknown) => void;
  watchdogMs?: number;
  maxEvents?: number;
  /** How many recent ledger events to load on first connect (default 200). */
  seedEvents?: number;
}

export class LiveClient {
  private deps: LiveDeps;
  private es: EventSource | null = null;
  private state: LiveState = { status: 'connecting', lastSeq: 0, version: null, lastMessageAt: null, reconnects: 0, gaps: 0, events: [] };
  private listeners = new Set<() => void>();
  private watchdog: unknown = null;
  private retry: unknown = null;
  private backoff = 500;
  private stopped = false;
  private pendingInvalidate = new Map<string, unknown[]>();
  private flushTimer: unknown = null;
  private hadConnection = false;
  private backfilling = false;
  private held: LedgerEvent[] = [];

  constructor(deps: LiveDeps) {
    this.deps = deps;
  }

  private now() {
    return this.deps.now ? this.deps.now() : Date.now();
  }

  private timer(fn: () => void, ms: number) {
    return this.deps.setTimer ? this.deps.setTimer(fn, ms) : setTimeout(fn, ms);
  }

  private clear(t: unknown) {
    if (t == null) return;
    if (this.deps.clearTimer) this.deps.clearTimer(t);
    else clearTimeout(t as ReturnType<typeof setTimeout>);
  }

  subscribe = (fn: () => void) => {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  };

  getState = () => this.state;

  private set(patch: Partial<LiveState>) {
    this.state = { ...this.state, ...patch };
    for (const l of this.listeners) l();
  }

  start() {
    this.stopped = false;
    this.open();
  }

  stop() {
    this.stopped = true;
    this.clear(this.watchdog);
    this.clear(this.retry);
    this.es?.close();
    this.es = null;
    this.set({ status: 'offline' });
  }

  private open() {
    if (this.stopped) return;
    this.es?.close();
    const url = `/api/v1/events/stream${this.state.lastSeq ? `?after=${this.state.lastSeq}` : ''}`;
    const es = new this.deps.EventSourceImpl(url);
    this.es = es;
    this.set({ status: this.hadConnection ? 'reconnecting' : 'connecting' });
    es.addEventListener('hello', (m) => this.onHello(m as MessageEvent));
    es.addEventListener('event', (m) => this.onEvent(m as MessageEvent));
    es.addEventListener('snapshot', () => {
      this.touch();
      this.deps.invalidate('all');
    });
    es.addEventListener('heartbeat', () => this.touch());
    es.onerror = () => {
      // EventSource retries by itself while CONNECTING; a CLOSED source needs us.
      if (this.stopped) return;
      this.set({ status: 'reconnecting' });
      if (es.readyState === 2 /* CLOSED */) this.scheduleReconnect();
    };
    this.armWatchdog();
  }

  private scheduleReconnect() {
    this.clear(this.retry);
    const delay = this.backoff;
    this.backoff = Math.min(this.backoff * 2, 15_000);
    this.retry = this.timer(() => {
      this.set({ reconnects: this.state.reconnects + 1 });
      this.open();
    }, delay);
  }

  private armWatchdog() {
    this.clear(this.watchdog);
    this.watchdog = this.timer(() => {
      if (this.stopped) return;
      this.set({ status: 'reconnecting' });
      this.es?.close();
      this.scheduleReconnect();
    }, this.deps.watchdogMs ?? 40_000);
  }

  private touch() {
    this.armWatchdog();
    this.set({ lastMessageAt: this.now(), status: 'live' });
  }

  private onHello(m: MessageEvent) {
    let data: { version?: number; lastSeq?: number; gap?: boolean } = {};
    try {
      data = JSON.parse(m.data);
    } catch {
      /* ignore */
    }
    const reconnected = this.hadConnection;
    this.hadConnection = true;
    this.backoff = 500;
    if (data.gap) this.set({ gaps: this.state.gaps + 1 });
    this.set({ version: data.version ?? this.state.version });
    this.touch();
    // After a reconnect (or a gap the server cannot replay), snapshots are re-read once.
    if (reconnected || data.gap) this.deps.invalidate('all');
    // First connection: seed the feed with the most recent ledger history so the console does not
    // claim "no events" while the ledger has them. Live events arriving meanwhile are held, then applied.
    if (!reconnected && this.state.lastSeq === 0 && (data.lastSeq ?? 0) > 0) void this.seed(data.lastSeq as number);
  }

  private async seed(serverLast: number) {
    if (this.backfilling) return;
    this.backfilling = true;
    try {
      const after = Math.max(0, serverLast - (this.deps.seedEvents ?? 200));
      const r = await this.deps.fetchJson<{ events: LedgerEvent[] }>(`/api/v1/events?after=${after}&limit=5000`);
      const fresh = r.events.filter((x) => typeof x.gseq === 'number' && x.gseq > this.state.lastSeq).sort((a, b) => a.gseq - b.gseq);
      if (fresh.length) this.set({ events: [...this.state.events, ...fresh].slice(-(this.deps.maxEvents ?? 2000)), lastSeq: fresh[fresh.length - 1].gseq });
    } catch {
      /* the feed simply starts empty */
    } finally {
      this.backfilling = false;
      const held = this.held.splice(0);
      if (held.length) this.ingest(held);
    }
  }

  private onEvent(m: MessageEvent) {
    let e: LedgerEvent;
    try {
      e = JSON.parse(m.data);
    } catch {
      return;
    }
    this.touch();
    this.ingest([e]);
  }

  /** Applies events in order; exposed for tests. */
  ingest(list: LedgerEvent[]) {
    if (this.backfilling) {
      // One backfill at a time: hold later events until the gap is filled, then apply them in order.
      this.held.push(...list);
      return;
    }
    const sorted = list.filter((e) => typeof e.gseq === 'number').sort((a, b) => a.gseq - b.gseq);
    const fresh: LedgerEvent[] = [];
    let last = this.state.lastSeq;
    for (let k = 0; k < sorted.length; k += 1) {
      const e = sorted[k];
      if (e.gseq <= last) continue; // duplicate
      // A hole anywhere in the batch (not only at its start) is backfilled before later events apply.
      if (last > 0 && e.gseq > last + 1) {
        this.commit(fresh);
        this.backfilling = true;
        this.held.push(...sorted.slice(k + 1));
        void this.backfill(last, e).finally(() => {
          this.backfilling = false;
          const held = this.held.splice(0);
          if (held.length) this.ingest(held);
        });
        return;
      }
      fresh.push(e);
      last = e.gseq;
    }
    this.commit(fresh);
  }

  private commit(fresh: LedgerEvent[]) {
    if (!fresh.length) return;
    const max = this.deps.maxEvents ?? 2000;
    this.set({ events: [...this.state.events, ...fresh].slice(-max), lastSeq: fresh[fresh.length - 1].gseq });
    for (const e of fresh) this.queueInvalidation(e);
  }

  private async backfill(after: number, trigger: LedgerEvent) {
    this.set({ gaps: this.state.gaps + 1 });
    try {
      const r = await this.deps.fetchJson<{ events: LedgerEvent[]; oldestSeq: number }>(`/api/v1/events?after=${after}&limit=5000`);
      if (r.oldestSeq > after + 1) this.deps.invalidate('all'); // part of the gap is gone from the server window
      const merged = [...r.events, trigger].sort((a, b) => a.gseq - b.gseq);
      // ingest without gap detection beyond what the server returned
      const fresh = merged.filter((x) => x.gseq > this.state.lastSeq);
      if (fresh.length) {
        const max = this.deps.maxEvents ?? 2000;
        this.set({ events: [...this.state.events, ...fresh].slice(-max), lastSeq: fresh[fresh.length - 1].gseq });
        for (const e of fresh) this.queueInvalidation(e);
      }
    } catch {
      this.deps.invalidate('all');
    }
  }

  private queueInvalidation(e: LedgerEvent) {
    const keys: unknown[][] = [['overview'], ['runs']];
    if (e.run_id) keys.push(['run', e.run_id]);
    for (const i of e.issue_ids || []) keys.push(['issue', i], ['audit', i]);
    if (e.type === 'approval.recorded') keys.push(['approvals']);
    for (const k of keys) this.pendingInvalidate.set(JSON.stringify(k), k);
    if (this.flushTimer == null) {
      this.flushTimer = this.timer(() => {
        this.flushTimer = null;
        const ks = Array.from(this.pendingInvalidate.values());
        this.pendingInvalidate.clear();
        this.deps.invalidate(ks);
      }, 400);
    }
  }
}

let singleton: LiveClient | null = null;

export function initLive(deps: LiveDeps): LiveClient {
  if (!singleton) {
    singleton = new LiveClient(deps);
    singleton.start();
  }
  return singleton;
}

const OFFLINE: LiveState = { status: 'offline', lastSeq: 0, version: null, lastMessageAt: null, reconnects: 0, gaps: 0, events: [] };

export function useLive(): LiveState {
  return useSyncExternalStore(
    (fn) => (singleton ? singleton.subscribe(fn) : () => undefined),
    () => (singleton ? singleton.getState() : OFFLINE),
    () => OFFLINE,
  );
}
