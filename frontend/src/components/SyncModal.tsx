import { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Link2, Send } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentComparison, GlobalGraph, HistoryRelation, LinkResult, PushResult } from '../api/types';
import { ancestorsOf } from '../lib/graphLayout';
import { firstLine, shortHash } from '../lib/format';
import { ChangeList } from './ChangeList';
import { Modal } from './Modal';
import { useI18n } from '../i18n';
import { useToast } from './Toasts';

interface Props {
  graph: GlobalGraph;
  defaultSource: string;
  onDone: (result: PushResult) => void;
  /** the target was linked to the source history; the modal stays open so the remaining commits can be pushed */
  onLinked: (result: LinkResult) => void;
  onClose: () => void;
}

const RELATION_TONE: Record<HistoryRelation, string> = {
  SAME: 'text-teal-300',
  TARGET_BEHIND: 'text-amber-300',
  TARGET_EMPTY: 'text-amber-300',
  TARGET_NOT_INITIALIZED: 'text-amber-300',
  TARGET_AHEAD: 'text-sky-300',
  DIVERGED: 'text-red-300',
};

const PUSHABLE: HistoryRelation[] = ['TARGET_BEHIND', 'TARGET_EMPTY', 'TARGET_NOT_INITIALIZED'];

export function SyncModal({ graph, defaultSource, onDone, onLinked, onClose }: Props) {
  const toast = useToast();
  const { t, tn } = useI18n();
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
  const [reload, setReload] = useState(0);

  const byHash = useMemo(() => new Map(graph.commits.map((c) => [c.hash, c])), [graph]);
  const sourceHead = sources.find((s) => s.id === source)?.head ?? null;
  const history = useMemo(() => {
    const hashes = ancestorsOf(byHash, sourceHead);
    return graph.commits.filter((c) => hashes.has(c.hash));
  }, [graph, byHash, sourceHead]);

  useEffect(() => {
    if (!targets.some((env) => env.id === target)) setTarget(targets[0]?.id ?? '');
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
  }, [source, target, reload]);

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
      toast.success(
        t('sync.pushed', { env: targetName, commits: tn('plural.commits', result.appliedCommits.length), hash: shortHash(result.newHead) }),
      );
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const targetName = graph.environments.find((e) => e.id === target)?.name ?? target;
  const sourceName = graph.environments.find((e) => e.id === source)?.name ?? source;
  // the target already has a schema but no history shared with the source: it can be linked (baseline)
  const linkable =
    comparison !== null &&
    (comparison.relation === 'DIVERGED' ||
      ((comparison.relation === 'TARGET_EMPTY' || comparison.relation === 'TARGET_NOT_INITIALIZED') &&
        comparison.targetHasUncommittedChanges));
  const relation = comparison
    ? {
        label: t(`sync.relation.${comparison.relation}.label`),
        text: t(`sync.relation.${comparison.relation}.text`),
        tone: RELATION_TONE[comparison.relation],
      }
    : null;

  return (
    <Modal
      title={t('sync.title')}
      subtitle={t('sync.subtitle')}
      onClose={onClose}
      width="max-w-4xl"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            {t('sync.allowDirty')}
          </label>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={skipVerification} onChange={(e) => setSkipVerification(e.target.checked)} />
            {t('common.skipVerification')}
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button className="btn btn-primary" disabled={!pushable || busy || pushing.length === 0 || (linkable && !allowDirty)} onClick={() => void push()}>
            <Send size={14} /> {busy ? t('sync.pushing') : tn('plural.pushCommits', pushing.length)}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-[1fr_auto_1fr_1fr] items-end gap-3">
          <label>
            <span className="label">{t('sync.from')}</span>
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
            <span className="label">{t('sync.to')}</span>
            <select className="input" value={target} onChange={(e) => setTarget(e.target.value)}>
              {targets.map((env) => (
                <option key={env.id} value={env.id} disabled={!env.reachable}>
                  {env.name}
                  {env.reachable ? '' : ` ${t('sync.offline')}`}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="label">{t('sync.upTo')}</span>
            <select className="input" value={tip} onChange={(e) => setTip(e.target.value)} disabled={!pushable}>
              <option value="">{t('sync.sourceHead')}</option>
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
        {!comparison && !error && <p className="text-gray-500">{t('sync.comparing')}</p>}
        {comparison && relation && (
          <>
            <div className="rounded-md border border-kraken-line bg-kraken-bg px-3 py-2.5">
              <div className="flex items-center gap-2">
                <span className={`font-semibold ${relation.tone}`}>{relation.label}</span>
                <span className="text-gray-400">{relation.text}</span>
              </div>
              <div className="mt-1 font-mono text-xs text-gray-500">
                {t('sync.heads', { source: shortHash(comparison.sourceHead), target: shortHash(comparison.targetHead) })}
                {comparison.commonAncestor && t('sync.commonAncestor', { hash: shortHash(comparison.commonAncestor) })}
              </div>
              {comparison.targetHasUncommittedChanges && (
                <div className="mt-1.5 text-xs text-amber-300">
                  {t('sync.targetDirty')}
                </div>
              )}
            </div>
            {linkable && (
              <LinkPanel
                key={`${source}>${target}:${comparison.sourceHead}:${comparison.targetHead}`}
                comparison={comparison}
                history={history}
                sourceName={sourceName}
                targetName={targetName}
                onLinked={(r) => {
                  onLinked(r);
                  setReload((n) => n + 1);
                }}
              />
            )}
            {pushing.length > 0 && (!linkable || allowDirty) && (
              <div>
                <div className="panel-title mb-1.5">{t('sync.commitsToPush')}</div>
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
              <div className="panel-title mb-1.5">{t('sync.schemaDiff')}</div>
              <div className="max-h-56 overflow-auto rounded-md border border-kraken-line px-2">
                <ChangeList changes={comparison.schemaDiff.changes} emptyText={t('sync.identical')} />
              </div>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

interface LinkPanelProps {
  comparison: EnvironmentComparison;
  /** source history, newest first */
  history: { hash: string; message: string }[];
  sourceName: string;
  targetName: string;
  onLinked: (result: LinkResult) => void;
}

/** Baseline: make the target (which already has the schema) part of the source history without running SQL. */
function LinkPanel({ comparison, history, sourceName, targetName, onLinked }: LinkPanelProps) {
  const toast = useToast();
  const { t, tn, rich } = useI18n();
  const match = comparison.matchingCommit;
  const [commit, setCommit] = useState(match?.hash ?? history[0]?.hash ?? '');
  const [allowDifferences, setAllowDifferences] = useState(false);
  const [keepOwn, setKeepOwn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isMatch = match !== null && commit === match.hash;
  const chosen = history.find((c) => c.hash === commit);
  const after = history.findIndex((c) => c.hash === commit);

  async function link() {
    setBusy(true);
    setError(null);
    try {
      const result = await api.link({
        sourceEnvironmentId: comparison.sourceEnvironmentId,
        targetEnvironmentId: comparison.targetEnvironmentId,
        commitHash: commit || null,
        allowSchemaDifferences: allowDifferences,
        keepTargetCommits: keepOwn,
      });
      toast.success(
        result.discardedCommits
          ? t('link.doneReplaced', { target: targetName, hash: shortHash(result.newHead), count: result.discardedCommits })
          : t('link.done', { target: targetName, hash: shortHash(result.newHead) }),
      );
      onLinked(result);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-md border border-sky-800 bg-sky-950/30 px-3 py-3">
      <div className="flex items-center gap-2 font-semibold text-sky-200">
        <Link2 size={15} /> {t('link.title', { target: targetName, source: sourceName })}
      </div>
      <p className="mt-1 text-gray-300">
        {rich('link.intro', { target: targetName, source: sourceName, noSql: <b>{t('link.noSql')}</b> })}
      </p>
      {match ? (
        <p className="mt-2 text-teal-300">
          {rich('link.matches', {
            target: targetName,
            hash: <span className="font-mono">{shortHash(match.hash)}</span>,
            message: firstLine(match.message),
          })}
        </p>
      ) : (
        <p className="mt-2 text-amber-300">
          {t('link.noMatch', { target: targetName, source: sourceName })}
        </p>
      )}
      <div className="mt-3 grid grid-cols-[1fr_auto] items-end gap-3">
        <label>
          <span className="label">{t('link.commitOf', { source: sourceName })}</span>
          <select className="input" value={commit} onChange={(e) => setCommit(e.target.value)}>
            {history.map((c) => (
              <option key={c.hash} value={c.hash}>
                {shortHash(c.hash)} · {firstLine(c.message)}
                {match?.hash === c.hash ? `  ${t('link.matchesMark')}` : ''}
              </option>
            ))}
          </select>
        </label>
        <button className="btn btn-primary" disabled={busy || !commit} onClick={() => void link()}>
          <Link2 size={14} /> {busy ? t('link.linking') : t('link.button', { hash: shortHash(commit) })}
        </button>
      </div>
      {chosen && after > 0 && (
        <p className="mt-2 text-xs text-gray-400">
          {tn('plural.newerCommits', after, { source: sourceName, target: targetName })}
        </p>
      )}
      <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1">
        {!isMatch && (
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDifferences} onChange={(e) => setAllowDifferences(e.target.checked)} />
            {t('link.allowDifferences', { target: targetName })}
          </label>
        )}
        {comparison.targetHead && (
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={keepOwn} onChange={(e) => setKeepOwn(e.target.checked)} />
            {t('link.keepOwn', { target: targetName })}
          </label>
        )}
      </div>
      {error && (
        <p className="mt-2 whitespace-pre-wrap rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>
      )}
    </div>
  );
}
