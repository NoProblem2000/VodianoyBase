import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Copy, FileUp, LayoutGrid, Search, X } from 'lucide-react';
import { api } from '../api/client';
import type {
  DiffIgnoreRules,
  EnvironmentView,
  MatrixCell,
  MatrixCellStatus,
  MatrixComparison,
  MatrixRow,
  MatrixSnapshotUpload,
  SnapshotBundle,
} from '../api/types';
import { useI18n } from '../i18n';
import { DEFAULT_IGNORE_RULES, IgnoreRulesPanel } from './IgnoreRulesEditor';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useToast } from './Toasts';

interface Props {
  environments: EnvironmentView[];
  activeEnvId: string | null;
  onClose: () => void;
}

const STATUS_CLASSES: Record<MatrixCellStatus, string> = {
  IDENTICAL: 'border-emerald-700/60 bg-emerald-950/40 text-emerald-300',
  MODIFIED: 'border-amber-600/70 bg-amber-950/50 text-amber-200',
  MISSING: 'border-red-700/70 bg-red-950/50 text-red-200',
  EXTRA_ONLY_HERE: 'border-sky-700/70 bg-sky-950/50 text-sky-200',
  ABSENT: 'border-transparent text-gray-600',
  UNAVAILABLE: 'border-gray-700 bg-gray-900 text-gray-500',
};

const CLICKABLE: MatrixCellStatus[] = ['MODIFIED', 'MISSING', 'EXTRA_ONLY_HERE'];

function isBundle(value: unknown): value is SnapshotBundle {
  return !!value && typeof value === 'object' && 'schemaSnapshot' in value && 'formatVersion' in value;
}

interface Selection {
  row: MatrixRow;
  dbId: string;
}

