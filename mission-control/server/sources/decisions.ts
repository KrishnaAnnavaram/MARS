/** Human decision records (docs/agent_output/decisions/DEC-*.json), with integrity re-verification. */
import crypto from 'node:crypto';
import type { Workspace } from './workspace.js';

export interface DecisionRecord {
  schema: string;
  decision_id: string;
  type: string;
  decision: 'APPROVED' | 'REJECTED' | string;
  issue_id: string;
  subject: { path: string; sha256_before: string; sha256_after: string; status_before: string; status_after: string };
  actor: string;
  actor_authentication: string;
  channel: string;
  rationale: string;
  timestamp: string;
  prev_record_sha256: string | null;
  record_sha256: string;
  file: string;
  integrity: 'verified' | 'tampered' | 'chain_broken';
}

function canonical(obj: unknown): string {
  if (Array.isArray(obj)) return `[${obj.map(canonical).join(',')}]`;
  if (obj && typeof obj === 'object') {
    return `{${Object.keys(obj as Record<string, unknown>).sort().map((k) => `${JSON.stringify(k)}:${canonical((obj as Record<string, unknown>)[k])}`).join(',')}}`;
  }
  return JSON.stringify(obj);
}

export function loadDecisions(ws: Workspace): DecisionRecord[] {
  const files = ws.list('docs/agent_output/decisions', (n) => /^DEC-.+\.json$/.test(n));
  const out: DecisionRecord[] = [];
  let prev: string | null = null;
  for (const f of files) {
    const raw = ws.readJson<Omit<DecisionRecord, 'file' | 'integrity'>>(f.path);
    if (!raw || !raw.decision_id) continue;
    const { record_sha256: claimed, ...rest } = raw as Omit<DecisionRecord, 'file' | 'integrity'> & { record_sha256: string };
    const recomputed = crypto.createHash('sha256').update(canonical(rest)).digest('hex');
    let integrity: DecisionRecord['integrity'] = recomputed === claimed ? 'verified' : 'tampered';
    if (integrity === 'verified' && raw.prev_record_sha256 !== prev) integrity = 'chain_broken';
    prev = claimed;
    out.push({ ...(raw as Omit<DecisionRecord, 'file' | 'integrity'>), file: f.path, integrity });
  }
  return out.sort((a, b) => a.timestamp.localeCompare(b.timestamp));
}
