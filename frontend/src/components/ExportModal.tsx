import { useEffect, useMemo, useState } from 'react';
import { Download, FileCode2 } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentHead, GlobalGraph, OfflineBundle } from '../api/types';
import { ancestorsOf } from '../lib/graphLayout';
import { firstLine, shortHash } from '../lib/format';
import { useI18n } from '../i18n';
import { Modal } from './Modal';

interface Props {
  graph: GlobalGraph;
  defaultSource: string;
  /** preselected range (e.g. "Export Single Commit") */
  initialFrom?: string | null;
  initialTo?: string | null;
  onClose: () => void;
}

const EMPTY = '__empty__';

export function ExportModal({ graph, defaultSource, initialFrom, initialTo, onClose }: Props) {
  const { t, tn } = useI18n();
  const sources = graph.environments.filter((e) => e.initialized && e.head);
  const [source, setSource] = useState(sources.some((s) => s.id === defaultSource) ? defaultSource : sources[0]?.id ?? '');
  const sourceHead = sources.find((s) => s.id === source)?.head ?? null;
  const byHash = useMemo(() => new Map(graph.commits.map((c) => [c.hash, c])), [graph]);
  // the source's own history, newest first
  const history = useMemo(() => {
    const hashes = ancestorsOf(byHash, sourceHead);
    return graph.commits.filter((c) => hashes.has(c.hash));
  }, [graph, byHash, sourceHead]);

  const [to, setTo] = useState<string>(initialTo ?? sourceHead ?? '');
  const [from, setFrom] = useState<string>(initialFrom === undefined ? EMPTY : initialFrom ?? EMPTY);
  const [preview, setPreview] = useState<OfflineBundle | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!history.some((c) => c.hash === to)) setTo(sourceHead ?? '');
  }, [history, sourceHead, to]);

  useEffect(() => {
    setPreview(null);
    setError(null);
    if (!source || !to) return;
    let cancelled = false;
    api
      .bundlePreview(source, from === EMPTY ? null : from, to)
      .then((b) => !cancelled && setPreview(b))
      .catch((e: Error) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, [source, from, to]);

  const targets: EnvironmentHead[] = graph.environments.filter((e) => e.id !== source);

  return (
    <Modal
      title={t('export.title')}
      subtitle={t('export.subtitle')}
      onClose={onClose}
      width="max-w-3xl"
      footer={
        <>
          <span className="text-xs text-gray-500">
            {t('export.footer')}
          </span>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <a
            className={`btn btn-primary ${preview ? '' : 'pointer-events-none opacity-50'}`}
            href={preview ? api.bundleDownloadUrl(source, from === EMPTY ? null : from, to) : undefined}
            download
          >
            <Download size={14} /> {t('export.download', { file: preview?.fileName ?? '.sql' })}
          </a>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-3 gap-3">
          <label>
            <span className="label">{t('export.source')}</span>
            <select className="input" value={source} onChange={(e) => setSource(e.target.value)}>
              {sources.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="label">{t('export.from')}</span>
            <select className="input" value={from} onChange={(e) => setFrom(e.target.value)}>
              <option value={EMPTY}>{t('export.fromEmpty')}</option>
              {history.map((c) => (
                <option key={c.hash} value={c.hash}>
                  {shortHash(c.hash)} · {firstLine(c.message)}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="label">{t('export.to')}</span>
            <select className="input" value={to} onChange={(e) => setTo(e.target.value)}>
              {history.map((c) => (
                <option key={c.hash} value={c.hash}>
                  {shortHash(c.hash)} · {firstLine(c.message)}
                </option>
              ))}
            </select>
          </label>
        </div>
        {targets.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 text-xs text-gray-400">
            {t('export.startFrom')}
            {targets.map((env) => (
              <button
                key={env.id}
                className="rounded border border-kraken-line px-2 py-0.5 hover:bg-kraken-hover disabled:opacity-40"
                disabled={!env.head || !history.some((c) => c.hash === env.head)}
                onClick={() => env.head && setFrom(env.head)}
                title={env.head ? t('export.envAt', { name: env.name, hash: shortHash(env.head) }) : t('export.envNoHistory', { name: env.name })}
              >
                <span className="mr-1 inline-block h-2 w-2 rounded-full" style={{ background: env.colorBadge }} />
                {env.name}
              </button>
            ))}
          </div>
        )}

        {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
        {preview && (
          <div className="rounded-md border border-kraken-line">
            <div className="flex items-center gap-2 border-b border-kraken-line px-3 py-2">
              <FileCode2 size={15} className="text-teal-400" />
              <span className="font-mono text-[12.5px]">{preview.fileName}</span>
              <span className="ml-auto text-xs text-gray-500">
                {tn('plural.commits', preview.commits.length)} ·{' '}
                {(preview.sql.length / 1024).toFixed(1)} KB
              </span>
            </div>
            <ol className="max-h-56 overflow-auto px-3 py-2">
              {preview.commits.map((c, i) => (
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
      </div>
    </Modal>
  );
}
