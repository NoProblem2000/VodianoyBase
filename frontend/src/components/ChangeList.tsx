import { useEffect, useRef, useState } from 'react';
import { ChevronRight, Package, Play, Undo2, Wrench } from 'lucide-react';
import type { ChangeItem, ChangeType, QuickFix } from '../api/types';
import { groupChanges, type ChangeGroup } from '../lib/changeGroups';
import { CHANGE_CLASSES } from '../lib/changes';
import { useI18n } from '../i18n';
import { ChangeBadge } from './Badges';
import { CodeBlock } from './CodeBlock';
import { DataLossBadge, SafetyBadges, SafetyNotes, ZeroDowntimeButton } from './SafetyBadges';

interface Props {
  changes: ChangeItem[];
  emptyText?: string;
  /** show the per-change UP / DOWN SQL when an item is expanded */
  expandable?: boolean;
  /** offers "Use Zero-Downtime Pattern" (UP and DOWN) for changes that have one */
  onUsePattern?: (change: ChangeItem) => void;
  /** changes whose pattern is already in use (by index) */
  patternUsed?: Set<number>;
  /** offers "Discard / Revert Item" for every change (working tree) */
  onDiscard?: (index: number) => void;
  /** checkboxes: the selected changes (by index in {@code changes}) */
  selected?: Set<number>;
  onToggleSelect?: (index: number) => void;
  /** selects / deselects several changes at once (the checkbox of a group) */
  onSelectMany?: (indexes: number[], select: boolean) => void;
  /** shows only the changes it accepts; indexes stay those of the full list */
  filter?: (change: ChangeItem, index: number) => boolean;
  /** text when the filter hides every change */
  filteredText?: string;
  /** groups the changes by entity (tables of one name in every schema, their sequences, FKs, views) */
  grouped?: boolean;
  /** offers "Apply to database" for the quick fixes of the changes (working tree) */
  onQuickFix?: (fix: QuickFix) => void;
  /** object name of the quick fix that is running */
  quickFixBusy?: string | null;
}

const TYPE_ORDER: ChangeType[] = ['ADDED', 'MODIFIED', 'RENAMED', 'DELETED'];

/** A checkbox that shows "some selected" (indeterminate). */
function GroupCheckbox({ state, onChange, label }: { state: 'all' | 'some' | 'none'; onChange: () => void; label: string }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'some';
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="ml-1 shrink-0 accent-teal-500"
      checked={state === 'all'}
      onChange={onChange}
      onClick={(e) => e.stopPropagation()}
      aria-label={label}
      title={label}
    />
  );
}

