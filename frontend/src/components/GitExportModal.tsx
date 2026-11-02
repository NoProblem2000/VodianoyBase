import { useState } from 'react';
import { Archive, FolderGit2, FolderOpen } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentView, GitExportResult } from '../api/types';
import { useI18n } from '../i18n';
import { shortHash } from '../lib/format';
import { loadSetting, saveSetting } from '../lib/storage';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  /** commit to export; null: HEAD of the environment */
  commitHash: string | null;
  /** the environment has commits (otherwise the live schema is exported) */
  hasCommits: boolean;
  onClose: () => void;
}

const PATH_KEY = 'pgvc.gitExportPath';

/** "📁 Export Git Folder (.zip / Local Path)": one file per object for code review. */
export function GitExportModal({ env, commitHash, hasCommits, onClose }: Props) {
  const { t } = useI18n();
  const toast = useToast();
  const [mode, setMode] = useState<'zip' | 'folder'>(() => (loadSetting(PATH_KEY, '') ? 'folder' : 'zip'));
  const [path, setPath] = useState(() => loadSetting(PATH_KEY, ''));
  const [migrations, setMigrations] = useState(true);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<GitExportResult | null>(null);

  async function run() {
    setBusy(true);
    setResult(null);
    try {
      if (mode === 'zip') {
        const file = await api.gitExportZip({ envId: env.id, commitHash, includeMigrations: migrations });
        toast.success(t('gitExport.downloaded', { file }));
      } else {
        saveSetting(PATH_KEY, path.trim());
        const r = await api.gitExportToFolder({ envId: env.id, commitHash, targetPath: path.trim(), includeMigrations: migrations });
        setResult(r);
        toast.success(
          t('gitExport.written', {
            written: r.written.length,
            unchanged: r.unchanged,
            deleted: r.deleted.length,
            files: r.files,
            path: r.targetPath,
          }),
        );
      }
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const source = commitHash
    ? t('gitExport.commit', { hash: shortHash(commitHash) })
    : hasCommits
      ? t('gitExport.head', { env: env.name })
      : t('gitExport.live');

  return (
    <Modal
      title={t('gitExport.title')}
      subtitle={t('gitExport.subtitle', { env: env.name })}
      onClose={onClose}
      width="max-w-3xl"
      footer={
        <>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button className="btn btn-primary" onClick={() => void run()} disabled={busy || (mode === 'folder' && !path.trim())}>
            <FolderGit2 size={14} /> {busy ? t('gitExport.exporting') : t('gitExport.export')}
          </button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="text-[12.5px] text-gray-300">
          {t('gitExport.source')}: <span className="font-mono text-teal-300">{source}</span>
        </div>
        <div className="grid grid-cols-2 gap-2" role="radiogroup">
          <label
            className={`flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2.5 ${
              mode === 'zip' ? 'border-teal-500 bg-teal-950/30' : 'border-kraken-line'
            }`}
          >
            <input type="radio" name="git-export-mode" checked={mode === 'zip'} onChange={() => setMode('zip')} />
            <Archive size={15} className="text-gray-400" />
            <span className="text-gray-100">{t('gitExport.zip')}</span>
          </label>
          <label
            className={`flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2.5 ${
              mode === 'folder' ? 'border-teal-500 bg-teal-950/30' : 'border-kraken-line'
            }`}
          >
            <input type="radio" name="git-export-mode" checked={mode === 'folder'} onChange={() => setMode('folder')} />
            <FolderOpen size={15} className="text-gray-400" />
            <span className="text-gray-100">{t('gitExport.folder')}</span>
          </label>
        </div>
        {mode === 'folder' && (
          <label>
            <span className="label">{t('gitExport.path')}</span>
            <input
              className="input font-mono"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder={t('gitExport.pathPlaceholder')}
              spellCheck={false}
            />
            <span className="mt-1 block text-[11px] leading-snug text-gray-500">{t('gitExport.pathHint')}</span>
          </label>
        )}
        <label className="flex items-center gap-2 text-[12.5px] text-gray-200">
          <input type="checkbox" checked={migrations} onChange={(e) => setMigrations(e.target.checked)} />
          {t('gitExport.migrations')}
        </label>
        <pre className="rounded-md border border-kraken-line bg-kraken-bg p-3 font-mono text-[11.5px] leading-relaxed text-gray-400">
          {`schema/schemas.sql
schema/extensions/<extension>.sql
schema/enums/<schema>.<type>.sql
schema/sequences/<schema>.<sequence>.sql
schema/tables/<schema>.<table>.sql
schema/views/<schema>.<view>.sql
schema/functions/<schema>.<function>.sql
schema/triggers/<schema>.<table>.<trigger>.sql
schema/reference_data/<schema>.<table>.sql
migrations/0001_<hash>_<slug>.up.sql / .down.sql`}
        </pre>
        {result && (
          <div className="rounded-md border border-kraken-line px-3 py-2 text-xs">
            {result.written.length === 0 && result.deleted.length === 0 ? (
              <p className="text-emerald-300">{t('gitExport.nothingChanged')}</p>
            ) : (
              <>
                {result.written.length > 0 && (
                  <>
                    <div className="mb-1 uppercase text-gray-500">{t('gitExport.writtenTitle')}</div>
                    <ul className="mb-2 max-h-40 overflow-auto font-mono text-gray-300">
                      {result.written.map((f) => (
                        <li key={f}>{f}</li>
                      ))}
                    </ul>
                  </>
                )}
                {result.deleted.length > 0 && (
                  <>
                    <div className="mb-1 uppercase text-gray-500">{t('gitExport.deletedTitle')}</div>
                    <ul className="max-h-32 overflow-auto font-mono text-red-300">
                      {result.deleted.map((f) => (
                        <li key={f}>{f}</li>
                      ))}
                    </ul>
                  </>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
