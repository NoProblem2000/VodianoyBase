import axios from 'axios';
import { getLang } from '../i18n';
import type {
  AboutInfo,
  AuditLogEntry,
  CommitDetails,
  CommitResult,
  ConnectionTestResult,
  DatabaseSnapshot,
  EnvironmentComparison,
  EnvironmentInput,
  EnvironmentView,
  DeletionMode,
  DryRunResult,
  SqlSafetyReport,
  DeletionPlan,
  DeletionResult,
  ClearLogResult,
  DiffIgnoreRules,
  DiscardPlan,
  DiscardResult,
  GitExportResult,
  GlobalGraph,
  MatrixComparison,
  ReferenceDataTable,
  MatrixSnapshotUpload,
  SnapshotComparison,
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

// server messages (errors, validation) come back in the UI language
http.interceptors.request.use((config) => {
  config.headers.set('Accept-Language', getLang());
  return config;
});

// Turn backend errors ({status, error, message}) into Error(message); blob requests (downloads) carry it as a Blob
http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const fallback = error?.message || (getLang() === 'ru' ? 'Ошибка запроса' : 'Request failed');
    const data = error?.response?.data;
    if (data instanceof Blob) {
      try {
        const parsed = JSON.parse(await data.text()) as { message?: string };
        return Promise.reject(new Error(parsed.message || fallback));
      } catch {
        return Promise.reject(new Error(fallback));
      }
    }
    const message: string | undefined = data?.message;
    return Promise.reject(new Error(message || fallback));
  },
);

const e = encodeURIComponent;

/** Mode B request (dryRun is set by the client function). */
export interface MigrationBody {
  message: string;
  author: string;
  upSql: string;
  downSql: string;
  allowDirty: boolean;
  lockTimeout?: string;
  statementTimeout?: string;
  preScript?: string;
  postScript?: string;
}

export interface SnapshotApplyBody {
  envId: string;
  upSql: string;
  downSql: string;
  author: string;
  message: string;
  sourceCommitHash: string | null;
  lockTimeout?: string;
  statementTimeout?: string;
  preScript?: string;
  postScript?: string;
}

async function get<T>(url: string, params?: Record<string, string | undefined>): Promise<T> {
  return (await http.get<T>(url, { params })).data;
}

/**
 * POST of a JSON body that is already text. Snapshot files are embedded as they are: parsing and serializing them
 * in JavaScript would round 64-bit numbers (9223372036854775807 -> 9223372036854776000, out of range for the server).
 */
