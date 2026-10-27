import { useState } from 'react';
import { Play } from 'lucide-react';
import { api } from '../api/client';
import type { CommitResult, EnvironmentView } from '../api/types';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useI18n } from '../i18n';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  author: string;
  onAuthorChange: (author: string) => void;
  onDone: (result: CommitResult) => void;
  onClose: () => void;
}

type Tab = 'up' | 'down';

export function NewMigrationModal({ env, author, onAuthorChange, onDone, onClose }: Props) {
  const toast = useToast();
  const { t } = useI18n();
  const [tab, setTab] = useState<Tab>('up');
  const [up, setUp] = useState(() => t('migration.template'));
  const [down, setDown] = useState('');
  const [message, setMessage] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [busy, setBusy] = useState(false);

  async function execute() {
    if (!message.trim()) {
      toast.error(t('common.writeMessage'));
      return;
    }
    setBusy(true);
    try {
      const result = await api.execute(env.id, { message, author, upSql: up, downSql: down, allowDirty });
      toast.success(t('migration.done', { hash: result.commit.hash.slice(0, 7), env: env.name }));
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={t('migration.title')}
      subtitle={t('migration.subtitle', { env: env.name })}
      onClose={onClose}
      width="max-w-5xl"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            {t('migration.allowDirty')}
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="btn btn-primary" onClick={() => void execute()} disabled={busy}>
            <Play size={14} /> {busy ? t('migration.executing') : t('migration.execute')}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="overflow-hidden rounded-md border border-kraken-line">
          <Tabs<Tab>
            tabs={[
              { id: 'up', label: t('common.upSql') },
              { id: 'down', label: down.trim() ? t('common.downSql') : t('migration.downGenerated') },
            ]}
            active={tab}
            onChange={setTab}
          />
          <div className="h-[340px]">
            {tab === 'up' ? (
              <SqlEditor value={up} onChange={setUp} />
            ) : (
              <SqlEditor value={down} onChange={setDown} />
            )}
          </div>
        </div>
        <div className="grid grid-cols-[200px_1fr] gap-3">
          <label>
            <span className="label">{t('common.author')}</span>
            <input className="input" value={author} onChange={(e) => onAuthorChange(e.target.value)} placeholder={t('common.authorPlaceholder')} />
          </label>
          <label>
            <span className="label">{t('common.commitMessage')}</span>
            <input
              className="input"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t('migration.messagePlaceholder')}
            />
          </label>
        </div>
      </div>
    </Modal>
  );
}
