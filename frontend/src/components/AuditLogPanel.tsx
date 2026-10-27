import type { AuditLogEntry } from '../api/types';
import { useI18n } from '../i18n';
import { timeOfDay } from '../lib/format';

export function AuditLogPanel({ entries, live }: { entries: AuditLogEntry[]; live: boolean }) {
  const { t, locale } = useI18n();
  const newestFirst = [...entries].reverse();
  return (
    <section className="flex h-[38%] min-h-[160px] flex-col border-t border-kraken-line" aria-label={t('audit.title')}>
      <div className="flex items-center gap-2 px-3 pb-2 pt-3">
        <h2 className="panel-title">{t('audit.title')}</h2>
        {live && (
          <span className="relative flex h-2 w-2" title={t('audit.polling')}>
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-teal-400 opacity-60" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-teal-400" />
          </span>
        )}
        <span className="ml-auto text-[11px] text-gray-500">{entries.length}</span>
      </div>
      <ol className="min-h-0 flex-1 overflow-auto px-3 pb-2">
        {newestFirst.length === 0 && <li className="py-1 text-gray-500">{t('audit.empty')}</li>}
        {newestFirst.map((e) => (
          <li key={e.id} className="border-b border-kraken-line/50 py-1.5" title={e.rawQuery}>
            <div className="flex items-center gap-2">
              <span
                className={`rounded px-1.5 py-px text-[10.5px] font-semibold ${
                  e.eventType === 'sql_drop' ? 'bg-red-950 text-red-300' : 'bg-sky-950 text-sky-300'
                }`}
              >
                {e.commandTag}
              </span>
              <span className="ml-auto shrink-0 font-mono text-[11px] text-gray-500">{timeOfDay(e.executedAt, locale)}</span>
            </div>
            <div className="mt-0.5 flex items-center gap-2">
              <span className="truncate font-mono text-[12px] text-gray-200">{e.objectIdentity}</span>
              <span className="ml-auto shrink-0 text-[11px] text-gray-500">{e.dbUser}</span>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
