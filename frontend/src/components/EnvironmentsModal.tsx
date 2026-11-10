import { useEffect, useState } from 'react';
import { CheckCircle2, Plug, Plus, ShieldAlert, Trash2, XCircle } from 'lucide-react';
import { api } from '../api/client';
import {
  ALL_USER_SCHEMAS,
  type ConnectionTestResult,
  type DiscoveryRequest,
  type EnvironmentInput,
  type EnvironmentView,
  type PgVcStatus,
} from '../api/types';
import { DatabaseSchemaPicker } from './DatabaseSchemaPicker';
import { Modal } from './Modal';
import { useI18n } from '../i18n';
import { useToast } from './Toasts';

interface Props {
  environments: EnvironmentView[];
  onChanged: () => void;
  onClose: () => void;
}

const PALETTE = ['#22c55e', '#3b82f6', '#f59e0b', '#ef4444', '#a855f7', '#14b8a6', '#ec4899'];

/** Server (host / port / database + browser) or a hand-written JDBC URL (multi-host, special parameters). */
type ConnectionMode = 'server' | 'jdbc';

function emptyForm(index: number): EnvironmentInput {
  return {
    name: '',
    host: 'localhost',
    port: 5432,
    databaseName: '',
    jdbcUrl: 'jdbc:postgresql://localhost:5432/postgres',
    username: 'postgres',
    password: '',
    schemaName: 'public',
    trackedSchemas: [ALL_USER_SCHEMAS],
    colorBadge: PALETTE[index % PALETTE.length],
  };
}

function toForm(env: EnvironmentView): EnvironmentInput {
  return {
    id: env.id,
    name: env.name,
    host: env.host ?? '',
    port: env.port ?? 5432,
    databaseName: env.databaseName ?? '',
    jdbcUrl: env.jdbcUrl,
    username: env.username,
    password: undefined,
    schemaName: env.schemaName,
    trackedSchemas: env.trackedSchemas?.length ? env.trackedSchemas : [ALL_USER_SCHEMAS],
    colorBadge: env.colorBadge,
  };
}

/** A multi-host (failover) URL cannot be shown as one host: such environments open in JDBC mode. */
function modeOf(env: EnvironmentView | null): ConnectionMode {
  if (!env) return 'server';
  const hosts = /^jdbc:postgresql:\/\/([^/?]*)/.exec(env.jdbcUrl)?.[1] ?? '';
  return !env.host || hosts.includes(',') ? 'jdbc' : 'server';
}

