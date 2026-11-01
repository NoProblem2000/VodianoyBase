import { CheckCircle2, ShieldCheck, Unplug, XCircle } from 'lucide-react';
import type { DryRunResult, ExecutionOptions, SqlSafetyReport } from '../api/types';
import { useI18n } from '../i18n';
import { ChangeList } from './ChangeList';
import { SafetyBadges, SafetyNotes, ZeroDowntimeButton } from './SafetyBadges';

export const DEFAULT_EXECUTION: ExecutionOptions = { lockTimeout: '3s', statementTimeout: '60s', preScript: '', postScript: '' };

/** lock_timeout / statement_timeout inputs. */
export function TimeoutFields({ value, onChange }: { value: ExecutionOptions; onChange: (v: ExecutionOptions) => void }) {
  const { t } = useI18n();
  return (
    <div className="flex items-end gap-2" title={t('exec.timeoutsHint')}>
      <label className="w-[104px]">
        <span className="label font-mono">{t('exec.lockTimeout')}</span>
        <input
          className="input font-mono"
          value={value.lockTimeout}
          onChange={(e) => onChange({ ...value, lockTimeout: e.target.value })}
          aria-label={t('exec.lockTimeout')}
          spellCheck={false}
        />
      </label>
      <label className="w-[124px]">
        <span className="label font-mono">{t('exec.statementTimeout')}</span>
        <input
          className="input font-mono"
          value={value.statementTimeout}
          onChange={(e) => onChange({ ...value, statementTimeout: e.target.value })}
          aria-label={t('exec.statementTimeout')}
          spellCheck={false}
        />
      </label>
    </div>
  );
}

/** Statement-by-statement safety analysis with "Use Zero-Downtime Pattern" per statement. */
export function SqlSafetyPanel({
  report,
  loading,
  onUsePattern,
}: {
  report: SqlSafetyReport | null;
  loading: boolean;
  onUsePattern?: (statement: string, replacement: string) => void;
}) {
  const { t, tn } = useI18n();
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-2 border-b border-kraken-line px-3 py-2">
        <ShieldCheck size={14} className="text-teal-400" />
        <span className="panel-title">{t('safety.title')}</span>
        {loading && <span className="text-xs text-gray-500">{t('safety.analyzing')}</span>}
        {report && (
          <span className="ml-auto flex items-center gap-2 text-xs text-gray-400">
            {tn('plural.steps', report.steps)}
            <SafetyBadges report={report.overall} compact />
          </span>
        )}
      </div>
      <div className="min-h-0 flex-1 overflow-auto px-3 py-2">
        {report && report.statements.length === 0 && <p className="text-gray-500">{t('safety.noStatements')}</p>}
        <ol className="space-y-2">
          {report?.statements.map((s) => (
            <li key={s.index} className="rounded-md border border-kraken-line bg-kraken-bg px-2.5 py-2">
              <div className="flex items-start gap-2">
                <span className="shrink-0 font-mono text-[10.5px] text-gray-500">{t('safety.statement', { index: s.index, step: s.step })}</span>
                <span className="ml-auto">
                  <SafetyBadges report={s.report} />
                </span>
              </div>
              <pre className="mt-1 max-h-20 overflow-auto whitespace-pre-wrap font-mono text-[11.5px] text-gray-300">{s.sql}</pre>
              <SafetyNotes report={s.report} />
              {s.report.zeroDowntimeAlternativeSql && onUsePattern && (
                <div className="mt-1.5">
                  <ZeroDowntimeButton onClick={() => onUsePattern(s.sql, s.report.zeroDowntimeAlternativeSql!)} />
                </div>
              )}
            </li>
          ))}
        </ol>
        <p className="mt-3 text-[11px] text-gray-500">{t('safety.stepHint')}</p>
      </div>
    </div>
  );
}

/** Result of a dry-run. */
export function DryRunPanel({ result }: { result: DryRunResult | null }) {
  const { t, tn } = useI18n();
  if (!result) {
    return <p className="p-3 text-gray-500">{t('exec.notRun')}</p>;
  }
  return (
    <div className="h-full min-h-0 space-y-3 overflow-auto px-3 py-2.5" data-testid="dry-run-result">
      <div
        className={`flex items-start gap-2 rounded-md border px-3 py-2 ${
          result.success ? 'border-emerald-800 bg-emerald-950/30 text-emerald-200' : 'border-red-800 bg-red-950/40 text-red-200'
        }`}
      >
        {result.success ? <CheckCircle2 size={16} className="mt-0.5 shrink-0" /> : <XCircle size={16} className="mt-0.5 shrink-0" />}
        <div>
          <div className="font-medium">
            {result.success ? t('exec.dryOk', { ms: result.elapsedMs }) : t('exec.dryFailed', { step: result.failedStep ?? '?' })}
          </div>
          {result.error && <div className="mt-1 whitespace-pre-wrap font-mono text-[12px]">{result.error}</div>}
          <div className="mt-1 text-[11px] opacity-75">
            {t('exec.dryTimeouts', { lock: result.timeouts.lockTimeout, statement: result.timeouts.statementTimeout })}
          </div>
        </div>
      </div>

      <ol className="rounded-md border border-kraken-line">
        {result.steps.map((s) => (
          <li key={s.index} className="flex items-center gap-3 border-b border-kraken-line/60 px-3 py-1.5 last:border-b-0">
            <span className="w-16 font-medium">{t('exec.step', { index: s.index })}</span>
            <span className="flex items-center gap-1 text-xs text-gray-400">
              {!s.transactional && <Unplug size={11} className="text-sky-300" />}
              {s.transactional ? t('exec.stepTx') : t('exec.stepNonTx')}
            </span>
            <span className="ml-auto font-mono text-xs text-gray-300">
              {tn('plural.statements', s.statements)} · {s.elapsedMs} ms
            </span>
          </li>
        ))}
      </ol>

      {result.rewrittenStatements.length > 0 && (
        <div className="text-xs text-sky-200">
          <p>{t('exec.rewritten')}</p>
          <ul className="mt-1 space-y-0.5 font-mono text-[11px] text-gray-400">
            {result.rewrittenStatements.map((s, i) => (
              <li key={i} className="truncate">
                {s}
              </li>
            ))}
          </ul>
        </div>
      )}
      {result.uncommittedChanges > 0 && (
        <p className="text-xs text-amber-300">{t('exec.dryUncommitted', { count: result.uncommittedChanges })}</p>
      )}
      {result.success && (
        <div>
          <div className="panel-title mb-1">{t('exec.dryChanges')}</div>
          <div className="rounded-md border border-kraken-line px-2">
            <ChangeList changes={result.changes} />
          </div>
        </div>
      )}
    </div>
  );
}
