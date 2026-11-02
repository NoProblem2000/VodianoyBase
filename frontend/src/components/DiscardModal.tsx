import { useEffect, useState } from 'react';
import { Eraser, Trash2, TriangleAlert } from 'lucide-react';
import { api } from '../api/client';
import type { DiscardPlan, EnvironmentView, ExecutionOptions } from '../api/types';
import { useI18n } from '../i18n';
import { shortHash } from '../lib/format';
import { ChangeList } from './ChangeList';
import { DEFAULT_EXECUTION, SqlSafetyPanel, TimeoutFields } from './ExecutionPanels';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  /** null: discard everything (reset to HEAD); otherwise positions in the working-tree change list */
  changeIndexes: number[] | null;
  onDone: () => void;
  onClose: () => void;
}

/**
 * "Discard All Changes (Reset to HEAD)" and "Discard / Revert Item": shows the SQL that reverts the uncommitted
 * changes (editable), its risk, and runs it in one transaction.
 */
export function DiscardModal({ env, changeIndexes, onDone, onClose }: Props) {
  const { t, tn } = useI18n();
  const toast = useToast();
  const [plan, setPlan] = useState<DiscardPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sql, setSql] = useState('');
  const [options, setOptions] = useState<ExecutionOptions>(DEFAULT_EXECUTION);
  const [skipVerification, setSkipVerification] = useState(false);
  const [busy, setBusy] = useState<'discard' | 'log' | null>(null);

  useEffect(() => {
    api
      .discardPlan(env.id, changeIndexes)
      .then((p) => {
        setPlan(p);
        setSql(p.downSql);
      })
      .catch((e: Error) => setError(e.message));
    // loaded once for the selection the dialog was opened with
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const edited = !!plan && sql !== plan.downSql;

  async function discard() {
    if (!plan) return;
    setBusy('discard');
    try {
      const r = await api.discard({
        envId: env.id,
        changeIndexes,
        customDownSql: edited ? sql : null,
        expectedTotalChanges: plan.totalChanges,
        skipVerification,
        lockTimeout: options.lockTimeout,
        statementTimeout: options.statementTimeout,
      });
      toast.success(
        r.full
          ? t('discard.doneAll', { env: env.name, head: r.head ? shortHash(r.head) : t('discard.emptySchema') })
          : t('discard.doneSome', { count: r.discardedChanges, left: r.remainingChanges }),
      );
      onDone();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function clearLog() {
    setBusy('log');
    try {
      const r = await api.clearDdlLog(env.id);
      toast.success(t('discard.logCleared', { count: r.removed }));
      onDone();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const full = plan?.full ?? changeIndexes === null;
  const title = full ? t('discard.titleAll') : t('discard.titleItem');
  const target = plan?.head ? t('discard.toHead', { head: shortHash(plan.head) }) : t('discard.toEmpty');

  return (
    <Modal
      title={title}
      subtitle={t('discard.subtitle', { env: env.name, target })}
      onClose={onClose}
      width="max-w-[min(1300px,96vw)]"
      footer={
        <>
          {full && (
            <label className="flex items-center gap-2 text-xs text-gray-400">
              <input type="checkbox" checked={skipVerification} onChange={(e) => setSkipVerification(e.target.checked)} />
              {t('common.skipVerification')}
            </label>
          )}
          <TimeoutFields value={options} onChange={setOptions} />
          <span className="flex-1" />
          {full && plan && plan.auditEntries > 0 && (
            <button className="btn" onClick={() => void clearLog()} disabled={busy !== null} title={t('discard.clearLogHint')}>
              <Eraser size={14} /> {busy === 'log' ? t('discard.clearing') : t('discard.clearLog')}
            </button>
          )}
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="btn btn-danger" onClick={() => void discard()} disabled={busy !== null || !plan || !sql.trim()}>
            <Trash2 size={14} /> {busy === 'discard' ? t('discard.running') : full ? t('discard.runAll') : t('discard.runItem')}
          </button>
        </>
      }
    >
      {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
      {!plan && !error && <p className="text-gray-500">{t('common.loading')}</p>}
      {plan && (
        <div className="flex flex-col gap-3">
          <div className="flex items-start gap-2 rounded-md border border-red-900 bg-red-950/30 px-3 py-2 text-[12.5px] text-red-100">
            <TriangleAlert size={15} className="mt-0.5 shrink-0" />
            <span>
              {full
                ? plan.head
                  ? t('discard.warnAll', { env: env.name, head: shortHash(plan.head) })
                  : t('discard.warnEmpty', { env: env.name })
                : t('discard.warnItem')}
              {full && plan.auditEntries > 0 && <> {t('discard.warnLog', { count: plan.auditEntries })}</>}
            </span>
          </div>
          <div className="grid h-[min(440px,calc(100vh-24rem))] min-h-[300px] grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-3">
            <div className="flex min-h-0 flex-col gap-2">
              <div className="panel-title">{tn('plural.changes', plan.changes.length)}</div>
              <div className="min-h-0 flex-1 overflow-auto rounded-md border border-kraken-line px-2">
                <ChangeList changes={plan.changes} expandable={false} />
              </div>
              <div className="h-[45%] min-h-0 overflow-hidden rounded-md border border-kraken-line">
                <SqlSafetyPanel report={plan.safety} loading={false} />
              </div>
            </div>
            <div className="flex min-h-0 flex-col overflow-hidden rounded-md border border-kraken-line">
              <div className="flex items-center gap-2 border-b border-kraken-line px-3 py-1.5 text-xs text-gray-400">
                <span className="flex-1">{t('discard.sqlTitle')}</span>
                {edited && (
                  <>
                    <span className="text-amber-300">{t('common.sqlEdited')}</span>
                    <button className="underline hover:text-gray-200" onClick={() => setSql(plan.downSql)}>
                      {t('common.reset')}
                    </button>
                  </>
                )}
              </div>
              <div className="min-h-0 flex-1">
                <SqlEditor value={sql} onChange={setSql} />
              </div>
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}
