import { useEffect, useMemo, useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  FolderTree,
  GitCommitHorizontal,
  List,
  Maximize2,
  Search,
  ShieldAlert,
  Trash2,
  TriangleAlert,
  X,
} from 'lucide-react';
import { api } from '../api/client';
import type { ChangeItem, ChangeType, CommitResult, EnvironmentView, QuickFix, WorkingTreeStatus } from '../api/types';
import { applyAlternatives, SafetyBadges, SafetyNotes, ZeroDowntimeButton } from './SafetyBadges';
import { shortHash } from '../lib/format';
import { loadSetting, saveSetting } from '../lib/storage';
import { ChangeBadge } from './Badges';
import { ChangeList, QuickFixBlock } from './ChangeList';
import { CommitMessageFields } from './CommitMessageFields';
import { DiscardModal } from './DiscardModal';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useI18n } from '../i18n';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  workingTree: WorkingTreeStatus;
  author: string;
  onAuthorChange: (author: string) => void;
  onCommitted: (result: CommitResult) => void;
  /** uncommitted changes were discarded (or the DDL log cleared) */
  onDiscarded: () => void;
  /** a quick fix ran on the database: reload the working tree */
  onQuickFixApplied?: () => void;
}

type Tab = 'changes' | 'up' | 'down' | 'safety';

const COMMIT_FORM_KEY = 'pgvc.commitFormOpen';
const GROUPING_KEY = 'pgvc.changeGrouping';
const CHANGE_TYPES: ChangeType[] = ['ADDED', 'MODIFIED', 'RENAMED', 'DELETED'];

/** A change that needs attention: dangerous / warning risk, an exclusive lock, a rewrite or data loss. */
function isRisky(c: ChangeItem): boolean {
  const s = c.safety;
  return !!s && (s.riskLevel !== 'SAFE' || s.causesDataLoss || s.causesTableRewrite || s.lockType === 'ACCESS EXCLUSIVE');
}

/**
 * Right panel of the working tree: tabs over the whole height (changes with filter and checkboxes, UP SQL, DOWN SQL,
 * safety), a one-line collapsible safety summary on top and a compact commit bar that opens the commit form.
 */
