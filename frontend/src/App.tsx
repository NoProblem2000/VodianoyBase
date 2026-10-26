import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { CommitResult, EnvironmentConfig, Graph, PushResult, WorkingTree } from './types';
import { ancestryRange, shortHash } from './layout';
import { load, save } from './storage';
import { CommitGraph, WIP_ID } from './components/CommitGraph';
import { CommitPanel } from './components/CommitPanel';
import { WorkingTreePanel } from './components/WorkingTreePanel';
import { NewMigrationPanel } from './components/NewMigrationPanel';
import { EnvironmentsDialog } from './components/EnvironmentsDialog';

type Panel = 'none' | 'working-tree' | 'commit' | 'new-migration';
type Toast = { kind: 'info' | 'error'; text: string; id: number };

export function App() {
  const [envs, setEnvs] = useState<EnvironmentConfig[]>([]);
  const [graph, setGraph] = useState<Graph | null>(null);
  const [activeEnvId, setActiveEnvId] = useState<string | null>(() => load('pgvc.activeEnv', '') || null);
  const [author, setAuthor] = useState(() => load('pgvc.author', ''));
  const [workingTree, setWorkingTree] = useState<WorkingTree | null>(null);
  const [workingTreeError, setWorkingTreeError] = useState<string | null>(null);
  const [panel, setPanel] = useState<Panel>('none');
  const [anchor, setAnchor] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);
  const [showEnvs, setShowEnvs] = useState(false);
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState<Toast | null>(null);

  const notify = useCallback((kind: Toast['kind'], text: string) => setToast({ kind, text, id: Date.now() }), []);
  const showError = useCallback((text: string) => notify('error', text), [notify]);
  const showInfo = useCallback((text: string) => notify('info', text), [notify]);

  useEffect(() => {
    if (toast?.kind !== 'info') return;
    const t = window.setTimeout(() => setToast((cur) => (cur?.id === toast.id ? null : cur)), 5000);
    return () => window.clearTimeout(t);
  }, [toast]);

  const activeStatus = graph?.environments.find((e) => e.id === activeEnvId) ?? null;

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [environments, g] = await Promise.all([api.environments(), api.graph()]);
      setEnvs(environments);
      setGraph(g);
      let active = activeEnvId;
      if (!active || !environments.some((e) => e.id === active)) {
        active = environments[0]?.id ?? null;
        setActiveEnvId(active);
      }
      const st = g.environments.find((e) => e.id === active);
      if (active && st?.reachable && st.installed) {
        try {
          setWorkingTree(await api.workingTree(active));
          setWorkingTreeError(null);
        } catch (e) {
          setWorkingTree(null);
          setWorkingTreeError((e as Error).message);
        }
      } else {
        setWorkingTree(null);
        setWorkingTreeError(null);
      }
    } catch (e) {
      showError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [activeEnvId, showError]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (activeEnvId) save('pgvc.activeEnv', activeEnvId);
  }, [activeEnvId]);

  useEffect(() => {
    save('pgvc.author', author);
  }, [author]);

  const commitMap = useMemo(() => new Map((graph?.commits ?? []).map((c) => [c.hash, c])), [graph]);

  const range = useMemo(() => {
    if (!anchor || !focus || anchor === focus || anchor === WIP_ID || focus === WIP_ID) return null;
    return ancestryRange(commitMap, anchor, focus);
  }, [anchor, focus, commitMap]);
  const rangeHashes = useMemo(() => new Set(range?.hashes ?? []), [range]);

  const displayedCommit = range ? commitMap.get(range.to) ?? null : focus ? commitMap.get(focus) ?? null : null;

  function select(id: string, extend: boolean) {
    if (id === WIP_ID) {
      setAnchor(null);
      setFocus(WIP_ID);
      setPanel('working-tree');
      return;
    }
    if (extend && anchor && anchor !== WIP_ID) {
      setFocus(id);
      const r = ancestryRange(commitMap, anchor, id);
      if (!r) showError('These commits are on different branches: pick a commit that is an ancestor of the other.');
    } else {
      setAnchor(id);
      setFocus(id);
    }
    setPanel('commit');
  }

  async function afterCommit(result: CommitResult) {
    showInfo(`Committed ${shortHash(result.commit.hash)}: ${result.commit.message}`);
    setAnchor(result.commit.hash);
    setFocus(result.commit.hash);
    setPanel('commit');
    await refresh();
  }

  async function afterPush(result: PushResult) {
    const target = graph?.environments.find((e) => e.id === result.targetEnvironmentId)?.name ?? result.targetEnvironmentId;
    const n = result.appliedCommits.length;
    showInfo(`Pushed ${n} commit${n === 1 ? '' : 's'} to ${target}. It is now at ${shortHash(result.newHead)}.`);
    await refresh();
  }

  async function initialize() {
    if (!activeEnvId) return;
    try {
      await api.install(activeEnvId);
      showInfo('Version control is set up. Changes made from now on are captured by DDL event triggers.');
      await refresh();
    } catch (e) {
      showError((e as Error).message);
    }
  }

  const changeCount = workingTree?.summary.length ?? 0;
  const showWip = !!workingTree?.hasChanges;
  const unreachable = graph?.environments.filter((e) => !e.reachable) ?? [];

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden="true">
            <path d="M11 7v18M11 16c0-4 10-3 10-9" stroke="#2dd4bf" strokeWidth="2.5" fill="none" strokeLinecap="round" />
            <circle cx="11" cy="25" r="3" fill="#2dd4bf" />
            <circle cx="21" cy="7" r="3" fill="#a78bfa" />
            <circle cx="11" cy="16" r="3" fill="#2dd4bf" />
          </svg>
          <span>PgGitVisualizer</span>
        </div>

        <label className="field-inline">
          <span>Environment</span>
          <select
            value={activeEnvId ?? ''}
            onChange={(e) => {
              setActiveEnvId(e.target.value || null);
              setPanel('none');
            }}
            disabled={envs.length === 0}
          >
            {envs.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name}
              </option>
            ))}
          </select>
        </label>

        <label className="field-inline">
          <span>Author</span>
          <input value={author} onChange={(e) => setAuthor(e.target.value)} placeholder="your name" size={14} />
        </label>

        <span className="spacer" />

        {unreachable.length > 0 && (
          <span className="offline" title={unreachable.map((e) => `${e.name}: ${e.error ?? 'unreachable'}`).join('\n')}>
            {unreachable.map((e) => e.name).join(', ')} offline
          </span>
        )}
        <button className="btn ghost" onClick={() => void refresh()} disabled={loading}>
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
        <button
          className="btn"
          onClick={() => {
            setFocus(null);
            setAnchor(null);
            setPanel('new-migration');
          }}
          disabled={!activeStatus?.installed}
        >
          New SQL migration
        </button>
        <button className="btn" onClick={() => setShowEnvs(true)}>
          Environments
        </button>
      </header>

      {activeStatus && activeStatus.reachable && !activeStatus.installed && (
        <div className="banner">
          <span>
            Version control isn't set up in <strong>{activeStatus.name}</strong> yet. Setting it up creates the <code>_pgvc</code>{' '}
            schema and DDL event triggers{activeStatus.superuser ? '.' : ', which needs a SUPERUSER connection.'}
          </span>
          <button className="btn primary" onClick={() => void initialize()}>
            Set up version control
          </button>
        </div>
      )}
      {activeStatus && !activeStatus.reachable && (
        <div className="banner bad">
          <span>
            Can't connect to <strong>{activeStatus.name}</strong>: {activeStatus.error}
          </span>
          <button className="btn" onClick={() => setShowEnvs(true)}>
            Edit connection
          </button>
        </div>
      )}
      {workingTreeError && (
        <div className="banner bad">
          <span>Couldn't read the working tree: {workingTreeError}</span>
        </div>
      )}

      <main className="workspace">
        <section className="graph-pane" aria-label="Commit graph">
          {envs.length === 0 && !loading ? (
            <div className="empty">
              <h1>Track your PostgreSQL schema like a git repository</h1>
              <p>Add a database connection to start. Every DDL change becomes a commit you can push to other environments.</p>
              <button className="btn primary" onClick={() => setShowEnvs(true)}>
                Add a database
              </button>
            </div>
          ) : graph && graph.commits.length === 0 && !showWip ? (
            <div className="empty">
              <h1>No commits yet</h1>
              <p>
                Change the schema in your IDE and the changes appear here as a working tree, or write a migration with
                New SQL migration. The first commit records the current schema as the baseline.
              </p>
            </div>
          ) : (
            <CommitGraph
              commits={graph?.commits ?? []}
              environments={graph?.environments ?? []}
              activeEnvId={activeEnvId}
              showWorkingTree={showWip}
              workingTreeCount={changeCount}
              selectedId={focus}
              rangeHashes={rangeHashes}
              onSelect={select}
            />
          )}
        </section>

        <aside className={panel === 'none' ? 'side-pane idle' : 'side-pane'}>
          {panel === 'working-tree' && activeStatus && workingTree && (
            <WorkingTreePanel
              env={activeStatus}
              workingTree={workingTree}
              author={author}
              onCommitted={(r) => void afterCommit(r)}
              onError={showError}
            />
          )}
          {panel === 'commit' && displayedCommit && (
            <CommitPanel
              key={displayedCommit.hash}
              commit={displayedCommit}
              range={range}
              environments={graph?.environments ?? []}
              activeEnvId={activeEnvId}
              onPushed={(r) => void afterPush(r)}
              onError={showError}
            />
          )}
          {panel === 'new-migration' && activeStatus && (
            <NewMigrationPanel env={activeStatus} author={author} onCommitted={(r) => void afterCommit(r)} onError={showError} />
          )}
          {(panel === 'none' || (panel === 'working-tree' && !workingTree)) && (
            <div className="side-empty">
              <p>Select a commit to see its SQL, push it to another environment or export it for an offline site.</p>
              {showWip && (
                <button className="btn" onClick={() => select(WIP_ID, false)}>
                  Review {changeCount} uncommitted change{changeCount === 1 ? '' : 's'}
                </button>
              )}
            </div>
          )}
        </aside>
      </main>

      {showEnvs && (
        <EnvironmentsDialog
          environments={envs}
          statuses={graph?.environments ?? []}
          onChanged={() => void refresh()}
          onClose={() => setShowEnvs(false)}
          onError={showError}
          onInfo={showInfo}
        />
      )}

      {toast && (
        <div className={`toast ${toast.kind}`} role={toast.kind === 'error' ? 'alert' : 'status'}>
          <span>{toast.text}</span>
          <button className="btn ghost" onClick={() => setToast(null)} aria-label="Dismiss">
            Dismiss
          </button>
        </div>
      )}
    </div>
  );
}
