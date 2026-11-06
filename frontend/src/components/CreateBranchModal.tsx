import { GitBranchPlus, RotateCcw, Send } from 'lucide-react';
import type { GraphCommit } from '../api/types';
import { firstLine, shortHash } from '../lib/format';
import { useI18n } from '../i18n';
import { Modal } from './Modal';

interface Props {
  commit: GraphCommit;
  /** name of the active environment, null when there is none */
  activeEnvName: string | null;
  /** null when the active environment can be rolled back to the commit */
  rollbackBlockedReason: string | null;
  /** another environment exists that could take the history up to the commit */
  hasOtherEnvironments: boolean;
  onRollbackHere: () => void;
  onPushElsewhere: () => void;
  onClose: () => void;
}

/**
 * "Create branch here": there are no named branches - every database has one history and one HEAD, and a branch is a
 * line of the graph that starts where a database continues from an older commit. So the dialog offers the two ways a
 * line starts at this commit: roll the active database back here (its next commit starts the branch), or push the
 * history up to here into another (empty or behind) database that then goes its own way.
 */
export function CreateBranchModal({
  commit,
  activeEnvName,
  rollbackBlockedReason,
  hasOtherEnvironments,
  onRollbackHere,
  onPushElsewhere,
  onClose,
}: Props) {
  const { t } = useI18n();
  return (
    <Modal
      title={t('branch.title')}
      subtitle={t('branch.subtitle', { hash: shortHash(commit.hash), message: firstLine(commit.message) })}
      onClose={onClose}
      width="max-w-3xl"
      footer={
        <>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <p className="flex items-start gap-2 text-[12.5px] leading-relaxed text-gray-300">
          <GitBranchPlus size={16} className="mt-0.5 shrink-0 text-teal-400" />
          <span>{t('branch.intro')}</span>
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          <section className="flex flex-col rounded-md border border-kraken-line bg-kraken-bg p-3">
            <h3 className="font-medium text-gray-100">{t('branch.inActive', { env: activeEnvName ?? '—' })}</h3>
            <p className="mt-1 flex-1 text-[12.5px] leading-relaxed text-gray-400">
              {t('branch.inActiveText', { env: activeEnvName ?? '—', hash: shortHash(commit.hash) })}
            </p>
            <button
              className="btn btn-danger mt-3 self-start"
              onClick={onRollbackHere}
              disabled={rollbackBlockedReason !== null}
              title={rollbackBlockedReason ?? undefined}
            >
              <RotateCcw size={14} /> {t('branch.inActiveButton', { env: activeEnvName ?? '—' })}
            </button>
            {rollbackBlockedReason && <p className="mt-1.5 text-[11.5px] text-gray-500">{rollbackBlockedReason}</p>}
          </section>
          <section className="flex flex-col rounded-md border border-kraken-line bg-kraken-bg p-3">
            <h3 className="font-medium text-gray-100">{t('branch.inOther')}</h3>
            <p className="mt-1 flex-1 text-[12.5px] leading-relaxed text-gray-400">
              {t('branch.inOtherText', { hash: shortHash(commit.hash) })}
            </p>
            <button className="btn btn-primary mt-3 self-start" onClick={onPushElsewhere} disabled={!hasOtherEnvironments}>
              <Send size={14} /> {t('branch.inOtherButton')}
            </button>
            {!hasOtherEnvironments && <p className="mt-1.5 text-[11.5px] text-gray-500">{t('branch.noOther')}</p>}
          </section>
        </div>
      </div>
    </Modal>
  );
}
