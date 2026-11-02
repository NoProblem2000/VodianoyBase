import { useEffect, useState } from 'react';
import { ChevronRight, Filter } from 'lucide-react';
import type { DiffIgnoreRules } from '../api/types';
import { useI18n, type TKey } from '../i18n';

export const DEFAULT_IGNORE_RULES: DiffIgnoreRules = {
  ignoreComments: false,
  ignoreTablespaces: true,
  ignoreOwnersAndGrants: true,
  ignorePartitions: false,
  excludedObjectPatterns: [],
};

type Flag = 'ignoreComments' | 'ignoreTablespaces' | 'ignoreOwnersAndGrants' | 'ignorePartitions';

const FLAGS: { id: Flag; label: TKey; hint: TKey }[] = [
  { id: 'ignoreComments', label: 'ignore.comments', hint: 'ignore.commentsHint' },
  { id: 'ignoreOwnersAndGrants', label: 'ignore.owners', hint: 'ignore.ownersHint' },
  { id: 'ignoreTablespaces', label: 'ignore.tablespaces', hint: 'ignore.tablespacesHint' },
  { id: 'ignorePartitions', label: 'ignore.partitions', hint: 'ignore.partitionsHint' },
];

export function sameRules(a: DiffIgnoreRules | null, b: DiffIgnoreRules | null): boolean {
  if (!a || !b) return a === b;
  return (
    a.ignoreComments === b.ignoreComments &&
    a.ignoreTablespaces === b.ignoreTablespaces &&
    a.ignoreOwnersAndGrants === b.ignoreOwnersAndGrants &&
    a.ignorePartitions === b.ignorePartitions &&
    a.excludedObjectPatterns.join('\n') === b.excludedObjectPatterns.join('\n')
  );
}

/** "Ignored: comments, owners & grants, 2 pattern(s)". */
export function useRulesSummary() {
  const { t } = useI18n();
  return (rules: DiffIgnoreRules | null): string => {
    if (!rules) return '';
    const parts: string[] = [];
    if (rules.ignoreComments) parts.push(t('ignore.sComments'));
    if (rules.ignoreOwnersAndGrants) parts.push(t('ignore.sOwners'));
    if (rules.ignoreTablespaces) parts.push(t('ignore.sTablespaces'));
    if (rules.ignorePartitions) parts.push(t('ignore.sPartitions'));
    if (rules.excludedObjectPatterns.length) parts.push(t('ignore.sPatterns', { count: rules.excludedObjectPatterns.length }));
    return parts.length ? t('ignore.summary', { list: parts.join(', ') }) : t('ignore.nothing');
  };
}

interface EditorProps {
  value: DiffIgnoreRules;
  onChange: (rules: DiffIgnoreRules) => void;
  compact?: boolean;
}

/** Checkboxes + one pattern per line. */
export function IgnoreRulesEditor({ value, onChange, compact = false }: EditorProps) {
  const { t } = useI18n();
  // the text area keeps what is typed (blank lines too); the rules get the trimmed patterns
  const [text, setText] = useState(value.excludedObjectPatterns.join('\n'));
  useEffect(() => {
    const current = text
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
    if (current.join('\n') !== value.excludedObjectPatterns.join('\n')) {
      setText(value.excludedObjectPatterns.join('\n'));
    }
  }, [value.excludedObjectPatterns]);

  return (
    <div className={`grid gap-3 ${compact ? 'grid-cols-1' : 'grid-cols-[minmax(0,1fr)_minmax(0,1fr)]'}`}>
      <div className="flex flex-col gap-2">
        {FLAGS.map((f) => (
          <label key={f.id} className="flex cursor-pointer items-start gap-2">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={value[f.id]}
              onChange={(e) => onChange({ ...value, [f.id]: e.target.checked })}
            />
            <span>
              <span className="block text-[12.5px] text-gray-100">{t(f.label)}</span>
              <span className="block text-[11px] text-gray-500">{t(f.hint)}</span>
            </span>
          </label>
        ))}
      </div>
      <label className="flex min-w-0 flex-col">
        <span className="label">{t('ignore.patterns')}</span>
        <textarea
          className="input h-auto min-h-[92px] flex-1 resize-y py-1.5 font-mono text-[12px]"
          aria-label={t('ignore.patterns')}
          value={text}
          placeholder={t('ignore.patternsPlaceholder')}
          spellCheck={false}
          onChange={(e) => {
            setText(e.target.value);
            onChange({
              ...value,
              excludedObjectPatterns: e.target.value
                .split('\n')
                .map((l) => l.trim())
                .filter(Boolean),
            });
          }}
        />
        <span className="mt-1 text-[11px] leading-snug text-gray-500">{t('ignore.patternsHint')}</span>
      </label>
    </div>
  );
}

interface PanelProps {
  value: DiffIgnoreRules;
  onChange: (rules: DiffIgnoreRules) => void;
  /** e.g. "Compare again" when the rules differ from those of the shown result */
  actionLabel?: string;
  onAction?: () => void;
  actionDisabled?: boolean;
  /** offers "Save as default" */
  onSaveDefault?: () => void;
  savingDefault?: boolean;
  defaultOpen?: boolean;
}

/** Collapsible one-off rules of a comparison (snapshot compare, matrix). */
export function IgnoreRulesPanel(p: PanelProps) {
  const { t } = useI18n();
  const summary = useRulesSummary();
  const [open, setOpen] = useState(p.defaultOpen ?? false);
  return (
    <div className="rounded-md border border-kraken-line bg-kraken-bg">
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-kraken-hover"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <ChevronRight size={13} className={`text-gray-500 transition-transform ${open ? 'rotate-90' : ''}`} />
        <Filter size={13} className="text-gray-500" />
        <span className="text-[12.5px] font-medium text-gray-200">{t('ignore.thisComparison')}</span>
        <span className="min-w-0 flex-1 truncate text-[11.5px] text-gray-500">{summary(p.value)}</span>
      </button>
      {open && (
        <div className="space-y-3 border-t border-kraken-line px-3 py-3">
          <IgnoreRulesEditor value={p.value} onChange={p.onChange} />
          <div className="flex items-center gap-2">
            <span className="flex-1 text-[11px] text-gray-500">{t('ignore.oneOff')}</span>
            {p.onSaveDefault && (
              <button type="button" className="btn" onClick={p.onSaveDefault} disabled={p.savingDefault}>
                {p.savingDefault ? t('ignore.saving') : t('ignore.save')}
              </button>
            )}
            {p.onAction && p.actionLabel && (
              <button type="button" className="btn btn-primary" onClick={p.onAction} disabled={p.actionDisabled}>
                {p.actionLabel}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
