// Mirrors of the backend DTOs (see src/main/java/com/vodianoy/pgvc).

export interface EnvironmentView {
  id: string;
  name: string;
  jdbcUrl: string;
  username: string;
  hasPassword: boolean;
  schemaName: string;
  colorBadge: string;
  active: boolean;
}

export interface EnvironmentInput {
  id?: string;
  name: string;
  jdbcUrl: string;
  username: string;
  /** undefined keeps the stored password when updating */
  password?: string;
  schemaName: string;
  colorBadge: string;
}

export interface ConnectionTestResult {
  success: boolean;
  version: string | null;
  versionNumber: number | null;
  superuser: boolean;
  message: string;
}

export interface PgVcStatus {
  environmentId: string;
  reachable: boolean;
  initialized: boolean;
  ddlTriggerEnabled: boolean;
  dropTriggerEnabled: boolean;
  superuser: boolean;
  head: string | null;
  commitCount: number;
  pendingDdlCount: number;
  error: string | null;
}

export interface AuditLogEntry {
  id: number;
  eventType: string;
  commandTag: string;
  objectType: string;
  objectIdentity: string;
  rawQuery: string;
  dbUser: string;
  executedAt: string;
}

export type ChangeType = 'ADDED' | 'MODIFIED' | 'RENAMED' | 'DELETED';
export type ObjectType = 'SCHEMA' | 'TABLE' | 'PARTITION' | 'COLUMN' | 'CONSTRAINT' | 'INDEX' | 'VIEW' | 'SEQUENCE';

export interface ChangeItem {
  changeType: ChangeType;
  objectType: ObjectType;
  objectName: string;
  details: string;
  upSql: string;
  downSql: string;
  /** snapshot compare only: position of the change, referenced by ScriptStatement.changeId */
  id?: number;
  /** snapshot compare only: the object exists only in this database, not in the reference snapshot */
  extraInTargetDb?: boolean;
  /** warnings that apply to this change */
  warnings?: string[];
}

export interface SchemaDiffResult {
  hasChanges: boolean;
  changes: ChangeItem[];
  upSql: string;
  downSql: string;
  warnings: string[];
}

export interface WorkingTreeStatus {
  environmentId: string;
  head: string | null;
  diff: SchemaDiffResult;
  auditLog: AuditLogEntry[];
}

export interface CommitNode {
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
}

export interface CommitResult {
  commit: CommitNode;
  changes: ChangeItem[];
}

export interface CommitDetails {
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
  upSql: string;
  downSql: string;
  schemaSnapshot: DatabaseSnapshot;
  ddlLogHistory: unknown;
}

export interface RollbackPlan {
  head: string;
  targetHash: string;
  commitsToRevert: CommitNode[];
  rollbackSql: string;
}

export interface RollbackResult {
  previousHead: string;
  newHead: string;
  revertedCommits: CommitNode[];
  executedSql: string;
  verified: boolean;
}

export interface GraphCommit {
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
  environments: string[];
}

export interface EnvironmentHead {
  id: string;
  name: string;
  colorBadge: string;
  active: boolean;
  reachable: boolean;
  initialized: boolean;
  head: string | null;
  error: string | null;
}

export interface GlobalGraph {
  commits: GraphCommit[];
  environments: EnvironmentHead[];
}

export type HistoryRelation =
  | 'SAME'
  | 'TARGET_BEHIND'
  | 'TARGET_AHEAD'
  | 'DIVERGED'
  | 'TARGET_EMPTY'
  | 'TARGET_NOT_INITIALIZED';

export interface EnvironmentComparison {
  sourceEnvironmentId: string;
  targetEnvironmentId: string;
  sourceHead: string | null;
  targetHead: string | null;
  relation: HistoryRelation;
  commonAncestor: string | null;
  commitsToPush: CommitNode[];
  targetHasUncommittedChanges: boolean;
  schemaDiff: SchemaDiffResult;
  /** newest source commit whose schema equals the live target schema (only when the histories are not connected) */
  matchingCommit: CommitNode | null;
}