/** Compares N databases (environments and uploaded snapshots) with one baseline. */
export function MatrixModal({ environments, activeEnvId, onClose }: Props) {
  const { t } = useI18n();
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement>(null);
  const [envIds, setEnvIds] = useState<string[]>(() => environments.map((e) => e.id));
  const [uploads, setUploads] = useState<MatrixSnapshotUpload[]>([]);
  const [baseline, setBaseline] = useState<string>(() => `env:${activeEnvId ?? environments[0]?.id ?? ''}`);
  const [rules, setRules] = useState<DiffIgnoreRules>(DEFAULT_IGNORE_RULES);
  const [savingRules, setSavingRules] = useState(false);
  const [result, setResult] = useState<MatrixComparison | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [onlyDiffs, setOnlyDiffs] = useState(true);
  const [filter, setFilter] = useState('');
  const [selection, setSelection] = useState<Selection | null>(null);
  const [sqlTab, setSqlTab] = useState<'up' | 'down'>('up');

  useEffect(() => {
    api
      .ignoreRules()
      .then(setRules)
      .catch(() => setRules(DEFAULT_IGNORE_RULES));
  }, []);

  // databases that can be the baseline: the selected environments and the uploaded snapshots
  const candidates = useMemo(
    () => [
      ...environments.filter((e) => envIds.includes(e.id)).map((e) => ({ id: `env:${e.id}`, name: e.name })),
      ...uploads.map((u, i) => ({ id: `snapshot:${i}`, name: u.name })),
    ],
    [environments, envIds, uploads],
  );
  useEffect(() => {
    if (candidates.length > 0 && !candidates.some((c) => c.id === baseline)) setBaseline(candidates[0].id);
  }, [candidates, baseline]);

  async function addFiles(files: FileList | null) {
    if (!files) return;
    const added: MatrixSnapshotUpload[] = [];
    for (const file of Array.from(files)) {
      try {
        const parsed: unknown = JSON.parse(await file.text());
        if (!isBundle(parsed)) throw new Error('not a bundle');
        added.push({ name: file.name.replace(/\.pgsnap\.json$/i, ''), bundle: parsed });
      } catch {
        toast.error(t('matrix.badFile', { file: file.name }));
      }
    }
    setUploads((u) => [...u, ...added]);
    if (fileInput.current) fileInput.current.value = '';
  }

  async function compare() {
    setLoading(true);
    setError(null);
    setSelection(null);
    try {
      setResult(await api.matrix({ envIds, snapshots: uploads, baseline, ignoreRules: rules }));
    } catch (e) {
      setError((e as Error).message);
      setResult(null);
    } finally {
      setLoading(false);
    }
  }

  async function saveRules() {
    setSavingRules(true);
    try {
      setRules(await api.saveIgnoreRules(rules));
      toast.success(t('ignore.saved'));
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setSavingRules(false);
    }
  }

  const rows = useMemo(() => {
    if (!result) return [];
    const needle = filter.trim().toLowerCase();
    return result.rows.filter((r) => (!onlyDiffs || r.differs) && (!needle || r.name.toLowerCase().includes(needle)));
  }, [result, onlyDiffs, filter]);

  const baselineDb = result?.databases.find((d) => d.baseline) ?? null;
  const selectedDb = selection ? result?.databases.find((d) => d.id === selection.dbId) ?? null : null;
  const selectedCell: MatrixCell | null = selection ? selection.row.cells[selection.dbId] ?? null : null;
  const tooFew = envIds.length + uploads.length < 2;

  async function copySql(sql: string) {
    try {
      await navigator.clipboard.writeText(sql);
      toast.success(t('matrix.copied'));
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

  return (
    <Modal title={t('matrix.title')} subtitle={t('matrix.subtitle')} onClose={onClose} width="max-w-[min(1840px,98vw)]">
      <div className="grid h-[calc(100vh-9.5rem)] min-h-[480px] grid-cols-[310px_minmax(0,1fr)] gap-4">
        {/* ---------------------------------------------------------- configuration */}
        <div className="flex min-h-0 flex-col gap-3 overflow-auto pr-1">
          <section>
            <h3 className="panel-title mb-1.5">{t('matrix.environments')}</h3>
            <ul className="space-y-1">
              {environments.map((env) => (
                <li key={env.id}>
                  <label className="flex cursor-pointer items-center gap-2 rounded px-1.5 py-1 hover:bg-kraken-hover">
                    <input
                      type="checkbox"
                      checked={envIds.includes(env.id)}
                      onChange={(e) =>
                        setEnvIds((ids) => (e.target.checked ? [...ids, env.id] : ids.filter((x) => x !== env.id)))
                      }
                    />
                    <span className="h-2.5 w-2.5 rounded-full" style={{ background: env.colorBadge }} />
                    <span className="truncate text-gray-100">{env.name}</span>
                  </label>
                </li>
              ))}
            </ul>
          </section>

          <section>
            <h3 className="panel-title mb-1.5">{t('matrix.snapshots')}</h3>
            {uploads.length > 0 && (
              <ul className="mb-2 space-y-1">
                {uploads.map((u, i) => (
                  <li key={`${u.name}-${i}`} className="flex items-center gap-2 rounded bg-kraken-bg px-2 py-1">
                    <FileUp size={13} className="shrink-0 text-gray-500" />
                    <span className="min-w-0 flex-1 truncate font-mono text-[12px] text-gray-200" title={u.name}>
                      {u.name}
                    </span>
                    <button
                      className="btn btn-ghost h-6 px-1"
                      onClick={() => setUploads((all) => all.filter((_, j) => j !== i))}
                      aria-label={t('matrix.removeSnapshot', { name: u.name })}
                      title={t('matrix.removeSnapshot', { name: u.name })}
                    >
                      <X size={13} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <button className="btn w-full justify-center" onClick={() => fileInput.current?.click()}>
              <FileUp size={14} /> {t('matrix.addSnapshot')}
            </button>
            <input
              ref={fileInput}
              type="file"
              multiple
              accept=".json,application/json"
              className="hidden"
              aria-label={t('matrix.snapshotLabel')}
              onChange={(e) => void addFiles(e.target.files)}
            />
          </section>

          <label>
            <span className="label">{t('matrix.baseline')}</span>
            <select
              className="input"
              aria-label={t('matrix.baseline')}
              value={baseline}
              onChange={(e) => setBaseline(e.target.value)}
              disabled={candidates.length === 0}
            >
              {candidates.map((c) => (
                <option key={c.id} value={c.id} className="bg-kraken-panel">
                  {c.name}
                </option>
              ))}
            </select>
          </label>

          <IgnoreRulesPanel value={rules} onChange={setRules} onSaveDefault={() => void saveRules()} savingDefault={savingRules} />

          <button className="btn btn-primary justify-center" onClick={() => void compare()} disabled={loading || tooFew}>
            <LayoutGrid size={14} /> {loading ? t('matrix.comparing') : t('matrix.compare')}
          </button>
          {tooFew && <p className="text-xs text-gray-500">{t('matrix.pickTwo')}</p>}
          {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        </div>

        {/* ---------------------------------------------------------- result */}
        <div className="flex min-h-0 flex-col gap-2">
          {!result ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 rounded-md border border-dashed border-kraken-line text-gray-500">
              <LayoutGrid size={30} className="text-gray-600" />
              <p>{t('matrix.empty')}</p>
            </div>
          ) : (
            <>
              <div className="flex items-center gap-3">
                <label className="flex items-center gap-2 text-[12.5px] text-gray-200">
                  <input type="checkbox" checked={onlyDiffs} onChange={(e) => setOnlyDiffs(e.target.checked)} />
                  {t('matrix.onlyDiffs')}
                </label>
                <div className="relative w-72">
                  <Search size={13} className="pointer-events-none absolute left-2.5 top-2.5 text-gray-500" />
                  <input
                    className="input pl-8"
                    placeholder={t('matrix.filter')}
                    aria-label={t('matrix.filterLabel')}
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
                  />
                </div>
                <span className="text-xs text-gray-500">
                  {t('matrix.rows', { shown: rows.length, total: result.rows.length })}
                </span>
                <span className="flex-1" />
                <Legend />
              </div>

              <div className="min-h-0 flex-1 overflow-auto rounded-md border border-kraken-line">
                <table className="w-full border-separate border-spacing-0 text-[12.5px]" aria-label={t('matrix.title')}>
                  <thead>
                    <tr>
                      <th className="sticky left-0 top-0 z-20 min-w-[300px] border-b border-r border-kraken-line bg-kraken-panel px-3 py-2 text-left font-medium text-gray-300">
                        {t('matrix.object')}
                      </th>
                      {result.databases.map((d) => (
                        <th
                          key={d.id}
                          className={`sticky top-0 z-10 min-w-[150px] border-b border-kraken-line px-2 py-2 text-left align-top font-medium ${
                            d.baseline ? 'bg-teal-950/60' : 'bg-kraken-panel'
                          }`}
                        >
                          <div className="flex items-center gap-1.5">
                            <span className="truncate text-gray-100" title={d.description ?? d.name}>
                              {d.name}
                            </span>
                            {d.baseline && (
                              <span className="rounded bg-teal-500/20 px-1 text-[10px] uppercase text-teal-300">
                                {t('matrix.baselineBadge')}
                              </span>
                            )}
                            {d.kind === 'SNAPSHOT' && <FileUp size={11} className="shrink-0 text-gray-500" />}
                          </div>
                          {d.error ? (
                            <div className="mt-0.5 flex items-center gap-1 text-[11px] font-normal text-red-300" title={d.error}>
                              <AlertTriangle size={11} /> {t('matrix.unavailable', { error: d.error.slice(0, 60) })}
                            </div>
                          ) : (
                            !d.baseline && (
                              <div className="mt-0.5 text-[11px] font-normal text-gray-500">
                                {t('matrix.counts', { modified: d.modified, missing: d.missing, extra: d.extra })}
                              </div>
                            )
                          )}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={`${row.type}|${row.name}`} className="group">
                        <td className="sticky left-0 z-10 border-b border-r border-kraken-line/60 bg-kraken-panel px-3 py-1 group-hover:bg-kraken-hover">
                          <span className="mr-2 text-[10.5px] uppercase text-gray-500">{t(`objectType.${row.type}`)}</span>
                          <span className="font-mono text-gray-100">{row.name}</span>
                        </td>
                        {result.databases.map((d) => {
                          const cell = row.cells[d.id];
                          const status = cell?.status ?? 'ABSENT';
                          const clickable = CLICKABLE.includes(status);
                          const active = selection?.row === row && selection.dbId === d.id;
                          return (
                            <td key={d.id} className="border-b border-kraken-line/60 px-2 py-1">
                              <button
                                type="button"
                                disabled={!clickable}
                                onClick={() => {
                                  setSelection({ row, dbId: d.id });
                                  setSqlTab('up');
                                }}
                                title={cell?.differences.join('\n') || t(`matrix.status.${status}`)}
                                className={`inline-flex h-[22px] min-w-[86px] items-center justify-center rounded border px-2 text-[11.5px] ${
                                  STATUS_CLASSES[status]
                                } ${clickable ? 'cursor-pointer hover:brightness-125' : 'cursor-default'} ${
                                  active ? 'ring-2 ring-teal-400' : ''
                                }`}
                              >
                                {t(`matrix.status.${status}`)}
                              </button>
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
                {rows.length === 0 && (
                  <p className="px-3 py-6 text-center text-gray-500">
                    {result.rows.some((r) => r.differs) || filter ? t('matrix.noRows') : t('matrix.allIdentical')}
                  </p>
                )}
              </div>

              {selection && selectedCell && selectedDb ? (
                <div className="flex h-[38%] min-h-[220px] flex-col overflow-hidden rounded-md border border-kraken-line">
                  <div className="flex items-center gap-2 border-b border-kraken-line bg-kraken-bg px-3 py-1.5">
                    <span className={`rounded border px-1.5 text-[11px] ${STATUS_CLASSES[selectedCell.status]}`}>
                      {t(`matrix.status.${selectedCell.status}`)}
                    </span>
                    <span className="min-w-0 flex-1 truncate font-mono text-[12.5px] text-gray-100">
                      {t('matrix.cellTitle', { object: selection.row.name, db: selectedDb.name })}
                    </span>
                    <button
                      className="btn h-7"
                      onClick={() => void copySql(sqlTab === 'up' ? selectedCell.upSql : selectedCell.downSql)}
                    >
                      <Copy size={13} /> {t('matrix.copy')}
                    </button>
                    <button className="btn btn-ghost h-7 px-1.5" onClick={() => setSelection(null)} aria-label={t('common.close')}>
                      <X size={14} />
                    </button>
                  </div>
                  <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
                    <div className="min-h-0 overflow-auto border-r border-kraken-line px-3 py-2">
                      <div className="mb-1 text-[11px] uppercase text-gray-500">{t('matrix.differences')}</div>
                      <ul className="space-y-1">
                        {selectedCell.differences.map((d, i) => (
                          <li key={i} className="font-mono text-[11.5px] text-gray-300">
                            {d}
                          </li>
                        ))}
                      </ul>
                      {selectedCell.warnings.length > 0 && (
                        <>
                          <div className="mb-1 mt-3 text-[11px] uppercase text-amber-400">{t('matrix.warnings')}</div>
                          <ul className="space-y-1">
                            {selectedCell.warnings.map((w, i) => (
                              <li key={i} className="text-[11.5px] text-amber-200">
                                {w}
                              </li>
                            ))}
                          </ul>
                        </>
                      )}
                    </div>
                    <div className="flex min-h-0 flex-col">
                      <Tabs<'up' | 'down'>
                        tabs={[
                          { id: 'up', label: t('matrix.upTitle', { db: selectedDb.name, base: baselineDb?.name ?? '' }) },
                          { id: 'down', label: t('matrix.downTitle') },
                        ]}
                        active={sqlTab}
                        onChange={setSqlTab}
                      />
                      <div className="min-h-0 flex-1">
                        <SqlEditor value={(sqlTab === 'up' ? selectedCell.upSql : selectedCell.downSql) || '-- —'} readOnly />
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <p className="text-xs text-gray-500">{t('matrix.clickHint')}</p>
              )}
            </>
          )}
        </div>
      </div>
    </Modal>
  );
}

function Legend() {
  const { t } = useI18n();
  const shown: MatrixCellStatus[] = ['IDENTICAL', 'MODIFIED', 'MISSING', 'EXTRA_ONLY_HERE'];
  return (
    <div className="flex items-center gap-1.5" aria-label={t('matrix.legend')}>
      {shown.map((s) => (
        <span key={s} className={`rounded border px-1.5 text-[11px] ${STATUS_CLASSES[s]}`}>
          {t(`matrix.status.${s}`)}
        </span>
      ))}
    </div>
  );
}
