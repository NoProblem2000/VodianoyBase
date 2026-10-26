export function shortHash(hash: string | null | undefined, length = 7): string {
  return hash ? hash.slice(0, length) : '—';
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
  if (seconds < 45) return 'just now';
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  if (seconds < 86400 * 7) return `${Math.round(seconds / 86400)} d ago`;
  return formatDate(iso);
}

export function timeOfDay(iso: string | null | undefined): string {
  if (!iso) return '';
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function firstLine(text: string): string {
  return text.split('\n')[0];
}
