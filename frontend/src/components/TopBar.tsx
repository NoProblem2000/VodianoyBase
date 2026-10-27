import { Database, Download, GitBranchPlus, Globe, Info, RefreshCw, Send, Settings2 } from 'lucide-react';
import type { EnvironmentView, PgVcStatus } from '../api/types';
import { LANGS, useI18n } from '../i18n';

interface Props {
  environments: EnvironmentView[];
  activeEnv: EnvironmentView | null;
  status: PgVcStatus | null;
  loading: boolean;
  onSwitch: (id: string) => void;
  onManageEnvironments: () => void;
  onNewMigration: () => void;
  onExport: () => void;
  onSync: () => void;
  onRefresh: () => void;
  onAbout: () => void;
}

export function TopBar(p: Props) {
  const { t } = useI18n();
  const ready = !!p.status?.reachable && !!p.status.initialized;
  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-kraken-line bg-kraken-panel px-3">
      <div className="flex items-center gap-2 pr-2">
        <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M11 7v18M11 16c0-4 10-3 10-9" stroke="#2dd4bf" strokeWidth="2.5" fill="none" strokeLinecap="round" />
          <circle cx="11" cy="25" r="3" fill="#2dd4bf" />
          <circle cx="21" cy="7" r="3" fill="#a78bfa" />
          <circle cx="11" cy="16" r="3" fill="#2dd4bf" />
        </svg>
        <span className="text-[14px] font-semibold tracking-tight text-gray-100">PgGitVisualizer</span>
      </div>

      <div className="flex items-center gap-1 rounded-md border border-kraken-line bg-kraken-bg pl-2">
        <Database size={14} className="text-gray-500" />
        {p.activeEnv && <span className="h-2.5 w-2.5 rounded-full" style={{ background: p.activeEnv.colorBadge }} />}
        <select
          aria-label={t('topBar.currentEnvironment')}
          className="h-8 cursor-pointer bg-transparent pr-2 text-[13px] font-medium text-gray-100 focus:outline-none"
          value={p.activeEnv?.id ?? ''}
          onChange={(e) => p.onSwitch(e.target.value)}
          disabled={p.environments.length === 0}
        >
          {p.environments.length === 0 && <option value="">{t('topBar.noEnvironments')}</option>}
          {p.environments.map((env) => (
            <option key={env.id} value={env.id} className="bg-kraken-panel">
              {env.name}
            </option>
          ))}
        </select>
      </div>
      <button className="btn btn-ghost px-2" onClick={p.onManageEnvironments} title={t('topBar.manageEnvironments')}>
        <Settings2 size={16} />
        <span className="hidden lg:inline">{t('topBar.environments')}</span>
      </button>

      <div className="flex-1" />

      <button className="btn btn-ghost px-2" onClick={p.onRefresh} disabled={p.loading} title={t('topBar.refresh')}>
        <RefreshCw size={15} className={p.loading ? 'animate-spin' : ''} />
      </button>
      <button className="btn" onClick={p.onNewMigration} disabled={!ready}>
        <GitBranchPlus size={15} />
        {t('topBar.newMigration')}
      </button>
      <button className="btn" onClick={p.onExport} disabled={!ready}>
        <Download size={15} />
        {t('topBar.export')}
      </button>
      <button className="btn" onClick={p.onSync} disabled={!ready || p.environments.length < 2}>
        <Send size={15} />
        {t('topBar.sync')}
      </button>

      <span className="mx-0.5 h-6 w-px bg-kraken-line" aria-hidden="true" />
      <LanguageSwitch />
      <button className="btn btn-ghost px-2" onClick={p.onAbout} title={t('topBar.about')} aria-label={t('topBar.about')}>
        <Info size={16} />
      </button>
    </header>
  );
}

/** RU | EN: switches the whole UI instantly, the choice is kept in localStorage (pgvc_lang). */
function LanguageSwitch() {
  const { lang, setLang, t } = useI18n();
  return (
    <div className="flex items-center gap-1.5 text-gray-400" role="group" aria-label={t('language.label')} title={t('language.label')}>
      <Globe size={14} />
      <div className="flex overflow-hidden rounded-md border border-kraken-line">
        {LANGS.map((l) => (
          <button
            key={l}
            type="button"
            onClick={() => setLang(l)}
            aria-pressed={lang === l}
            title={t(`language.${l}`)}
            className={`px-1.5 py-0.5 text-[11px] font-semibold tracking-wide ${
              lang === l ? 'bg-teal-500/20 text-teal-300' : 'text-gray-400 hover:bg-kraken-hover hover:text-gray-200'
            }`}
          >
            {l.toUpperCase()}
          </button>
        ))}
      </div>
    </div>
  );
}
