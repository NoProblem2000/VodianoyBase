import type {
  CommitDetails,
  CommitResult,
  ConnectionTest,
  EnvironmentConfig,
  EnvironmentStatus,
  Graph,
  PushResult,
  WorkingTree,
} from './types';

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  let data: unknown = undefined;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: text };
    }
  }
  if (!res.ok) {
    const message = (data as { error?: string } | undefined)?.error;
    throw new Error(message || `${res.status} ${res.statusText}`);
  }
  return data as T;
}

const enc = encodeURIComponent;

export const api = {
  environments: () => request<EnvironmentConfig[]>('GET', '/api/environments'),
  createEnvironment: (env: Partial<EnvironmentConfig>) => request<EnvironmentConfig>('POST', '/api/environments', env),
  updateEnvironment: (id: string, env: Partial<EnvironmentConfig>) =>
    request<EnvironmentConfig>('PUT', `/api/environments/${enc(id)}`, env),
  deleteEnvironment: (id: string) => request<void>('DELETE', `/api/environments/${enc(id)}`),
  testConnection: (env: Partial<EnvironmentConfig>) => request<ConnectionTest>('POST', '/api/environments/test', env),
  status: (id: string) => request<EnvironmentStatus>('GET', `/api/environments/${enc(id)}/status`),
  install: (id: string) => request<EnvironmentStatus>('POST', `/api/environments/${enc(id)}/install`),

  graph: () => request<Graph>('GET', '/api/graph'),
  commit: (envId: string, hash: string) =>
    request<CommitDetails>('GET', `/api/environments/${enc(envId)}/commits/${enc(hash)}`),

  workingTree: (envId: string) => request<WorkingTree>('GET', `/api/environments/${enc(envId)}/working-tree`),
  commitWorkingTree: (
    envId: string,
    body: { message: string; author: string; upSql: string; downSql: string; expectedHead: string | null },
  ) => request<CommitResult>('POST', `/api/environments/${enc(envId)}/commit`, body),

  executeAndCommit: (
    envId: string,
    body: { message: string; author: string; upSql: string; downSql: string; allowDirty: boolean },
  ) => request<CommitResult>('POST', `/api/environments/${enc(envId)}/execute`, body),

  push: (targetId: string, body: { sourceEnvironmentId: string; commitHash: string; allowDirty: boolean }) =>
    request<PushResult>('POST', `/api/environments/${enc(targetId)}/push`, body),

  bundleUrl: (sourceId: string, fromHash: string | null, toHash: string) =>
    `/api/bundle?source=${enc(sourceId)}&to=${enc(toHash)}` + (fromHash ? `&from=${enc(fromHash)}` : ''),
};
