import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { DatabaseZap, Plus, ServerCrash, ShieldAlert } from 'lucide-react';
import { api } from './api/client';
import type { GraphCommit } from './api/types';
import { useAppData } from './hooks/useAppData';
import { useResizablePanel } from './hooks/useResizablePanel';
import { ancestorsOf } from './lib/graphLayout';
import { loadSetting, saveSetting } from './lib/storage';
import { I18nProvider, useI18n } from './i18n';
import { AboutModal } from './components/AboutModal';
import { AuditLogPanel } from './components/AuditLogPanel';
import { CommitContextMenu } from './components/CommitContextMenu';
import { CommitGraph, WORKING_TREE_ID } from './components/CommitGraph';
import { CommitInspector } from './components/CommitInspector';
import { CreateBranchModal } from './components/CreateBranchModal';
import { DeleteCommitModal } from './components/DeleteCommitModal';
import { EnvironmentsModal } from './components/EnvironmentsModal';
import { ExportModal } from './components/ExportModal';
import { GitExportModal } from './components/GitExportModal';
import { ReferenceDataModal } from './components/ReferenceDataModal';
import { DocumentationModal } from './components/DocumentationModal';
import { IgnoreRulesModal } from './components/IgnoreRulesModal';
import { MatrixModal } from './components/MatrixModal';
import { NewMigrationModal } from './components/NewMigrationModal';
import { RevertModal } from './components/RevertModal';
import { RollbackModal } from './components/RollbackModal';
import { SchemaExplorer } from './components/SchemaExplorer';
import { SnapshotSyncModal } from './components/SnapshotSyncModal';
import { SyncModal } from './components/SyncModal';
import { ToastProvider, useToast } from './components/Toasts';
import { TopBar } from './components/TopBar';
import { WorkingTreeInspector } from './components/WorkingTreeInspector';

