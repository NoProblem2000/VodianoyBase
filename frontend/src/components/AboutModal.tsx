import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink, Scale } from 'lucide-react';
import { api } from '../api/client';
import type { AboutInfo } from '../api/types';
import { useI18n } from '../i18n';
import { Modal } from './Modal';

/** About & License: name, version of the running build, Apache-2.0 summary and the full license text. */
export function AboutModal({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const [info, setInfo] = useState<AboutInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showLicense, setShowLicense] = useState(false);

  useEffect(() => {
    api
      .about()
      .then(setInfo)
      .catch((e: Error) => setError(e.message));
  }, []);

  return (
    <Modal
      title={t('about.title')}
      onClose={onClose}
      width="max-w-2xl"
      footer={
        <>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </>
      }
    >
      {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
      {!info && !error && <p className="text-gray-500">{t('about.loading')}</p>}
      {info && (
        <div className="flex flex-col gap-4">
          <div className="flex items-center gap-3">
            <svg width="40" height="40" viewBox="0 0 32 32" aria-hidden="true">
              <path d="M11 7v18M11 16c0-4 10-3 10-9" stroke="#2dd4bf" strokeWidth="2.5" fill="none" strokeLinecap="round" />
              <circle cx="11" cy="25" r="3" fill="#2dd4bf" />
              <circle cx="21" cy="7" r="3" fill="#a78bfa" />
              <circle cx="11" cy="16" r="3" fill="#2dd4bf" />
            </svg>
            <div>
              <h3 className="text-lg font-semibold text-gray-100">
                {info.name} <span className="font-mono text-[15px] text-teal-300">v{info.version}</span>
              </h3>
              <p className="text-gray-400">{t('about.status')}</p>
            </div>
          </div>

          <dl className="grid grid-cols-[140px_1fr] gap-x-3 gap-y-1.5 text-[12.5px]">
            <dt className="text-gray-500">{t('about.version')}</dt>
            <dd className="font-mono">{info.version}</dd>
            <dt className="text-gray-500">{t('about.license')}</dt>
            <dd>
              <a className="inline-flex items-center gap-1 text-teal-300 hover:underline" href={info.licenseUrl} target="_blank" rel="noreferrer">
                {info.license} <ExternalLink size={11} />
              </a>
            </dd>
            <dt className="text-gray-500">{t('about.copyright')}</dt>
            <dd>{info.copyright}</dd>
            <dt className="text-gray-500">{t('about.repository')}</dt>
            <dd>
              <a className="inline-flex items-center gap-1 text-teal-300 hover:underline" href={info.repository} target="_blank" rel="noreferrer">
                {info.repository.replace(/^https:\/\//, '')} <ExternalLink size={11} />
              </a>
            </dd>
          </dl>

          <div className="flex items-start gap-2.5 rounded-md border border-kraken-line bg-kraken-bg px-3 py-2.5">
            <Scale size={16} className="mt-0.5 shrink-0 text-teal-400" />
            <p className="text-[12.5px] leading-relaxed text-gray-300">{t('about.licenseSummary')}</p>
          </div>

          <div className="rounded-md border border-kraken-line">
            <button
              className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-gray-300 hover:bg-kraken-hover"
              onClick={() => setShowLicense(!showLicense)}
              aria-expanded={showLicense}
            >
              {showLicense ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              {showLicense ? t('about.hideLicense') : t('about.showLicense')}
            </button>
            {showLicense && (
              <pre className="max-h-[340px] overflow-auto whitespace-pre-wrap border-t border-kraken-line px-3 py-2 font-mono text-[11px] leading-snug text-gray-400">
                {info.licenseText ?? t('about.licenseUnavailable')}
              </pre>
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
