import { QueryClient, useQuery } from '@tanstack/react-query';
import type {
  AgentInfo, ApprovalInfo, ArchitectureView, ArtifactContent, ArtifactInfo, AuditItem, HealthCheck, IntegrityFinding, IssueDetail, IssueSummary,
  LedgerEvent, LineageGraph, Overview, Registry, RunDetail, RunSummary, SessionInfo, SkillInfo,
} from '../shared/types';

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(url, { signal, headers: { Accept: 'application/json' } });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'Mission Control server is unreachable.');
  }
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError(res.status, 'BAD_RESPONSE', 'The server returned a response that is not JSON.');
  }
  if (!res.ok) {
    const b = (body || {}) as { code?: string; message?: string };
    throw new ApiError(res.status, b.code || 'HTTP_ERROR', b.message || `Request failed (${res.status}).`);
  }
  return body as T;
}

const TOKEN_KEY = 'mc-decision-token';

/**
 * The per-launch decision token arrives once, in the URL fragment of the link the server printed
 * (fragments are never sent to the server or written to logs). It is kept for this tab only and
 * removed from the address bar immediately.
 */
export function captureDecisionToken(loc: Location = window.location): void {
  const m = /(?:^#|&)mc-token=([A-Za-z0-9_-]{24,})/.exec(loc.hash);
  if (!m) return;
  try {
    sessionStorage.setItem(TOKEN_KEY, m[1]);
  } catch {
    /* without storage the token cannot be kept; decisions stay unavailable */
  }
  history.replaceState(null, '', loc.pathname + loc.search);
}

export function decisionToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export async function postJson<T>(url: string, payload: unknown): Promise<T> {
  const token = decisionToken();
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-MC-Request': '1', Accept: 'application/json', ...(token ? { 'X-MC-Token': token } : {}) }, body: JSON.stringify(payload) });
  const body = (await res.json().catch(() => ({}))) as { code?: string; message?: string };
  if (!res.ok) throw new ApiError(res.status, body.code || 'HTTP_ERROR', body.message || `Request failed (${res.status}).`);
  return body as T;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: true,
      retry: (count, err) => !(err instanceof ApiError && err.status >= 400 && err.status < 500) && count < 2,
    },
  },
});

const useQ = <T,>(key: unknown[], url: string | null, opts: { enabled?: boolean; refetchInterval?: number } = {}) =>
  useQuery<T, ApiError>({ queryKey: key, queryFn: ({ signal }) => getJson<T>(url as string, signal), enabled: url != null && opts.enabled !== false, refetchInterval: opts.refetchInterval });

export const useOverview = () => useQ<Overview>(['overview'], '/api/v1/overview', { refetchInterval: 30_000 });
export const useIssues = () => useQ<{ issues: IssueSummary[] }>(['issues'], '/api/v1/issues');
export const useIssue = (id: string | undefined) => useQ<IssueDetail>(['issue', id], id ? `/api/v1/issues/${encodeURIComponent(id)}` : null);
export const useLineage = (id: string | undefined) => useQ<LineageGraph>(['lineage', id], id ? `/api/v1/issues/${encodeURIComponent(id)}/lineage` : null);
export const useRuns = () => useQ<{ runs: RunSummary[]; ledger: { events: number; malformed: number; lastEventAt: string | null; dir: string } }>(['runs'], '/api/v1/runs');
export const useRun = (id: string | undefined) => useQ<RunDetail>(['run', id], id ? `/api/v1/runs/${encodeURIComponent(id)}` : null);
export const useRegistry = () => useQ<Registry>(['registry'], '/api/v1/registry');
export const useAgent = (id: string | undefined) => useQ<{ agent: AgentInfo; skills: SkillInfo[]; runs: RunSummary[] }>(['agent', id], id ? `/api/v1/registry/agents/${encodeURIComponent(id)}` : null);
export const useSkill = (id: string | undefined) => useQ<{ skill: SkillInfo; skillMd: string | null; recent: LedgerEvent[] }>(['skill', id], id ? `/api/v1/registry/skills/${encodeURIComponent(id)}` : null);
export const useApprovals = () => useQ<{ approvals: ApprovalInfo[]; decisions: { enabled: boolean; reason: string } }>(['approvals'], '/api/v1/approvals');
export const useApproval = (id: string | undefined) => useQ<{ approval: ApprovalInfo; issue: Pick<IssueDetail, 'id' | 'title' | 'severity' | 'priority' | 'rca' | 'blast' | 'codeRefs' | 'fix' | 'findingList'> }>(['approval', id], id ? `/api/v1/approvals/${encodeURIComponent(id)}` : null);
export const useArtifacts = () => useQ<{ artifacts: ArtifactInfo[]; findings: IntegrityFinding[] }>(['artifacts'], '/api/v1/artifacts');
export const useArtifactContent = (path: string | undefined) => useQ<ArtifactContent>(['artifact', path], path ? `/api/v1/artifacts/content?path=${encodeURIComponent(path)}` : null);
export const useAudit = (issue?: string | null) => useQ<{ items: AuditItem[] }>(['audit', issue || 'all'], `/api/v1/audit${issue ? `?issue=${encodeURIComponent(issue)}` : ''}`);
export const useArchitecture = (issue?: string | null, focus?: string | null) => useQ<ArchitectureView>(['architecture', issue || '', focus || ''], `/api/v1/architecture?${new URLSearchParams({ ...(issue ? { issue } : {}), ...(focus ? { focus } : {}) }).toString()}`);
export const useHealth = () => useQ<{ checks: HealthCheck[] }>(['health'], '/api/v1/health');
export const useSession = () => useQ<SessionInfo>(['session'], '/api/v1/session');