type ModalState =
  | { kind: 'none' }
  | { kind: 'environments' }
  | { kind: 'new-migration' }
  | { kind: 'export'; source?: string; from?: string | null; to?: string }
  | { kind: 'sync'; source?: string; tip?: string }
  | { kind: 'rollback'; target: string }
  | { kind: 'revert'; target: string }
  | { kind: 'branch'; target: string }
  | { kind: 'about' }
  | { kind: 'delete'; target: string }
  | { kind: 'snapshot-sync' }
  | { kind: 'matrix' }
  | { kind: 'ignore-rules' }
  | { kind: 'reference-data' }
  | { kind: 'git-export' }
  | { kind: 'docs' };

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
  const inspector = useResizablePanel('pgvc.inspectorWidth');
  const [modal, setModal] = useState<ModalState>({ kind: 'none' });
  const [menu, setMenu] = useState<{ hash: string; x: number; y: number } | null>(null);
  const closeMenu = useCallback(() => setMenu(null), []);
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

  function revertBlockedReason(commit: GraphCommit): string | null {
    if (!activeEnv || !ready || !activeHead) return t('app.revertUnavailable');
    if (!activeAncestors.has(commit.hash)) return t('app.revertNotInHistory', { name: activeEnv.name });
    return null;
  }

  /** Environment to read a commit from: the active one when its history has the commit. */
  function sourceOf(commit: GraphCommit): string {
    return activeEnv && commit.environments.includes(activeEnv.id) ? activeEnv.id : commit.environments[0];
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(t('ctx.copied', { what }));
    } catch {
      toast.error(t('ctx.copyFailed'));
    }
  }

  // environments whose current history (HEAD and its ancestors) contains each commit
  const liveIn = useMemo(() => {
    const result = new Map<string, string[]>();
    for (const env of graph?.environments ?? []) {
      for (const hash of ancestorsOf(commitsByHash, env.head)) {
        result.set(hash, [...(result.get(hash) ?? []), env.name]);
      }
    }
    return result;
  }, [graph, commitsByHash]);

  function deleteBlockedReason(commit: GraphCommit): string | null {
    const envs = liveIn.get(commit.hash);
    return envs && envs.length > 0 ? t('commit.deleteBlocked', { envs: envs.join(', ') }) : null;
  }

  const afterChange = useCallback(
    async (selectHash?: string) => {
      setModal({ kind: 'none' });
      await data.refresh();
      if (selectHash) setSelected(selectHash);
    },
    [data],
  );

  /** Downloads the reference snapshot of a commit (default: HEAD of the active environment, or its live schema). */
  async function exportSnapshot(envId: string, commitHash?: string | null) {
    try {
      const file = await api.downloadSnapshot(envId, commitHash);
      toast.success(t('snapshot.exported', { file }));
    } catch (e) {
      toast.error((e as Error).message);
    }
  }

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
        onExportSnapshot={() => activeEnv && void exportSnapshot(activeEnv.id)}
        onCompareSnapshot={() => setModal({ kind: 'snapshot-sync' })}
        onMatrix={() => setModal({ kind: 'matrix' })}
        onIgnoreRules={() => setModal({ kind: 'ignore-rules' })}
        onReferenceData={() => setModal({ kind: 'reference-data' })}
        onGitExport={() => setModal({ kind: 'git-export' })}
        onDocs={() => setModal({ kind: 'docs' })}
        timing={data.timing}
        onSchemasChanged={() => void data.refresh()}
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

      <main className="grid min-h-0 flex-1" style={{ gridTemplateColumns: `280px minmax(0,1fr) ${inspector.width}px` }}>
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
              tooltipsDisabled={menu !== null}
              onActivateEnvironment={(id) => {
                const env = environments.find((e) => e.id === id);
                if (!env || env.id === activeEnv?.id) return;
                toast.success(t('graph.switched', { name: env.name.toUpperCase() }));
                void data.setActive(id);
              }}
              onCommitContextMenu={(hash, x, y) => {
                if (commitsByHash.has(hash)) {
                  setSelected(hash);
                  setMenu({ hash, x, y });
                }
              }}
            />
          )}
        </section>

        <aside className="relative min-h-0 border-l border-kraken-line bg-kraken-panel" aria-label={t('app.inspector')}>
          <div
            {...inspector.handleProps}
            aria-label={t('app.resizeInspector')}
            title={t('app.resizeInspector')}
            className="absolute -left-1 top-0 z-20 h-full w-2 cursor-col-resize touch-none outline-none transition-colors hover:bg-teal-500/40 focus-visible:bg-teal-500/50"
          />
          {selected === WORKING_TREE_ID && activeEnv && workingTree && hasChanges ? (
            <WorkingTreeInspector
              env={activeEnv}
              workingTree={workingTree}
              author={author}
              onAuthorChange={setAuthor}
              onCommitted={(r) => void afterChange(r.commit.hash)}
              onDiscarded={() => {
                setSelected(null);
                void data.refresh();
              }}
              onQuickFixApplied={() => void data.refresh()}
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
              deleteBlockedReason={deleteBlockedReason(selectedCommit)}
              onDelete={() => setModal({ kind: 'delete', target: selectedCommit.hash })}
              revertBlockedReason={revertBlockedReason(selectedCommit)}
              onRevert={() => setModal({ kind: 'revert', target: selectedCommit.hash })}
              onExportSnapshot={() => void exportSnapshot(inspectorSource, selectedCommit.hash)}
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
          defaultSource={modal.source ?? activeEnv.id}
          defaultTip={modal.tip}
          onDone={() => void afterChange()}
          onLinked={() => void data.refresh()}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'about' && <AboutModal onClose={closeModal} />}
      {modal.kind === 'docs' && <DocumentationModal onClose={closeModal} />}
      {modal.kind === 'matrix' && (
        <MatrixModal environments={environments} activeEnvId={activeEnv?.id ?? null} onClose={closeModal} />
      )}
      {modal.kind === 'ignore-rules' && <IgnoreRulesModal onSaved={() => void data.refresh()} onClose={closeModal} />}
      {modal.kind === 'reference-data' && activeEnv && (
        <ReferenceDataModal
          env={activeEnv}
          author={author}
          initialized={!!status?.initialized}
          onChanged={() => void data.refresh()}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'git-export' && activeEnv && (
        <GitExportModal env={activeEnv} commitHash={null} hasCommits={!!status?.head} onClose={closeModal} />
      )}
      {modal.kind === 'snapshot-sync' && activeEnv && (
        <SnapshotSyncModal
          env={activeEnv}
          author={author}
          onAuthorChange={setAuthor}
          onApplied={(r) => {
            setSelected(r.commit.hash);
            void data.refresh();
          }}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'delete' && graph && (
        <DeleteCommitModal
          hash={modal.target}
          environments={graph.environments}
          onDone={() => {
            setSelected(null);
            void afterChange();
          }}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'revert' && activeEnv && (
        <RevertModal
          env={activeEnv}
          targetHash={modal.target}
          author={author}
          onAuthorChange={setAuthor}
          onDone={(r) => void afterChange(r.commit.hash)}
          onClose={closeModal}
        />
      )}
      {modal.kind === 'branch' && commitsByHash.has(modal.target) && (
        <CreateBranchModal
          commit={commitsByHash.get(modal.target)!}
          activeEnvName={activeEnv?.name ?? null}
          rollbackBlockedReason={rollbackBlockedReason(commitsByHash.get(modal.target)!)}
          hasOtherEnvironments={environments.length > 1}
          onRollbackHere={() => setModal({ kind: 'rollback', target: modal.target })}
          onPushElsewhere={() =>
            setModal({ kind: 'sync', source: sourceOf(commitsByHash.get(modal.target)!), tip: modal.target })
          }
          onClose={closeModal}
        />
      )}
      {menu && commitsByHash.has(menu.hash) && (() => {
        const c = commitsByHash.get(menu.hash)!;
        return (
          <CommitContextMenu
            commit={c}
            x={menu.x}
            y={menu.y}
            onClose={closeMenu}
            actions={{
              revertBlocked: revertBlockedReason(c),
              rollbackBlocked: rollbackBlockedReason(c),
              deleteBlocked: deleteBlockedReason(c),
              onRevert: () => setModal({ kind: 'revert', target: c.hash }),
              onRollback: () => setModal({ kind: 'rollback', target: c.hash }),
              onCreateBranch: () => setModal({ kind: 'branch', target: c.hash }),
              onExportSnapshot: () => void exportSnapshot(sourceOf(c), c.hash),
              onExportSql: () => setModal({ kind: 'export', source: sourceOf(c), from: c.parentHash, to: c.hash }),
              onCopyHash: () => void copy(c.hash, t('ctx.hashWord')),
              onCopyMessage: () => void copy([c.message, c.description].filter(Boolean).join('\n\n'), t('ctx.messageWord')),
              onDelete: () => setModal({ kind: 'delete', target: c.hash }),
            }}
          />
        );
      })()}
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
