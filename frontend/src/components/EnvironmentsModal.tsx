import { useEffect, useState } from 'react';
import { CheckCircle2, Plug, Plus, ShieldAlert, Trash2, XCircle } from 'lucide-react';
import { api } from '../api/client';
import type { ConnectionTestResult, EnvironmentInput, EnvironmentView, PgVcStatus } from '../api/types';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  environments: EnvironmentView[];
  onChanged: () => void;
  onClose: () => void;
}

const PALETTE = ['#22c55e', '#3b82f6', '#f59e0b', '#ef4444', '#a855f7', '#14b8a6', '#ec4899'];

function emptyForm(index: number): EnvironmentInput {
  return {
    name: '',
    jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
    username: 'postgres',
    password: '',
    schemaName: 'public',
    colorBadge: PALETTE[index % PALETTE.length],
  };
}

function toForm(env: EnvironmentView): EnvironmentInput {
  return {
    id: env.id,
    name: env.name,
    jdbcUrl: env.jdbcUrl,
    username: env.username,
    password: undefined,
    schemaName: env.schemaName,
    colorBadge: env.colorBadge,
  };
}

export function EnvironmentsModal({ environments, onChanged, onClose }: Props) {
  const toast = useToast();
  const [selectedId, setSelectedId] = useState<string | null>(environments[0]?.id ?? null);
  const [form, setForm] = useState<EnvironmentInput>(environments[0] ? toForm(environments[0]) : emptyForm(0));
  const [test, setTest] = useState<ConnectionTestResult | null>(null);
  const [status, setStatus] = useState<PgVcStatus | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setStatus(null);
    if (selectedId) {
      api.status(selectedId).then(setStatus).catch(() => setStatus(null));
    }
  }, [selectedId]);

  function select(env: EnvironmentView | null) {
    setSelectedId(env?.id ?? null);
    setForm(env ? toForm(env) : emptyForm(environments.length));
    setTest(null);
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const field = (key: keyof EnvironmentInput) => ({
    value: (form[key] as string | undefined) ?? '',
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value }),
  });

  // an empty password field of a saved environment means "keep the stored password"
  const payload = (): EnvironmentInput => ({ ...form, password: form.password ? form.password : selectedId ? undefined : '' });

  const save = () =>
    run(async () => {
      const saved = selectedId ? await api.updateEnvironment(selectedId, payload()) : await api.addEnvironment(payload());
      toast.success(`Saved ${saved.name}`);
      setSelectedId(saved.id);
      setForm(toForm(saved));
      onChanged();
    });

  return (
    <Modal
      title="Environments"
      subtitle="Database connections are stored in pgvc-environments.json next to the application."
      onClose={onClose}
      width="max-w-4xl"
    >
      <div className="grid grid-cols-[220px_1fr] gap-5">
        <nav className="flex flex-col gap-0.5">
          {environments.map((env) => (
            <button
              key={env.id}
              onClick={() => select(env)}
              className={`flex items-center gap-2 rounded-md px-2.5 py-2 text-left ${
                env.id === selectedId ? 'bg-kraken-raised text-gray-100' : 'text-gray-300 hover:bg-kraken-hover'
              }`}
            >
              <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: env.colorBadge }} />
              <span className="flex-1 truncate">{env.name}</span>
              {env.active && <span className="text-[10.5px] text-teal-400">active</span>}
            </button>
          ))}
          <button
            onClick={() => select(null)}
            className={`flex items-center gap-2 rounded-md px-2.5 py-2 text-left text-teal-400 ${
              selectedId === null ? 'bg-kraken-raised' : 'hover:bg-kraken-hover'
            }`}
          >
            <Plus size={14} /> Add environment
          </button>
        </nav>

        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <div className="grid grid-cols-[1fr_auto] gap-3">
            <label>
              <span className="label">Name</span>
              <input className="input" placeholder="Dev, Test, Stage, Prod" required {...field('name')} />
            </label>
            <label>
              <span className="label">Badge color</span>
              <input
                type="color"
                className="h-8 w-16 cursor-pointer rounded-md border border-kraken-line bg-kraken-bg p-0.5"
                {...field('colorBadge')}
              />
            </label>
          </div>
          <label>
            <span className="label">JDBC URL</span>
            <input className="input font-mono" spellCheck={false} required {...field('jdbcUrl')} />
          </label>
          <div className="grid grid-cols-3 gap-3">
            <label>
              <span className="label">User</span>
              <input className="input" autoComplete="off" {...field('username')} />
            </label>
            <label>
              <span className="label">Password</span>
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                placeholder={selectedId ? 'unchanged' : ''}
                {...field('password')}
              />
            </label>
            <label>
              <span className="label">Default schema</span>
              <input className="input" {...field('schemaName')} />
            </label>
          </div>
          <p className="text-xs text-gray-500">
            Version control tracks every user schema of the database; the default schema is only the search_path of the
            connection. Initializing version control needs a SUPERUSER (it installs DDL event triggers).
          </p>

          {test && (
            <div
              className={`flex items-start gap-2 rounded-md border px-3 py-2 text-[12.5px] ${
                test.success
                  ? test.superuser
                    ? 'border-teal-800 bg-teal-950/40 text-teal-200'
                    : 'border-amber-800 bg-amber-950/40 text-amber-200'
                  : 'border-red-800 bg-red-950/40 text-red-200'
              }`}
            >
              {test.success ? <CheckCircle2 size={15} className="mt-0.5" /> : <XCircle size={15} className="mt-0.5" />}
              <div>
                <div>{test.message}</div>
                {test.version && <div className="mt-0.5 font-mono text-[11.5px] opacity-80">{test.version}</div>}
              </div>
            </div>
          )}
          {status && status.reachable && !status.initialized && (
            <div className="flex items-center gap-2 rounded-md border border-amber-800 bg-amber-950/40 px-3 py-2 text-[12.5px] text-amber-200">
              <ShieldAlert size={15} /> Version control is not initialized in this database yet.
            </div>
          )}

          <div className="mt-1 flex flex-wrap items-center gap-2">
            {selectedId && (
              <button
                type="button"
                className="btn btn-danger"
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
                <Trash2 size={14} /> Remove
              </button>
            )}
            <span className="flex-1" />
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={() => void run(async () => setTest(await api.testConnection(payload())))}
            >
              <Plug size={14} /> Test connection
            </button>
            {status && status.reachable && !status.initialized && selectedId && (
              <button
                type="button"
                className="btn"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    setStatus(await api.initialize(selectedId));
                    toast.success('Version control initialized: schema _pgvc and DDL event triggers installed');
                    onChanged();
                  })
                }
              >
                Initialize version control
              </button>
            )}
            <button type="submit" className="btn btn-primary" disabled={busy}>
              Save
            </button>
          </div>
        </form>
      </div>
    </Modal>
  );
}
