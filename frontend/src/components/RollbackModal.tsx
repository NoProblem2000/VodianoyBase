import { useEffect, useState } from 'react';
import { RotateCcw, TriangleAlert } from 'lucide-react';
import { api } from '../api/client';
import type { EnvironmentView, RollbackPlan, RollbackResult } from '../api/types';
import { firstLine, shortHash } from '../lib/format';
import { Modal } from './Modal';
import { SqlEditor } from './SqlEditor';
import { useToast } from './Toasts';

interface Props {
  env: EnvironmentView;
  targetHash: string;
  onDone: (result: RollbackResult) => void;
  onClose: () => void;
}

export function RollbackModal({ env, targetHash, onDone, onClose }: Props) {
  const toast = useToast();
  const [plan, setPlan] = useState<RollbackPlan | null>(null);
  const [sql, setSql] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [allowDirty, setAllowDirty] = useState(false);
  const [skipVerification, setSkipVerification] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .rollbackPlan(env.id, targetHash)
      .then((p) => {
        setPlan(p);
        setSql(p.rollbackSql);
      })
      .catch((e: Error) => setError(e.message));
  }, [env.id, targetHash]);

  async function rollback() {
    if (!plan) return;
    setBusy(true);
    try {
      const result = await api.rollback(env.id, {
        targetHash,
        customRollbackSql: sql === plan.rollbackSql ? null : sql,
        allowDirty,
        skipVerification,
      });
      toast.success(`${env.name} rolled back to ${shortHash(result.newHead)} (${result.revertedCommits.length} commit(s) reverted)`);
      onDone(result);
    } catch (e) {
      toast.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={`Rollback ${env.name} to ${shortHash(targetHash)}`}
      subtitle="The script runs in one transaction; the resulting schema must match the snapshot of the target commit."
      onClose={onClose}
      width="max-w-5xl"
      footer={
        <>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={allowDirty} onChange={(e) => setAllowDirty(e.target.checked)} />
            Allow uncommitted changes
          </label>
          <label className="flex items-center gap-2 text-xs text-gray-400">
            <input type="checkbox" checked={skipVerification} onChange={(e) => setSkipVerification(e.target.checked)} />
            Skip schema verification
          </label>
          <span className="flex-1" />
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-danger" onClick={() => void rollback()} disabled={!plan || busy}>
            <RotateCcw size={14} /> {busy ? 'Rolling back…' : 'Roll back database'}
          </button>
        </>
      }
    >
      {error && <p className="rounded-md border border-red-800 bg-red-950/40 px-3 py-2 text-red-200">{error}</p>}
      {plan && (
        <div className="flex flex-col gap-3">
          <div className="flex items-start gap-2 rounded-md border border-amber-800 bg-amber-950/30 px-3 py-2 text-[12.5px] text-amber-200">
            <TriangleAlert size={15} className="mt-0.5 shrink-0" />
            <div>
              Reverts {plan.commitsToRevert.length} commit{plan.commitsToRevert.length === 1 ? '' : 's'} on {env.name}. Dropped
              tables and columns lose their data. The commits stay in the history; HEAD moves to {shortHash(targetHash)}.
            </div>
          </div>
          <ol className="rounded-md border border-kraken-line px-3 py-1.5">
            {plan.commitsToRevert.map((c) => (
              <li key={c.hash} className="flex gap-3 py-0.5">
                <span className="font-mono text-gray-400">{shortHash(c.hash)}</span>
                <span className="truncate">{firstLine(c.message)}</span>
                <span className="ml-auto shrink-0 text-gray-500">{c.author}</span>
              </li>
            ))}
          </ol>
          <div>
            <div className="mb-1.5 flex items-center gap-2">
              <span className="panel-title">Rollback script</span>
              {sql !== plan.rollbackSql && (
                <>
                  <span className="text-xs text-amber-300">edited</span>
                  <button className="text-xs text-gray-400 underline hover:text-gray-200" onClick={() => setSql(plan.rollbackSql)}>
                    reset
                  </button>
                </>
              )}
            </div>
            <div className="h-[340px] overflow-hidden rounded-md border border-kraken-line">
              <SqlEditor value={sql} onChange={setSql} />
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}
