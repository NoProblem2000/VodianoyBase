import { useEffect, useState } from 'react';
import { Copy, Download, RotateCcw } from 'lucide-react';
import { api } from '../api/client';
import type { AuditLogEntry, CommitDetails, EnvironmentHead, GraphCommit, SchemaDiffResult } from '../api/types';
import { formatDate, shortHash, timeOfDay } from '../lib/format';
import { EnvBadge } from './Badges';
import { ChangeList } from './ChangeList';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';

interface Props {
  commit: GraphCommit;
  /** environment the commit details are read from */
  sourceEnvId: string;
  environments: EnvironmentHead[];
  activeEnvId: string | null;
  /** null when a rollback of the active environment to this commit is not possible */
  rollbackBlockedReason: string | null;
  onExportSingle: () => void;
  onRollback: () => void;
}

type Tab = 'changes' | 'up' | 'down' | 'ddl';

export function CommitInspector(p: Props) {
  const [details, setDetails] = useState<CommitDetails | null>(null);
  const [changes, setChanges] = useState<SchemaDiffResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('changes');

  useEffect(() => {
    let cancelled = false;
    setDetails(null);
    setChanges(null);
    setError(null);
    Promise.all([api.commitDetails(p.sourceEnvId, p.commit.hash), api.commitChanges(p.sourceEnvId, p.commit.hash)])
      .then(([d, c]) => {
        if (!cancelled) {
          setDetails(d);
          setChanges(c);
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [p.sourceEnvId, p.commit.hash]);

  const ddl: AuditLogEntry[] = Array.isArray(details?.ddlLogHistory) ? (details!.ddlLogHistory as AuditLogEntry[]) : [];
  const message = p.commit.message.split('\n');

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="border-b border-kraken-line px-4 py-3">
        <h2 className="break-words text-[15px] font-semibold text-gray-100">{message[0]}</h2>
        {message.length > 1 && <p className="mt-1 whitespace-pre-wrap text-xs text-gray-400">{message.slice(1).join('\n').trim()}</p>}
        <dl className="mt-2.5 grid grid-cols-[72px_1fr] gap-x-3 gap-y-1 text-[12.5px]">
          <dt className="text-gray-500">Commit</dt>
          <dd className="flex items-center gap-1.5">
            <span className="font-mono text-gray-200" title={p.commit.hash}>
              {shortHash(p.commit.hash, 12)}
            </span>
            <button
              className="text-gray-500 hover:text-gray-200"
              title="Copy full hash"
              onClick={() => void navigator.clipboard?.writeText(p.commit.hash)}
            >
              <Copy size={12} />
            </button>
          </dd>
          <dt className="text-gray-500">Parent</dt>
          <dd className="font-mono text-gray-400">{shortHash(p.commit.parentHash, 12)}</dd>
          <dt className="text-gray-500">Author</dt>
          <dd>{p.commit.author}</dd>
          <dt className="text-gray-500">Date</dt>
          <dd>{formatDate(p.commit.createdAt)}</dd>
          <dt className="text-gray-500">In</dt>
          <dd className="flex flex-wrap gap-1">
            {p.environments
              .filter((e) => p.commit.environments.includes(e.id))
              .map((e) => (
                <EnvBadge key={e.id} name={e.name} color={e.colorBadge} active={e.head === p.commit.hash} />
              ))}
          </dd>
        </dl>
      </header>

      <Tabs<Tab>
        tabs={[
          { id: 'changes', label: 'Changes', count: changes?.changes.length },
          { id: 'up', label: 'UP SQL' },
          { id: 'down', label: 'DOWN SQL' },
          { id: 'ddl', label: 'DDL Trigger Log', count: ddl.length },
        ]}
        active={tab}
        onChange={setTab}
      />
      <div className="relative min-h-0 flex-1">
        {error && <p className="m-4 rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        {!error && !details && <p className="p-4 text-gray-500">Loading commit…</p>}
        {details && tab === 'changes' && (
          <div className="absolute inset-0 overflow-auto px-3 py-1">
            <ChangeList changes={changes?.changes ?? []} emptyText="This commit did not change the schema." />
          </div>
        )}
        {details && tab === 'up' && <SqlEditor value={details.upSql} readOnly />}
        {details && tab === 'down' && <SqlEditor value={details.downSql} readOnly />}
        {details && tab === 'ddl' && (
          <ol className="absolute inset-0 overflow-auto px-4 py-2">
            {ddl.length === 0 && (
              <li className="text-gray-500">No DDL was captured by the event triggers for this commit.</li>
            )}
            {ddl.map((e) => (
              <li key={e.id} className="border-b border-kraken-line/50 py-2">
                <div className="flex items-center gap-2">
                  <span
                    className={`rounded px-1.5 py-px text-[10.5px] font-semibold ${
                      e.eventType === 'sql_drop' ? 'bg-red-950 text-red-300' : 'bg-sky-950 text-sky-300'
                    }`}
                  >
                    {e.commandTag}
                  </span>
                  <span className="font-mono text-[12px]">{e.objectIdentity}</span>
                  <span className="ml-auto text-[11px] text-gray-500">
                    {e.dbUser}, {timeOfDay(e.executedAt)}
                  </span>
                </div>
                <pre className="mt-1 max-h-24 overflow-auto whitespace-pre-wrap font-mono text-[11.5px] text-gray-400">
                  {e.rawQuery}
                </pre>
              </li>
            ))}
          </ol>
        )}
      </div>

      <footer className="flex items-center gap-2 border-t border-kraken-line px-4 py-3">
        <button className="btn" onClick={p.onExportSingle}>
          <Download size={14} /> Export Single Commit .SQL
        </button>
        <span className="flex-1" />
        <button
          className="btn btn-danger"
          onClick={p.onRollback}
          disabled={p.rollbackBlockedReason !== null}
          title={p.rollbackBlockedReason ?? 'Revert the active environment to this commit'}
        >
          <RotateCcw size={14} /> Rollback DB to here
        </button>
      </footer>
    </div>
  );
}
