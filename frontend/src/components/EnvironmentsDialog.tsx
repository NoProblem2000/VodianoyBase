import { useEffect, useState, type ChangeEvent } from 'react';
import { api } from '../api';
import type { ConnectionTest, EnvironmentConfig, EnvironmentStatus } from '../types';

interface Props {
  environments: EnvironmentConfig[];
  statuses: EnvironmentStatus[];
  onChanged: () => void;
  onClose: () => void;
  onError: (message: string) => void;
  onInfo: (message: string) => void;
}

const EMPTY: EnvironmentConfig = {
  id: '',
  name: '',
  color: '#2dd4bf',
  jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
  username: 'postgres',
  password: '',
};

export function EnvironmentsDialog({ environments, statuses, onChanged, onClose, onError, onInfo }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(environments[0]?.id ?? null);
  const [form, setForm] = useState<EnvironmentConfig>(environments[0] ?? EMPTY);
  const [test, setTest] = useState<ConnectionTest | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  function select(env: EnvironmentConfig | null) {
    setSelectedId(env?.id ?? null);
    setForm(env ?? EMPTY);
    setTest(null);
  }

  const status = statuses.find((s) => s.id === selectedId);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const set = (key: keyof EnvironmentConfig) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: e.target.value });

  return (
    <div className="dialog-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="env-dialog-title">
        <header className="dialog-head">
          <h2 id="env-dialog-title">Environments</h2>
          <button className="btn ghost" onClick={onClose} aria-label="Close">
            Close
          </button>
        </header>
        <div className="dialog-grid">
          <nav className="env-list">
            {environments.map((e) => {
              const st = statuses.find((s) => s.id === e.id);
              return (
                <button
                  key={e.id}
                  className={e.id === selectedId ? 'env-item active' : 'env-item'}
                  onClick={() => select(e)}
                >
                  <span className="env-dot" style={{ background: e.color }} />
                  <span className="env-name">{e.name}</span>
                  <span className={st?.reachable ? 'state ok' : 'state bad'}>
                    {!st ? '' : !st.reachable ? 'offline' : st.installed ? 'tracked' : 'not set up'}
                  </span>
                </button>
              );
            })}
            <button className={selectedId === null ? 'env-item add active' : 'env-item add'} onClick={() => select(null)}>
              + Add environment
            </button>
          </nav>

          <form
            className="env-form"
            onSubmit={(e) => {
              e.preventDefault();
              void run(async () => {
                const saved = selectedId
                  ? await api.updateEnvironment(selectedId, form)
                  : await api.createEnvironment(form);
                setSelectedId(saved.id);
                setForm(saved);
                onInfo(`Saved ${saved.name}`);
                onChanged();
              });
            }}
          >
            <div className="form-row two">
              <label>
                <span>Name</span>
                <input value={form.name} onChange={set('name')} placeholder="Dev, Test, Stage, Prod" required />
              </label>
              <label className="color-field">
                <span>Badge color</span>
                <input type="color" value={form.color} onChange={set('color')} />
              </label>
            </div>
            <label>
              <span>JDBC URL</span>
              <input value={form.jdbcUrl} onChange={set('jdbcUrl')} spellCheck={false} required />
            </label>
            <div className="form-row two">
              <label>
                <span>User</span>
                <input value={form.username} onChange={set('username')} autoComplete="off" />
              </label>
              <label>
                <span>Password</span>
                <input type="password" value={form.password} onChange={set('password')} autoComplete="new-password" />
              </label>
            </div>
            <p className="muted small">
              Stored in plain text in pgvc-environments.json next to the application. The user needs SUPERUSER rights to
              install DDL event triggers.
            </p>

            {test && (
              <p className={test.ok ? (test.superuser ? 'note ok' : 'note warn') : 'note bad'}>
                {test.ok ? `PostgreSQL ${test.serverVersion}. ${test.message}` : test.message}
              </p>
            )}
            {status && status.reachable && !status.installed && (
              <p className="note warn">Version control is not set up in this database yet.</p>
            )}
            {status && status.installed && !status.triggersInstalled && (
              <p className="note warn">The _pgvc schema exists but the DDL event triggers are missing or disabled.</p>
            )}

            <div className="form-actions">
              {selectedId && (
                <button
                  type="button"
                  className="btn danger"
                  disabled={busy}
                  onClick={() => {
                    if (!window.confirm(`Remove ${form.name} from the list? The database itself is not changed.`)) return;
                    void run(async () => {
                      await api.deleteEnvironment(selectedId);
                      onChanged();
                      const rest = environments.filter((e) => e.id !== selectedId);
                      select(rest[0] ?? null);
                    });
                  }}
                >
                  Remove
                </button>
              )}
              <span className="spacer" />
              <button
                type="button"
                className="btn"
                disabled={busy}
                onClick={() => void run(async () => setTest(await api.testConnection({ ...form, id: selectedId ?? undefined })))}
              >
                Test connection
              </button>
              {status && status.reachable && (!status.installed || !status.triggersInstalled) && (
                <button
                  type="button"
                  className="btn"
                  disabled={busy}
                  onClick={() =>
                    void run(async () => {
                      await api.install(status.id);
                      onInfo(`Version control set up in ${status.name}`);
                      onChanged();
                    })
                  }
                >
                  Set up version control
                </button>
              )}
              <button type="submit" className="btn primary" disabled={busy}>
                Save
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