async function rawJson<T>(url: string, json: string): Promise<T> {
  const r = await http.post<T>(url, json, {
    headers: { 'Content-Type': 'application/json' },
    transformRequest: [(data: string) => data],
  });
  return r.data;
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
  execute: (id: string, body: MigrationBody) =>
    post<CommitResult>(`/environments/${e(id)}/vc/execute`, { ...body, dryRun: false }),
  /** runs the migration in a transaction that is rolled back */
  dryRun: (id: string, body: MigrationBody) =>
    post<DryRunResult>(`/environments/${e(id)}/vc/execute`, { ...body, dryRun: true }),
  analyze: (id: string, body: { sql: string; preScript?: string; postScript?: string }) =>
    post<SqlSafetyReport>(`/environments/${e(id)}/vc/analyze`, body),
  commitDetails: (id: string, hash: string) => get<CommitDetails>(`/environments/${e(id)}/vc/commits/${e(hash)}`),
  commitChanges: (id: string, hash: string) =>
    get<SchemaDiffResult>(`/environments/${e(id)}/vc/commits/${e(hash)}/changes`),
  rollbackPlan: (id: string, target: string) =>
    get<RollbackPlan>(`/environments/${e(id)}/vc/rollback-plan`, { target }),
  rollback: (
    id: string,
    body: { targetHash: string; customRollbackSql: string | null; allowDirty: boolean; skipVerification: boolean },
  ) => post<RollbackResult>(`/environments/${e(id)}/vc/rollback`, body),

  about: () => get<AboutInfo>('/about'),

  // reference data (dictionaries)
  referenceData: (envId: string) => get<ReferenceDataTable[]>(`/environments/${e(envId)}/reference-data`),
  trackTable: (envId: string, table: string, author: string) =>
    post<ReferenceDataTable>(`/environments/${e(envId)}/reference-data`, { table, author }),
  untrackTable: (envId: string, table: string) =>
    http.delete<void>(`/environments/${e(envId)}/reference-data`, { params: { table } }).then(() => undefined),

  // schema as a Git folder ("1 object = 1 file")
  gitExportToFolder: (body: { envId: string; commitHash: string | null; targetPath: string; includeMigrations: boolean }) =>
    post<GitExportResult>('/vc/git-export', body),
  gitExportZip: async (body: { envId: string; commitHash: string | null; includeMigrations: boolean }): Promise<string> => {
    const response = await http.post<Blob>('/vc/git-export', { ...body, targetPath: null }, { responseType: 'blob' });
    const fileName = fileNameOf(response.headers['content-disposition']) ?? 'pgvc-git-export.zip';
    const url = URL.createObjectURL(response.data);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 10000);
    return fileName;
  },

  // default ignore rules of every comparison
  ignoreRules: () => get<DiffIgnoreRules>('/settings/ignore-rules'),
  saveIgnoreRules: (rules: DiffIgnoreRules) =>
    http.put<DiffIgnoreRules>('/settings/ignore-rules', rules).then((r) => r.data),

  // N-database matrix
  /** Snapshot files are embedded as their original text (see rawJson). */
  matrix: (body: {
    envIds: string[];
    snapshots: MatrixSnapshotUpload[];
    baseline: string | null;
    ignoreRules: DiffIgnoreRules | null;
  }) =>
    rawJson<MatrixComparison>(
      '/vc/matrix/compare',
      `{"envIds":${JSON.stringify(body.envIds)},"baseline":${JSON.stringify(body.baseline)},` +
        `"ignoreRules":${JSON.stringify(body.ignoreRules)},"snapshots":[` +
        body.snapshots.map((s) => `{"name":${JSON.stringify(s.name)},"bundle":${s.text}}`).join(',') +
        ']}',
    ),

  // discard uncommitted changes (reset to HEAD)
  discardPlan: (envId: string, changeIndexes: number[] | null) =>
    post<DiscardPlan>('/vc/working-tree/discard-plan', { envId, changeIndexes }),
  discard: (body: {
    envId: string;
    changeIndexes: number[] | null;
    customDownSql: string | null;
    expectedTotalChanges: number;
    skipVerification: boolean;
    lockTimeout: string;
    statementTimeout: string;
  }) => post<DiscardResult>('/vc/working-tree/discard', body),
  clearDdlLog: (envId: string) =>
    http.post<ClearLogResult>('/vc/working-tree/clear-log', null, { params: { envId } }).then((r) => r.data),

  // reference snapshots (.pgsnap.json)
  /** Downloads snapshot_<branch>_<hash>.pgsnap.json of a commit (default: HEAD, or the live schema). */
  downloadSnapshot: async (envId: string, commitHash?: string | null): Promise<string> => {
    const params: Record<string, string> = { envId };
    if (commitHash) params.commitHash = commitHash;
    const response = await http.get<Blob>('/vc/snapshot/export', { params, responseType: 'blob' });
    const fileName = fileNameOf(response.headers['content-disposition']) ?? 'snapshot.pgsnap.json';
    const url = URL.createObjectURL(response.data);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 10000);
    return fileName;
  },
  /** {@code bundleText}: the .pgsnap.json exactly as read from the file (embedded unchanged, see rawJson). */
  compareSnapshot: (envId: string, bundleText: string, ignoreRules?: DiffIgnoreRules | null) =>
    rawJson<SnapshotComparison>(
      '/vc/snapshot/compare',
      `{"envId":${JSON.stringify(envId)},"ignoreRules":${JSON.stringify(ignoreRules ?? null)},"bundle":${bundleText}}`,
    ),
  applySnapshot: (body: SnapshotApplyBody) => post<CommitResult>('/vc/snapshot/apply', { ...body, dryRun: false }),
  dryRunSnapshot: (body: SnapshotApplyBody) => post<DryRunResult>('/vc/snapshot/apply', { ...body, dryRun: true }),
  deletionPlan: (hash: string, mode: DeletionMode) =>
    get<DeletionPlan>(`/vc/commits/${e(hash)}/deletion-plan`, { mode }),
  deleteCommit: (hash: string, mode: DeletionMode) =>
    http.delete<DeletionResult>(`/vc/commits/${e(hash)}`, { params: { mode } }).then((r) => r.data),

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

/** File name from Content-Disposition (RFC 6266 filename* first). */
function fileNameOf(header: string | undefined): string | null {
  if (!header) return null;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (star) return decodeURIComponent(star[1].replace(/"/g, ''));
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1] : null;
}
