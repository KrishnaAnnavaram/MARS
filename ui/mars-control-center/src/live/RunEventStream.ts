import { authHeaders } from '../api/client';
import type { ExecutionEvent, WorkerStatus } from '../api/types';

export type ConnectionStatus = 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'OFFLINE';

export interface Hello {
  run_id: string;
  last_sequence: number;
  replay_after: number;
  replayed: number;
  server_time: string;
  malformed_lines: number;
}

export interface StreamHandlers {
  onEvent: (event: ExecutionEvent) => void;
  onHello?: (hello: Hello) => void;
  onWorker?: (worker: WorkerStatus) => void;
  onHeartbeat?: (at: string, lastSequence: number) => void;
  onStatus: (status: ConnectionStatus, detail?: string) => void;
  /** A sequence was skipped: the snapshot must be refetched and history re-read. */
  onGap?: (expected: number, received: number) => void;
}

const HEARTBEAT_TIMEOUT_MS = 40_000;
const BACKOFF_MS = [500, 1000, 2000, 5000, 10000, 15000];

/**
 * One Server-Sent Events connection to a run's execution events, implemented over fetch so it can
 * send Last-Event-ID and an Authorization header on every reconnect.
 *
 * Guarantees to its handlers: events arrive in strictly increasing sequence, never twice. A
 * reconnect resumes after the last delivered sequence. A disconnected stream says so; it never
 * implies that the run stopped.
 */
export class RunEventStream {
  private lastSequence = 0;
  private controller?: AbortController;
  private stopped = false;
  private attempts = 0;
  private watchdog?: ReturnType<typeof setTimeout>;
  private readonly runId: string;
  private readonly handlers: StreamHandlers;

  constructor(runId: string, handlers: StreamHandlers, startAfter = 0) {
    this.runId = runId;
    this.handlers = handlers;
    this.lastSequence = startAfter;
  }

  get cursor(): number {
    return this.lastSequence;
  }

  start(): void {
    this.stopped = false;
    void this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.clearWatchdog();
    this.controller?.abort();
  }

  private armWatchdog(): void {
    this.clearWatchdog();
    this.watchdog = setTimeout(() => {
      // no data and no heartbeat: the connection is dead even if the socket has not noticed
      this.controller?.abort();
    }, HEARTBEAT_TIMEOUT_MS);
  }

  private clearWatchdog(): void {
    if (this.watchdog) {
      clearTimeout(this.watchdog);
      this.watchdog = undefined;
    }
  }

  private async connect(): Promise<void> {
    if (this.stopped) {
      return;
    }
    this.handlers.onStatus(this.attempts === 0 ? 'CONNECTING' : 'RECONNECTING');
    this.controller = new AbortController();
    try {
      const response = await fetch(`/api/v1/runs/${encodeURIComponent(this.runId)}/events`, {
        credentials: 'same-origin',
        headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(this.lastSequence), ...(await authHeaders()) },
        signal: this.controller.signal,
        cache: 'no-store',
      });
      if (response.status === 401 || response.status === 403 || response.status === 404) {
        this.handlers.onStatus('OFFLINE', response.status === 404 ? 'Run not found' : 'Not permitted');
        this.stopped = true;
        return;
      }
      if (!response.ok || !response.body) {
        throw new Error(`HTTP ${response.status}`);
      }
      this.attempts = 0;
      this.handlers.onStatus('LIVE');
      this.armWatchdog();
      await this.read(response.body);
    } catch {
      // network failure or watchdog abort: handled below by reconnecting from the last delivered sequence
      if (this.stopped) {
        return;
      }
    }
    this.clearWatchdog();
    if (!this.stopped) {
      const delay = BACKOFF_MS[Math.min(this.attempts, BACKOFF_MS.length - 1)];
      this.attempts++;
      this.handlers.onStatus(this.attempts > 3 ? 'OFFLINE' : 'RECONNECTING',
        this.attempts > 3 ? 'The Control Center API cannot be reached; retrying' : undefined);
      setTimeout(() => void this.connect(), delay);
    }
  }

  private async read(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let event = { id: '', name: 'message', data: '' };
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        return;
      }
      this.armWatchdog();
      buffer += decoder.decode(value, { stream: true });
      let newline: number;
      while ((newline = buffer.search(/\r?\n/)) >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(buffer[newline] === '\r' ? newline + 2 : newline + 1);
        if (line === '') {
          if (event.data) {
            this.dispatch(event.name, event.id, event.data);
          }
          event = { id: '', name: 'message', data: '' };
        } else if (line.startsWith(':')) {
          // comment line: keep-alive
        } else {
          const colon = line.indexOf(':');
          const field = colon < 0 ? line : line.slice(0, colon);
          let value = colon < 0 ? '' : line.slice(colon + 1);
          if (value.startsWith(' ')) {
            value = value.slice(1);
          }
          if (field === 'id') event.id = value;
          else if (field === 'event') event.name = value;
          else if (field === 'data') event.data = event.data ? `${event.data}\n${value}` : value;
        }
      }
    }
  }

  /** Visible for tests. */
  dispatch(name: string, id: string, data: string): void {
    let payload: unknown;
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }
    switch (name) {
      case 'execution-event': {
        const e = payload as ExecutionEvent;
        const sequence = Number(id || e.sequence);
        if (!Number.isFinite(sequence) || sequence <= this.lastSequence) {
          return; // duplicate after a reconnect: already delivered
        }
        if (sequence > this.lastSequence + 1) {
          this.handlers.onGap?.(this.lastSequence + 1, sequence);
        }
        this.lastSequence = sequence;
        this.handlers.onEvent(e);
        return;
      }
      case 'hello':
        this.handlers.onHello?.(payload as Hello);
        return;
      case 'worker':
        this.handlers.onWorker?.(payload as WorkerStatus);
        return;
      case 'heartbeat': {
        const h = payload as { server_time: string; last_sequence: number };
        this.handlers.onHeartbeat?.(h.server_time, h.last_sequence);
        return;
      }
      default:
        return;
    }
  }
}