export function EnvironmentsModal({ environments, onChanged, onClose }: Props) {
  const toast = useToast();
  const { t } = useI18n();
  const [selectedId, setSelectedId] = useState<string | null>(environments[0]?.id ?? null);
  const [form, setForm] = useState<EnvironmentInput>(environments[0] ? toForm(environments[0]) : emptyForm(0));
  const [test, setTest] = useState<ConnectionTestResult | null>(null);
  const [status, setStatus] = useState<PgVcStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<ConnectionMode>(modeOf(environments[0] ?? null));

  useEffect(() => {
    setStatus(null);
    if (selectedId) {
      api.status(selectedId).then(setStatus).catch(() => setStatus(null));
    }
  }, [selectedId]);

  function select(env: EnvironmentView | null) {
    setSelectedId(env?.id ?? null);
    setForm(env ? toForm(env) : emptyForm(environments.length));
    setMode(modeOf(env));
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

  // an empty password field of a saved environment means "keep the stored password"; in JDBC mode the host is
  // left empty, so the server uses the URL as it is
  const payload = (): EnvironmentInput => ({
    ...form,
    host: mode === 'server' ? (form.host ?? '').trim() : '',
    port: mode === 'server' ? Number(form.port) || 5432 : undefined,
    databaseName: mode === 'server' ? (form.databaseName ?? '').trim() : undefined,
    password: form.password ? form.password : selectedId ? undefined : '',
  });

  // the connection the database / schema browser uses (stored password when the field is empty)
  const connection: DiscoveryRequest = {
    id: selectedId ?? undefined,
    host: mode === 'server' ? (form.host ?? '').trim() || undefined : undefined,
    port: mode === 'server' ? Number(form.port) || 5432 : undefined,
    username: form.username,
    password: form.password ? form.password : selectedId ? undefined : '',
    jdbcUrl: form.jdbcUrl || undefined,
  };
  const pickedDatabase = mode === 'server' ? (form.databaseName ?? '').trim() || null : jdbcDatabase(form.jdbcUrl);

  const save = () =>
    run(async () => {
      const saved = selectedId ? await api.updateEnvironment(selectedId, payload()) : await api.addEnvironment(payload());
      toast.success(t('environments.saved', { name: saved.name }));
      setSelectedId(saved.id);
      setForm(toForm(saved));
      onChanged();
    });

  return (
    <Modal
      title={t('environments.title')}
      subtitle={t('environments.subtitle')}
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
              {env.active && <span className="text-[10.5px] text-teal-400">{t('environments.active')}</span>}
            </button>
          ))}
          <button
            onClick={() => select(null)}
            className={`flex items-center gap-2 rounded-md px-2.5 py-2 text-left text-teal-400 ${
              selectedId === null ? 'bg-kraken-raised' : 'hover:bg-kraken-hover'
            }`}
          >
            <Plus size={14} /> {t('environments.add')}
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
              <span className="label">{t('environments.name')}</span>
              <input className="input" placeholder={t('environments.namePlaceholder')} required {...field('name')} />
            </label>
            <label>
              <span className="label">{t('environments.color')}</span>
              <input
                type="color"
                className="h-8 w-16 cursor-pointer rounded-md border border-kraken-line bg-kraken-bg p-0.5"
                {...field('colorBadge')}
              />
            </label>
          </div>
          <div className="flex items-center gap-1 text-[12px]" role="group" aria-label={t('environments.connectionMode')}>
            {(['server', 'jdbc'] as const).map((m) => (
              <button
                key={m}
                type="button"
                className={`rounded-md border px-2 py-0.5 ${
                  mode === m ? 'border-teal-600 bg-teal-500/15 text-gray-100' : 'border-kraken-line text-gray-400 hover:text-gray-200'
                }`}
                aria-pressed={mode === m}
                onClick={() => setMode(m)}
              >
                {m === 'server' ? t('environments.modeServer') : t('environments.modeJdbc')}
              </button>
            ))}
          </div>
          {mode === 'server' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_110px] gap-3">
              <label>
                <span className="label">{t('environments.host')}</span>
                <input className="input font-mono" spellCheck={false} required {...field('host')} placeholder="db.example.com" />
              </label>
              <label>
                <span className="label">{t('environments.port')}</span>
                <input
                  className="input font-mono"
                  type="number"
                  min={1}
                  max={65535}
                  value={form.port ?? 5432}
                  onChange={(e) => setForm({ ...form, port: e.target.value === '' ? undefined : Number(e.target.value) })}
                />
              </label>
            </div>
          ) : (
            <label>
              <span className="label">{t('environments.jdbcUrl')}</span>
              <input className="input font-mono" spellCheck={false} required {...field('jdbcUrl')} />
            </label>
          )}
          <div className="grid grid-cols-3 gap-3">
            <label>
              <span className="label">{t('environments.user')}</span>
              <input className="input" autoComplete="off" {...field('username')} />
            </label>
            <label>
              <span className="label">{t('environments.password')}</span>
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                placeholder={selectedId ? t('environments.passwordUnchanged') : ''}
                {...field('password')}
              />
            </label>
            <label>
              <span className="label">{t('environments.defaultSchema')}</span>
              <input className="input" {...field('schemaName')} />
            </label>
          </div>
          <div>
            <div className="mb-1 flex items-baseline gap-2">
              <span className="label mb-0">{t('environments.databaseAndSchemas')}</span>
              <span className="text-[11.5px] text-gray-400">
                {pickedDatabase ? (
                  <>
                    <span className="font-mono text-gray-200">{pickedDatabase}</span>
                    {' · '}
                    {form.trackedSchemas?.includes(ALL_USER_SCHEMAS)
                      ? t('discovery.allSchemas')
                      : (form.trackedSchemas ?? []).join(', ') || t('discovery.noneSelected')}
                  </>
                ) : (
                  t('discovery.pickDatabase')
                )}
              </span>
            </div>
            <DatabaseSchemaPicker
              connection={connection}
              databaseName={pickedDatabase}
              onDatabaseChange={(db) => {
                if (mode === 'server') setForm({ ...form, databaseName: db, trackedSchemas: [ALL_USER_SCHEMAS] });
                else setForm({ ...form, jdbcUrl: withJdbcDatabase(form.jdbcUrl ?? '', db), trackedSchemas: [ALL_USER_SCHEMAS] });
              }}
              tracked={form.trackedSchemas ?? [ALL_USER_SCHEMAS]}
              onTrackedChange={(trackedSchemas) => setForm({ ...form, trackedSchemas })}
            />
          </div>
          <p className="text-xs text-gray-500">
            {t('environments.hint')}
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
              <ShieldAlert size={15} /> {t('environments.notInitialized')}
            </div>
          )}

          <div className="mt-1 flex flex-wrap items-center gap-2">
            {selectedId && (
              <button
                type="button"
                className="btn btn-danger"
                disabled={busy}
                onClick={() => {
                  if (!window.confirm(t('environments.removeConfirm', { name: form.name }))) return;
                  void run(async () => {
                    await api.deleteEnvironment(selectedId);
                    onChanged();
                    const rest = environments.filter((e) => e.id !== selectedId);
                    select(rest[0] ?? null);
                  });
                }}
              >
                <Trash2 size={14} /> {t('environments.remove')}
              </button>
            )}
            <span className="flex-1" />
            <button
              type="button"
              className="btn"
              disabled={busy}
              onClick={() => void run(async () => setTest(await api.testConnection(payload())))}
            >
              <Plug size={14} /> {t('environments.test')}
            </button>
            {status && status.reachable && !status.initialized && selectedId && (
              <button
                type="button"
                className="btn"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    setStatus(await api.initialize(selectedId));
                    toast.success(t('environments.initialized'));
                    onChanged();
                  })
                }
              >
                {t('environments.initialize')}
              </button>
            )}
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {t('common.save')}
            </button>
          </div>
        </form>
      </div>
    </Modal>
  );
}

/** Database of a JDBC URL (jdbc:postgresql://host:port/db?params), null when it has none. */
function jdbcDatabase(url: string | undefined): string | null {
  const m = /^jdbc:postgresql:\/\/[^/?]*\/([^?]*)/.exec(url ?? '');
  return m && m[1] ? decodeURIComponent(m[1]) : null;
}

/** The JDBC URL pointing to another database of the same server (parameters kept). */
function withJdbcDatabase(url: string, database: string): string {
  const m = /^(jdbc:postgresql:\/\/[^/?]*)(?:\/[^?]*)?(\?.*)?$/.exec(url);
  if (!m) return url;
  return `${m[1]}/${encodeURIComponent(database)}${m[2] ?? ''}`;
}
