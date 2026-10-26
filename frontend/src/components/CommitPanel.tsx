import { useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import type { CommitDetails, EnvironmentStatus, GraphCommit, PushResult } from '../types';
import { shortHash } from '../layout';
import { EnvBadge } from './EnvBadge';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';

interface Props {
  commit: GraphCommit;
  range: { from: string; to: string; hashes: string[] } | null;
  environments: EnvironmentStatus[];
  activeEnvId: string | null;
  onPushed: (result: PushResult) => void;
  onError: (message: string) => void;
}

type Tab = 'up' | 'down' | 'snapshot' | 'log';

export function CommitPanel({ commit, range, environments, activeEnvId, onPushed, onError }: Props) {
  const sourceEnvId =
    activeEnvId && commit.environments.includes(activeEnvId) ? activeEnvId : commit.environments[0] ?? null;
  const [details, setDetails] = useState<CommitDetails | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('up');
  const [target, setTarget] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [busy, setBusy] = useState(false);

  const tip = range ? range.to : commit.hash;
  const tipCommitEnvs = commit.environments;

  useEffect(() => {
    let cancelled = false;
    setDetails(null);
    setLoadError(null);
    if (!sourceEnvId) {
      setLoadError('No reachable environment contains this commit.');
      return;
    }
    api
      .commit(sourceEnvId, commit.hash)
      .then((d) => !cancelled && setDetails(d))
      .catch((e: Error) => !cancelled && setLoadError(e.message));
    return () => {
      cancelled = true;
    };
  }, [commit.hash, sourceEnvId]);

  const pushTargets = useMemo(
    () => environments.filter((e) => e.reachable && e.id !== sourceEnvId && e.head !== tip),
    [environments, sourceEnvId, tip],
  );

  useEffect(() => {
    if (!pushTargets.some((t) => t.id === target)) {
      setTarget(pushTargets[0]?.id ?? '');
    }
  }, [pushTargets, target]);

  async function push() {
    if (!sourceEnvId || !target) return;
    setBusy(true);
    try {
      const result = await api.push(target, { sourceEnvironmentId: sourceEnvId, commitHash: tip, allowDirty });
      onPushed(result);
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const snapshotJson = useMemo(
    () => (details ? JSON.stringify(details.snapshot, null, 2) : ''),
    [details],
  );
  const logJson = useMemo(
    () => (details?.ddlLogHistory ? JSON.stringify(details.ddlLogHistory, null, 2) : '// No DDL was captured for this commit'),
    [details],
  );

  return (
    <div className="panel-body">
      <header className="panel-head">
        <h2 className="commit-title">{commit.message.split('\n')[0]}</h2>
        {commit.message.includes('\n') && <p className="commit-body">{commit.message.split('\n').slice(1).join('\n').trim()}</p>}
        <dl className="facts">
          <dt>Commit</dt>
          <dd>
            <code title={commit.hash}>{commit.hash.slice(0, 16)}</code>
          </dd>
          <dt>Parent</dt>
          <dd>
            <code>{shortHash(commit.parentHash)}</code>
          </dd>
          <dt>Author</dt>
          <dd>{commit.author}</dd>
          <dt>Date</dt>
          <dd>{new Date(commit.createdAt).toLocaleString()}</dd>
          <dt>Present in</dt>
          <dd className="badges">
            {commit.environments.map((id) => {
              const e = environments.find((x) => x.id === id);
              return e ? <EnvBadge key={id} name={e.name} color={e.color} active={e.head === commit.hash} /> : null;
            })}
          </dd>
        </dl>
      </header>

      <Tabs<Tab>
        tabs={[
          { id: 'up', label: 'UP SQL' },
          { id: 'down', label: 'DOWN SQL' },
          { id: 'snapshot', label: 'Schema snapshot' },
          { id: 'log', label: 'Captured DDL' },
        ]}
        active={tab}
        onChange={setTab}
      />
      <div className="editor-slot">
        {loadError && <p className="error-text">{loadError}</p>}
        {!loadError && !details && <p className="muted pad">Loading commit…</p>}
        {details && tab === 'up' && <SqlEditor value={details.upSql} readOnly />}
        {details && tab === 'down' && <SqlEditor value={details.downSql} readOnly />}
        {details && tab === 'snapshot' && <SqlEditor value={snapshotJson} readOnly language="json" />}
        {details && tab === 'log' && <SqlEditor value={logJson} readOnly language="json" />}
      </div>

      <footer className="actions">
        <div className="selection-note">
          {range ? (
            <>
              Range of <strong>{range.hashes.length} commits</strong>: <code>{shortHash(range.from)}</code> to{' '}
              <code>{shortHash(range.to)}</code>
            </>
          ) : (
            <span className="muted">Shift-click another commit to select a range.</span>
          )}
        </div>
        <div className="action-row">
          <label className="field-inline">
            <span>Push to</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)} disabled={pushTargets.length === 0}>
              {pushTargets.length === 0 && <option value="">no other environment</option>}
              {pushTargets.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name} (at {shortHash(e.head)})
                </option>
              ))}
            </select>
          </label>
          <label className="check">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            Allow uncommitted changes on target
          </label>
          <button className="btn primary" disabled={busy || !target || !sourceEnvId} onClick={() => void push()}>
            {busy ? 'Pushing…' : 'Push commit(s) to environment'}
          </button>
        </div>
        <div className="action-row">
          {sourceEnvId && tipCommitEnvs.length > 0 ? (
            <a
              className="btn"
              href={api.bundleUrl(sourceEnvId, range ? range.from : commit.hash, tip)}
              download
            >
              Export offline SQL bundle{range ? ` (${range.hashes.length} commits)` : ''}
            </a>
          ) : null}
          <span className="muted small">
            A single .sql file for psql or DBeaver. It checks the target HEAD before applying anything.
          </span>
        </div>
      </footer>
    </div>
  );
}
