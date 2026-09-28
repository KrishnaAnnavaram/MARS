import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ExecutionEvent } from '../api/types';
import { RunEventStream, type ConnectionStatus } from './RunEventStream';
import { EventBuffer, MAX_BUFFERED_EVENTS } from './LiveRun';

const event = (sequence: number, type = 'RCA_COMPLETED'): ExecutionEvent => ({
  event_id: `EVT-${sequence}`, run_id: 'RUN-X', sequence, timestamp: '2026-09-27T10:00:00Z', type, category: 'SECURITY',
  status: 'COMPLETED', subjects: [], evidence_refs: [], artifact_refs: [], attributes: {},
});

function sseBody(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
      controller.close();
    },
  });
}

const frame = (name: string, data: unknown, id?: string) => `${id ? `id: ${id}\n` : ''}event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

afterEach(() => vi.restoreAllMocks());

describe('RunEventStream', () => {
  it('delivers events in order, drops duplicates after a reconnect, and reports gaps', () => {
    const received: number[] = [];
    const gaps: [number, number][] = [];
    const stream = new RunEventStream('RUN-X', { onEvent: (e) => received.push(e.sequence), onStatus: () => {},
      onGap: (a, b) => gaps.push([a, b]) });
    stream.dispatch('execution-event', '1', JSON.stringify(event(1)));
    stream.dispatch('execution-event', '2', JSON.stringify(event(2)));
    stream.dispatch('execution-event', '2', JSON.stringify(event(2))); // replayed after reconnect
    stream.dispatch('execution-event', '1', JSON.stringify(event(1)));
    stream.dispatch('execution-event', '5', JSON.stringify(event(5))); // 3 and 4 missing
    expect(received).toEqual([1, 2, 5]);
    expect(gaps).toEqual([[3, 5]]);
    expect(stream.cursor).toBe(5);
  });

  it('resumes after the last delivered sequence with Last-Event-ID', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(sseBody([
      frame('hello', { run_id: 'RUN-X', last_sequence: 9 }),
      frame('execution-event', event(8), '8'),
      frame('execution-event', event(9), '9'),
    ]), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    const received: number[] = [];
    const statuses: ConnectionStatus[] = [];
    const stream = new RunEventStream('RUN-X', { onEvent: (e) => received.push(e.sequence), onStatus: (s) => statuses.push(s) }, 7);
    stream.start();
    await vi.waitFor(() => expect(received).toEqual([8, 9]));
    stream.stop();
    const headers = fetchMock.mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers['Last-Event-ID']).toBe('7');
    expect(statuses).toContain('LIVE');
  });

  it('parses events split across network chunks', async () => {
    const whole = frame('execution-event', event(1), '1');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(sseBody([whole.slice(0, 17), whole.slice(17, 40), whole.slice(40)]),
      { status: 200 }));
    const received: ExecutionEvent[] = [];
    const stream = new RunEventStream('RUN-X', { onEvent: (e) => received.push(e), onStatus: () => {} });
    stream.start();
    await vi.waitFor(() => expect(received).toHaveLength(1));
    stream.stop();
    expect(received[0].event_id).toBe('EVT-1');
  });

  it('stops without retrying when the run does not exist or access is refused', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 404 }));
    const statuses: [ConnectionStatus, string | undefined][] = [];
    const stream = new RunEventStream('RUN-X', { onEvent: () => {}, onStatus: (s, d) => statuses.push([s, d]) });
    stream.start();
    await vi.waitFor(() => expect(statuses.at(-1)?.[0]).toBe('OFFLINE'));
    expect(statuses.at(-1)?.[1]).toBe('Run not found');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('EventBuffer', () => {
  it('is bounded and notifies once per batch', () => {
    vi.useFakeTimers();
    const buffer = new EventBuffer();
    const listener = vi.fn();
    buffer.subscribe(listener);
    for (let i = 1; i <= MAX_BUFFERED_EVENTS + 10; i++) buffer.add(event(i));
    expect(listener).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(listener).toHaveBeenCalledTimes(1);
    const snapshot = buffer.getSnapshot();
    expect(snapshot).toHaveLength(MAX_BUFFERED_EVENTS);
    expect(snapshot[0].sequence).toBe(11);
    vi.useRealTimers();
  });
});
