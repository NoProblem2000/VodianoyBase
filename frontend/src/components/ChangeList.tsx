import { useState } from 'react';
import { ChevronRight } from 'lucide-react';
import type { ChangeItem } from '../api/types';
import { useI18n } from '../i18n';
import { ChangeBadge } from './Badges';

interface Props {
  changes: ChangeItem[];
  emptyText?: string;
  /** show the per-change UP / DOWN SQL when an item is expanded */
  expandable?: boolean;
}

export function ChangeList({ changes, emptyText, expandable = true }: Props) {
  const { t } = useI18n();
  const [open, setOpen] = useState<number | null>(null);
  if (changes.length === 0) {
    return <p className="px-1 py-2 text-gray-500">{emptyText ?? t('workingTree.noChanges')}</p>;
  }
  return (
    <ul className="divide-y divide-kraken-line/60">
      {changes.map((c, i) => (
        <li key={`${c.objectType}-${c.objectName}-${c.changeType}-${i}`}>
          <button
            className="flex w-full items-start gap-2 px-1 py-1.5 text-left hover:bg-kraken-hover disabled:hover:bg-transparent"
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
            </span>
            {expandable && (
              <ChevronRight size={14} className={`mt-1 shrink-0 text-gray-500 transition-transform ${open === i ? 'rotate-90' : ''}`} />
            )}
          </button>
          {expandable && open === i && (
            <div className="mb-2 ml-7 space-y-1.5 text-xs">
              <div>
                <div className="mb-0.5 text-gray-500">{t('common.up')}</div>
                <pre className="overflow-auto rounded bg-kraken-bg p-2 font-mono text-[11.5px] text-gray-300">{c.upSql || '—'}</pre>
              </div>
              <div>
                <div className="mb-0.5 text-gray-500">{t('common.down')}</div>
                <pre className="overflow-auto rounded bg-kraken-bg p-2 font-mono text-[11.5px] text-gray-300">{c.downSql || '—'}</pre>
              </div>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
