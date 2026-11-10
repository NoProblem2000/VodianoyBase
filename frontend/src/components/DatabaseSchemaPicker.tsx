import { useEffect, useState } from 'react';
import { Database, FolderTree, Loader2, RefreshCw } from 'lucide-react';
import { api } from '../api/client';
import { ALL_USER_SCHEMAS, type DiscoveredDatabase, type DiscoveredSchema, type DiscoveryRequest } from '../api/types';
import { useI18n } from '../i18n';

/** Only the picked schemas, or null for "every user schema". */
export function explicitSchemas(tracked: string[] | undefined | null): string[] | null {
  if (!tracked || tracked.length === 0 || tracked.includes(ALL_USER_SCHEMAS)) return null;
  return tracked;
}

/** "scada, mdc +1" / "All schemas". */
export function schemasLabel(tracked: string[] | undefined | null, all: string, max = 2): string {
  const list = explicitSchemas(tracked);
  if (!list) return all;
  return list.length <= max ? list.join(', ') : `${list.slice(0, max).join(', ')} +${list.length - max}`;
}

interface SchemaListProps {
  connection: DiscoveryRequest;
  /** database to list the schemas of; null shows a hint */
  databaseName: string | null;
  tracked: string[];
  onTrackedChange: (tracked: string[]) => void;
  /**
   * false: do not connect yet (a new environment before "Load databases" - its password may still be missing); the
   * pane shows a hint instead
   */
  enabled?: boolean;
}

/** Right pane: "All schemas" plus one checkbox per user schema of the database, with comments and object counts. */
export function SchemaList({ connection, databaseName, tracked, onTrackedChange, enabled = true }: SchemaListProps) {
  const { t, tn } = useI18n();
  const [schemas, setSchemas] = useState<DiscoveredSchema[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const key = JSON.stringify({ ...connection, databaseName });

  useEffect(() => {
    if (!databaseName || !enabled) {
      setSchemas(null);
      setError(null);
      return;
    }
    let cancelled = false;
    // typing in the connection fields must not open a connection per key stroke
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError(null);
      api
        .discoverSchemas({ ...connection, databaseName })
        .then((list) => !cancelled && setSchemas(list))
        .catch((e: Error) => {
          if (cancelled) return;
          setSchemas(null);
          setError(e.message);
        })
        .finally(() => !cancelled && setLoading(false));
    }, 350);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    // the request is described completely by the key
  }, [key, enabled]); // eslint-disable-line react-hooks/exhaustive-deps

  const all = explicitSchemas(tracked) === null;
  const picked = new Set(explicitSchemas(tracked) ?? []);
  const userSchemas = (schemas ?? []).filter((s) => !s.extension);

  function toggle(name: string) {
    const base = all ? userSchemas.map((s) => s.name) : [...picked];
    const next = base.includes(name) ? base.filter((n) => n !== name) : [...base, name];
    // order of the database; picked schemas that do not exist (yet) stay at the end - they may be created later
    const known = userSchemas.map((s) => s.name);
    onTrackedChange([...known.filter((n) => next.includes(n)), ...next.filter((n) => !known.includes(n))]);
  }

  return (
    <div className="flex min-h-0 flex-col" data-testid="schema-list">
      <div className="flex items-center gap-1.5 border-b border-kraken-line px-2.5 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
        <FolderTree size={12} />
        <span className="flex-1">{databaseName ? t('discovery.schemasOf', { db: databaseName }) : t('discovery.schemas')}</span>
        {loading && <Loader2 size={12} className="animate-spin" />}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5">
        {(!databaseName || !enabled) && <p className="px-1.5 py-2 text-[12px] text-gray-500">{t('discovery.pickDatabase')}</p>}
        {error && <p className="rounded border border-red-800 bg-red-950/40 px-2 py-1.5 text-[12px] text-red-200">{error}</p>}
        {databaseName && schemas && (
          <>
            <label className="flex cursor-pointer items-start gap-2 rounded px-1.5 py-1 hover:bg-kraken-hover">
              <input
                type="checkbox"
                className="mt-0.5 accent-teal-500"
                checked={all}
                onChange={() => onTrackedChange(all ? userSchemas.map((s) => s.name) : [ALL_USER_SCHEMAS])}
              />
              <span>
                <span className="font-medium text-gray-100">{t('discovery.allSchemas')}</span>
                <span className="block text-[11px] text-gray-500">{t('discovery.allSchemasHint')}</span>
              </span>
            </label>
            <div className="my-1 border-t border-kraken-line/60" />
            {schemas.length === 0 && <p className="px-1.5 py-1 text-[12px] text-gray-500">{t('discovery.noSchemas')}</p>}
            {schemas.map((s) => (
              <label
                key={s.name}
                className={`flex items-start gap-2 rounded px-1.5 py-1 ${s.extension ? 'opacity-50' : 'cursor-pointer hover:bg-kraken-hover'}`}
                title={s.extension ? t('discovery.extensionSchema') : s.comment ?? undefined}
              >
                <input
                  type="checkbox"
                  className="mt-0.5 accent-teal-500"
                  checked={!s.extension && (all || picked.has(s.name))}
                  disabled={s.extension}
                  onChange={() => toggle(s.name)}
                  aria-label={t('discovery.schemaCheckbox', { name: s.name })}
                />
                <span className="min-w-0 flex-1">
                  <span className="flex items-baseline gap-2">
                    <span className="font-mono text-[12.5px] text-gray-100">{s.name}</span>
                    <span className="text-[10.5px] text-gray-500">{tn('plural.objects', s.tableCount)}</span>
                    {s.owner && <span className="truncate text-[10.5px] text-gray-600">{s.owner}</span>}
                  </span>
                  {s.comment && <span className="block truncate text-[11px] text-gray-400">{s.comment}</span>}
                  {s.extension && <span className="block text-[11px] text-gray-500">{t('discovery.extensionSchema')}</span>}
                </span>
              </label>
            ))}
          </>
        )}
      </div>
    </div>
  );
}

