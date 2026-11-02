import { useEffect, useMemo, useState } from 'react';
import { KeyRound, Pin, Search } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentView, ReferenceDataTable } from '../api/types';
import { useI18n } from '../i18n';
import { formatDate } from '../lib/format';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  author: string;
  initialized: boolean;
  onChanged: () => void;
  onClose: () => void;
}

/** "📌 Reference Data Tables": which tables of the environment have their rows version controlled. */
export function ReferenceDataModal({ env, author, initialized, onChanged, onClose }: Props) {
  const { t, lang } = useI18n();
  const toast = useToast();
  const [tables, setTables] = useState<ReferenceDataTable[] | null>(null);
  const [filter, setFilter] = useState('');
  const [onlyTracked, setOnlyTracked] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  async function load() {
    try {
      setTables(await api.referenceData(env.id));
    } catch (e) {
      toast.error((e as Error).message);
      setTables([]);
    }
  }

  useEffect(() => {
    void load();
    // load once per environment: the toast API is a new object on every render
  }, [env.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function toggle(table: ReferenceDataTable, track: boolean) {
    setBusy(table.key);
    // optimistic: the checkbox follows the click at once, the list is reloaded (or restored) afterwards
    setTables((all) => (all ?? []).map((x) => (x.key === table.key ? { ...x, tracked: track } : x)));
    try {
      if (track) {
        await api.trackTable(env.id, table.key, author);
        toast.success(t('refdata.tracked', { table: table.key }));
      } else {
        await api.untrackTable(env.id, table.key);
        toast.success(t('refdata.untracked', { table: table.key }));
      }
      await load();
      onChanged();
    } catch (e) {
      toast.error((e as Error).message);
      await load();
    } finally {
      setBusy(null);
    }
  }

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return (tables ?? []).filter((x) => (!onlyTracked || x.tracked) && (!needle || x.key.toLowerCase().includes(needle)));
  }, [tables, filter, onlyTracked]);
  const max = tables?.[0]?.maxRows ?? 10000;

  return (
    <Modal
      title={t('refdata.title')}
      subtitle={t('refdata.subtitle', { env: env.name })}
      onClose={onClose}
      width="max-w-4xl"
      footer={
        <>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <p className="rounded-md border border-kraken-line bg-kraken-bg px-3 py-2 text-xs text-gray-400">
          {t('refdata.hint', { max: max.toLocaleString(lang) })}
        </p>
        {!initialized && (
          <p className="rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-amber-100">{t('refdata.notInitialized')}</p>
        )}
        <div className="flex items-center gap-3">
          <div className="relative w-80">
            <Search size={13} className="pointer-events-none absolute left-2.5 top-2.5 text-gray-500" />
            <input
              className="input pl-8"
              placeholder={t('refdata.filter')}
              aria-label={t('refdata.filterLabel')}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
          </div>
          <label className="flex items-center gap-2 text-[12.5px] text-gray-300">
            <input type="checkbox" checked={onlyTracked} onChange={(e) => setOnlyTracked(e.target.checked)} />
            {t('refdata.onlyTracked')}
          </label>
        </div>

        {!tables && <p className="text-gray-500">{t('common.loading')}</p>}
        {tables && shown.length === 0 && <p className="text-gray-500">{t('refdata.none')}</p>}
        {shown.length > 0 && (
          <table className="w-full text-[12.5px]" aria-label={t('refdata.title')}>
            <thead>
              <tr className="border-b border-kraken-line text-left text-[11px] uppercase text-gray-500">
                <th className="w-12 py-1.5" />
                <th className="py-1.5">{t('refdata.table')}</th>
                <th className="py-1.5">{t('refdata.rows')}</th>
                <th className="py-1.5">{t('refdata.status')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((x) => {
                const blocked = !x.tracked && (!x.eligible || !initialized);
                const reason = !x.hasPrimaryKey
                  ? t('refdata.noPk')
                  : x.estimatedRows > x.maxRows
                    ? t('refdata.tooBig', { max: x.maxRows.toLocaleString(lang) })
                    : null;
                return (
                  <tr key={x.key} className={`border-b border-kraken-line/60 ${x.tracked ? 'bg-teal-950/20' : ''}`}>
                    <td className="py-1.5 pl-1">
                      <input
                        type="checkbox"
                        checked={x.tracked}
                        disabled={blocked || busy !== null}
                        aria-label={`${t('refdata.track')}: ${x.key}`}
                        title={reason ?? t('refdata.track')}
                        onChange={(e) => void toggle(x, e.target.checked)}
                      />
                    </td>
                    <td className="py-1.5">
                      <span className="flex items-center gap-1.5 font-mono">
                        {x.tracked && <Pin size={12} className="shrink-0 text-teal-300" />}
                        <span className="text-gray-500">{x.schemaName}.</span>
                        <span className="text-gray-100">{x.tableName}</span>
                        {x.hasPrimaryKey && <KeyRound size={11} className="shrink-0 text-gray-600" aria-hidden="true" />}
                      </span>
                    </td>
                    <td className="py-1.5 text-gray-300">
                      {x.rowCount !== null
                        ? t('refdata.rowsTracked', { rows: x.rowCount.toLocaleString(lang) })
                        : t('refdata.rowsEstimated', { rows: x.estimatedRows.toLocaleString(lang) })}
                    </td>
                    <td className="py-1.5 text-xs">
                      {x.overLimit ? (
                        <span className="text-amber-300">{t('refdata.overLimit')}</span>
                      ) : x.tracked ? (
                        <span className="text-gray-400">
                          {t('refdata.trackedBy', { who: x.trackedBy ?? '—', when: formatDate(x.trackedAt, lang) })}
                        </span>
                      ) : (
                        reason && <span className="text-gray-500">{reason}</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </Modal>
  );
}
