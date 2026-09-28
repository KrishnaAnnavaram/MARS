import { Play, Plus, Search } from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { newIdempotencyKey } from '../api/client';
import { useRepositories, useRuns, useSession, useStartRun } from '../api/queries';
import type { RepositoryOption } from '../api/types';
import { Button, EmptyState, ErrorState, LoadingState, Modal, StatusBadge, Table, Tabs, td, th } from '../components/ui';
import { between, formatDateTime, formatDuration } from '../lib/format';
import { livenessStatus, lookup, verdictStatus } from '../lib/status';

const FILTERS = [
  { value: '', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'waiting', label: 'Waiting' },
  { value: 'needs_human', label: 'Needs human' },
  { value: 'complete', label: 'Complete' },
  { value: 'failed', label: 'Failed' },
] as const;

type Filter = (typeof FILTERS)[number]['value'];

function StartRunDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const repos = useRepositories(open);
  const start = useStartRun();
  const navigate = useNavigate();
  const [repo, setRepo] = useState<RepositoryOption>();
  const [inputs, setInputs] = useState<string[]>([]);
  const [probes, setProbes] = useState(true);
  const [skipBuild, setSkipBuild] = useState(false);
  const [key] = useState(newIdempotencyKey);

  const choose = (path: string) => {
    const r = repos.data?.find((x) => x.path === path);
    setRepo(r);
    setInputs(r?.finding_inputs ?? []);
  };

  const submit = () => {
    if (!repo) return;
    start.mutate(
      {
        key,
        body: {
          repository: repo.path,
          finding_inputs: inputs,
          probes: probes ? repo.probes : undefined,
          skip_build: skipBuild,
        },
      },
      { onSuccess: (r) => navigate(`/runs/${r.run_id}/pipeline`) },
    );
  };

  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Start a MARS run"
      description="Phases 0-4 run (ingest, inventory, identity, graph, baseline, discovery) and stop at Human Gate A. Nothing is executed without a recorded decision.">
      {repos.isLoading && <LoadingState />}
      {repos.error && <ErrorState error={repos.error} />}
      {repos.data && repos.data.length === 0 && (
        <EmptyState title="No repositories available">
          The server offers repositories only from its configured roots (mars.control-center.repository-roots).
        </EmptyState>
      )}
      {repos.data && repos.data.length > 0 && (
        <div className="space-y-3">
          <label className="block">
            <span className="mb-1 block text-muted">Repository (under a configured root; never modified)</span>
            <select className="h-8 w-full rounded border border-border-strong bg-panel-2 px-2" value={repo?.path ?? ''}
              onChange={(e) => choose(e.target.value)}>
              <option value="" disabled>Choose a repository…</option>
              {repos.data.map((r) => (
                <option key={r.path} value={r.path}>{r.path}</option>
              ))}
            </select>
          </label>
          {repo && (
            <fieldset className="space-y-1">
              <legend className="mb-1 text-muted">Finding inputs</legend>
              {repo.finding_inputs.length === 0 && <p className="text-faint">None found next to this repository.</p>}
              {repo.finding_inputs.map((i) => (
                <label key={i} className="flex items-center gap-2">
                  <input type="checkbox" checked={inputs.includes(i)}
                    onChange={(e) => setInputs(e.target.checked ? [...inputs, i] : inputs.filter((x) => x !== i))} />
                  <span className="mono">{i}</span>
                </label>
              ))}
              {repo.probes && (
                <label className="flex items-center gap-2">
                  <input type="checkbox" checked={probes} onChange={(e) => setProbes(e.target.checked)} />
                  <span>Behaviour probes <span className="mono text-faint">{repo.probes}</span></span>
                </label>
              )}
              <label className="flex items-center gap-2">
                <input type="checkbox" checked={skipBuild} onChange={(e) => setSkipBuild(e.target.checked)} />
                <span>Skip the baseline build (recorded as NOT_RUN, never as passed; migration cannot execute)</span>
              </label>
            </fieldset>
          )}
          {start.error && <ErrorState error={start.error} title="The run was not started" />}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)}>Cancel</Button>
            <Button variant="primary" disabled={!repo} loading={start.isPending} onClick={submit}>
              <Play aria-hidden className="size-4" /> Start analysis
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}

