/**
 * Event ledger reader. Tails .mars/ledger/events-*.jsonl by byte offset (append-only), keeps
 * a bounded in-memory window, and assigns a stable global sequence `gseq` (file order, then line
 * order) used as the SSE event id. Because gseq is derived from the files, a restarted server
 * assigns the same ids and a client's Last-Event-ID stays valid.
 *
 * Only newline-terminated lines are parsed; a torn final line is kept until completed. Malformed
 * lines are counted, never fatal. Events are witnesses — nothing here turns an event into state.
 */
import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import type { LedgerEvent } from '../../shared/types.js';

interface FileCursor {
  offset: number;
  partial: string;
  lines: number;
}

export class LedgerReader extends EventEmitter {
  readonly dir: string;
  private cursors = new Map<string, FileCursor>();
  private events: LedgerEvent[] = [];
  private gseq = 0;
  malformed = 0;
  readonly maxEvents: number;
  private timer: NodeJS.Timeout | null = null;

  constructor(dir: string, maxEvents = 50000) {
    super();
    this.dir = dir;
    this.maxEvents = maxEvents;
  }

  files(): string[] {
    try {
      return fs.readdirSync(this.dir).filter((f) => /^events-\d{4}-\d{2}\.jsonl$/.test(f)).sort();
    } catch {
      return [];
    }
  }

  /** Reads any newly appended bytes; returns the new events (also emitted as 'events'). */
  poll(): LedgerEvent[] {
    const fresh: LedgerEvent[] = [];
    for (const name of this.files()) {
      const file = path.join(this.dir, name);
      let size = 0;
      try {
        size = fs.statSync(file).size;
      } catch {
        continue;
      }
      const cur = this.cursors.get(name) || { offset: 0, partial: '', lines: 0 };
      if (size < cur.offset) {
        // Truncated or replaced: start over for this file (ids for this file are re-derived).
        cur.offset = 0;
        cur.partial = '';
        cur.lines = 0;
      }
      if (size === cur.offset) {
        this.cursors.set(name, cur);
        continue;
      }
      const fd = fs.openSync(file, 'r');
      try {
        const buf = Buffer.alloc(size - cur.offset);
        fs.readSync(fd, buf, 0, buf.length, cur.offset);
        cur.offset = size;
        const text = cur.partial + buf.toString('utf8');
        const parts = text.split('\n');
        cur.partial = parts.pop() || '';
        for (const line of parts) {
          const trimmed = line.trim();
          if (!trimmed) continue;
          cur.lines += 1;
          try {
            const obj = JSON.parse(trimmed) as LedgerEvent;
            if (!obj || typeof obj !== 'object' || typeof obj.type !== 'string' || typeof obj.time !== 'string') {
              this.malformed += 1;
              continue;
            }
            this.gseq += 1;
            const ev: LedgerEvent = { ...obj, gseq: this.gseq };
            this.events.push(ev);
            fresh.push(ev);
          } catch {
            this.malformed += 1;
          }
        }
      } finally {
        fs.closeSync(fd);
      }
      this.cursors.set(name, cur);
    }
    if (this.events.length > this.maxEvents) this.events.splice(0, this.events.length - this.maxEvents);
    if (fresh.length) this.emit('events', fresh);
    return fresh;
  }

  start(intervalMs = 500): void {
    this.poll();
    if (this.timer) return;
    this.timer = setInterval(() => {
      try {
        this.poll();
      } catch {
        /* never fatal */
      }
    }, intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  all(): LedgerEvent[] {
    return this.events;
  }

  lastSeq(): number {
    return this.gseq;
  }

  oldestSeq(): number {
    return this.events.length ? this.events[0].gseq : this.gseq + 1;
  }

  after(seq: number, limit = 5000): LedgerEvent[] {
    // events are ordered by gseq; binary search for the first > seq
    let lo = 0;
    let hi = this.events.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.events[mid].gseq <= seq) lo = mid + 1;
      else hi = mid;
    }
    return this.events.slice(lo, lo + limit);
  }
}
