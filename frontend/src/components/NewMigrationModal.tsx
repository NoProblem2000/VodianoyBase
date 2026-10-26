import { useState } from 'react';
import { Play } from 'lucide-react';
import { api } from '../api/client';
import type { CommitResult, EnvironmentView } from '../api/types';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  author: string;
  onAuthorChange: (author: string) => void;
  onDone: (result: CommitResult) => void;
  onClose: () => void;
}

const TEMPLATE = `-- UP SQL: executed in one transaction together with the commit.
-- If any statement fails, nothing is applied.

`;

type Tab = 'up' | 'down';

export function NewMigrationModal({ env, author, onAuthorChange, onDone, onClose }: Props) {
  const toast = useToast();
  const [tab, setTab] = useState<Tab>('up');
  const [up, setUp] = useState(TEMPLATE);
  const [down, setDown] = useState('');
  const [message, setMessage] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [busy, setBusy] = useState(false);

  async function execute() {
    if (!message.trim()) {
      toast.error('Write a commit message.');
      return;
    }
    setBusy(true);
    try {
      const result = await api.execute(env.id, { message, author, upSql: up, downSql: down, allowDirty });
      toast.success(`Executed and committed ${result.commit.hash.slice(0, 7)} on ${env.name}`);
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="New SQL Migration (Mode B)"
      subtitle={`Runs on ${env.name} in a single transaction; the resulting schema is recorded as a new commit.`}
      onClose={onClose}
      width="max-w-5xl"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            Also include uncommitted changes that are already in the database
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={() => void execute()} disabled={busy}>
            <Play size={14} /> {busy ? 'Executing…' : 'Execute & Commit'}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="overflow-hidden rounded-md border border-kraken-line">
          <Tabs<Tab>
            tabs={[
              { id: 'up', label: 'UP SQL' },
              { id: 'down', label: down.trim() ? 'DOWN SQL' : 'DOWN SQL (generated if empty)' },
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
            <span className="label">Author</span>
            <input className="input" value={author} onChange={(e) => onAuthorChange(e.target.value)} placeholder="your name" />
          </label>
          <label>
            <span className="label">Commit message</span>
            <input
              className="input"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="What does this migration do?"
            />
          </label>
        </div>
      </div>
    </Modal>
  );
}
