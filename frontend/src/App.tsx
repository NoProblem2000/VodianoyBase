import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { DatabaseZap, Plus, ServerCrash, ShieldAlert } from 'lucide-react';
import { api } from './api/client';
import type { GraphCommit } from './api/types';
import { useAppData } from './hooks/useAppData';
import { ancestorsOf } from './lib/graphLayout';
import { loadSetting, saveSetting } from './lib/storage';
import { AuditLogPanel } from './components/AuditLogPanel';
import { CommitGraph, WORKING_TREE_ID } from './components/CommitGraph';
import { CommitInspector } from './components/CommitInspector';
import { EnvironmentsModal } from './components/EnvironmentsModal';
import { ExportModal } from './components/ExportModal';
import { NewMigrationModal } from './components/NewMigrationModal';
import { RollbackModal } from './components/RollbackModal';
import { SchemaExplorer } from './components/SchemaExplorer';
import { SyncModal } from './components/SyncModal';
import { ToastProvider, useToast } from './components/Toasts';
import { TopBar } from './components/TopBar';
import { WorkingTreeInspector } from './components/WorkingTreeInspector';

type ModalState =
  | { kind: 'none' }
  | { kind: 'environments' }
  | { kind: 'new-migration' }
  | { kind: 'export'; source?: string; from?: string | null; to?: string }
  | { kind: 'sync' }
  | { kind: 'rollback'; target: string };

export function App() {
  return (
    <ToastProvider>
      <Workspace />
    </ToastProvider>
  );
}