export interface LinkResult {
  sourceEnvironmentId: string;
  targetEnvironmentId: string;
  previousHead: string | null;
  newHead: string;
  linkedCommits: CommitNode[];
  discardedCommits: number;
  acceptedDifferences: ChangeItem[];
}

export interface PushResult {
  sourceEnvironmentId: string;
  targetEnvironmentId: string;
  previousHead: string | null;
  newHead: string;
  appliedCommits: CommitNode[];
  verified: boolean;
}

export interface OfflineBundle {
  sourceEnvironmentId: string;
  fromCommitHash: string | null;
  toCommitHash: string;
  fileName: string;
  commits: CommitNode[];
  sql: string;
}

// ---- schema snapshot (only the fields the UI uses)

export interface ColumnModel {
  attnum: number;
  name: string;
  dataType: string;
  nullable: boolean;
  defaultValue: string | null;
  identityGeneration: string | null;
}

export interface TableModel {
  oid: number;
  schemaName: string;
  name: string;
  partitioned: boolean;
  partitionKeyDef: string | null;
  parentTable: string | null;
  partitionBoundExpr: string | null;
  columns: Record<string, ColumnModel>;
  constraints: Record<string, unknown>;
  indexes: Record<string, unknown>;
}

export interface ViewModel {
  schemaName: string;
  name: string;
  materialized: boolean;
}

export interface SequenceModel {
  schemaName: string;
  name: string;
  dataType: string;
}

export interface DatabaseSnapshot {
  timestamp: string;
  schemas: string[];
  tables: Record<string, TableModel>;
  views: Record<string, ViewModel>;
  sequences: Record<string, SequenceModel>;
}

export interface AboutInfo {
  name: string;
  version: string;
  license: string;
  licenseSpdx: string;
  licenseUrl: string;
  copyright: string;
  repository: string;
  /** full license text bundled into the jar (null in development without it) */
  licenseText: string | null;
}

export type DeletionMode = 'COMMIT' | 'BRANCH';

export interface DeletionPlan {
  hash: string;
  mode: DeletionMode;
  /** commits that would be deleted, newest first */
  commits: GraphCommit[];
  /** ids of the environments whose history tables contain them */
  environments: string[];
  /** names of environments whose current history uses them */
  blockedBy: string[];
  /** names of unreachable environments */
  offlineEnvironments: string[];
  deletable: boolean;
}

export interface DeletionResult {
  hash: string;
  mode: DeletionMode;
  deletedCommits: string[];
  perEnvironment: Record<string, number>;
}

/** One statement of a migration in execution order; changeId -1 = belongs to no single change. */
export interface ScriptStatement {
  changeId: number;
  sql: string;
}

/** Contents of a .pgsnap.json file. */
export interface SnapshotBundle {
  formatVersion: string;
  exportedAt: string;
  sourceEnvironmentName: string;
  sourceBranch: string;
  sourceCommitHash: string | null;
  sourceCommitMessage: string | null;
  schemaSnapshot: DatabaseSnapshot;
}

export interface SnapshotSource {
  formatVersion: string;
  exportedAt: string;
  sourceEnvironmentName: string;
  sourceBranch: string;
  sourceCommitHash: string | null;
  sourceCommitMessage: string | null;
  serverVersion: number;
}

export interface SnapshotComparison {
  environmentId: string;
  environmentName: string;
  targetHead: string | null;
  source: SnapshotSource;
  identical: boolean;
  missingOrModifiedItems: ChangeItem[];
  destructiveItems: ChangeItem[];
  upStatements: ScriptStatement[];
  downStatements: ScriptStatement[];
  upSql: string;
  downSql: string;
  warnings: string[];
  targetHasUncommittedChanges: boolean;
  serverVersion: number;
}
