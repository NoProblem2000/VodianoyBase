import { useState } from 'react';
import { ChevronRight, Undo2 } from 'lucide-react';
import type { ChangeItem } from '../api/types';
import { useI18n } from '../i18n';
import { ChangeBadge } from './Badges';
import { SafetyBadges, SafetyNotes, ZeroDowntimeButton } from './SafetyBadges';

interface Props {
  changes: ChangeItem[];
  emptyText?: string;
  /** show the per-change UP / DOWN SQL when an item is expanded */
  expandable?: boolean;
  /** offers "Use Zero-Downtime Pattern" for changes that have one */
  onUsePattern?: (change: ChangeItem) => void;
  /** changes whose pattern is already in use (by index) */
  patternUsed?: Set<number>;
  /** offers "Discard / Revert Item" for every change (working tree) */
  onDiscard?: (index: number) => void;
  /** checkboxes: the selected changes (by index in {@code changes}) */
  selected?: Set<number>;
  onToggleSelect?: (index: number) => void;
  /** shows only the changes it accepts; indexes stay those of the full list */
  filter?: (change: ChangeItem, index: number) => boolean;
  /** text when the filter hides every change */
  filteredText?: string;
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
  filter,
  filteredText,
}: Props) {
  const { t } = useI18n();
  const [open, setOpen] = useState<number | null>(null);
  if (changes.length === 0) {
    return <p className="px-1 py-2 text-gray-500">{emptyText ?? t('workingTree.noChanges')}</p>;
  }
  const shown = changes.map((c, i) => [c, i] as const).filter(([c, i]) => !filter || filter(c, i));
  if (shown.length === 0) {
    return <p className="px-1 py-2 text-gray-500">{filteredText ?? t('workingTree.noneMatch')}</p>;
  }
  return (
    <ul className="divide-y divide-kraken-line/60">
      {shown.map(([c, i]) => {
        const pattern = !!c.safety?.alternatives?.length && !!onUsePattern;
        return (
          <li key={`${c.objectType}-${c.objectName}-${c.changeType}-${i}`}>
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
            <button
              className="flex min-w-0 flex-1 items-start gap-2 px-1 py-1.5 text-left hover:bg-kraken-hover disabled:hover:bg-transparent"
              onClick={() => setOpen(open === i ? null : i)}
              disabled={!expandable}
            >
              <ChangeBadge type={c.changeType} />
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline gap-2">
                  <span className="truncate font-mono text-[12.5px] text-gray-100">{c.objectName}</span>
                  <span className="shrink-0 text-[10.5px] uppercase text-gray-500">{t(`objectType.${c.objectType}`)}</span>
                </span>
                <span className="block truncate text-xs text-gray-400">{c.details}</span>
                {c.safety && (
                  <span className="mt-1 flex flex-wrap items-center gap-1.5">
                    <SafetyBadges report={c.safety} />
                    {pattern && (
                      <ZeroDowntimeButton used={patternUsed?.has(i)} onClick={() => onUsePattern!(c)} />
                    )}
                  </span>
                )}
              </span>
              {expandable && (
                <ChevronRight size={14} className={`mt-1 shrink-0 text-gray-500 transition-transform ${open === i ? 'rotate-90' : ''}`} />
              )}
            </button>
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
                {c.safety && <SafetyNotes report={c.safety} />}
                <div>
                  <div className="mb-0.5 text-gray-500">{t('common.up')}</div>
                  <pre className="overflow-auto rounded bg-kraken-bg p-2 font-mono text-[11.5px] text-gray-300">{c.upSql || '—'}</pre>
                </div>
                {c.safety?.zeroDowntimeAlternativeSql && (
                  <div>
                    <div className="mb-0.5 text-amber-300">{t('safety.usePattern')}</div>
                    <pre className="overflow-auto rounded bg-kraken-bg p-2 font-mono text-[11.5px] text-amber-100/90">
                      {c.safety.zeroDowntimeAlternativeSql}
                    </pre>
                  </div>
                )}
                <div>
                  <div className="mb-0.5 text-gray-500">{t('common.down')}</div>
                  <pre className="overflow-auto rounded bg-kraken-bg p-2 font-mono text-[11.5px] text-gray-300">{c.downSql || '—'}</pre>
                </div>
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
