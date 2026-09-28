import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getJson, getText, newIdempotencyKey, postJson } from './client';
import type {
  ArtifactEntry,
  ChangesView,
  DecisionRecorded,
  DecisionView,
  EvidencePage,
  ExecutionEvent,
  FindingDetail,
  GraphView,
  HumanActionsView,
  LogChunk,
  LogFile,
  MigrationView,
  ProposalDetail,
  RepositoryOption,
  RunPage,
  RunSnapshot,
  SecurityView,
  SessionView,
  Timeline,
  ValidationView,
  VerdictView,
} from './types';

const enc = encodeURIComponent;

/** Query keys. Every run-scoped key starts with ['run', runId] so one invalidation refreshes a run. */
export const keys = {
  session: ['session'] as const,
  runs: (filter: string) => ['runs', filter] as const,
  repositories: ['repositories'] as const,
  run: (runId: string) => ['run', runId] as const,
  part: (runId: string, part: string, ...rest: unknown[]) => ['run', runId, part, ...rest] as const,
};

export function useSession() {
  return useQuery({ queryKey: keys.session, queryFn: ({ signal }) => getJson<SessionView>('/api/v1/session', signal) });
}

export function useRuns(params: { status?: string; capability?: string; q?: string }) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v && search.set(k, v));
  const qs = search.toString();
  return useQuery({
    queryKey: keys.runs(qs),
    queryFn: ({ signal }) => getJson<RunPage>(`/api/v1/runs${qs ? `?${qs}` : ''}`, signal),
    refetchInterval: 10_000,
    placeholderData: keepPreviousData,
  });
}

export function useRepositories(enabled: boolean) {
  return useQuery({
    queryKey: keys.repositories,
    queryFn: ({ signal }) => getJson<RepositoryOption[]>('/api/v1/repositories', signal),
    enabled,
  });
}

/** The authoritative snapshot. Live events invalidate it; a slow poll reconciles anything missed. */
export function useRun(runId: string) {
  return useQuery({
    queryKey: keys.run(runId),
    queryFn: ({ signal }) => getJson<RunSnapshot>(`/api/v1/runs/${enc(runId)}`, signal),
    refetchInterval: 30_000,
  });
}

function runPart<T>(runId: string, part: string, path: string, enabled = true) {
  return {
    queryKey: keys.part(runId, part, path),
    queryFn: ({ signal }: { signal: AbortSignal }) => getJson<T>(`/api/v1/runs/${enc(runId)}${path}`, signal),
    enabled,
  };
}

export const useHumanActions = (runId: string) =>
  useQuery(runPart<HumanActionsView>(runId, 'human-actions', '/human-actions'));
export const useFindings = (runId: string) => useQuery(runPart<SecurityView>(runId, 'findings', '/findings'));
export const useFinding = (runId: string, findingId: string) =>
  useQuery(runPart<FindingDetail>(runId, 'finding', `/findings/${enc(findingId)}`));
export const useProposals = (runId: string) => useQuery(runPart<ChangesView>(runId, 'proposals', '/proposals'));
export const useProposal = (runId: string, proposalId: string | undefined) =>
  useQuery(runPart<ProposalDetail>(runId, 'proposal', `/proposals/${enc(proposalId ?? '')}`, !!proposalId));
export const useMigration = (runId: string) => useQuery(runPart<MigrationView>(runId, 'migration', '/migration'));
export const useValidation = (runId: string) => useQuery(runPart<ValidationView>(runId, 'validation', '/validation'));
export const useVerdict = (runId: string) => useQuery(runPart<VerdictView>(runId, 'verdict', '/verdict'));
export const useTimeline = (runId: string) => useQuery(runPart<Timeline>(runId, 'timeline', '/timeline'));
export const useDecisions = (runId: string) => useQuery(runPart<DecisionView[]>(runId, 'decisions', '/decisions'));
export const useArtifacts = (runId: string) => useQuery(runPart<ArtifactEntry[]>(runId, 'artifacts', '/artifacts'));
export const useLogs = (runId: string) => useQuery(runPart<LogFile[]>(runId, 'logs', '/logs'));

export function useEvidence(runId: string, params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== '' && search.set(k, String(v)));
  return useQuery({
    ...runPart<EvidencePage>(runId, 'evidence', `/evidence?${search.toString()}`),
    placeholderData: keepPreviousData,
  });
}

export function useGraph(runId: string, params: Record<string, string | number | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v !== undefined && v !== '' && search.set(k, String(v)));
  return useQuery({ ...runPart<GraphView>(runId, 'graph', `/graph?${search.toString()}`), placeholderData: keepPreviousData });
}

export function useLog(runId: string, path: string | undefined, params: { level?: string; q?: string; from?: number }) {
  const search = new URLSearchParams({ path: path ?? '', max: '5000', from: String(params.from ?? 1) });
  if (params.level) search.set('level', params.level);
  if (params.q) search.set('q', params.q);
  return useQuery({
    ...runPart<LogChunk>(runId, 'log', `/logs/content?${search.toString()}`, !!path),
    placeholderData: keepPreviousData,
  });
}

export function useArtifactText(runId: string, path: string | undefined) {
  return useQuery({
    queryKey: keys.part(runId, 'artifact', path),
    queryFn: ({ signal }) => getText(`/api/v1/runs/${enc(runId)}/artifacts/content?path=${enc(path ?? '')}`, signal),
    enabled: !!path,
  });
}

export function useEventHistory(runId: string, after: number) {
  return useQuery(runPart<ExecutionEvent[]>(runId, 'event-history', `/events/history?after=${after}&limit=5000`));
}

// ------------------------------------------------------------------ commands

/**
 * A decision command. The mutation resolves only once the server returns the recorded decision
 * (its DEC- id); until then the UI shows "recording", never "approved".
 *
 * `onRecorded` runs at the hook level, so it runs even when the gate that was decided disappears
 * (a decided gate stops waiting and its card unmounts). The refetch is not awaited for the same reason.
 */
export function useDecision<Body>(runId: string, path: string, onRecorded?: (r: DecisionRecorded) => void) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: Body; key: string }) =>
      postJson<DecisionRecorded>(`/api/v1/runs/${enc(runId)}${path}`, body, key),
    onSuccess: (r) => onRecorded?.(r),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.run(runId) });
    },
  });
}

export function useResume(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (acceptPending: boolean) =>
      postJson<{ run_id: string; message: string }>(`/api/v1/runs/${enc(runId)}/resume`, { accept_pending: acceptPending },
        newIdempotencyKey()),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: keys.run(runId) });
    },
  });
}

export function useStartRun() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: Record<string, unknown>; key: string }) =>
      postJson<{ run_id: string }>('/api/v1/runs', body, key),
    onSettled: () => client.invalidateQueries({ queryKey: ['runs'] }),
  });
}
