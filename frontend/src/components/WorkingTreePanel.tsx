import { useEffect, useState } from 'react';
import { api } from '../api';
import type { CommitResult, EnvironmentStatus, WorkingTree } from '../types';
import { shortHash } from '../layout';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';

interface Props {
  env: EnvironmentStatus;
  workingTree: WorkingTree;
  author: string;
  onCommitted: (result: CommitResult) => void;
  onError: (message: string) => void;
}

type Tab = 'up' | 'down' | 'log';

export function WorkingTreePanel({ env, workingTree, author, onCommitted, onError }: Props) {
  const [tab, setTab] = useState<Tab>('up');
  const [up, setUp] = useState(workingTree.upSql);
  const [down, setDown] = useState(workingTree.downSql);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setUp(workingTree.upSql);
    setDown(workingTree.downSql);
  }, [workingTree.upSql, workingTree.downSql]);

  const edited = up !== workingTree.upSql || down !== workingTree.downSql;

  async function commit() {
    if (!message.trim()) {
      onError('Write a commit message first.');
      return;
    }
    setBusy(true);
    try {
      const result = await api.commitWorkingTree(env.id, {
        message,
        author,
        upSql: up,
        downSql: down,
        expectedHead: workingTree.head,
      });
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
        <h2>Working tree</h2>
        <p className="muted">
          Changes in <strong>{env.name}</strong> since {workingTree.head ? <code>{shortHash(workingTree.head)}</code> : 'the beginning of history'}.
          The SQL was generated from pg_catalog; edit it before committing if needed (for example add a USING clause).
        </p>
      </header>

      {workingTree.warnings.length > 0 && (
        <ul className="warnings">
          {workingTree.warnings.map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      )}

      <details className="changes" open={workingTree.summary.length <= 12}>
        <summary>{workingTree.summary.length} detected change{workingTree.summary.length === 1 ? '' : 's'}</summary>
        <ul>
          {workingTree.summary.map((s, i) => (
            <li key={i} className={`chg chg-${s[0] === '+' ? 'add' : s[0] === '-' ? 'del' : 'mod'}`}>
              {s}
            </li>
          ))}
        </ul>
      </details>

      <Tabs<Tab>
        tabs={[
          { id: 'up', label: 'UP SQL' },
          { id: 'down', label: 'DOWN SQL' },
          { id: 'log', label: 'Captured DDL', badge: workingTree.auditLog.length },
        ]}
        active={tab}
        onChange={setTab}
      />
      <div className="editor-slot">
        {tab === 'up' && <SqlEditor value={up} onChange={setUp} />}
        {tab === 'down' && <SqlEditor value={down} onChange={setDown} />}
        {tab === 'log' && (
          <div className="audit-log">
            {workingTree.auditLog.length === 0 && (
              <p className="muted">No DDL was captured by the event triggers since the last commit.</p>
            )}
            {workingTree.auditLog.map((a) => (
              <div key={a.id} className="audit-row">
                <div className="audit-head">
                  <span className={a.eventType === 'sql_drop' ? 'tag tag-del' : 'tag'}>{a.commandTag}</span>
                  <code>{a.objectIdentity}</code>
                  <span className="muted small">
                    {a.dbUser}, {new Date(a.executedAt).toLocaleTimeString()}
                  </span>
                </div>
                <pre className="audit-query">{a.rawQuery}</pre>
              </div>
            ))}
          </div>
        )}
      </div>

      <footer className="commit-box">
        <textarea
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Commit message"
          rows={2}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
              void commit();
            }
          }}
        />
        <div className="row">
          <span className="muted small">
            {edited ? 'SQL edited manually. ' : ''}Committing records the current schema as the new HEAD.
          </span>
          {edited && (
            <button
              className="btn ghost"
              onClick={() => {
                setUp(workingTree.upSql);
                setDown(workingTree.downSql);
              }}
            >
              Reset SQL
            </button>
          )}
          <button className="btn primary" disabled={busy} onClick={() => void commit()}>
            {busy ? 'Committing…' : 'Commit'}
          </button>
        </div>
      </footer>
    </div>
  );
}