export function ChangeList({
  changes,
  emptyText,
  expandable = true,
  onUsePattern,
  patternUsed,
  onDiscard,
  selected,
  onToggleSelect,
  onSelectMany,
  filter,
  filteredText,
  grouped = false,
  onQuickFix,
  quickFixBusy,
}: Props) {
  const { t, tn } = useI18n();
  const [open, setOpen] = useState<number | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  if (changes.length === 0) {
    return <p className="px-1 py-2 text-gray-500">{emptyText ?? t('workingTree.noChanges')}</p>;
  }
  const accepted = (i: number) => !filter || filter(changes[i], i);
  const shown = changes.map((c, i) => [c, i] as const).filter(([, i]) => accepted(i));
  if (shown.length === 0) {
    return <p className="px-1 py-2 text-gray-500">{filteredText ?? t('workingTree.noneMatch')}</p>;
  }

  function renderChange(c: ChangeItem, i: number, nested: boolean) {
    const hasPattern = !!onUsePattern && (!!c.safety?.alternatives?.length || !!c.downAlternatives?.length);
    const hasBadges = !!c.dataLoss || !!c.safety || !!c.quickFixes?.length || hasPattern;
    // a warning explained by a quick fix is shown once, in the quick fix block
    const warnings = (c.warnings ?? []).filter((w) => !c.quickFixes?.some((f) => f.message === w));
    return (
      <li key={`${c.objectType}-${c.objectName}-${c.changeType}-${i}`} className={nested ? 'pl-5' : ''} data-change-index={i}>
        <div className="group flex items-start">
          {onToggleSelect && (
            <input
              type="checkbox"
              className="ml-1 mt-2 shrink-0 accent-teal-500"
              checked={selected?.has(i) ?? false}
              onChange={() => onToggleSelect(i)}
              aria-label={`${t('workingTree.select')}: ${c.objectName}`}
            />
          )}
          <div className="min-w-0 flex-1 hover:bg-kraken-hover/60">
            <button
              className={`flex w-full min-w-0 items-start gap-2 px-1 pt-1.5 text-left disabled:cursor-default ${hasBadges ? '' : 'pb-1.5'}`}
              onClick={() => setOpen(open === i ? null : i)}
              disabled={!expandable}
              aria-expanded={expandable ? open === i : undefined}
            >
              <ChangeBadge type={c.changeType} />
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline gap-2">
                  <span className="truncate font-mono text-[12.5px] text-gray-100">{c.objectName}</span>
                  <span className="shrink-0 text-[10.5px] uppercase text-gray-500">{t(`objectType.${c.objectType}`)}</span>
                </span>
                <span className="block truncate text-xs text-gray-400" title={c.details}>
                  {c.details}
                </span>
              </span>
              {expandable && (
                <ChevronRight size={14} className={`mt-1 shrink-0 text-gray-500 transition-transform ${open === i ? 'rotate-90' : ''}`} />
              )}
            </button>
            {/* badges and actions sit outside the row button: a button inside a button does not get its clicks */}
            {hasBadges && (
            <div className="flex flex-wrap items-center gap-1.5 pb-1.5 pl-[30px] pr-1 pt-1">
              {c.dataLoss && <DataLossBadge change={c} />}
              {c.safety && <SafetyBadges report={c.safety} hideDataLoss={!!c.dataLoss} />}
              {!!c.quickFixes?.length && (
                <span
                  className="inline-flex h-[18px] items-center gap-1 rounded border border-sky-700/80 bg-sky-950/50 px-1.5 text-[10px] font-semibold text-sky-200"
                  title={c.quickFixes.map((f) => f.message).join('\n')}
                  data-quick-fix={c.quickFixes[0].kind}
                >
                  <Wrench size={10} />
                  {t('quickFix.badge')}
                </span>
              )}
              {hasPattern && (
                <ZeroDowntimeButton used={patternUsed?.has(i)} onClick={() => onUsePattern!(c)} label={t('safety.useInEditor')} />
              )}
            </div>
            )}
          </div>
          {onDiscard && (
            <button
              type="button"
              className="mt-1 shrink-0 rounded p-1 text-gray-500 opacity-60 hover:bg-red-950/50 hover:text-red-300 hover:opacity-100 group-hover:opacity-100"
              onClick={() => onDiscard(i)}
              title={t('workingTree.discardItem')}
              aria-label={`${t('workingTree.discardItem')}: ${c.objectName}`}
            >
              <Undo2 size={13} />
            </button>
          )}
        </div>
        {expandable && open === i && (
          <div className="mb-2 ml-7 space-y-1.5 text-xs">
            {warnings.length > 0 && (
              <ul
                className={`space-y-0.5 rounded border px-2 py-1.5 ${
                  c.dataLoss === 'ALREADY_LOST' ? 'border-red-800 bg-red-950/40 text-red-100' : 'border-amber-800/70 bg-amber-950/25 text-amber-100'
                }`}
              >
                {warnings.map((w, k) => (
                  <li key={k}>{w}</li>
                ))}
              </ul>
            )}
            {c.safety && <SafetyNotes report={c.safety} />}
            {c.quickFixes?.map((fix) => (
              <QuickFixBlock key={`${fix.kind}-${fix.objectName}`} fix={fix} onApply={onQuickFix} busy={quickFixBusy === fix.objectName} />
            ))}
            <CodeBlock title={t('common.up')} sql={c.upSql} testId="sql-up" />
            {c.safety?.zeroDowntimeAlternativeSql && (
              <CodeBlock title={t('safety.patternUp')} sql={c.safety.zeroDowntimeAlternativeSql} tone="pattern" testId="sql-up-pattern" />
            )}
            <CodeBlock title={t('common.down')} sql={c.downSql} testId="sql-down" />
            {c.zeroDowntimeDownSql && (
              <CodeBlock title={t('safety.patternDown')} sql={c.zeroDowntimeDownSql} tone="pattern" testId="sql-down-pattern" />
            )}
          </div>
        )}
      </li>
    );
  }

  if (!grouped) {
    return <ul className="divide-y divide-kraken-line/60">{shown.map(([c, i]) => renderChange(c, i, false))}</ul>;
  }

  const groups: ChangeGroup[] = groupChanges(changes)
    .map((g) => ({ ...g, indexes: g.indexes.filter(accepted) }))
    .filter((g) => g.indexes.length > 0);

  function toggleGroup(g: ChangeGroup) {
    const all = g.indexes.every((i) => selected?.has(i));
    if (onSelectMany) onSelectMany(g.indexes, !all);
    else if (onToggleSelect) g.indexes.filter((i) => (selected?.has(i) ?? false) === all).forEach(onToggleSelect);
  }

  function toggleCollapsed(key: string) {
    const next = new Set(collapsed);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setCollapsed(next);
  }

  return (
    <ul className="divide-y divide-kraken-line/60" data-grouped="true">
      {groups.map((g) => {
        if (g.indexes.length === 1) return renderChange(changes[g.indexes[0]], g.indexes[0], false);
        const isCollapsed = collapsed.has(g.key);
        const count = g.indexes.filter((i) => selected?.has(i)).length;
        const state = count === 0 ? 'none' : count === g.indexes.length ? 'all' : 'some';
        const counts = new Map<ChangeType, number>();
        g.indexes.forEach((i) => counts.set(changes[i].changeType, (counts.get(changes[i].changeType) ?? 0) + 1));
        return (
          <li key={`group-${g.key}`} data-group={g.key}>
            <div className="flex items-center gap-1.5 bg-kraken-panel/60 py-1">
              {onToggleSelect && (
                <GroupCheckbox state={state} onChange={() => toggleGroup(g)} label={t('group.select', { name: g.key })} />
              )}
              <button
                type="button"
                className="flex min-w-0 flex-1 items-center gap-1.5 px-1 py-0.5 text-left hover:bg-kraken-hover"
                onClick={() => toggleCollapsed(g.key)}
                aria-expanded={!isCollapsed}
                title={isCollapsed ? t('group.expand') : t('group.collapse')}
              >
                <ChevronRight size={14} className={`shrink-0 text-gray-500 transition-transform ${isCollapsed ? '' : 'rotate-90'}`} />
                <Package size={13} className="shrink-0 text-teal-400" />
                <span className="min-w-0 truncate text-[12.5px]">
                  <span className="font-mono font-semibold text-gray-100">{g.key}</span>
                  <span className="ml-1.5 text-gray-400">
                    ({tn('plural.relatedChanges', g.indexes.length)}
                    {g.schemas.length > 0 && `: ${g.schemas.join(', ')}`})
                  </span>
                </span>
                <span className="ml-auto flex shrink-0 items-center gap-1">
                  {TYPE_ORDER.filter((type) => counts.has(type)).map((type) => (
                    <span
                      key={type}
                      className={`inline-flex h-[17px] items-center rounded border px-1.5 text-[10px] font-semibold ${CHANGE_CLASSES[type]}`}
                      data-group-count={type}
                    >
                      {t(`group.count.${type}`, { count: counts.get(type) ?? 0 })}
                    </span>
                  ))}
                </span>
              </button>
            </div>
            {!isCollapsed && (
              <ul className="divide-y divide-kraken-line/40 border-l border-kraken-line/60 ml-3">
                {g.indexes.map((i) => renderChange(changes[i], i, true))}
              </ul>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/** Warning + SQL of a quick fix with "Copy" and "Apply to database". */
export function QuickFixBlock({ fix, onApply, busy }: { fix: QuickFix; onApply?: (fix: QuickFix) => void; busy?: boolean }) {
  const { t } = useI18n();
  return (
    <div className="space-y-1 rounded border border-sky-800/70 bg-sky-950/25 px-2 py-1.5" data-testid="quick-fix">
      <p className="text-sky-100">{fix.message}</p>
      <CodeBlock
        title={
          <span className="inline-flex items-center gap-1">
            <Wrench size={11} />
            {t('quickFix.title')}
          </span>
        }
        sql={fix.sql}
        tone="fix"
        actions={
          onApply && (
            <button
              type="button"
              className="inline-flex shrink-0 items-center gap-1 rounded border border-sky-600/80 bg-sky-900/40 px-1.5 py-0.5 text-[10.5px] font-semibold text-sky-100 hover:bg-sky-800/60 disabled:opacity-50"
              onClick={(e) => {
                e.stopPropagation();
                onApply(fix);
              }}
              disabled={busy}
              title={t('quickFix.applyHint')}
            >
              <Play size={11} />
              {busy ? t('quickFix.applying') : t('quickFix.apply')}
            </button>
          )
        }
      />
    </div>
  );
}
