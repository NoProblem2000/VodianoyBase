import { useEffect, useState } from 'react';
import { GitCommitHorizontal, TriangleAlert } from 'lucide-react';
import { api } from '../api/client';
import type { CommitResult, EnvironmentView, WorkingTreeStatus } from '../api/types';
import { shortHash } from '../lib/format';
import { ChangeList } from './ChangeList';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useI18n } from '../i18n';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  workingTree: WorkingTreeStatus;
  author: string;
  onAuthorChange: (author: string) => void;
  onCommitted: (result: CommitResult) => void;
}

type Tab = 'up' | 'down';

export function WorkingTreeInspector({ env, workingTree, author, onAuthorChange, onCommitted }: Props) {
  const toast = useToast();
  const { t } = useI18n();
  const diff = workingTree.diff;
  const [tab, setTab] = useState<Tab>('up');
  const [up, setUp] = useState(diff.upSql);
  const [down, setDown] = useState(diff.downSql);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  // new DDL arrived: show the regenerated scripts
  useEffect(() => {
    setUp(diff.upSql);
    setDown(diff.downSql);
  }, [diff.upSql, diff.downSql]);

  const edited = up !== diff.upSql || down !== diff.downSql;

  async function commit() {
    if (!message.trim()) {
      toast.error(t('common.writeMessage'));
      return;
    }
    setBusy(true);
    try {
      const result = await api.commit(env.id, {
        message,
        author,
        upSql: up,
        downSql: down,
        expectedHead: workingTree.head ?? '',
      });
      toast.success(t('workingTree.committed', { hash: shortHash(result.commit.hash), message: result.commit.message }));
      setMessage('');
      onCommitted(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="border-b border-kraken-line px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="relative flex h-2.5 w-2.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-400 opacity-60" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-amber-400" />
          </span>
          <h2 className="text-[15px] font-semibold text-gray-100">{t('workingTree.title')}</h2>
          <span className="ml-auto text-xs text-gray-500">
            {t('workingTree.since', { env: env.name, head: workingTree.head ? shortHash(workingTree.head) : t('common.emptyHistory') })}
          </span>
        </div>
        <p className="mt-1 text-xs text-gray-400">
          {t('workingTree.hint')}
        </p>
      </header>

      {diff.warnings.length > 0 && (
        <ul className="mx-4 mt-3 space-y-1 rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-xs text-amber-200">
          {diff.warnings.map((w, i) => (
            <li key={i} className="flex gap-1.5">
              <TriangleAlert size={13} className="mt-px shrink-0" />
              {w}
            </li>
          ))}
        </ul>
      )}

      <div className="px-4 pt-3">
        <div className="panel-title mb-1">
          {t('workingTree.changedObjects')} <span className="text-gray-500">({diff.changes.length})</span>
        </div>
      </div>
      <div className="max-h-[32%] min-h-[80px] overflow-auto px-3">
        <ChangeList changes={diff.changes} />
      </div>

      <div className="mt-2 flex min-h-[180px] flex-1 flex-col border-t border-kraken-line">
        <Tabs<Tab>
          tabs={[
            { id: 'up', label: t('common.upSql') },
            { id: 'down', label: t('common.downSql') },
          ]}
          active={tab}
          onChange={setTab}
        />
        <div className="min-h-0 flex-1">
          {tab === 'up' ? <SqlEditor value={up} onChange={setUp} /> : <SqlEditor value={down} onChange={setDown} />}
        </div>
      </div>

      <footer className="flex flex-col gap-2 border-t border-kraken-line px-4 py-3">
        <div className="grid grid-cols-[160px_1fr] gap-2">
          <label>
            <span className="label">{t('common.author')}</span>
            <input className="input" value={author} onChange={(e) => onAuthorChange(e.target.value)} placeholder={t('common.authorPlaceholder')} />
          </label>
          <label>
            <span className="label">{t('common.commitMessage')}</span>
            <textarea
              className="input h-[52px] resize-none py-1.5"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t('workingTree.messagePlaceholder')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) void commit();
              }}
            />
          </label>
        </div>
        <div className="flex items-center gap-2">
          {edited && (
            <>
              <span className="text-xs text-amber-300">{t('common.sqlEdited')}</span>
              <button
                className="text-xs text-gray-400 underline hover:text-gray-200"
                onClick={() => {
                  setUp(diff.upSql);
                  setDown(diff.downSql);
                }}
              >
                {t('common.reset')}
              </button>
            </>
          )}
          <span className="flex-1" />
          <button className="btn btn-primary" onClick={() => void commit()} disabled={busy}>
            <GitCommitHorizontal size={15} /> {busy ? t('workingTree.committing') : t('workingTree.commit')}
          </button>
        </div>
      </footer>
    </div>
  );
}
