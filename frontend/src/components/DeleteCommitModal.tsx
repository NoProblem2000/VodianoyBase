import { useEffect, useState } from 'react';
import { GitBranch, GitCommitHorizontal, Trash2, TriangleAlert } from 'lucide-react';
import { api } from '../api/client';
import type { DeletionMode, DeletionPlan, DeletionResult, EnvironmentHead } from '../api/types';
import { useI18n } from '../i18n';
import { firstLine, shortHash } from '../lib/format';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  hash: string;
  environments: EnvironmentHead[];
  onDone: (result: DeletionResult) => void;
  onClose: () => void;
}

/** Confirms deleting an abandoned commit (with its descendants) or its whole abandoned branch. */
export function DeleteCommitModal({ hash, environments, onDone, onClose }: Props) {
  const { t, tn } = useI18n();
  const toast = useToast();
  const [mode, setMode] = useState<DeletionMode>('COMMIT');
  const [plan, setPlan] = useState<DeletionPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setPlan(null);
    setError(null);
    api
      .deletionPlan(hash, mode)
      .then((p) => !cancelled && setPlan(p))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [hash, mode]);

  async function remove() {
    setBusy(true);
    try {
      const result = await api.deleteCommit(hash, mode);
      toast.success(t('deletion.done', { commits: tn('plural.commits', result.deletedCommits.length) }));
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const envNames = (ids: string[]) =>
    ids.map((id) => environments.find((e) => e.id === id)?.name ?? id).join(', ');

  const modes: { id: DeletionMode; icon: typeof GitBranch; label: string; hint: string }[] = [
    { id: 'COMMIT', icon: GitCommitHorizontal, label: t('deletion.modeCommit'), hint: t('deletion.modeCommitHint') },
    { id: 'BRANCH', icon: GitBranch, label: t('deletion.modeBranch'), hint: t('deletion.modeBranchHint') },
  ];

  return (
    <Modal
      title={t('deletion.title', { hash: shortHash(hash) })}
      subtitle={t('deletion.subtitle')}
      onClose={onClose}
      width="max-w-2xl"
      footer={
        <>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            className="btn btn-danger"
            onClick={() => void remove()}
            disabled={!plan || !plan.deletable || busy}
          >
            <Trash2 size={14} />
            {busy ? t('deletion.deleting') : tn('plural.deleteCommits', plan?.commits.length ?? 0)}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-2" role="radiogroup">
          {modes.map(({ id, icon: Icon, label, hint }) => (
            <button
              key={id}
              role="radio"
              aria-checked={mode === id}
              onClick={() => setMode(id)}
              className={`rounded-md border px-3 py-2.5 text-left ${
                mode === id ? 'border-red-700 bg-red-950/30' : 'border-kraken-line hover:bg-kraken-hover'
              }`}
            >
              <span className="flex items-center gap-2 font-medium text-gray-100">
                <Icon size={15} className={mode === id ? 'text-red-300' : 'text-gray-500'} />
                {label}
              </span>
              <span className="mt-1 block text-xs text-gray-400">{hint}</span>
            </button>
          ))}
        </div>

        {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        {!plan && !error && <p className="text-gray-500">{t('deletion.loading')}</p>}
        {plan && (
          <>
            {plan.offlineEnvironments.length > 0 && (
              <p className="rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-amber-200">
                {t('deletion.offline', { envs: plan.offlineEnvironments.join(', ') })}
              </p>
            )}
            {plan.blockedBy.length > 0 && (
              <p className="rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-amber-200">
                {t('deletion.blocked', { envs: plan.blockedBy.join(', ') })}
              </p>
            )}
            <div>
              <div className="panel-title mb-1.5">{t('deletion.willDelete')}</div>
              <ol className="max-h-56 overflow-auto rounded-md border border-kraken-line px-3 py-1.5">
                {plan.commits.map((c) => (
                  <li key={c.hash} className="flex gap-3 py-0.5">
                    <span className="font-mono text-gray-400">{shortHash(c.hash)}</span>
                    <span className={`truncate ${c.hash === hash ? 'font-medium text-gray-100' : ''}`}>{firstLine(c.message)}</span>
                    <span className="ml-auto shrink-0 text-gray-500">{c.author}</span>
                  </li>
                ))}
              </ol>
              <p className="mt-1.5 text-xs text-gray-400">{t('deletion.fromEnvironments', { envs: envNames(plan.environments) })}</p>
            </div>
            {plan.deletable && (
              <div className="flex items-start gap-2 rounded-md border border-red-900 bg-red-950/30 px-3 py-2 text-[12.5px] text-red-200">
                <TriangleAlert size={15} className="mt-0.5 shrink-0" />
                {t('deletion.irreversible')}
              </div>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