interface PickerProps {
  connection: DiscoveryRequest;
  databaseName: string | null;
  onDatabaseChange: (name: string) => void;
  tracked: string[];
  onTrackedChange: (tracked: string[]) => void;
}

/**
 * DataGrip-like browser of a server: "Load databases" connects to the maintenance database "postgres" and lists the
 * databases (left); picking one lists its schemas with checkboxes and "All schemas" (right).
 */
export function DatabaseSchemaPicker({ connection, databaseName, onDatabaseChange, tracked, onTrackedChange }: PickerProps) {
  const { t } = useI18n();
  const [databases, setDatabases] = useState<DiscoveredDatabase[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setDatabases(await api.discoverDatabases({ ...connection, databaseName: databaseName ?? undefined }));
    } catch (e) {
      setDatabases(null);
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-col gap-2" data-testid="database-schema-picker">
      <div className="flex items-center gap-2">
        <button type="button" className="btn" onClick={() => void load()} disabled={loading || (!connection.host && !connection.jdbcUrl)}>
          {loading ? <Loader2 size={14} className="animate-spin" /> : databases ? <RefreshCw size={14} /> : <Database size={14} />}
          {databases ? t('discovery.reload') : t('discovery.load')}
        </button>
        <span className="text-[11.5px] text-gray-500">{t('discovery.hint')}</span>
      </div>
      {error && <p className="rounded border border-red-800 bg-red-950/40 px-2 py-1.5 text-[12px] text-red-200" role="alert">{error}</p>}
      <div className="grid h-[240px] grid-cols-[minmax(0,2fr)_minmax(0,3fr)] overflow-hidden rounded-md border border-kraken-line bg-kraken-bg">
        <div className="flex min-h-0 flex-col border-r border-kraken-line">
          <div className="flex items-center gap-1.5 border-b border-kraken-line px-2.5 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-gray-500">
            <Database size={12} />
            {t('discovery.databases')}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-1.5" role="listbox" aria-label={t('discovery.databases')}>
            {!databases && (
              <p className="px-1.5 py-2 text-[12px] text-gray-500">
                {databaseName ? t('discovery.currentDatabase', { db: databaseName }) : t('discovery.notLoaded')}
              </p>
            )}
            {databases?.map((d) => (
              <button
                key={d.name}
                type="button"
                role="option"
                aria-selected={d.name === databaseName}
                className={`flex w-full flex-col items-start rounded px-2 py-1 text-left ${
                  d.name === databaseName ? 'bg-teal-500/15 text-gray-100 ring-1 ring-teal-600/60' : 'text-gray-300 hover:bg-kraken-hover'
                } ${d.canConnect ? '' : 'opacity-50'}`}
                onClick={() => d.canConnect && onDatabaseChange(d.name)}
                disabled={!d.canConnect}
                title={d.canConnect ? d.comment ?? undefined : t('discovery.noConnect')}
              >
                <span className="w-full truncate font-mono text-[12.5px]">{d.name}</span>
                {d.comment && <span className="w-full truncate text-[11px] text-gray-500">{d.comment}</span>}
              </button>
            ))}
          </div>
        </div>
        <SchemaList
          connection={connection}
          databaseName={databaseName}
          tracked={tracked}
          onTrackedChange={onTrackedChange}
          enabled={databases !== null || !!connection.id}
        />
      </div>
    </div>
  );
}
