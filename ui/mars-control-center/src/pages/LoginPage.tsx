import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, LogIn } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { ApiError, getJson, postJson } from '../api/client';
import { keys, useSession } from '../api/queries';
import type { DevUserOption, SessionView } from '../api/types';
import { Button } from '../components/ui';

export function LoginPage() {
  const session = useSession();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = params.get('next') ?? '/runs';
  const users = useQuery({
    queryKey: ['dev-users'],
    queryFn: () => getJson<DevUserOption[]>('/api/v1/session/dev-users'),
    enabled: session.data?.auth_mode === 'dev',
  });
  const [username, setUsername] = useState('approver');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  if (session.data?.authenticated) return <Navigate to={next} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const s = await postJson<SessionView>('/api/v1/session/login', { username, password });
      client.setQueryData(keys.session, s);
      navigate(next, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Sign-in failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="flex min-h-full items-center justify-center p-6">
      <div className="w-full max-w-sm rounded-lg border border-border bg-panel p-6">
        <div className="mb-5 flex items-center gap-2">
          <img src="/mars.svg" alt="" className="size-7" />
          <div>
            <h1 className="text-[15px] font-semibold text-strong">MARS Control Center</h1>
            <p className="text-muted">Migration and Remediation System</p>
          </div>
        </div>
        {session.data?.auth_mode === 'oidc' ? (
          <p className="text-muted">
            This Control Center uses your organisation's identity provider. Sign in there; the API accepts its bearer
            token.
          </p>
        ) : (
          <form onSubmit={submit} className="space-y-3">
            <div role="note" className="flex gap-2 rounded border border-warning/40 bg-warning-soft p-2 text-[12px] text-text">
              <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
              <span>{session.data?.warning}</span>
            </div>
            <label className="block">
              <span className="mb-1 block text-muted">Development user</span>
              <select
                className="h-8 w-full rounded border border-border-strong bg-panel-2 px-2"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              >
                {(users.data ?? []).map((u) => (
                  <option key={u.username} value={u.username}>
                    {u.display_name} ({u.roles.join(', ')})
                  </option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block text-muted">Password</span>
              <input
                type="password"
                autoComplete="current-password"
                className="h-8 w-full rounded border border-border-strong bg-panel-2 px-2"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
            {error && (
              <p role="alert" className="text-danger">
                {error}
              </p>
            )}
            <Button type="submit" variant="primary" className="w-full" loading={busy}>
              <LogIn aria-hidden className="size-4" /> Sign in
            </Button>
          </form>
        )}
      </div>
    </main>
  );
}