export function RunsPage() {
  const [status, setStatus] = useState<Filter>('');
  const [capability, setCapability] = useState('');
  const [q, setQ] = useState('');
  const [starting, setStarting] = useState(false);
  const session = useSession();
  const runs = useRuns({ status, capability, q });
  const canOperate = session.data?.permissions.includes('operate');

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="flex h-12 items-center gap-3 border-b border-border bg-panel px-4">
        <h1 className="text-[15px] font-semibold text-strong">Runs</h1>
        <span className="text-muted">{runs.data ? `${runs.data.total} run(s) in the runs root` : ''}</span>
        <div className="ml-auto">
          {canOperate && (
            <Button variant="primary" onClick={() => setStarting(true)}>
              <Plus aria-hidden className="size-4" /> New run
            </Button>
          )}
        </div>
      </header>
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-2">
        <Tabs label="Status filter" value={status} onChange={setStatus} options={FILTERS.map((f) => ({ value: f.value, label: f.label }))} />
        <Tabs label="Capability filter" value={capability} onChange={setCapability}
          options={[{ value: '', label: 'Any capability' }, { value: 'migration', label: 'Migration' }, { value: 'security', label: 'Security' }]} />
        <label className="relative ml-auto">
          <Search aria-hidden className="absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
          <span className="sr-only">Search runs</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Run, application or source"
            className="h-8 w-72 rounded border border-border-strong bg-panel-2 pl-7 pr-2" />
        </label>
      </div>
      <div className="min-h-0 flex-1 overflow-auto p-4">
        {runs.isLoading && <LoadingState label="Reading the runs root…" />}
        {runs.error && <ErrorState error={runs.error} />}
        {runs.data && runs.data.runs.length === 0 && (
          <EmptyState title="No runs match">
            {status || q || capability ? 'Change the filters to see other runs.' : 'Runs started from the CLI or here appear in this list.'}
          </EmptyState>
        )}
        {runs.data && runs.data.runs.length > 0 && (
          <Table label="Runs">
            <thead>
              <tr>
                {['Run', 'Application', 'Source', 'Started', 'Duration', 'Current state', 'Health', 'Migration', 'Security', 'Human actions', 'Verdict']
                  .map((h) => <th key={h} className={th}>{h}</th>)}
              </tr>
            </thead>
            <tbody>
              {runs.data.runs.map((r) => (
                <tr key={r.run_id} className="hover:bg-panel-2">
                  <td className={`${td} whitespace-nowrap`}>
                    <Link className="mono text-primary hover:underline" to={`/runs/${r.run_id}/overview`}>{r.run_id}</Link>
                  </td>
                  <td className={`${td} whitespace-nowrap`}>{r.application ?? '—'}</td>
                  <td className={`${td} mono max-w-[220px] truncate text-muted`} title={r.source}>{r.source ?? '—'}</td>
                  <td className={`${td} whitespace-nowrap text-muted`}>{formatDateTime(r.created_at)}</td>
                  <td className={`${td} mono whitespace-nowrap text-muted`} title="From creation to the last persisted state transition">
                    {formatDuration(between(r.created_at, r.updated_at))}
                  </td>
                  <td className={`${td} mono whitespace-nowrap`}>{r.phase}</td>
                  <td className={td}><StatusBadge status={lookup(livenessStatus, r.liveness.state)} /></td>
                  <td className={td}>{r.migration ?? '—'}</td>
                  <td className={`${td} min-w-[160px] text-muted`}>{r.security ?? '—'}</td>
                  <td className={`${td} whitespace-nowrap`}>{r.human_actions > 0 ? <span className="font-medium text-human">{r.human_actions} · {r.gate}</span> : '—'}</td>
                  <td className={td}>{r.verdict ? <StatusBadge status={lookup(verdictStatus, r.verdict)} /> : <span className="text-faint">not yet</span>}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </div>
      <StartRunDialog open={starting} onOpenChange={setStarting} />
    </div>
  );
}