export function WorkingTreeInspector({ env, workingTree, author, onAuthorChange, onCommitted, onDiscarded, onQuickFixApplied }: Props) {
  const toast = useToast();
  const { t, tn } = useI18n();
  const diff = workingTree.diff;
  const [tab, setTab] = useState<Tab>('changes');
  const [up, setUp] = useState(diff.upSql);
  const [down, setDown] = useState(diff.downSql);
  const [message, setMessage] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  // null: closed; []: discard everything; [i, ...]: the given changes
  const [discarding, setDiscarding] = useState<number[] | null>(null);
  const [safetyOpen, setSafetyOpen] = useState(false);
  const [commitOpen, setCommitOpen] = useState(() => loadSetting(COMMIT_FORM_KEY, 'false') === 'true');
  const [fullscreen, setFullscreen] = useState<'up' | 'down' | null>(null);
  const [query, setQuery] = useState('');
  const [types, setTypes] = useState<Set<ChangeType>>(new Set());
  const [selected, setSelected] = useState<Set<number>>(new Set());
  // grouped by entity by default (tables of one name in every schema with their sequences, FKs, views)
  const [grouped, setGrouped] = useState(() => loadSetting(GROUPING_KEY, 'grouped') !== 'flat');
  const [fixing, setFixing] = useState<string | null>(null);

  useEffect(() => {
    saveSetting(GROUPING_KEY, grouped ? 'grouped' : 'flat');
  }, [grouped]);

  // new DDL arrived: show the regenerated scripts, the old selection means other changes now
  useEffect(() => {
    setUp(diff.upSql);
    setDown(diff.downSql);
  }, [diff.upSql, diff.downSql]);
  useEffect(() => setSelected(new Set()), [diff.changes.length, diff.upSql]);

  useEffect(() => {
    saveSetting(COMMIT_FORM_KEY, String(commitOpen));
  }, [commitOpen]);

  const edited = up !== diff.upSql || down !== diff.downSql;
  const [patternUsed, setPatternUsed] = useState<Set<number>>(new Set());

  // new DDL arrived: patterns have to be chosen again
  useEffect(() => setPatternUsed(new Set()), [diff.upSql, diff.downSql]);

  const risky = useMemo(() => diff.changes.map((c, i) => [c, i] as const).filter(([c]) => isRisky(c)), [diff.changes]);
  const exclusive = risky.filter(([c]) => c.safety?.lockType === 'ACCESS EXCLUSIVE').length;
  const dataLoss = risky.filter(([c]) => c.safety?.causesDataLoss).length;
  const typeCounts = useMemo(() => {
    const m = new Map<ChangeType, number>();
    diff.changes.forEach((c) => m.set(c.changeType, (m.get(c.changeType) ?? 0) + 1));
    return m;
  }, [diff.changes]);

  const needle = query.trim().toLowerCase();
  const filter = (c: ChangeItem) =>
    (types.size === 0 || types.has(c.changeType)) &&
    (!needle || `${c.objectName} ${c.details} ${c.objectType}`.toLowerCase().includes(needle));
  const filtering = types.size > 0 || !!needle;
  const shownIndexes = diff.changes.map((c, i) => (filter(c) ? i : -1)).filter((i) => i >= 0);
  const allShownSelected = shownIndexes.length > 0 && shownIndexes.every((i) => selected.has(i));

  function toggle(i: number) {
    const next = new Set(selected);
    if (next.has(i)) next.delete(i);
    else next.add(i);
    setSelected(next);
  }

  function selectMany(indexes: number[], select: boolean) {
    const next = new Set(selected);
    indexes.forEach((i) => (select ? next.add(i) : next.delete(i)));
    setSelected(next);
  }

  function toggleShown() {
    const next = new Set(selected);
    if (allShownSelected) shownIndexes.forEach((i) => next.delete(i));
    else shownIndexes.forEach((i) => next.add(i));
    setSelected(next);
  }

  function toggleType(type: ChangeType) {
    const next = new Set(types);
    if (next.has(type)) next.delete(type);
    else next.add(type);
    setTypes(next);
  }

  /** "⚡ Use in Editor": the zero-downtime patterns of the change go into the UP and the DOWN SQL. */
  function usePattern(change: ChangeItem) {
    const upAlternatives = change.safety?.alternatives ?? [];
    const downAlternatives = change.downAlternatives ?? [];
    const nextUp = upAlternatives.length ? applyAlternatives(up, upAlternatives) : up;
    const nextDown = downAlternatives.length ? applyAlternatives(down, downAlternatives) : down;
    if (nextUp === null || nextDown === null) {
      toast.error(t('safety.patternNotFound'));
      return;
    }
    setUp(nextUp);
    setDown(nextDown);
    setTab(upAlternatives.length ? 'up' : 'down');
    setPatternUsed(new Set([...patternUsed, diff.changes.indexOf(change)]));
    toast.success(
      upAlternatives.length && downAlternatives.length
        ? t('safety.patternAppliedBoth')
        : downAlternatives.length
          ? t('safety.patternAppliedDown')
          : t('safety.patternApplied'),
    );
  }

  /** Runs a quick fix on the database (not committed: the working tree then shows the fixed object). */
  async function applyQuickFix(fix: QuickFix) {
    setFixing(fix.objectName);
    try {
      await api.quickFix(env.id, fix.objectName, fix.kind);
      toast.success(t('quickFix.applied', { object: fix.objectName, env: env.name }));
      onQuickFixApplied?.();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setFixing(null);
    }
  }

  const globalFixes = diff.quickFixes ?? [];

  async function commit() {
    if (!message.trim()) {
      setCommitOpen(true);
      toast.error(t('common.writeMessage'));
      return;
    }
    setBusy(true);
    try {
      const result = await api.commit(env.id, {
        message: message.trim(),
        description: description.trim() || undefined,
        author,
        upSql: up,
        downSql: down,
        expectedHead: workingTree.head ?? '',
      });
      toast.success(t('workingTree.committed', { hash: shortHash(result.commit.hash), message: result.commit.message }));
      setMessage('');
      setDescription('');
      onCommitted(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const hasSafetySummary = risky.length > 0 || diff.warnings.length > 0 || globalFixes.length > 0;

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* ---------------------------------------------------------- header */}
      <header className="border-b border-kraken-line px-4 py-2.5">
        <div className="flex items-center gap-2">
          <span className="relative flex h-2.5 w-2.5 shrink-0">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-400 opacity-60" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-amber-400" />
          </span>
          <h2 className="text-[15px] font-semibold text-gray-100">{t('workingTree.title')}</h2>
          <span className="ml-auto truncate text-xs text-gray-500">
            {t('workingTree.since', { env: env.name, head: workingTree.head ? shortHash(workingTree.head) : t('common.emptyHistory') })}
          </span>
        </div>
        <p className="mt-0.5 line-clamp-1 text-[11.5px] text-gray-500" title={t('workingTree.hint')}>
          {t('workingTree.hint')}
        </p>
      </header>

      {/* ---------------------------------------------------------- collapsible safety summary */}
      {hasSafetySummary && (
        <div className="border-b border-amber-900/70 bg-amber-950/25">
          <button
            className="flex w-full items-center gap-2 px-4 py-1.5 text-left text-[12px] text-amber-200 hover:bg-amber-950/40"
            onClick={() => setSafetyOpen(!safetyOpen)}
            aria-expanded={safetyOpen}
            aria-controls="wt-safety-details"
          >
            <TriangleAlert size={14} className="shrink-0 text-amber-400" />
            <span className="min-w-0 flex-1 truncate">
              {risky.length > 0 ? tn('plural.riskyChanges', risky.length) : tn('plural.warnings', diff.warnings.length)}
              {exclusive > 0 && <span className="text-amber-300/80"> · {t('workingTree.summaryExclusive', { count: exclusive })}</span>}
              {dataLoss > 0 && <span className="text-red-300/90"> · {t('workingTree.summaryDataLoss', { count: dataLoss })}</span>}
            </span>
            <span className="flex shrink-0 items-center gap-0.5 text-[11px] text-amber-300/80">
              {safetyOpen ? t('workingTree.hideDetails') : t('workingTree.showDetails')}
              {safetyOpen ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
            </span>
          </button>
          {safetyOpen && (
            <div id="wt-safety-details" className="max-h-[32vh] overflow-auto px-4 pb-2">
              <ul className="space-y-1 text-[11.5px] text-amber-100/90">
                {diff.warnings.map((w, i) => (
                  <li key={i} className="flex gap-1.5">
                    <span className="text-amber-500">•</span>
                    {w}
                  </li>
                ))}
              </ul>
              {globalFixes.length > 0 && (
                <div className="mt-1.5 space-y-1.5 text-xs">
                  {globalFixes.map((fix) => (
                    <QuickFixBlock key={fix.objectName} fix={fix} onApply={(f) => void applyQuickFix(f)} busy={fixing === fix.objectName} />
                  ))}
                </div>
              )}
              {risky.length > 0 && (
                <button className="mt-1.5 text-[11.5px] text-amber-300 underline hover:text-amber-100" onClick={() => setTab('safety')}>
                  {t('workingTree.openSafetyTab')}
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {/* ---------------------------------------------------------- tabs over the full height */}
      <div className="flex items-end">
        <div className="min-w-0 flex-1">
          <Tabs<Tab>
            tabs={[
              { id: 'changes', label: t('workingTree.tabChanges'), count: diff.changes.length },
              { id: 'up', label: t('workingTree.tabUp') },
              { id: 'down', label: t('workingTree.tabDown') },
              { id: 'safety', label: t('workingTree.tabSafety'), count: risky.length },
            ]}
            active={tab}
            onChange={setTab}
          />
        </div>
        {(tab === 'up' || tab === 'down') && (
          <button
            className="mb-1 mr-2 shrink-0 rounded p-1.5 text-gray-400 hover:bg-kraken-hover hover:text-gray-100"
            onClick={() => setFullscreen(tab)}
            title={t('workingTree.expand')}
            aria-label={t('workingTree.expand')}
          >
            <Maximize2 size={14} />
          </button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col">
        {tab === 'changes' && (
          <>
            <div className="flex flex-wrap items-center gap-1.5 border-b border-kraken-line/60 px-3 py-2">
              <input
                type="checkbox"
                className="accent-teal-500"
                checked={allShownSelected}
                onChange={toggleShown}
                disabled={shownIndexes.length === 0}
                aria-label={t('workingTree.selectAll')}
                title={t('workingTree.selectAll')}
              />
              <div className="relative min-w-[140px] flex-1">
                <Search size={12} className="pointer-events-none absolute left-2 top-2 text-gray-500" />
                <input
                  className="input h-7 pl-7 pr-6 text-[12px]"
                  placeholder={t('workingTree.filterPlaceholder')}
                  aria-label={t('workingTree.filter')}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
                {query && (
                  <button
                    className="absolute right-1 top-1 rounded p-0.5 text-gray-500 hover:text-gray-200"
                    onClick={() => setQuery('')}
                    aria-label={t('docs.clearSearch')}
                  >
                    <X size={13} />
                  </button>
                )}
              </div>
              <div className="flex overflow-hidden rounded-md border border-kraken-line" role="group" aria-label={t('workingTree.grouping')}>
                <button
                  type="button"
                  className={`flex items-center gap-1 px-1.5 py-0.5 text-[11px] ${
                    !grouped ? 'bg-teal-500/20 text-teal-200' : 'text-gray-400 hover:bg-kraken-hover hover:text-gray-200'
                  }`}
                  onClick={() => setGrouped(false)}
                  aria-pressed={!grouped}
                  title={t('workingTree.flatList')}
                  aria-label={t('workingTree.flatList')}
                >
                  <List size={12} />
                </button>
                <button
                  type="button"
                  className={`flex items-center gap-1 border-l border-kraken-line px-1.5 py-0.5 text-[11px] ${
                    grouped ? 'bg-teal-500/20 text-teal-200' : 'text-gray-400 hover:bg-kraken-hover hover:text-gray-200'
                  }`}
                  onClick={() => setGrouped(true)}
                  aria-pressed={grouped}
                  title={t('workingTree.groupByEntity')}
                  aria-label={t('workingTree.groupByEntity')}
                >
                  <FolderTree size={12} />
                </button>
              </div>
              <div className="flex items-center gap-1" role="group" aria-label={t('workingTree.typeFilter')}>
                {CHANGE_TYPES.filter((type) => typeCounts.has(type)).map((type) => (
                  <button
                    key={type}
                    className={`flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] ${
                      types.has(type) ? 'border-teal-600 bg-teal-500/15 text-gray-100' : 'border-kraken-line text-gray-400 hover:text-gray-200'
                    }`}
                    onClick={() => toggleType(type)}
                    aria-pressed={types.has(type)}
                    title={t(`changeType.${type}`)}
                  >
                    <ChangeBadge type={type} />
                    {typeCounts.get(type)}
                  </button>
                ))}
              </div>
            </div>
            {filtering && (
              <div className="px-4 pt-1.5 text-[11px] text-gray-500">
                {t('workingTree.shown', { shown: shownIndexes.length, total: diff.changes.length })}
              </div>
            )}
            <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
              <ChangeList
                changes={diff.changes}
                onUsePattern={usePattern}
                patternUsed={patternUsed}
                onDiscard={(i) => setDiscarding([i])}
                selected={selected}
                onToggleSelect={toggle}
                onSelectMany={selectMany}
                filter={filter}
                grouped={grouped}
                onQuickFix={(f) => void applyQuickFix(f)}
                quickFixBusy={fixing}
              />
            </div>
          </>
        )}

        {tab === 'up' && (
          <div className="h-full min-h-[400px] flex-1">
            <SqlEditor value={up} onChange={setUp} />
          </div>
        )}
        {tab === 'down' && (
          <div className="h-full min-h-[400px] flex-1">
            <SqlEditor value={down} onChange={setDown} />
          </div>
        )}

        {tab === 'safety' && (
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-3">
            {risky.length === 0 ? (
              <p className="flex items-center gap-2 text-[12.5px] text-emerald-300">
                <ShieldAlert size={15} /> {t('workingTree.noRisks')}
              </p>
            ) : (
              <ul className="space-y-2">
                {risky.map(([c, i]) => (
                  <li key={i} className="rounded-md border border-kraken-line bg-kraken-bg px-3 py-2">
                    <div className="flex items-start gap-2">
                      <ChangeBadge type={c.changeType} />
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-mono text-[12.5px] text-gray-100">{c.objectName}</div>
                        <div className="truncate text-xs text-gray-400">{c.details}</div>
                      </div>
                    </div>
                    {c.safety && (
                      <div className="mt-1.5 space-y-1.5 pl-7">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <SafetyBadges report={c.safety} />
                          {(!!c.safety.alternatives?.length || !!c.downAlternatives?.length) && (
                            <ZeroDowntimeButton used={patternUsed.has(i)} onClick={() => usePattern(c)} label={t('safety.useInEditor')} />
                          )}
                        </div>
                        <div className="text-xs">
                          <SafetyNotes report={c.safety} />
                        </div>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {diff.warnings.length > 0 && (
              <div>
                <div className="panel-title mb-1">{t('workingTree.warningsTitle')}</div>
                <ul className="space-y-1 rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-xs text-amber-200">
                  {diff.warnings.map((w, i) => (
                    <li key={i} className="flex gap-1.5">
                      <TriangleAlert size={13} className="mt-px shrink-0" />
                      {w}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>

      {/* ---------------------------------------------------------- compact commit bar */}
      <footer className="border-t border-kraken-line px-3 py-2">
        {commitOpen && (
          <div className="mb-2 grid grid-cols-[150px_minmax(0,1fr)] gap-2">
            <label>
              <span className="label">{t('common.author')}</span>
              <input className="input" value={author} onChange={(e) => onAuthorChange(e.target.value)} placeholder={t('common.authorPlaceholder')} />
            </label>
            <CommitMessageFields
              message={message}
              onMessageChange={setMessage}
              description={description}
              onDescriptionChange={setDescription}
              onSubmit={() => void commit()}
              autoFocus
            />
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <button className="btn btn-danger" onClick={() => setDiscarding([])} disabled={busy} title={t('workingTree.discardAll')}>
            {t('workingTree.discardAll')}
          </button>
          {selected.size > 0 && (
            <button className="btn" onClick={() => setDiscarding([...selected].sort((a, b) => a - b))} disabled={busy}>
              <Trash2 size={13} /> {t('workingTree.discardSelected', { count: selected.size })}
            </button>
          )}
          <span className="flex-1" />
          {edited && (
            <>
              <span className="text-xs text-amber-300">{t('common.sqlEdited')}</span>
              <button
                className="text-xs text-gray-400 underline hover:text-gray-200"
                onClick={() => {
                  setUp(diff.upSql);
                  setDown(diff.downSql);
                  setPatternUsed(new Set());
                }}
              >
                {t('common.reset')}
              </button>
            </>
          )}
          {commitOpen ? (
            <>
              <button
                className="btn btn-ghost px-2"
                onClick={() => setCommitOpen(false)}
                title={t('workingTree.hideCommit')}
                aria-label={t('workingTree.hideCommit')}
              >
                <ChevronDown size={15} />
              </button>
              <button className="btn btn-primary" onClick={() => void commit()} disabled={busy}>
                <GitCommitHorizontal size={15} /> {busy ? t('workingTree.committing') : t('workingTree.commit')}
              </button>
            </>
          ) : (
            <button className="btn btn-primary" onClick={() => setCommitOpen(true)}>
              <GitCommitHorizontal size={15} /> {t('workingTree.openCommit')}
            </button>
          )}
        </div>
      </footer>

      {fullscreen && (
        <Modal
          title={fullscreen === 'up' ? t('workingTree.tabUp') : t('workingTree.tabDown')}
          subtitle={t('workingTree.since', { env: env.name, head: workingTree.head ? shortHash(workingTree.head) : t('common.emptyHistory') })}
          onClose={() => setFullscreen(null)}
          width="max-w-[min(1600px,97vw)]"
          footer={
            <>
              <span className="flex-1 text-xs text-gray-500">{t('workingTree.fullscreenHint')}</span>
              <button className="btn" onClick={() => setFullscreen(null)}>
                {t('common.close')}
              </button>
            </>
          }
        >
          <div className="h-[calc(100vh-14rem)] min-h-[400px] overflow-hidden rounded-md border border-kraken-line">
            {fullscreen === 'up' ? <SqlEditor value={up} onChange={setUp} /> : <SqlEditor value={down} onChange={setDown} />}
          </div>
        </Modal>
      )}

      {discarding !== null && (
        <DiscardModal
          env={env}
          changeIndexes={discarding.length === 0 ? null : discarding}
          onDone={() => {
            setDiscarding(null);
            setSelected(new Set());
            onDiscarded();
          }}
          onClose={() => setDiscarding(null)}
        />
      )}
    </div>
  );
}
