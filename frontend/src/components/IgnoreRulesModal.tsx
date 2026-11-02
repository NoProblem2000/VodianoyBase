import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { api } from '../api/client';
import type { DiffIgnoreRules } from '../api/types';
import { useI18n } from '../i18n';
import { DEFAULT_IGNORE_RULES, IgnoreRulesEditor, sameRules } from './IgnoreRulesEditor';
import { Modal } from './Modal';
import { useToast } from './Toasts';

interface Props {
  onSaved: () => void;
  onClose: () => void;
}

/** Default ignore rules of the installation (pgvc-settings.json). */
export function IgnoreRulesModal({ onSaved, onClose }: Props) {
  const { t } = useI18n();
  const toast = useToast();
  const [saved, setSaved] = useState<DiffIgnoreRules | null>(null);
  const [rules, setRules] = useState<DiffIgnoreRules>(DEFAULT_IGNORE_RULES);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .ignoreRules()
      .then((r) => {
        setSaved(r);
        setRules(r);
      })
      .catch((e: Error) => toast.error(e.message));
    // load once: the toast API is a new object on every render
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    setBusy(true);
    try {
      const stored = await api.saveIgnoreRules(rules);
      setSaved(stored);
      setRules(stored);
      toast.success(t('ignore.saved'));
      onSaved();
      onClose();
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={t('ignore.title')}
      subtitle={t('ignore.subtitle')}
      onClose={onClose}
      width="max-w-3xl"
      footer={
        <>
          <button className="btn" onClick={() => saved && setRules(saved)} disabled={!saved || sameRules(saved, rules)}>
            {t('ignore.reset')}
          </button>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button className="btn btn-primary" onClick={() => void save()} disabled={busy || !saved}>
            <Save size={14} /> {busy ? t('ignore.saving') : t('ignore.save')}
          </button>
        </>
      }
    >
      <IgnoreRulesEditor value={rules} onChange={setRules} />
    </Modal>
  );
}
