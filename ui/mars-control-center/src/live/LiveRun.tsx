import { createContext, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { keys } from '../api/queries';
import type { ExecutionEvent, WorkerStatus } from '../api/types';
import { RunEventStream, type ConnectionStatus, type Hello } from './RunEventStream';

/** Kept in memory for the feed; older events stay on the server and are paged from the history endpoint. */
export const MAX_BUFFERED_EVENTS = 20_000;

/** Events that change what a snapshot says; they trigger a (debounced) refetch of the run's queries. */
const REFRESHING = new Set([
  'STATE_TRANSITION', 'DECISION_RECORDED', 'HUMAN_ACTION_REQUIRED', 'PROPOSAL_REGISTERED', 'MUTATION_APPLIED',
  'MUTATION_REFUSED', 'MUTATION_ROLLED_BACK', 'PROPOSAL_VALIDATION_RECORDED', 'VERDICT_COMPUTED', 'RUN_COMPLETED',
  'RUN_FAILED', 'ADVANCE_STOPPED', 'MIGRATION_ROUND_COMPLETED', 'FIX_VERIFICATION_COMPLETED', 'REMEDIATION_PLANNING_COMPLETED',
  'SECURITY_DISCOVERY_COMPLETED', 'MIGRATION_ASSESSMENT_COMPLETED', 'FINAL_VALIDATION_COMPLETED', 'GRAPH_REBUILT',
  'BASELINE_SEALED', 'IDENTITY_COMPLETED', 'INVENTORY_COMPLETED', 'GRAPH_BUILD_COMPLETED', 'RUN_CREATED',
]);

/** Bounded, append-only client buffer. Notifications are batched so a replay of thousands of events renders once. */
export class EventBuffer {
  private events: ExecutionEvent[] = [];
  private listeners = new Set<() => void>();
  private snapshot: ExecutionEvent[] = [];
  private scheduled?: ReturnType<typeof setTimeout>;

  add(e: ExecutionEvent) {
    this.events.push(e);
    if (this.events.length > MAX_BUFFERED_EVENTS) {
      this.events.splice(0, this.events.length - MAX_BUFFERED_EVENTS);
    }
    if (!this.scheduled) {
      this.scheduled = setTimeout(() => this.flush(), 60);
    }
  }

  flush() {
    if (this.scheduled) {
      clearTimeout(this.scheduled);
      this.scheduled = undefined;
    }
    this.snapshot = this.events.slice();
    this.listeners.forEach((l) => l());
  }

  clear() {
    if (this.scheduled) {
      clearTimeout(this.scheduled);
      this.scheduled = undefined;
    }
    this.events = [];
    this.snapshot = [];
    this.listeners.forEach((l) => l());
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = () => this.snapshot;
}

export interface LiveRunState {
  runId: string;
  status: ConnectionStatus;
  detail?: string;
  hello?: Hello;
  worker?: WorkerStatus;
  lastHeartbeat?: string;
  gaps: number;
  buffer: EventBuffer;
}

const LiveRunContext = createContext<LiveRunState | undefined>(undefined);

/**
 * The single live connection for the run being viewed. Every screen reads from here; no component
 * opens its own EventSource. Events update a bounded client buffer and invalidate the run's
 * snapshot queries, so the screens always re-read the authoritative state from the API.
 */
export function LiveRunProvider({ runId, children }: { runId: string; children: ReactNode }) {
  const client = useQueryClient();
  const buffer = useMemo(() => new EventBuffer(), []);
  const [status, setStatus] = useState<ConnectionStatus>('CONNECTING');
  const [detail, setDetail] = useState<string>();
  const [hello, setHello] = useState<Hello>();
  const [worker, setWorker] = useState<WorkerStatus>();
  const [lastHeartbeat, setLastHeartbeat] = useState<string>();
  const [gaps, setGaps] = useState(0);
  const refresh = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    buffer.clear();
    const invalidate = () => {
      if (refresh.current) return;
      refresh.current = setTimeout(() => {
        refresh.current = undefined;
        void client.invalidateQueries({ queryKey: keys.run(runId) });
      }, 350);
    };
    const stream = new RunEventStream(runId, {
      onEvent: (e) => {
        buffer.add(e);
        if (REFRESHING.has(e.type)) invalidate();
      },
      onHello: setHello,
      onWorker: (w) => {
        setWorker(w);
        invalidate();
      },
      onHeartbeat: (at) => setLastHeartbeat(at),
      onStatus: (s, d) => {
        setStatus(s);
        setDetail(d);
        if (s === 'LIVE') invalidate(); // reconnected: reconcile with the snapshot
      },
      onGap: () => {
        setGaps((g) => g + 1);
        invalidate();
      },
    });
    stream.start();
    return () => {
      stream.stop();
      if (refresh.current) clearTimeout(refresh.current);
      refresh.current = undefined;
    };
  }, [runId, client, buffer]);

  const value = useMemo<LiveRunState>(() => ({ runId, status, detail, hello, worker, lastHeartbeat, gaps, buffer }),
    [runId, status, detail, hello, worker, lastHeartbeat, gaps, buffer]);
  return <LiveRunContext.Provider value={value}>{children}</LiveRunContext.Provider>;
}

export function useLiveRun(): LiveRunState {
  const live = useContext(LiveRunContext);
  if (!live) throw new Error('useLiveRun outside LiveRunProvider');
  return live;
}

/** The events received so far for the current run, re-rendering on each new one. */
export function useRunEvents(): ExecutionEvent[] {
  const { buffer } = useLiveRun();
  return useSyncExternalStore(buffer.subscribe, buffer.getSnapshot);
}
