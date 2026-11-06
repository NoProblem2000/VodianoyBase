import type { KeyboardEvent } from 'react';
import { useI18n } from '../i18n';

interface Props {
  message: string;
  onMessageChange: (message: string) => void;
  description: string;
  onDescriptionChange: (description: string) => void;
  messagePlaceholder?: string;
  /** Ctrl+Enter / ⌘+Enter in either field */
  onSubmit?: () => void;
  /** rows of the description textarea */
  rows?: number;
  autoFocus?: boolean;
}

/**
 * Commit summary (one line, shown in the graph) and an optional multi-line description (details, ticket numbers),
 * like the Summary + Description of GitKraken.
 */
export function CommitMessageFields({
  message,
  onMessageChange,
  description,
  onDescriptionChange,
  messagePlaceholder,
  onSubmit,
  rows = 2,
  autoFocus,
}: Props) {
  const { t } = useI18n();
  const submitOnCtrlEnter = (e: KeyboardEvent) => {
    if (onSubmit && e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      onSubmit();
    }
  };
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <label className="min-w-0">
        <span className="label">{t('common.commitSummary')}</span>
        <input
          className="input"
          value={message}
          onChange={(e) => onMessageChange(e.target.value.replace(/[\r\n]+/g, ' '))}
          placeholder={messagePlaceholder ?? t('workingTree.messagePlaceholder')}
          onKeyDown={submitOnCtrlEnter}
          autoFocus={autoFocus}
          maxLength={500}
        />
      </label>
      <label className="min-w-0">
        <span className="label">{t('common.commitDescription')}</span>
        <textarea
          className="input resize-y py-1.5 leading-snug"
          rows={rows}
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          placeholder={t('common.commitDescriptionPlaceholder')}
          onKeyDown={submitOnCtrlEnter}
        />
      </label>
    </div>
  );
}
