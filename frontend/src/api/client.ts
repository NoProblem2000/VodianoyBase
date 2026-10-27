import axios from 'axios';
import type {
  AuditLogEntry,
  CommitDetails,
  CommitResult,
  ConnectionTestResult,
  DatabaseSnapshot,
  EnvironmentComparison,
  EnvironmentInput,
  EnvironmentView,
  GlobalGraph,
  LinkResult,
  OfflineBundle,
  PgVcStatus,
  PushResult,
  RollbackPlan,
  RollbackResult,
  SchemaDiffResult,
  WorkingTreeStatus,
} from './types';

const http = axios.create({ baseURL: '/api' });

// Turn backend errors ({status, error, message}) into Error(message)
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message: string | undefined = error?.response?.data?.message;
    return Promise.reject(new Error(message || error?.message || 'Request failed'));
  },
);

const e = encodeURIComponent;

async function get<T>(url: string, params?: Record<string, string | undefined>): Promise<T> {
  return (await http.get<T>(url, { params })).data;
}

async function post<T>(url: string, body?: unknown): Promise<T> {
  return (await http.post<T>(url, body)).data;
}

export const api = {
  // environments
  environments: () => get<EnvironmentView[]>('/environments'),
  addEnvironment: (env: EnvironmentInput) => post<EnvironmentView>('/environments', env),
  updateEnvironment: (id: string, env: EnvironmentInput) =>
    http.put<EnvironmentView>(`/environments/${e(id)}`, env).then((r) => r.data),
  deleteEnvironment: (id: string) => http.delete(`/environments/${e(id)}`).then(() => undefined),
  testConnection: (env: EnvironmentInput) => post<ConnectionTestResult>('/environments/test', env),
  activate: (id: string) => http.put<EnvironmentView>('/environments/active', { environmentId: id }).then((r) => r.data),

  // _pgvc
  initialize: (id: string) => post<PgVcStatus>(`/environments/${e(id)}/pgvc/initialize`),
  status: (id: string) => get<PgVcStatus>(`/environments/${e(id)}/pgvc/status`),
  auditLog: (id: string) => get<AuditLogEntry[]>(`/environments/${e(id)}/pgvc/audit-log`),

  // schema
  snapshot: (id: string) => get<DatabaseSnapshot>(`/environments/${e(id)}/schema/snapshot`),

  // version control
  workingTree: (id: string) => get<WorkingTreeStatus>(`/environments/${e(id)}/vc/working-tree`),
  commit: (id: string, body: { message: string; author: string; upSql: string; downSql: string; expectedHead: string }) =>
    post<CommitResult>(`/environments/${e(id)}/vc/commit`, body),
  execute: (id: string, body: { message: string; author: string; upSql: string; downSql: string; allowDirty: boolean }) =>
    post<CommitResult>(`/environments/${e(id)}/vc/execute`, body),
  commitDetails: (id: string, hash: string) => get<CommitDetails>(`/environments/${e(id)}/vc/commits/${e(hash)}`),
  commitChanges: (id: string, hash: string) =>
    get<SchemaDiffResult>(`/environments/${e(id)}/vc/commits/${e(hash)}/changes`),
  rollbackPlan: (id: string, target: string) =>
    get<RollbackPlan>(`/environments/${e(id)}/vc/rollback-plan`, { target }),
  rollback: (
    id: string,
    body: { targetHash: string; customRollbackSql: string | null; allowDirty: boolean; skipVerification: boolean },
  ) => post<RollbackResult>(`/environments/${e(id)}/vc/rollback`, body),

  // all environments
  graph: () => get<GlobalGraph>('/vc/graph'),
  compare: (source: string, target: string) => get<EnvironmentComparison>('/vc/compare', { source, target }),
  push: (body: {
    sourceEnvironmentId: string;
    targetEnvironmentId: string;
    targetCommitHash: string | null;
    allowDirty: boolean;
    skipVerification: boolean;
  }) => post<PushResult>('/vc/push', body),
  link: (body: {
    sourceEnvironmentId: string;
    targetEnvironmentId: string;
    commitHash: string | null;
    allowSchemaDifferences: boolean;
    keepTargetCommits: boolean;
  }) => post<LinkResult>('/vc/link', body),

  // offline bundles
  bundlePreview: (source: string, from: string | null, to: string | null) =>
    get<OfflineBundle>('/migrations/preview', { source, from: from ?? undefined, to: to ?? undefined }),
  bundleDownloadUrl: (source: string, from: string | null, to: string | null) => {
    const q = new URLSearchParams({ source });
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return `/api/migrations/export?${q.toString()}`;
  },
};
