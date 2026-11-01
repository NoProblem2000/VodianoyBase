import { useEffect, useState } from 'react';
import { FlaskConical, Play } from 'lucide-react';
import { api, type MigrationBody } from '../api/client';
import type { CommitResult, DryRunResult, EnvironmentView, ExecutionOptions, SqlSafetyReport } from '../api/types';
import { useI18n } from '../i18n';
import { DEFAULT_EXECUTION, DryRunPanel, SqlSafetyPanel, TimeoutFields } from './ExecutionPanels';
import { Modal } from './Modal';
import { applyAlternatives } from './SafetyBadges';
import { SqlEditor } from './SqlEditor';
import { Tabs } from './Tabs';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  author: string;
  onAuthorChange: (author: string) => void;
  onDone: (result: CommitResult) => void;
  onClose: () => void;
}

type EditorTab = 'pre' | 'up' | 'down' | 'post';
type SideTab = 'safety' | 'dry';

/**
 * Mode B: a migration with pre / post scripts, lock_timeout / statement_timeout, live safety analysis,
 * zero-downtime patterns, dry-run and Execute &amp; Commit.
 */
export function NewMigrationModal({ env, author, onAuthorChange, onDone, onClose }: Props) {
  const toast = useToast();
  const { t, tn } = useI18n();
  const [tab, setTab] = useState<EditorTab>('up');
  const [side, setSide] = useState<SideTab>('safety');
  const [up, setUp] = useState(() => t('migration.template'));
  const [down, setDown] = useState('');
  const [options, setOptions] = useState<ExecutionOptions>(DEFAULT_EXECUTION);
  const [message, setMessage] = useState('');
  const [allowDirty, setAllowDirty] = useState(false);
  const [busy, setBusy] = useState<'execute' | 'dry' | null>(null);
  const [analysis, setAnalysis] = useState<SqlSafetyReport | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [dryRun, setDryRun] = useState<DryRunResult | null>(null);

  // live safety analysis of pre + up + post (debounced)
  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setAnalyzing(true);
      api
        .analyze(env.id, { sql: up, preScript: options.preScript, postScript: options.postScript })
        .then((r) => !cancelled && setAnalysis(r))
        .catch(() => !cancelled && setAnalysis(null))
        .finally(() => !cancelled && setAnalyzing(false));
    }, 600);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [env.id, up, options.preScript, options.postScript]);

  function body(): MigrationBody {
    return {
      message,
      author,
      upSql: up,
      downSql: down,
      allowDirty,
      lockTimeout: options.lockTimeout,
      statementTimeout: options.statementTimeout,
      preScript: options.preScript,
      postScript: options.postScript,
    };
  }

  async function execute() {
    if (!message.trim()) {
      toast.error(t('common.writeMessage'));
      return;
    }
    setBusy('execute');
    try {
      const result = await api.execute(env.id, body());
      const steps = result.steps ?? [];
      toast.success(
        t('migration.done', { hash: result.commit.hash.slice(0, 7), env: env.name }) +
          (steps.length > 1 || steps.some((st) => !st.transactional)
            ? ` (${t('exec.stepsDone', { steps: steps.length, ms: result.elapsedMs ?? 0 })})`
            : ''),
      );
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function runDry() {
    setBusy('dry');
    try {
      setDryRun(await api.dryRun(env.id, { ...body(), message: message || 'dry-run' }));
      setSide('dry');
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  function usePattern(statement: string, replacement: string) {
    const next = applyAlternatives(up, [{ statement, replacement }]);
    if (next === null) {
      toast.error(t('safety.patternNotFound'));
      return;
    }
    setUp(next);
    setTab('up');
    toast.success(t('safety.patternApplied'));
  }

  const editorTabs: { id: EditorTab; label: string }[] = [
    { id: 'pre', label: t('exec.preScript') + (options.preScript.trim() ? ' •' : '') },
    { id: 'up', label: t('common.upSql') },
    { id: 'down', label: down.trim() ? t('common.downSql') : t('migration.downGenerated') },
    { id: 'post', label: t('exec.postScript') + (options.postScript.trim() ? ' •' : '') },
  ];

  return (
    <Modal
      title={t('migration.title')}
      subtitle={t('migration.subtitle', { env: env.name })}
      onClose={onClose}
      width="max-w-[min(1500px,96vw)]"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            {t('migration.allowDirty')}
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="btn" onClick={() => void runDry()} disabled={busy !== null}>
            <FlaskConical size={14} /> {busy === 'dry' ? t('exec.dryRunning') : t('exec.dryRun')}
          </button>
          <button className="btn btn-primary" onClick={() => void execute()} disabled={busy !== null}>
            <Play size={14} /> {busy === 'execute' ? t('migration.executing') : t('migration.execute')}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="grid h-[min(460px,calc(100vh-22rem))] min-h-[300px] grid-cols-[minmax(0,1fr)_400px] gap-3">
          <div className="flex min-h-0 flex-col overflow-hidden rounded-md border border-kraken-line">
            <Tabs<EditorTab> tabs={editorTabs} active={tab} onChange={setTab} />
            <div className="min-h-0 flex-1">
              {tab === 'pre' && (
                <SqlEditor value={options.preScript} onChange={(v) => setOptions({ ...options, preScript: v })} />
              )}
              {tab === 'up' && <SqlEditor value={up} onChange={setUp} />}
              {tab === 'down' && <SqlEditor value={down} onChange={setDown} />}
              {tab === 'post' && (
                <SqlEditor value={options.postScript} onChange={(v) => setOptions({ ...options, postScript: v })} />
              )}
            </div>
          </div>
          <div className="flex min-h-0 flex-col overflow-hidden rounded-md border border-kraken-line">
            <Tabs<SideTab>
              tabs={[
                { id: 'safety', label: t('safety.title') },
                { id: 'dry', label: t('exec.dryTitle') },
              ]}
              active={side}
              onChange={setSide}
            />
            <div className="min-h-0 flex-1">
              {side === 'safety' ? (
                <SqlSafetyPanel report={analysis} loading={analyzing} onUsePattern={usePattern} />
              ) : (
                <DryRunPanel result={dryRun} />
              )}
            </div>
          </div>
        </div>
        <div className="grid grid-cols-[200px_1fr_auto] items-end gap-3">
          <label>
            <span className="label">{t('common.author')}</span>
            <input
              className="input"
              value={author}
              onChange={(e) => onAuthorChange(e.target.value)}
              placeholder={t('common.authorPlaceholder')}
            />
          </label>
          <label>
            <span className="label">{t('common.commitMessage')}</span>
            <input
              className="input"
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder={t('migration.messagePlaceholder')}
            />
          </label>
          <TimeoutFields value={options} onChange={setOptions} />
        </div>
        {analysis?.multiStep && (
          <p className="text-xs text-sky-300">{tn('plural.steps', analysis.steps)} · {t('safety.stepHint')}</p>
        )}
      </div>
    </Modal>
  );
}
