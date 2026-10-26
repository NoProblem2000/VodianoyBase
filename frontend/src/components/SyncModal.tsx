import { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Send } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentComparison, GlobalGraph, HistoryRelation, PushResult } from '../api/types';
import { ancestorsOf } from '../lib/graphLayout';
import { firstLine, shortHash } from '../lib/format';
import { ChangeList } from './ChangeList';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  graph: GlobalGraph;
  defaultSource: string;
  onDone: (result: PushResult) => void;
  onClose: () => void;
}

const RELATION_TEXT: Record<HistoryRelation, { label: string; text: string; tone: string }> = {
  SAME: { label: 'Up to date', text: 'Both environments are at the same commit.', tone: 'text-teal-300' },
  TARGET_BEHIND: { label: 'Behind', text: 'The target is behind: the commits below can be pushed.', tone: 'text-amber-300' },
  TARGET_EMPTY: { label: 'Empty history', text: 'Version control is set up in the target but it has no commits yet.', tone: 'text-amber-300' },
  TARGET_NOT_INITIALIZED: {
    label: 'Not initialized',
    text: 'The target has no _pgvc schema yet; the push installs it first (needs a SUPERUSER).',
    tone: 'text-amber-300',
  },
  TARGET_AHEAD: { label: 'Ahead', text: 'The target is ahead of the source: nothing to push.', tone: 'text-sky-300' },
  DIVERGED: {
    label: 'Diverged',
    text: 'Both histories changed since their common ancestor: the push is not possible.',
    tone: 'text-red-300',
  },
};

const PUSHABLE: HistoryRelation[] = ['TARGET_BEHIND', 'TARGET_EMPTY', 'TARGET_NOT_INITIALIZED'];

export function SyncModal({ graph, defaultSource, onDone, onClose }: Props) {
  const toast = useToast();
  const sources = graph.environments.filter((e) => e.initialized && e.head);
  const [source, setSource] = useState(sources.some((s) => s.id === defaultSource) ? defaultSource : sources[0]?.id ?? '');
  const targets = graph.environments.filter((e) => e.id !== source);
  const [target, setTarget] = useState(targets[0]?.id ?? '');
  const [comparison, setComparison] = useState<EnvironmentComparison | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tip, setTip] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [skipVerification, setSkipVerification] = useState(false);
  const [busy, setBusy] = useState(false);

  const byHash = useMemo(() => new Map(graph.commits.map((c) => [c.hash, c])), [graph]);
  const sourceHead = sources.find((s) => s.id === source)?.head ?? null;
  const history = useMemo(() => {
    const hashes = ancestorsOf(byHash, sourceHead);
    return graph.commits.filter((c) => hashes.has(c.hash));
  }, [graph, byHash, sourceHead]);

  useEffect(() => {
    if (!targets.some((t) => t.id === target)) setTarget(targets[0]?.id ?? '');
  }, [targets, target]);

  useEffect(() => {
    setComparison(null);
    setError(null);
    setTip('');
    if (!source || !target || source === target) return;
    let cancelled = false;
    api
      .compare(source, target)
      .then((c) => !cancelled && setComparison(c))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [source, target]);

  const pushable = comparison !== null && PUSHABLE.includes(comparison.relation);
  // commits that would be pushed up to the chosen tip
  const pushing = useMemo(() => {
    if (!comparison) return [];
    if (!tip) return comparison.commitsToPush;
    const index = comparison.commitsToPush.findIndex((c) => c.hash === tip);
    return index < 0 ? comparison.commitsToPush : comparison.commitsToPush.slice(0, index + 1);
  }, [comparison, tip]);

  async function push() {
    setBusy(true);
    try {
      const result = await api.push({
        sourceEnvironmentId: source,
        targetEnvironmentId: target,
        targetCommitHash: tip || null,
        allowDirty,
        skipVerification,
      });
      toast.success(`Pushed ${result.appliedCommits.length} commit(s) to ${targetName}: now at ${shortHash(result.newHead)}`);
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const targetName = graph.environments.find((e) => e.id === target)?.name ?? target;
  const relation = comparison ? RELATION_TEXT[comparison.relation] : null;

  return (
    <Modal
      title="Sync / Push to Environment"
      subtitle="Applies the missing commits of the source to the target in one transaction and verifies the result."
      onClose={onClose}
      width="max-w-4xl"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            Push even if the target has uncommitted changes
          </label>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={skipVerification} onChange={(e) => setSkipVerification(e.target.checked)} />
            Skip schema verification
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            Close
          </button>
          <button className="btn btn-primary" disabled={!pushable || busy || pushing.length === 0} onClick={() => void push()}>
            <Send size={14} /> {busy ? 'Pushing…' : `Push ${pushing.length} commit${pushing.length === 1 ? '' : 's'}`}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-[1fr_auto_1fr_1fr] items-end gap-3">
          <label>
            <span className="label">From (source)</span>
            <select className="input" value={source} onChange={(e) => setSource(e.target.value)}>
              {sources.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <ArrowRight size={16} className="mb-2 text-gray-500" />
          <label>
            <span className="label">To (target)</span>
            <select className="input" value={target} onChange={(e) => setTarget(e.target.value)}>
              {targets.map((t) => (
                <option key={t.id} value={t.id} disabled={!t.reachable}>
                  {t.name}
                  {t.reachable ? '' : ' (offline)'}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="label">Up to commit</span>
            <select className="input" value={tip} onChange={(e) => setTip(e.target.value)} disabled={!pushable}>
              <option value="">source HEAD</option>
              {history
                .filter((c) => comparison?.commitsToPush.some((p) => p.hash === c.hash))
                .map((c) => (
                  <option key={c.hash} value={c.hash}>
                    {shortHash(c.hash)} · {firstLine(c.message)}
                  </option>
                ))}
            </select>
          </label>
        </div>

        {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        {!comparison && !error && <p className="text-gray-500">Comparing…</p>}
        {comparison && relation && (
          <>
            <div className="rounded-md border border-kraken-line bg-kraken-bg px-3 py-2.5">
              <div className="flex items-center gap-2">
                <span className={`font-semibold ${relation.tone}`}>{relation.label}</span>
                <span className="text-gray-400">{relation.text}</span>
              </div>
              <div className="mt-1 font-mono text-xs text-gray-500">
                source {shortHash(comparison.sourceHead)} · target {shortHash(comparison.targetHead)}
                {comparison.commonAncestor && ` · common ancestor ${shortHash(comparison.commonAncestor)}`}
              </div>
              {comparison.targetHasUncommittedChanges && (
                <div className="mt-1.5 text-xs text-amber-300">
                  The target schema has changes that are not committed there.
                </div>
              )}
            </div>
            {pushing.length > 0 && (
              <div>
                <div className="panel-title mb-1.5">Commits to push</div>
                <ol className="rounded-md border border-kraken-line px-3 py-1.5">
                  {pushing.map((c, i) => (
                    <li key={c.hash} className="flex gap-3 py-0.5">
                      <span className="w-5 text-right text-gray-500">{i + 1}</span>
                      <span className="font-mono text-gray-400">{shortHash(c.hash)}</span>
                      <span className="truncate">{firstLine(c.message)}</span>
                      <span className="ml-auto shrink-0 text-gray-500">{c.author}</span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
            <div>
              <div className="panel-title mb-1.5">Schema difference (target → source HEAD)</div>
              <div className="max-h-56 overflow-auto rounded-md border border-kraken-line px-2">
                <ChangeList changes={comparison.schemaDiff.changes} emptyText="The schemas are identical." />
              </div>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}
