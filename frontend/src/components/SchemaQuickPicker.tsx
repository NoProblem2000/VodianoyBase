import { useEffect, useRef, useState } from 'react';
import { ChevronDown, FolderTree } from 'lucide-react';
import { api } from '../api/client';
import { ALL_USER_SCHEMAS, type EnvironmentView } from '../api/types';
import { useI18n } from '../i18n';
import { SchemaList, schemasLabel } from './DatabaseSchemaPicker';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  /** the tracked schemas were saved: reload the working tree */
  onChanged: () => void;
}

/**
 * Quick selector of the top bar: which schemas of the active database are under version control ("scada, mdc" /
 * "All schemas"). Opens the schema pane of the database browser; Apply saves the environment.
 */
export function SchemaQuickPicker({ env, onChanged }: Props) {
  const { t } = useI18n();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [tracked, setTracked] = useState<string[]>(env.trackedSchemas ?? [ALL_USER_SCHEMAS]);
  const [busy, setBusy] = useState(false);
  const box = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) setTracked(env.trackedSchemas ?? [ALL_USER_SCHEMAS]);
  }, [open, env.trackedSchemas]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as globalThis.Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('mousedown', onPointer, true);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onPointer, true);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const database = env.databaseName ?? null;
  const changed = JSON.stringify(tracked) !== JSON.stringify(env.trackedSchemas ?? [ALL_USER_SCHEMAS]);

  async function apply() {
    setBusy(true);
    try {
      const saved = await api.updateEnvironment(env.id, {
        id: env.id,
        name: env.name,
        // the stored connection is kept: host empty = use the stored JDBC URL as it is
        host: '',
        jdbcUrl: env.jdbcUrl,
        username: env.username,
        password: undefined,
        schemaName: env.schemaName,
        trackedSchemas: tracked,
        colorBadge: env.colorBadge,
      });
      toast.success(t('discovery.saved', { name: saved.name, schemas: schemasLabel(saved.trackedSchemas, t('discovery.allSchemas'), 6) }));
      setOpen(false);
      onChanged();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div ref={box} className="relative">
      <button
        type="button"
        className="flex h-8 max-w-[220px] items-center gap-1.5 rounded-md border border-kraken-line bg-kraken-bg px-2 text-[12px] text-gray-300 hover:text-gray-100"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-label={t('discovery.quickTitle')}
        title={`${t('discovery.quickTitle')}: ${database ?? '—'} · ${schemasLabel(env.trackedSchemas, t('discovery.allSchemas'), 20)}`}
      >
        <FolderTree size={13} className="shrink-0 text-gray-500" />
        {database && <span className="hidden truncate font-mono text-gray-400 xl:inline">{database}</span>}
        {database && <span className="hidden text-gray-600 xl:inline">/</span>}
        <span className="truncate">{schemasLabel(env.trackedSchemas, t('discovery.allSchemas'))}</span>
        <ChevronDown size={12} className="shrink-0 text-gray-500" />
      </button>
      {open && (
        <div className="absolute left-0 top-9 z-50 flex h-[340px] w-[360px] flex-col overflow-hidden rounded-lg border border-[#374151] bg-[#111827] shadow-[0_12px_32px_rgba(0,0,0,0.55)]">
          <div className="min-h-0 flex-1">
            <SchemaList
              connection={{ id: env.id, username: env.username, jdbcUrl: env.jdbcUrl }}
              databaseName={database}
              tracked={tracked}
              onTrackedChange={setTracked}
            />
          </div>
          <div className="flex items-center gap-2 border-t border-[#374151] px-2.5 py-2">
            <span className="flex-1 text-[11px] text-gray-500">{t('discovery.quickHint')}</span>
            <button type="button" className="btn" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="btn btn-primary" onClick={() => void apply()} disabled={busy || !changed || tracked.length === 0}>
              {t('discovery.apply')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
