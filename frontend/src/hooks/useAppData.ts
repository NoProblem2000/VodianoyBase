import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type {
  AuditLogEntry,
  DatabaseSnapshot,
  EnvironmentView,
  GlobalGraph,
  PgVcStatus,
  WorkingTreeStatus,
} from '../api/types';

const AUDIT_POLL_MS = 5000;

export interface AppData {
  environments: EnvironmentView[];
  activeEnv: EnvironmentView | null;
  graph: GlobalGraph | null;
  status: PgVcStatus | null;
  workingTree: WorkingTreeStatus | null;
  snapshot: DatabaseSnapshot | null;
  auditLog: AuditLogEntry[];
  loading: boolean;
  /** error of loading the working tree / snapshot of the active environment */
  envError: string | null;
  refresh: () => Promise<void>;
  setActive: (id: string) => Promise<void>;
}

/** Loads everything the main screen shows and keeps the DDL audit log fresh. */
export function useAppData(onError: (message: string) => void): AppData {
  const [environments, setEnvironments] = useState<EnvironmentView[]>([]);
  const [graph, setGraph] = useState<GlobalGraph | null>(null);
  const [status, setStatus] = useState<PgVcStatus | null>(null);
  const [workingTree, setWorkingTree] = useState<WorkingTreeStatus | null>(null);
  const [snapshot, setSnapshot] = useState<DatabaseSnapshot | null>(null);
  const [auditLog, setAuditLog] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [envError, setEnvError] = useState<string | null>(null);
  const activeIdRef = useRef<string | null>(null);
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const loadActive = useCallback(async (env: EnvironmentView | null) => {
    activeIdRef.current = env?.id ?? null;
    if (!env) {
      setStatus(null);
      setWorkingTree(null);
      setSnapshot(null);
      setAuditLog([]);
      return;
    }
    const st = await api.status(env.id);
    setStatus(st);
    if (!st.reachable || !st.initialized) {
      setWorkingTree(null);
      setSnapshot(null);
      setAuditLog([]);
      setEnvError(st.reachable ? null : st.error);
      return;
    }
    try {
      const [wt, snap] = await Promise.all([api.workingTree(env.id), api.snapshot(env.id)]);
      if (activeIdRef.current !== env.id) return;
      setWorkingTree(wt);
      setSnapshot(snap);
      setAuditLog(wt.auditLog);
      setEnvError(null);
    } catch (e) {
      setEnvError((e as Error).message);
    }
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [envs, g] = await Promise.all([api.environments(), api.graph()]);
      setEnvironments(envs);
      setGraph(g);
      await loadActive(envs.find((e) => e.active) ?? envs[0] ?? null);
    } catch (e) {
      onErrorRef.current((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [loadActive]);

  const setActive = useCallback(
    async (id: string) => {
      try {
        await api.activate(id);
      } catch (e) {
        onErrorRef.current((e as Error).message);
      }
      await refresh();
    },
    [refresh],
  );

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Live DDL audit log: poll the cheap audit endpoint, reload the working tree when new DDL arrives.
  const lastAuditKey = useRef('');
  useEffect(() => {
    lastAuditKey.current = auditKey(auditLog);
  }, [auditLog]);
  useEffect(() => {
    if (!status?.initialized || !status.reachable) return;
    const envId = status.environmentId;
    const timer = window.setInterval(async () => {
      try {
        const log = await api.auditLog(envId);
        if (activeIdRef.current !== envId) return;
        if (auditKey(log) !== lastAuditKey.current) {
          setAuditLog(log);
          const [wt, snap] = await Promise.all([api.workingTree(envId), api.snapshot(envId)]);
          if (activeIdRef.current === envId) {
            setWorkingTree(wt);
            setSnapshot(snap);
          }
        }
      } catch {
        // the next refresh reports problems
      }
    }, AUDIT_POLL_MS);
    return () => window.clearInterval(timer);
  }, [status?.initialized, status?.reachable, status?.environmentId]);

  const activeEnv = environments.find((e) => e.active) ?? environments[0] ?? null;
  return { environments, activeEnv, graph, status, workingTree, snapshot, auditLog, loading, envError, refresh, setActive };
}

function auditKey(log: AuditLogEntry[]): string {
  return `${log.length}:${log.length ? log[log.length - 1].id : 0}`;
}
