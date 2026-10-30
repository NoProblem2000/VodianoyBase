import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import { CheckCircle2, FileUp, GitCompare, Info, TriangleAlert, Upload } from 'lucide-react';
import { api } from '../api/client';
import type { ChangeItem, CommitResult, EnvironmentView, ScriptStatement, SnapshotBundle, SnapshotComparison } from '../api/types';
import { useI18n } from '../i18n';
import { formatDate, shortHash } from '../lib/format';
import { ChangeBadge } from './Badges';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  author: string;
  onAuthorChange: (author: string) => void;
  /** a commit was recorded: refresh the graph (the dialog stays open and compares again) */
  onApplied: (result: CommitResult) => void;
  onClose: () => void;
}

type Tab = 'up' | 'down';

/** Statements of the selected changes, in their original (dependency-safe) order; mirrors SchemaDiffEngine.assemble. */
export function assemble(statements: ScriptStatement[], selected: Set<number>): string {
  if (selected.size === 0) return '';
  return statements
    .filter((s) => s.changeId < 0 || selected.has(s.changeId))
    .map((s) => `${s.sql}\n`)
    .join('');
}

function isBundle(value: unknown): value is SnapshotBundle {
  const v = value as Partial<SnapshotBundle> | null;
  return !!v && typeof v === 'object' && typeof v.formatVersion === 'string' && !!v.schemaSnapshot;
}

const majorVersion = (num: number) => (num >= 100000 ? Math.floor(num / 10000) : Math.floor(num / 100));

/**
 * External Snapshot Synchronization: upload a .pgsnap.json file, review what differs from the live database of the
 * active environment, pick the changes, adjust the SQL and apply it as one commit.
 */