function Workspace() {
  const toast = useToast();
  const data = useAppData(toast.error);
  const { environments, activeEnv, graph, status, workingTree, snapshot, auditLog } = data;
  const [selected, setSelected] = useState<string | null>(null);
  const [modal, setModal] = useState<ModalState>({ kind: 'none' });
  const [author, setAuthorState] = useState(() => loadSetting('pgvc.author', ''));
  const [initializing, setInitializing] = useState(false);

  const setAuthor = useCallback((value: string) => {
    setAuthorState(value);
    saveSetting('pgvc.author', value);
  }, []);
  const closeModal = useCallback(() => setModal({ kind: 'none' }), []);

  const commitsByHash = useMemo(() => new Map((graph?.commits ?? []).map((c) => [c.hash, c])), [graph]);
  const activeHead = graph?.environments.find((e) => e.id === activeEnv?.id)?.head ?? null;
  const activeAncestors = useMemo(() => ancestorsOf(commitsByHash, activeHead), [commitsByHash, activeHead]);
  const hasChanges = !!workingTree?.diff.hasChanges;
  const ready = !!status?.reachable && !!status.initialized;

  const selectedCommit: GraphCommit | null = selected && selected !== WORKING_TREE_ID ? commitsByHash.get(selected) ?? null : null;
  const inspectorSource = selectedCommit
    ? activeEnv && selectedCommit.environments.includes(activeEnv.id)
      ? activeEnv.id
      : selectedCommit.environments[0]
    : null;

  function rollbackBlockedReason(commit: GraphCommit): string | null {
    if (!activeEnv || !ready) return 'The active environment is not available';
    if (commit.hash === activeHead) return `${activeEnv.name} is already at this commit`;
    if (!activeAncestors.has(commit.hash)) return `Not an earlier commit of ${activeEnv.name}'s history`;
    return null;
  }

  const afterChange = useCallback(
    async (selectHash?: string) => {
      setModal({ kind: 'none' });
      await data.refresh();
      if (selectHash) setSelected(selectHash);
    },
    [data],
  );

  async function initialize() {
    if (!activeEnv) return;
    setInitializing(true);
    try {
      await api.initialize(activeEnv.id);
      toast.success(`Version control initialized in ${activeEnv.name}`);
      await data.refresh();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setInitializing(false);
    }
  }

  return (
    <div className="flex h-full flex-col">
      <TopBar
        environments={environments}
        activeEnv={activeEnv}
        status={status}
        loading={data.loading}
        onSwitch={(id) => {
          setSelected(null);
          void data.setActive(id);
        }}
        onManageEnvironments={() => setModal({ kind: 'environments' })}
        onNewMigration={() => setModal({ kind: 'new-migration' })}
        onExport={() => setModal({ kind: 'export' })}
        onSync={() => setModal({ kind: 'sync' })}
        onRefresh={() => void data.refresh()}
      />

      {activeEnv && status && !status.reachable && (
        <div className="flex items-center gap-3 border-b border-red-900 bg-red-950/40 px-4 py-2 text-[12.5px] text-red-200">
          <ServerCrash size={15} />
          <span className="flex-1">
            Cannot connect to <b>{activeEnv.name}</b>: {status.error}
          </span>
          <button className="btn" onClick={() => setModal({ kind: 'environments' })}>
            Edit connection
          </button>
        </div>
      )}
      {activeEnv && status && status.reachable && !status.initialized && (
        <div className="flex items-center gap-3 border-b border-amber-900 bg-amber-950/30 px-4 py-2 text-[12.5px] text-amber-100">
          <ShieldAlert size={15} />
          <span className="flex-1">
            Version control is not set up in <b>{activeEnv.name}</b> yet. Initializing creates the <code>_pgvc</code> schema and
            the DDL event triggers{status.superuser ? '.' : ' and needs a SUPERUSER connection.'}
          </span>
          <button className="btn btn-primary" onClick={() => void initialize()} disabled={initializing}>
            {initializing ? 'Initializing…' : 'Initialize version control'}
          </button>
        </div>
      )}
      {data.envError && status?.reachable && (
        <div className="border-b border-red-900 bg-red-950/40 px-4 py-2 text-[12.5px] text-red-200">{data.envError}</div>
      )}

      <main className="grid min-h-0 flex-1 grid-cols-[280px_minmax(0,1fr)_minmax(420px,34%)]">
        <aside className="flex min-h-0 flex-col border-r border-kraken-line bg-kraken-panel">
          <SchemaExplorer snapshot={snapshot} changes={workingTree?.diff.changes ?? []} />
          <AuditLogPanel entries={auditLog} live={ready} />
        </aside>

        <section className="relative min-h-0 bg-kraken-bg" aria-label="Commit graph">
          {environments.length === 0 && !data.loading ? (
            <EmptyState
              title="Track your PostgreSQL schema like a git repository"
              text="Add a database connection to start. Every schema change becomes a commit you can push to other environments or export for an isolated site."
              action={
                <button className="btn btn-primary" onClick={() => setModal({ kind: 'environments' })}>
                  <Plus size={14} /> Add a database
                </button>
              }
            />
          ) : graph && graph.commits.length === 0 && !hasChanges ? (
            <EmptyState
              title="No commits yet"
              text={
                ready
                  ? 'Change the schema in your IDE and the changes appear here as the working tree, or run a migration with New SQL Migration. The first commit records the current schema as the baseline.'
                  : 'Initialize version control in the active environment to start recording commits.'
              }
            />
          ) : (
            <CommitGraph
              commits={graph?.commits ?? []}
              environments={graph?.environments ?? []}
              activeEnvId={activeEnv?.id ?? null}
              showWorkingTree={hasChanges}
              workingTreeChanges={workingTree?.diff.changes.length ?? 0}
              selectedId={selected}
              onSelect={setSelected}
            />
          )}
        </section>

        <aside className="min-h-0 border-l border-kraken-line bg-kraken-panel">
          {selected === WORKING_TREE_ID && activeEnv && workingTree && hasChanges ? (
            <WorkingTreeInspector
              env={activeEnv}
              workingTree={workingTree}
              author={author}
              onAuthorChange={setAuthor}
              onCommitted={(r) => void afterChange(r.commit.hash)}
            />
          ) : selectedCommit && inspectorSource ? (
            <CommitInspector
              key={selectedCommit.hash}
              commit={selectedCommit}
              sourceEnvId={inspectorSource}
              environments={graph?.environments ?? []}
              activeEnvId={activeEnv?.id ?? null}
              rollbackBlockedReason={rollbackBlockedReason(selectedCommit)}
              onExportSingle={() =>
                setModal({ kind: 'export', source: inspectorSource, from: selectedCommit.parentHash, to: selectedCommit.hash })
              }
              onRollback={() => setModal({ kind: 'rollback', target: selectedCommit.hash })}
            />
          ) : (
            <div className="flex h-full flex-col items-center justify-center gap-3 px-8 text-center text-gray-400">
              <DatabaseZap size={28} className="text-gray-600" />
              <p className="max-w-xs">
                Select the working tree to review and commit changes, or a commit to see its SQL, export it or roll back to it.
              </p>
              {hasChanges && (
                <button className="btn" onClick={() => setSelected(WORKING_TREE_ID)}>
                  Review {workingTree?.diff.changes.length} uncommitted change{workingTree?.diff.changes.length === 1 ? '' : 's'}
                </button>
              )}
            </div>
          )}
        </aside>
      </main>

      {modal.kind === 'environments' && (
        <EnvironmentsModal environments={environments} onChanged={() => void data.refresh()} onClose={closeModal} />
      )}
      {modal.kind === 'new-migration' && activeEnv && (
        <NewMigrationModal
          env={activeEnv}
          author={author}
          onAuthorChange={setAuthor}
          onDone={(r) => void afterChange(r.commit.hash)}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'export' && graph && activeEnv && (
        <ExportModal
          graph={graph}
          defaultSource={modal.source ?? activeEnv.id}
          initialFrom={modal.from}
          initialTo={modal.to}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'sync' && graph && activeEnv && (
        <SyncModal
          graph={graph}
          defaultSource={activeEnv.id}
          onDone={() => void afterChange()}
          onLinked={() => void data.refresh()}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'rollback' && activeEnv && (
        <RollbackModal env={activeEnv} targetHash={modal.target} onDone={(r) => void afterChange(r.newHead)} onClose={closeModal} />
      )}
    </div>
  );
}

function EmptyState({ title, text, action }: { title: string; text: string; action?: ReactNode }) {
  return (
    <div className="mx-auto mt-[16vh] max-w-lg px-6">
      <h1 className="text-2xl font-semibold leading-tight text-gray-100">{title}</h1>
      <p className="mt-2 text-gray-400">{text}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
