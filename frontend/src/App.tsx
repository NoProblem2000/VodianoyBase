import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { DatabaseZap, Plus, ServerCrash, ShieldAlert } from 'lucide-react';
import { api } from './api/client';
import type { GraphCommit } from './api/types';
import { useAppData } from './hooks/useAppData';
import { ancestorsOf } from './lib/graphLayout';
import { loadSetting, saveSetting } from './lib/storage';
import { I18nProvider, useI18n } from './i18n';
import { AboutModal } from './components/AboutModal';
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
  | { kind: 'rollback'; target: string }
  | { kind: 'about' };

export function App() {
  return (
    <I18nProvider>
      <ToastProvider>
        <Workspace />
      </ToastProvider>
    </I18nProvider>
  );
}

function Workspace() {
  const toast = useToast();
  const { t, tn, rich } = useI18n();
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
    if (!activeEnv || !ready) return t('app.rollbackUnavailable');
    if (commit.hash === activeHead) return t('app.rollbackAlreadyHere', { name: activeEnv.name });
    if (!activeAncestors.has(commit.hash)) return t('app.rollbackNotAncestor', { name: activeEnv.name });
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
      toast.success(t('app.initialized', { name: activeEnv.name }));
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
        onAbout={() => setModal({ kind: 'about' })}
      />

      {activeEnv && status && !status.reachable && (
        <div className="flex items-center gap-3 border-b border-red-900 bg-red-950/40 px-4 py-2 text-[12.5px] text-red-200">
          <ServerCrash size={15} />
          <span className="flex-1">{rich('app.cannotConnect', { name: <b>{activeEnv.name}</b>, error: status.error })}</span>
          <button className="btn" onClick={() => setModal({ kind: 'environments' })}>
            {t('app.editConnection')}
          </button>
        </div>
      )}
      {activeEnv && status && status.reachable && !status.initialized && (
        <div className="flex items-center gap-3 border-b border-amber-900 bg-amber-950/30 px-4 py-2 text-[12.5px] text-amber-100">
          <ShieldAlert size={15} />
          <span className="flex-1">
            {rich(status.superuser ? 'app.notInitialized' : 'app.notInitializedNoSuperuser', { name: <b>{activeEnv.name}</b> })}
          </span>
          <button className="btn btn-primary" onClick={() => void initialize()} disabled={initializing}>
            {initializing ? t('app.initializing') : t('app.initialize')}
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

        <section className="relative min-h-0 bg-kraken-bg" aria-label={t('app.commitGraph')}>
          {environments.length === 0 && !data.loading ? (
            <EmptyState
              title={t('app.welcomeTitle')}
              text={t('app.welcomeText')}
              action={
                <button className="btn btn-primary" onClick={() => setModal({ kind: 'environments' })}>
                  <Plus size={14} /> {t('app.addDatabase')}
                </button>
              }
            />
          ) : graph && graph.commits.length === 0 && !hasChanges ? (
            <EmptyState
              title={t('app.noCommitsTitle')}
              text={ready ? t('app.noCommitsReady') : t('app.noCommitsNotReady')}
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
              <p className="max-w-xs">{t('app.inspectorHint')}</p>
              {hasChanges && (
                <button className="btn" onClick={() => setSelected(WORKING_TREE_ID)}>
                  {tn('plural.uncommittedChanges', workingTree?.diff.changes.length ?? 0)}
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
      {modal.kind === 'about' && <AboutModal onClose={closeModal} />}
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
