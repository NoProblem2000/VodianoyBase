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

export interface PgVcStatus {
  environmentId: string;
  reachable: boolean;
  initialized: boolean;
  ddlTriggerEnabled: boolean;
  dropTriggerEnabled: boolean;
  head: string | null;
  commitCount: number;
  pendingDdlCount: number;
  error: string | null;
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url);
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new Error((data && data.message) || `${res.status} ${res.statusText}`);
  }
  return data as T;
}

export const api = {
  environments: () => get<EnvironmentView[]>('/api/environments'),
  status: (id: string) => get<PgVcStatus>(`/api/environments/${encodeURIComponent(id)}/pgvc/status`),
};
