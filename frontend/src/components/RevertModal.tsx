import { useEffect, useState } from 'react';
import { Info, TriangleAlert, Undo2 } from 'lucide-react';
import { api } from '../api/client';
import type { CommitResult, EnvironmentView, ExecutionOptions, RevertPlan } from '../api/types';
import { firstLine, shortHash } from '../lib/format';
import { useI18n } from '../i18n';
import { ChangeList } from './ChangeList';
import { CommitMessageFields } from './CommitMessageFields';
import { DEFAULT_EXECUTION, SqlSafetyPanel, TimeoutFields } from './ExecutionPanels';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  targetHash: string;
  author: string;
  onAuthorChange: (author: string) => void;
  onDone: (result: CommitResult) => void;
  onClose: () => void;
}

type Tab = 'sql' | 'changes';

/** The first few names of a list, "a, b, c +2". */
function names(list: string[], max = 4): string {
  return list.length <= max ? list.join(', ') : `${list.slice(0, max).join(', ')} +${list.length - max}`;
}

/**
 * "Revert Commit" (git revert): the DOWN SQL of one commit of the history runs on top of the current HEAD (editable
 * first, the schema may have changed since) and is recorded as a new commit with a summary and a description.
 */
export function RevertModal({ env, targetHash, author, onAuthorChange, onDone, onClose }: Props) {
  const toast = useToast();
  const { t, tn } = useI18n();
  const [plan, setPlan] = useState<RevertPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sql, setSql] = useState('');
  const [message, setMessage] = useState('');
  const [description, setDescription] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [options, setOptions] = useState<ExecutionOptions>(DEFAULT_EXECUTION);
  const [tab, setTab] = useState<Tab>('sql');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .revertPlan(env.id, targetHash)
      .then((p) => {
        setPlan(p);
        setSql(p.revertSql);
        setMessage(p.defaultMessage);
        setDescription(t('revert.defaultDescription', { hash: shortHash(p.target.hash), message: firstLine(p.target.message) }));
      })
      .catch((e: Error) => setError(e.message));
    // loaded once for the commit the dialog was opened with
  }, [env.id, targetHash]); // eslint-disable-line react-hooks/exhaustive-deps

  const edited = !!plan && sql !== plan.revertSql;

  async function revert() {
    if (!plan) return;
    if (!message.trim()) {
      toast.error(t('common.writeMessage'));
      return;
    }
    setBusy(true);
    try {
      const result = await api.revert({
        envId: env.id,
        targetCommitHash: plan.target.hash,
        author,
        message: message.trim(),
        description: description.trim(),
        customRevertSql: edited ? sql : null,
        allowDirty,
        lockTimeout: options.lockTimeout,
        statementTimeout: options.statementTimeout,
        expectedHead: plan.head,
      });
      toast.success(t('revert.done', { hash: shortHash(plan.target.hash), newHash: shortHash(result.commit.hash), env: env.name }));
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={t('revert.title')}
      subtitle={
        plan
          ? t('revert.subtitle', {
              env: env.name,
              hash: shortHash(plan.target.hash),
              message: firstLine(plan.target.message),
              head: shortHash(plan.head),
            })
          : env.name
      }
      onClose={onClose}
      width="max-w-[min(1300px,96vw)]"
      footer={
        <>
          {plan?.dirty && (
            <label className="flex items-center gap-2 text-xs text-gray-400">
              <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
              {t('revert.allowDirty')}
            </label>
          )}
          <TimeoutFields value={options} onChange={setOptions} />
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="btn btn-primary" onClick={() => void revert()} disabled={!plan || busy || !sql.trim()}>
            <Undo2 size={14} /> {busy ? t('revert.running') : t('revert.run')}
          </button>
        </>
      }
    >
      {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
      {!plan && !error && <p className="text-gray-500">{t('common.loading')}</p>}
      {plan && (
        <div className="flex flex-col gap-3">
          {/* ------------------------------------------------ what to watch out for */}
          <div className="flex flex-col gap-1.5">
            {plan.restoredObjects.length > 0 && (
              <p className="flex items-start gap-2 rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-[12.5px] text-red-100" role="alert">
                <TriangleAlert size={15} className="mt-0.5 shrink-0" />
                <span>{t('revert.restoredWarning', { objects: names(plan.restoredObjects) })}</span>
              </p>
            )}
            {plan.droppedObjects.length > 0 && (
              <p className="flex items-start gap-2 rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-[12.5px] text-red-100">
                <TriangleAlert size={15} className="mt-0.5 shrink-0" />
                <span>{t('revert.droppedWarning', { objects: names(plan.droppedObjects) })}</span>
              </p>
            )}
            {plan.conflicts.length > 0 && (
              <p className="flex items-start gap-2 rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-[12.5px] text-amber-100">
                <TriangleAlert size={15} className="mt-0.5 shrink-0" />
                <span>
                  {t('revert.conflictsWarning', {
                    commits: tn('plural.commits', plan.laterCommits),
                    objects: names(plan.conflicts),
                  })}
                </span>
              </p>
            )}
            {plan.targetIsHead && (
              <p className="flex items-start gap-2 rounded-md border border-kraken-line bg-kraken-bg px-3 py-2 text-[12.5px] text-gray-300">
                <Info size={15} className="mt-0.5 shrink-0 text-sky-300" />
                <span>{t('revert.headNote')}</span>
              </p>
            )}
            {plan.firstCommit && (
              <p className="flex items-start gap-2 rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-[12.5px] text-amber-100">
                <TriangleAlert size={15} className="mt-0.5 shrink-0" />
                <span>{t('revert.firstCommit')}</span>
              </p>
            )}
            {plan.dirty && <p className="text-xs text-amber-300">{t('revert.dirty', { env: env.name })}</p>}
          </div>

          {/* ------------------------------------------------ SQL / changes of the reverted commit */}
          <div className="grid h-[min(400px,calc(100vh-27rem))] min-h-[260px] grid-cols-[minmax(0,3fr)_minmax(0,2fr)] gap-3">
            <div className="flex min-h-0 flex-col overflow-hidden rounded-md border border-kraken-line">
              <Tabs<Tab>
                tabs={[
                  { id: 'sql', label: t('revert.sqlTab') },
                  { id: 'changes', label: t('revert.changesTab'), count: plan.targetChanges.length },
                ]}
                active={tab}
                onChange={setTab}
              />
              {tab === 'sql' ? (
                <>
                  <div className="flex items-center gap-2 border-b border-kraken-line px-3 py-1.5 text-xs text-gray-400">
                    <span className="flex-1">{t('revert.sqlTitle', { hash: shortHash(plan.target.hash) })}</span>
                    {edited && (
                      <>
                        <span className="text-amber-300">{t('common.sqlEdited')}</span>
                        <button className="underline hover:text-gray-200" onClick={() => setSql(plan.revertSql)}>
                          {t('common.reset')}
                        </button>
                      </>
                    )}
                  </div>
                  <div className="min-h-0 flex-1">
                    <SqlEditor value={sql} onChange={setSql} />
                  </div>
                </>
              ) : (
                <div className="min-h-0 flex-1 overflow-auto px-2">
                  <ChangeList changes={plan.targetChanges} emptyText={t('commit.noChanges')} />
                </div>
              )}
            </div>
            <div className="min-h-0 overflow-hidden rounded-md border border-kraken-line">
              <SqlSafetyPanel report={plan.safety} loading={false} />
            </div>
          </div>
          {edited && <p className="text-xs text-amber-300">{t('revert.editedNote')}</p>}

          {/* ------------------------------------------------ the new commit */}
          <div className="grid grid-cols-[200px_minmax(0,1fr)] gap-3">
            <label>
              <span className="label">{t('common.author')}</span>
              <input
                className="input"
                value={author}
                onChange={(e) => onAuthorChange(e.target.value)}
                placeholder={t('common.authorPlaceholder')}
              />
            </label>
            <CommitMessageFields
              message={message}
              onMessageChange={setMessage}
              description={description}
              onDescriptionChange={setDescription}
              onSubmit={() => void revert()}
            />
          </div>
        </div>
      )}
    </Modal>
  );
}
