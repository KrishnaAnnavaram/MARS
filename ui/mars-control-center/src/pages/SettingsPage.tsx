import { useState } from 'react';
import { useSession } from '../api/queries';
import { KeyValues, Panel, Tabs } from '../components/ui';
import { applyTheme, storedTheme, type Theme } from '../lib/theme';

export function SettingsPage() {
  const session = useSession().data;
  const [theme, setTheme] = useState<Theme>(storedTheme());
  return (
    <div className="max-w-4xl space-y-3 p-4">
      <h1 className="text-[15px] font-semibold text-strong">Settings</h1>
      <Panel title="Appearance">
        <Tabs label="Theme" value={theme} onChange={(t) => { setTheme(t); applyTheme(t); }}
          options={[{ value: 'dark', label: 'Dark' }, { value: 'light', label: 'Light' }]} />
      </Panel>
      <Panel title="Identity">
        <KeyValues rows={[
          ['User', session?.display_name ?? session?.username],
          ['Roles', session?.roles.join(', ')],
          ['Permissions', session?.permissions.join(', ')],
          ['Authentication mode', session?.auth_mode],
          ['Decisions record', session?.authentication],
        ]} />
        {session?.warning && <p className="mt-2 text-[12px] text-warning">{session.warning}</p>}
        <p className="mt-2 text-[12px] text-faint">
          Roles are enforced by the server. This interface only hides controls you cannot use.
        </p>
      </Panel>
      <Panel title="How to read the Control Center">
        <ul className="list-disc space-y-1 pl-5 text-muted">
          <li>Every value comes from the run's persisted artifacts or its execution events. Nothing is simulated.</li>
          <li>Progress is a domain count (findings analysed, stages completed); when the total is unknown the bar is indeterminate. No ETA is shown.</li>
          <li>"Approved" appears only after the approval store returned a decision ID; "Applied" only after the Mutation Gateway recorded it.</li>
          <li>Integrity (HMAC, hash chains) proves a record was not modified. It is not authentication.</li>
          <li>A disconnected event stream means this page is not receiving events; it never means the run stopped.</li>
        </ul>
      </Panel>
      <Panel title="API">
        <a className="text-primary hover:underline" href="/swagger-ui.html" target="_blank" rel="noreferrer">OpenAPI documentation</a>
      </Panel>
    </div>
  );
}
