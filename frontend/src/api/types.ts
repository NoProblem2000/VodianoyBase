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
export type ObjectType =
  | 'SCHEMA'
  | 'TABLE'
  | 'PARTITION'
  | 'COLUMN'
  | 'CONSTRAINT'
  | 'INDEX'
  | 'VIEW'
  | 'SEQUENCE'
  | 'FUNCTION'
  | 'PROCEDURE'
  | 'TRIGGER'
  | 'TYPE'
  | 'EXTENSION'
  | 'COMMENT'
  | 'DATA';

export interface ChangeItem {
  changeType: ChangeType;
  objectType: ObjectType;
  objectName: string;
  /** top-level object the change belongs to (a column belongs to its table) */
  rootType?: ObjectType;
  rootName?: string;
  details: string;
  upSql: string;
  downSql: string;
  /** snapshot compare only: position of the change, referenced by ScriptStatement.changeId */
  id?: number;
  /** snapshot compare only: the object exists only in this database, not in the reference snapshot */
  extraInTargetDb?: boolean;
  /** warnings that apply to this change */
  warnings?: string[];
  /** Migration Safety & Lock Analyzer result for upSql on the target database */
  safety?: SafetyReport;
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
  /** Mode B: how the steps went */
  steps?: StepReport[];
  elapsedMs?: number;
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

/**
 * What linking would leave behind. {@code differences} is written from the point of view of the target: its UP SQL
 * turns the live target into the commit (ADD COLUMN for what the target lacks) — exactly what Discard runs after a
 * link with accepted differences.
 */
export interface LinkPreview {
  sourceEnvironmentId: string;
  targetEnvironmentId: string;
  commit: CommitNode;
  identical: boolean;
  targetInitialized: boolean;
  differences: SchemaDiffResult;
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
  triggers?: Record<string, TriggerModel>;
  comment?: string | null;
  /** rows of a reference-data table (dictionary) */
  referenceData?: { rowCount: number; truncated: boolean; columns: string[] } | null;
}

export interface TriggerModel {
  name: string;
  definition: string;
  enabled: string;
  functionKey: string;
}

export interface FunctionModel {
  schemaName: string;
  name: string;
  identityArguments: string;
  /** f function, p procedure, w window function */
  kind: string;
  resultType: string | null;
  language: string;
}

export interface EnumTypeModel {
  schemaName: string;
  name: string;
  values: string[];
}

export interface ExtensionModel {
  name: string;
  schemaName: string;
  version: string;
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
  /** format 2 (v1.6.0+) */
  functions?: Record<string, FunctionModel>;
  enumTypes?: Record<string, EnumTypeModel>;
  extensions?: Record<string, ExtensionModel>;
}

// ---- reference data, Git folder export

export interface ReferenceDataTable {
  key: string;
  schemaName: string;
  tableName: string;
  tracked: boolean;
  hasPrimaryKey: boolean;
  estimatedRows: number;
  rowCount: number | null;
  overLimit: boolean;
  eligible: boolean;
  columns: number;
  trackedBy: string | null;
  trackedAt: string | null;
  maxRows: number;
}

export interface GitExportResult {
  envId: string;
  commitHash: string | null;
  targetPath: string;
  written: string[];
  unchanged: number;
  deleted: string[];
  files: number;
}

// ---- ignore rules, N-database matrix

export interface DiffIgnoreRules {
  ignoreComments: boolean;
  ignoreTablespaces: boolean;
  ignoreOwnersAndGrants: boolean;
  ignorePartitions: boolean;
  /** glob (tmp_%, bak_*) or regex (re:^tmp_\d+$, /_old$/) */
  excludedObjectPatterns: string[];
}

export type MatrixCellStatus = 'IDENTICAL' | 'MODIFIED' | 'MISSING' | 'EXTRA_ONLY_HERE' | 'ABSENT' | 'UNAVAILABLE';

export interface MatrixCell {
  status: MatrixCellStatus;
  differences: string[];
  /** brings this object in this database to the state of the baseline */
  upSql: string;
  downSql: string;
  warnings: string[];
}

export interface MatrixRow {
  type: ObjectType;
  name: string;
  differs: boolean;
  /** by database id */
  cells: Record<string, MatrixCell>;
}

export interface MatrixDatabase {
  /** env:<id> or snapshot:<index> */
  id: string;
  name: string;
  kind: 'ENVIRONMENT' | 'SNAPSHOT';
  baseline: boolean;
  description: string | null;
  error: string | null;
  serverVersion: number;
  identical: number;
  modified: number;
  missing: number;
  extra: number;
}

export interface MatrixComparison {
  baselineId: string;
  databases: MatrixDatabase[];
  rows: MatrixRow[];
  ignoreRules: DiffIgnoreRules;
}

export interface MatrixSnapshotUpload {
  name: string;
  /** the .pgsnap.json exactly as read from the file (sent unchanged) */
  text: string;
}

// ---- discard uncommitted changes

export interface DiscardPlan {
  envId: string;
  /** null: no commits yet, the database returns to an empty schema */
  head: string | null;
  full: boolean;
  totalChanges: number;
  changes: ChangeItem[];
  downSql: string;
  safety: SqlSafetyReport;
  auditEntries: number;
}

export interface DiscardResult {
  envId: string;
  head: string | null;
  full: boolean;
  discardedChanges: number;
  remainingChanges: number;
  executedSql: string;
  auditEntriesRemoved: number;
  verified: boolean;
}

export interface ClearLogResult {
  envId: string;
  removed: number;
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

export type RiskLevel = 'SAFE' | 'WARNING' | 'DANGEROUS';

export interface SqlAlternative {
  /** exact statement text in the script */
  statement: string;
  replacement: string;
}

export interface SafetyReport {
  riskLevel: RiskLevel;
  /** PostgreSQL lock mode, e.g. "ACCESS EXCLUSIVE"; absent for new objects */
  lockType?: string;
  causesTableRewrite: boolean;
  causesDataLoss: boolean;
  longRunning: boolean;
  nonTransactional: boolean;
  table?: string;
  estimatedRows?: number;
  totalSizeBytes?: number;
  prettySize?: string;
  estimatedSeconds?: number;
  estimatedDurationHint?: string;
  notes: string[];
  zeroDowntimeAlternativeSql?: string;
  alternatives?: SqlAlternative[];
}

export interface StatementSafety {
  index: number;
  step: number;
  sql: string;
  report: SafetyReport;
}

export interface SqlSafetyReport {
  overall: SafetyReport;
  statements: StatementSafety[];
  steps: number;
  multiStep: boolean;
}

export interface StepReport {
  index: number;
  transactional: boolean;
  statements: number;
  elapsedMs: number;
}

export interface Timeouts {
  lockTimeout: string;
  statementTimeout: string;
}

export interface DryRunResult {
  success: boolean;
  elapsedMs: number;
  steps: StepReport[];
  totalSteps: number;
  failedStep: number | null;
  error: string | null;
  changes: ChangeItem[];
  generatedDownSql: string | null;
  rewrittenStatements: string[];
  uncommittedChanges: number;
  safety: SqlSafetyReport;
  timeouts: Timeouts;
}

/** Execution settings shared by Mode B and snapshot apply. */
export interface ExecutionOptions {
  lockTimeout: string;
  statementTimeout: string;
  preScript: string;
  postScript: string;
}