export function SnapshotSyncModal({ env, author, onAuthorChange, onApplied, onClose }: Props) {
  const { t, rich, locale } = useI18n();
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement>(null);
  const [bundle, setBundle] = useState<SnapshotBundle | null>(null);
  const [comparison, setComparison] = useState<SnapshotComparison | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [up, setUp] = useState('');
  const [down, setDown] = useState('');
  const [edited, setEdited] = useState(false);
  const [rebuiltOverEdits, setRebuiltOverEdits] = useState(false);
  const [tab, setTab] = useState<Tab>('up');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  const compare = useCallback(
    async (b: SnapshotBundle) => {
      setLoading(true);
      setError(null);
      try {
        const c = await api.compareSnapshot(env.id, b);
        const defaults = new Set(c.missingOrModifiedItems.map((i) => i.id ?? -1).filter((id) => id >= 0));
        setComparison(c);
        setSelected(defaults);
        setUp(c.upSql);
        setDown(c.downSql);
        setEdited(false);
        setRebuiltOverEdits(false);
        setMessage(t('snapshot.messageDefault', { hash: shortHash(c.source.sourceCommitHash ?? 'live') }));
      } catch (e) {
        setError((e as Error).message);
        setComparison(null);
      } finally {
        setLoading(false);
      }
    },
    [env.id, t],
  );

  async function readFile(file: File) {
    setError(null);
    try {
      const parsed: unknown = JSON.parse(await file.text());
      if (!isBundle(parsed)) throw new Error('not a bundle');
      setBundle(parsed);
      await compare(parsed);
    } catch {
      setError(t('snapshot.badFile', { file: file.name }));
    }
  }

  function onDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) void readFile(file);
  }

  // checkbox changes rebuild both scripts from the statements of the selected changes
  function changeSelection(next: Set<number>) {
    if (!comparison) return;
    setSelected(next);
    setRebuiltOverEdits(edited);
    setEdited(false);
    setUp(assemble(comparison.upStatements, next));
    setDown(assemble(comparison.downStatements, next));
  }

  function toggle(id: number) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    changeSelection(next);
  }

  function toggleGroup(items: ChangeItem[], on: boolean) {
    const next = new Set(selected);
    for (const i of items) {
      if (i.id === undefined) continue;
      if (on) next.add(i.id);
      else next.delete(i.id);
    }
    changeSelection(next);
  }

  async function apply() {
    if (!comparison) return;
    if (!up.trim()) {
      toast.error(t('snapshot.nothingSelected'));
      return;
    }
    setBusy(true);
    try {
      const result = await api.applySnapshot({
        envId: env.id,
        upSql: up,
        downSql: down,
        author,
        message,
        sourceCommitHash: comparison.source.sourceCommitHash,
      });
      toast.success(t('snapshot.applied', { hash: shortHash(result.commit.hash), env: env.name }));
      onApplied(result);
      if (bundle) await compare(bundle);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  // warnings of the selected changes only
  const selectedWarnings = useMemo(() => {
    if (!comparison) return [];
    const all = [...comparison.missingOrModifiedItems, ...comparison.destructiveItems];
    return all.filter((i) => i.id !== undefined && selected.has(i.id)).flatMap((i) => i.warnings ?? []);
  }, [comparison, selected]);

  const selectedDestructive = useMemo(
    () => (comparison?.destructiveItems ?? []).filter((i) => i.id !== undefined && selected.has(i.id)).length,
    [comparison, selected],
  );

  // Esc closes; re-reading the same file must trigger onChange again
  useEffect(() => {
    if (fileInput.current) fileInput.current.value = '';
  }, [bundle]);

  const src = comparison?.source;
  const versionMismatch = comparison && src && src.serverVersion > 0 && majorVersion(src.serverVersion) !== majorVersion(comparison.serverVersion);

  return (
    <Modal
      title={t('snapshot.title')}
      subtitle={t('snapshot.subtitle', { env: env.name })}
      onClose={onClose}
      width="max-w-[min(1500px,96vw)]"
      footer={
        comparison && !comparison.identical ? (
          <>
            <label className="w-48">
              <span className="label">{t('common.author')}</span>
              <input
                className="input"
                value={author}
                onChange={(e) => onAuthorChange(e.target.value)}
                placeholder={t('common.authorPlaceholder')}
              />
            </label>
            <label className="min-w-0 flex-1">
              <span className="label">{t('common.commitMessage')}</span>
              <input className="input" value={message} onChange={(e) => setMessage(e.target.value)} />
            </label>
            <div className="flex flex-col items-end gap-1 self-end">
              {selectedDestructive > 0 && (
                <span className="text-xs text-red-300">{t('snapshot.dropsSelected', { count: selectedDestructive })}</span>
              )}
              <button
                className={`btn ${selectedDestructive > 0 ? 'btn-danger' : 'btn-primary'}`}
                onClick={() => void apply()}
                disabled={busy || !up.trim()}
              >
                <GitCompare size={14} /> {busy ? t('snapshot.applying') : t('snapshot.apply')}
              </button>
            </div>
          </>
        ) : (
          <>
            <span className="flex-1" />
            <button className="btn" onClick={onClose}>
              {t('common.close')}
            </button>
          </>
        )
      }
    >
      {!comparison && (
        <div className="flex flex-col gap-3">
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            className={`flex flex-col items-center justify-center gap-3 rounded-lg border-2 border-dashed px-6 py-16 text-center transition-colors ${
              dragging ? 'border-teal-400 bg-teal-950/30' : 'border-kraken-line bg-kraken-bg'
            }`}
          >
            <FileUp size={36} className={dragging ? 'text-teal-300' : 'text-gray-500'} />
            <div className="text-[15px] font-medium text-gray-100">{t('snapshot.dropTitle')}</div>
            <p className="max-w-lg text-gray-400">{t('snapshot.dropHint', { env: env.name })}</p>
            <button className="btn btn-primary" onClick={() => fileInput.current?.click()} disabled={loading}>
              <Upload size={14} /> {loading ? t('snapshot.reading') : t('snapshot.choose')}
            </button>
            <input
              ref={fileInput}
              type="file"
              accept=".json,application/json"
              className="hidden"
              aria-label={t('snapshot.fileLabel')}
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) void readFile(file);
              }}
            />
          </div>
          {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        </div>
      )}

      {comparison && src && (
        <div className="flex h-[calc(100vh-17rem)] min-h-[420px] flex-col gap-3">
          <div className="flex items-start gap-3 rounded-md border border-kraken-line bg-kraken-bg px-3 py-2.5">
            <GitCompare size={18} className="mt-0.5 shrink-0 text-teal-400" />
            <div className="min-w-0 flex-1">
              <div className="font-medium text-gray-100">
                {src.sourceCommitHash
                  ? rich('snapshot.headerCommit', {
                      env: <b>{comparison.environmentName}</b>,
                      hash: <span className="font-mono text-teal-300">#{shortHash(src.sourceCommitHash)}</span>,
                      branch: <span className="font-mono">{src.sourceBranch}</span>,
                    })
                  : rich('snapshot.headerLive', {
                      env: <b>{comparison.environmentName}</b>,
                      source: <b>{src.sourceEnvironmentName}</b>,
                    })}
              </div>
              <div className="mt-0.5 truncate text-xs text-gray-400">
                {t('snapshot.meta', {
                  message: src.sourceCommitMessage ?? '—',
                  date: formatDate(src.exportedAt, locale),
                  source: src.sourceEnvironmentName,
                })}
              </div>
            </div>
            <button
              className="btn btn-ghost shrink-0"
              onClick={() => {
                setComparison(null);
                setBundle(null);
              }}
            >
              <Upload size={14} /> {t('snapshot.otherFile')}
            </button>
          </div>

          {(versionMismatch || comparison.targetHasUncommittedChanges) && !comparison.identical && (
            <div className="flex flex-col gap-1 text-xs">
              {versionMismatch && (
                <p className="flex gap-1.5 text-amber-300">
                  <TriangleAlert size={13} className="mt-px shrink-0" />
                  {t('snapshot.versionMismatch', {
                    source: majorVersion(src.serverVersion),
                    target: majorVersion(comparison.serverVersion),
                  })}
                </p>
              )}
              {comparison.targetHasUncommittedChanges && (
                <p className="flex gap-1.5 text-gray-400">
                  <Info size={13} className="mt-px shrink-0" />
                  {t('snapshot.uncommitted', { env: comparison.environmentName })}
                </p>
              )}
            </div>
          )}

          {comparison.identical ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
              <CheckCircle2 size={36} className="text-teal-400" />
              <p className="text-[15px] text-gray-100">{t('snapshot.identical', { env: comparison.environmentName })}</p>
            </div>
          ) : (
            <div className="grid min-h-0 flex-1 grid-cols-[minmax(320px,38%)_1fr] gap-3">
              <div className="flex min-h-0 flex-col gap-3 overflow-auto pr-1">
                <ChangeGroup
                  title={t('snapshot.missing')}
                  hint={t('snapshot.missingHint')}
                  items={comparison.missingOrModifiedItems}
                  selected={selected}
                  onToggle={toggle}
                  onToggleAll={(on) => toggleGroup(comparison.missingOrModifiedItems, on)}
                />
                <ChangeGroup
                  destructive
                  title={t('snapshot.destructive')}
                  hint={t('snapshot.destructiveHint')}
                  items={comparison.destructiveItems}
                  selected={selected}
                  onToggle={toggle}
                  onToggleAll={(on) => toggleGroup(comparison.destructiveItems, on)}
                />
                {selectedWarnings.length > 0 && (
                  <div className="rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-xs text-amber-200">
                    <div className="mb-1 font-semibold">{t('snapshot.warnings')}</div>
                    <ul className="space-y-1">
                      {selectedWarnings.map((w, i) => (
                        <li key={i} className="flex gap-1.5">
                          <TriangleAlert size={12} className="mt-px shrink-0" />
                          {w}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>

              <div className="flex min-h-0 flex-col overflow-hidden rounded-md border border-kraken-line">
                <div className="flex items-center">
                  <div className="flex-1">
                    <Tabs<Tab>
                      tabs={[
                        { id: 'up', label: t('common.upSql') },
                        { id: 'down', label: t('common.downSql') },
                      ]}
                      active={tab}
                      onChange={setTab}
                    />
                  </div>
                  <div className="flex items-center gap-2 border-b border-kraken-line px-3 py-2 text-xs">
                    {edited && <span className="text-amber-300">{t('common.sqlEdited')}</span>}
                    {!edited && rebuiltOverEdits && <span className="text-gray-400">{t('snapshot.rebuilt')}</span>}
                    {edited && (
                      <button className="text-gray-400 underline hover:text-gray-200" onClick={() => changeSelection(new Set(selected))}>
                        {t('snapshot.resetSql')}
                      </button>
                    )}
                  </div>
                </div>
                <div className="min-h-0 flex-1">
                  {tab === 'up' ? (
                    <SqlEditor
                      value={up}
                      onChange={(v) => {
                        setUp(v);
                        setEdited(true);
                      }}
                    />
                  ) : (
                    <SqlEditor
                      value={down}
                      onChange={(v) => {
                        setDown(v);
                        setEdited(true);
                      }}
                    />
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

interface GroupProps {
  title: string;
  hint: string;
  items: ChangeItem[];
  selected: Set<number>;
  destructive?: boolean;
  onToggle: (id: number) => void;
  onToggleAll: (on: boolean) => void;
}

function ChangeGroup({ title, hint, items, selected, destructive, onToggle, onToggleAll }: GroupProps) {
  const { t } = useI18n();
  const all = items.length > 0 && items.every((i) => i.id !== undefined && selected.has(i.id));
  return (
    <section
      className={`rounded-md border ${destructive ? 'border-red-900 bg-red-950/20' : 'border-kraken-line'}`}
      aria-label={title}
    >
      <header className="flex items-center gap-2 border-b border-inherit px-3 py-2">
        {destructive && <TriangleAlert size={14} className="shrink-0 text-red-400" />}
        <span className={`font-semibold ${destructive ? 'text-red-200' : 'text-gray-100'}`}>{title}</span>
        <span className="text-xs text-gray-500">{items.length}</span>
        {items.length > 0 && (
          <label className="ml-auto flex items-center gap-1.5 text-xs text-gray-400">
            <input type="checkbox" checked={all} onChange={(e) => onToggleAll(e.target.checked)} />
            {t('snapshot.selectAll')}
          </label>
        )}
      </header>
      <p className={`px-3 pt-2 text-xs ${destructive ? 'text-red-300/80' : 'text-gray-500'}`}>{hint}</p>
      {items.length === 0 ? (
        <p className="px-3 py-2 text-gray-500">{t('snapshot.none')}</p>
      ) : (
        <ul className="px-1.5 py-1.5">
          {items.map((c) => {
            const id = c.id ?? -1;
            return (
              <li key={id}>
                <label className="flex cursor-pointer items-start gap-2 rounded px-1.5 py-1.5 hover:bg-kraken-hover">
                  <input
                    type="checkbox"
                    className="mt-0.5"
                    checked={selected.has(id)}
                    onChange={() => onToggle(id)}
                    aria-label={c.objectName}
                  />
                  <ChangeBadge type={c.changeType} />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-baseline gap-2">
                      <span className="truncate font-mono text-[12.5px] text-gray-100">{c.objectName}</span>
                      <span className="shrink-0 text-[10.5px] uppercase text-gray-500">{t(`objectType.${c.objectType}`)}</span>
                      {destructive && (
                        <span className="ml-auto shrink-0 rounded bg-red-900/70 px-1.5 py-px text-[10px] font-bold uppercase tracking-wide text-red-200">
                          {t('snapshot.destructiveBadge')}
                        </span>
                      )}
                    </span>
                    <span className="block truncate text-xs text-gray-400">{c.details}</span>
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
