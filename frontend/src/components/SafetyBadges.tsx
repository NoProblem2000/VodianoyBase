import { AlertOctagon, HardDrive, Lock, RefreshCcw, Timer, Unplug, Zap } from 'lucide-react';
import type { ChangeItem, RiskLevel, SafetyReport } from '../api/types';
import { useI18n } from '../i18n';

const RISK_CLASSES: Record<RiskLevel, string> = {
  SAFE: 'border-emerald-700/70 bg-emerald-950/40 text-emerald-300',
  WARNING: 'border-amber-700/70 bg-amber-950/40 text-amber-300',
  DANGEROUS: 'border-red-700/70 bg-red-950/50 text-red-300',
};

/** 4.5M / 120k / 950 */
export function formatRows(rows: number | undefined): string {
  if (rows === undefined || rows === null) return '?';
  if (rows >= 1_000_000) return `${(rows / 1_000_000).toFixed(1)}M`;
  if (rows >= 1_000) return `${Math.round(rows / 1_000)}k`;
  return String(rows);
}

/**
 * Data of a dropped table / partition / column: "💀 already lost" when the drop already happened in the live database
 * (working tree — discarding only recreates an empty structure), "⚠️ will be lost on apply" for a script to run.
 */
export function DataLossBadge({ change }: { change: ChangeItem }) {
  const { t } = useI18n();
  if (!change.dataLoss) return null;
  const already = change.dataLoss === 'ALREADY_LOST';
  const size = [change.lostPrettySize, change.lostRows ? t('dataLoss.rows', { rows: formatRows(change.lostRows) }) : null]
    .filter(Boolean)
    .join(' / ');
  const hint = change.warnings?.length ? change.warnings.join('\n') : already ? t('dataLoss.alreadyHint') : t('dataLoss.willBeHint');
  return (
    <span
      className={`inline-flex h-[18px] items-center gap-1 rounded px-1.5 text-[10px] font-semibold ${
        already ? 'border border-red-700 bg-black/60 text-red-200' : 'bg-red-900/70 text-red-100'
      }`}
      title={hint}
      data-data-loss={change.dataLoss}
    >
      {already ? t('dataLoss.already') : t('dataLoss.willBe')}
      {size && <span className="font-normal opacity-80">· {size}</span>}
    </span>
  );
}

/** Risk level, lock, table size and Data Loss / Table Rewrite markers of one change or statement. */
export function SafetyBadges({ report, compact, hideDataLoss }: { report: SafetyReport; compact?: boolean; hideDataLoss?: boolean }) {
  const { t } = useI18n();
  const details = [report.estimatedDurationHint, ...(report.notes ?? [])].filter(Boolean).join('\n');
  return (
    <span className="inline-flex flex-wrap items-center gap-1" title={details || undefined}>
      <span
        className={`inline-flex h-[18px] items-center rounded border px-1.5 text-[10px] font-bold uppercase tracking-wide ${RISK_CLASSES[report.riskLevel]}`}
        data-risk={report.riskLevel}
      >
        {t(`safety.risk.${report.riskLevel}`)}
      </span>
      {report.lockType && (
        <span
          className={`inline-flex h-[18px] items-center gap-1 rounded border px-1.5 font-mono text-[10px] ${
            report.lockType === 'ACCESS EXCLUSIVE'
              ? 'border-red-800/70 text-red-300'
              : report.lockType === 'SHARE UPDATE EXCLUSIVE' || report.lockType.startsWith('ROW')
                ? 'border-kraken-line text-gray-400'
                : 'border-amber-800/70 text-amber-300'
          }`}
          title={t('safety.lockTitle', { lock: report.lockType })}
        >
          <Lock size={10} />
          {report.lockType}
        </span>
      )}
      {report.prettySize && !compact && (
        <span
          className="inline-flex h-[18px] items-center gap-1 rounded border border-kraken-line px-1.5 text-[10px] text-gray-400"
          title={t('safety.sizeTitle', { table: report.table, size: report.prettySize, rows: formatRows(report.estimatedRows) })}
        >
          <HardDrive size={10} />
          {t('safety.size', { size: report.prettySize, rows: formatRows(report.estimatedRows) })}
        </span>
      )}
      {report.causesDataLoss && !hideDataLoss && (
        <span className="inline-flex h-[18px] items-center gap-1 rounded bg-red-900/70 px-1.5 text-[10px] font-semibold text-red-100">
          <AlertOctagon size={10} />
          {t('safety.dataLoss')}
        </span>
      )}
      {report.causesTableRewrite && (
        <span className="inline-flex h-[18px] items-center gap-1 rounded bg-amber-900/60 px-1.5 text-[10px] font-semibold text-amber-100">
          <RefreshCcw size={10} />
          {t('safety.rewrite')}
        </span>
      )}
      {report.longRunning && !report.causesTableRewrite && !compact && (
        <span className="inline-flex h-[18px] items-center gap-1 rounded border border-kraken-line px-1.5 text-[10px] text-gray-400">
          <Timer size={10} />
          {t('safety.longRunning')}
        </span>
      )}
      {report.nonTransactional && (
        <span className="inline-flex h-[18px] items-center gap-1 rounded border border-sky-800 px-1.5 text-[10px] text-sky-300">
          <Unplug size={10} />
          {t('safety.nonTx')}
        </span>
      )}
    </span>
  );
}

/** Notes and duration of a report (expanded view). */
export function SafetyNotes({ report }: { report: SafetyReport }) {
  if (!report.estimatedDurationHint && (!report.notes || report.notes.length === 0)) return null;
  return (
    <ul className="mt-1 space-y-0.5 text-[11.5px] text-gray-400">
      {report.estimatedDurationHint && (
        <li className="flex gap-1.5">
          <Timer size={11} className="mt-0.5 shrink-0" />
          {report.estimatedDurationHint}
        </li>
      )}
      {report.notes?.map((n, i) => (
        <li key={i} className="flex gap-1.5">
          <span className="mt-[5px] h-1 w-1 shrink-0 rounded-full bg-gray-500" />
          {n}
        </li>
      ))}
    </ul>
  );
}

/** "⚡ Use Zero-Downtime Pattern" for a report with alternatives. */
export function ZeroDowntimeButton({ onClick, used }: { onClick: () => void; used?: boolean }) {
  const { t } = useI18n();
  if (used) {
    return (
      <span className="inline-flex items-center gap-1 text-[11px] text-emerald-300">
        <Zap size={11} />
        {t('safety.patternUsed')}
      </span>
    );
  }
  return (
    <button
      type="button"
      className="inline-flex items-center rounded border border-amber-600/70 bg-amber-950/40 px-2 py-0.5 text-[11px] font-semibold text-amber-200 hover:bg-amber-900/50"
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
    >
      {t('safety.usePattern')}
    </button>
  );
}

/** Replaces every alternative's statement in the text (first occurrence); returns null when one is missing. */
export function applyAlternatives(text: string, alternatives: { statement: string; replacement: string }[]): string | null {
  let result = text;
  for (const a of alternatives) {
    const at = result.indexOf(a.statement);
    if (at < 0) return null;
    result = result.slice(0, at) + a.replacement + result.slice(at + a.statement.length);
  }
  return result;
}
