import { useState } from 'react';
import { api } from '../api';
import type { CommitResult, EnvironmentStatus } from '../types';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';

interface Props {
  env: EnvironmentStatus;
  author: string;
  onCommitted: (result: CommitResult) => void;
  onError: (message: string) => void;
}

type Tab = 'up' | 'down';

const UP_TEMPLATE = `-- UP: executed in a single transaction together with the commit.
-- Example:
-- ALTER TABLE public.orders ADD COLUMN comment text;
`;

export function NewMigrationPanel({ env, author, onCommitted, onError }: Props) {
  const [tab, setTab] = useState<Tab>('up');
  const [up, setUp] = useState(UP_TEMPLATE);
  const [down, setDown] = useState('');
  const [message, setMessage] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [busy, setBusy] = useState(false);

  async function execute() {
    if (!message.trim()) {
      onError('Write a commit message first.');
      return;
    }
    setBusy(true);
    try {
      const result = await api.executeAndCommit(env.id, { message, author, upSql: up, downSql: down, allowDirty });
      setUp(UP_TEMPLATE);
      setDown('');
      setMessage('');
      onCommitted(result);
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel-body">
      <header className="panel-head">
        <h2>New SQL migration</h2>
        <p className="muted">
          Runs on <strong>{env.name}</strong> in one transaction. The new schema is read inside that transaction and
          recorded as a commit; if any statement fails, nothing is applied. Leave DOWN SQL empty to generate it.
        </p>
      </header>
      <Tabs<Tab>
        tabs={[
          { id: 'up', label: 'UP SQL' },
          { id: 'down', label: down.trim() ? 'DOWN SQL' : 'DOWN SQL (auto)' },
        ]}
        active={tab}
        onChange={setTab}
      />
      <div className="editor-slot">
        {tab === 'up' && <SqlEditor value={up} onChange={setUp} />}
        {tab === 'down' && <SqlEditor value={down} onChange={setDown} />}
      </div>
      <footer className="commit-box">
        <textarea
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Commit message"
          rows={2}
        />
        <div className="row">
          <label className="check">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            Include uncommitted changes already in the database
          </label>
          <button className="btn primary" disabled={busy} onClick={() => void execute()}>
            {busy ? 'Executing…' : 'Execute & commit'}
          </button>
        </div>
      </footer>
    </div>
  );
}
