import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Check, Copy } from 'lucide-react';
import { useI18n } from '../i18n';

/** "-- pgvc:step" separates the steps of a script; a copied script runs as one, so the markers are left out. */
export function withoutStepMarkers(sql: string): string {
  return sql
    .split('\n')
    .filter((line) => line.trim() !== '-- pgvc:step')
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Copies text to the clipboard (navigator.clipboard, or a hidden textarea where it is not available). */
export async function copyText(text: string): Promise<void> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return;
    }
  } catch {
    // fall through to the textarea
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.top = '-1000px';
  document.body.appendChild(area);
  area.select();
  try {
    document.execCommand('copy');
  } finally {
    document.body.removeChild(area);
  }
}

/** "📋 Copy SQL" -> green "✓ Copied!" for two seconds. */
export function CopyButton({ text, label }: { text: string; label?: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | null>(null);
  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    [],
  );
  return (
    <button
      type="button"
      className={`inline-flex shrink-0 items-center gap-1 rounded border px-1.5 py-0.5 text-[10.5px] font-medium transition-colors ${
        copied
          ? 'border-emerald-700/80 bg-emerald-950/50 text-emerald-300'
          : 'border-kraken-line text-gray-400 hover:border-gray-500 hover:text-gray-100'
      }`}
      onClick={(e) => {
        e.stopPropagation();
        void copyText(withoutStepMarkers(text)).then(() => {
          setCopied(true);
          if (timer.current !== null) window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => setCopied(false), 2000);
        });
      }}
      title={t('copy.hint')}
      data-copied={copied || undefined}
    >
      {copied ? <Check size={11} /> : <Copy size={11} />}
      {copied ? t('copy.done') : (label ?? t('copy.sql'))}
    </button>
  );
}

type Tone = 'plain' | 'pattern' | 'fix';

const TONES: Record<Tone, { title: string; code: string; border: string }> = {
  plain: { title: 'text-gray-500', code: 'text-gray-300', border: 'border-transparent' },
  pattern: { title: 'text-amber-300', code: 'text-amber-100/90', border: 'border-amber-800/50' },
  fix: { title: 'text-sky-300', code: 'text-sky-100/90', border: 'border-sky-800/60' },
};

/** A titled SQL block with a copy button (and optional extra actions next to it). */
export function CodeBlock({
  title,
  sql,
  tone = 'plain',
  actions,
  testId,
}: {
  title: ReactNode;
  sql: string | null | undefined;
  tone?: Tone;
  actions?: ReactNode;
  testId?: string;
}) {
  const style = TONES[tone];
  const text = sql && sql.trim() ? sql : null;
  return (
    <div data-testid={testId}>
      <div className="mb-0.5 flex items-center gap-2">
        <span className={`min-w-0 flex-1 truncate ${style.title}`}>{title}</span>
        {actions}
        {text && <CopyButton text={text} />}
      </div>
      <pre className={`overflow-auto rounded border bg-kraken-bg p-2 font-mono text-[11.5px] ${style.border} ${style.code}`}>{text ?? '—'}</pre>
    </div>
  );
}
