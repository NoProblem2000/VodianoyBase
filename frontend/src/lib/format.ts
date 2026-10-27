export function shortHash(hash: string | null | undefined, length = 7): string {
  return hash ? hash.slice(0, length) : '—';
}

/** Date and time in the UI locale (e.g. "ru-RU", "en-US"). */
export function formatDate(iso: string | null | undefined, locale?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString(locale, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/**
 * "just now", "5 min. ago", "3 hours ago" ... in the UI locale; older than a week: the date.
 * @param justNow localized text for less than a minute
 */
export function relativeTime(iso: string | null | undefined, locale: string, justNow: string): string {
  if (!iso) return '';
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
  if (seconds < 45) return justNow;
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'always', style: 'short' });
  if (seconds < 3600) return rtf.format(-Math.max(1, Math.round(seconds / 60)), 'minute');
  if (seconds < 86400) return rtf.format(-Math.round(seconds / 3600), 'hour');
  if (seconds < 86400 * 7) return rtf.format(-Math.round(seconds / 86400), 'day');
  return formatDate(iso, locale);
}

export function timeOfDay(iso: string | null | undefined, locale?: string): string {
  if (!iso) return '';
  return new Date(iso).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function firstLine(text: string): string {
  return text.split('\n')[0];
}
