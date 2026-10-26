import { useEffect, useState } from 'react';
import { api, type EnvironmentView, type PgVcStatus } from './api';

// Step 1 shell: shows configured environments and whether version control is set up in each.
// The commit graph and editors arrive in the next steps.
export function App() {
  const [envs, setEnvs] = useState<EnvironmentView[] | null>(null);
  const [statuses, setStatuses] = useState<Record<string, PgVcStatus>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .environments()
      .then((list) => {
        setEnvs(list);
        list.forEach((e) =>
          api
            .status(e.id)
            .then((s) => setStatuses((prev) => ({ ...prev, [e.id]: s })))
            .catch(() => undefined),
        );
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <main className="shell">
      <h1>PgGitVisualizer</h1>
      <p className="lead">Environments from pgvc-environments.json. Manage them through the REST API described in the README.</p>
      {error && <p className="error">{error}</p>}
      {envs && envs.length === 0 && <p className="muted">No environments yet. Add one with POST /api/environments.</p>}
      {envs && envs.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Environment</th>
              <th>Connection</th>
              <th>Default schema</th>
              <th>Version control</th>
              <th>HEAD</th>
            </tr>
          </thead>
          <tbody>
            {envs.map((e) => {
              const s = statuses[e.id];
              return (
                <tr key={e.id}>
                  <td>
                    <span className="badge" style={{ background: e.colorBadge }} />
                    {e.name}
                    {e.active && <span className="active">active</span>}
                  </td>
                  <td className="mono">{e.jdbcUrl}</td>
                  <td className="mono">{e.schemaName}</td>
                  <td>
                    {!s
                      ? 'checking…'
                      : !s.reachable
                        ? <span className="error">unreachable</span>
                        : s.initialized
                          ? `set up, ${s.pendingDdlCount} uncommitted DDL`
                          : 'not set up'}
                  </td>
                  <td className="mono">{s?.head ? s.head.slice(0, 8) : '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </main>
  );
}
