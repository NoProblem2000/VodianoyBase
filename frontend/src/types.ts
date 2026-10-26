export interface EnvironmentConfig {
  id: string;
  name: string;
  color: string;
  jdbcUrl: string;
  username: string;
  password: string;
}

export interface EnvironmentStatus {
  id: string;
  name: string;
  color: string;
  reachable: boolean;
  installed: boolean;
  triggersInstalled: boolean;
  superuser: boolean;
  serverVersion: string | null;
  head: string | null;
  error: string | null;
}

export interface GraphCommit {
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
  environments: string[];
}

export interface Graph {
  commits: GraphCommit[];
  environments: EnvironmentStatus[];
}

export interface AuditEntry {
  id: number;
  eventType: string;
  commandTag: string;
  objectType: string;
  objectIdentity: string;
  rawQuery: string;
  dbUser: string;
  executedAt: string;
}

export interface WorkingTree {
  environmentId: string;
  head: string | null;
  hasChanges: boolean;
  upSql: string;
  downSql: string;
  summary: string[];
  warnings: string[];
  auditLog: AuditEntry[];
}

export interface CommitSummary {
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
}

export interface CommitDetails {
  environmentId: string;
  hash: string;
  parentHash: string | null;
  author: string;
  message: string;
  createdAt: string;
  upSql: string;
  downSql: string;
  ddlLogHistory: unknown;
  snapshot: unknown;
}

export interface CommitResult {
  commit: CommitSummary;
  summary: string[];
}

export interface PushResult {
  targetEnvironmentId: string;
  previousHead: string | null;
  newHead: string;
  appliedCommits: CommitSummary[];
}

export interface ConnectionTest {
  ok: boolean;
  serverVersion: string | null;
  superuser: boolean;
  message: string;
}
