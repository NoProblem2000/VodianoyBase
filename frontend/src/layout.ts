import type { GraphCommit } from './types';

export const LANE_COLORS = ['#2dd4bf', '#a78bfa', '#fbbf24', '#fb7185', '#38bdf8', '#a3e635', '#f472b6'];

export interface LaidOutCommit {
  commit: GraphCommit;
  lane: number;
  row: number;
}

export interface GraphLayout {
  rows: LaidOutCommit[];
  laneCount: number;
  byHash: Map<string, LaidOutCommit>;
}

/**
 * Orders commits children-before-parents (newest first among independent commits) and assigns each one a lane,
 * the way git log --graph does: a commit continues the lane that was waiting for it, new branch tips open a lane.
 */
export function layoutGraph(commits: GraphCommit[]): GraphLayout {
  const byHash = new Map(commits.map((c) => [c.hash, c]));
  const pendingChildren = new Map<string, number>();
  for (const c of commits) {
    if (c.parentHash && byHash.has(c.parentHash)) {
      pendingChildren.set(c.parentHash, (pendingChildren.get(c.parentHash) ?? 0) + 1);
    }
  }
  const newestFirst = (a: GraphCommit, b: GraphCommit) =>
    new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();

  const ready = commits.filter((c) => !pendingChildren.get(c.hash));
  const order: GraphCommit[] = [];
  const emitted = new Set<string>();
  while (ready.length > 0) {
    ready.sort(newestFirst);
    const c = ready.shift()!;
    order.push(c);
    emitted.add(c.hash);
    if (c.parentHash && byHash.has(c.parentHash)) {
      const left = (pendingChildren.get(c.parentHash) ?? 1) - 1;
      pendingChildren.set(c.parentHash, left);
      if (left === 0) {
        ready.push(byHash.get(c.parentHash)!);
      }
    }
  }
  for (const c of [...commits].sort(newestFirst)) {
    if (!emitted.has(c.hash)) {
      order.push(c);
    }
  }

  const lanes: (string | null)[] = [];
  const rows: LaidOutCommit[] = [];
  order.forEach((c, row) => {
    let lane = lanes.indexOf(c.hash);
    if (lane === -1) {
      lane = lanes.indexOf(null);
      if (lane === -1) {
        lane = lanes.length;
        lanes.push(null);
      }
    }
    for (let j = 0; j < lanes.length; j++) {
      if (j !== lane && lanes[j] === c.hash) {
        lanes[j] = null;
      }
    }
    lanes[lane] = c.parentHash && byHash.has(c.parentHash) ? c.parentHash : null;
    rows.push({ commit: c, lane, row });
  });

  const laneCount = rows.reduce((m, r) => Math.max(m, r.lane + 1), 1);
  return { rows, laneCount, byHash: new Map(rows.map((r) => [r.commit.hash, r])) };
}

/** Commits from `older` to `newer` (both inclusive, newest first) when one is an ancestor of the other. */
export function ancestryRange(
  commits: Map<string, GraphCommit>,
  a: string,
  b: string,
): { from: string; to: string; hashes: string[] } | null {
  const walk = (tip: string, target: string): string[] | null => {
    const path: string[] = [];
    let cur: string | null = tip;
    const seen = new Set<string>();
    while (cur && !seen.has(cur)) {
      seen.add(cur);
      path.push(cur);
      if (cur === target) {
        return path;
      }
      cur = commits.get(cur)?.parentHash ?? null;
    }
    return null;
  };
  const down = walk(b, a);
  if (down) {
    return { from: a, to: b, hashes: down };
  }
  const up = walk(a, b);
  if (up) {
    return { from: b, to: a, hashes: up };
  }
  return null;
}

export function shortHash(hash: string | null | undefined): string {
  return hash ? hash.slice(0, 8) : '—';
}

export function formatTime(iso: string): string {
  const d = new Date(iso);
  const now = Date.now();
  const diff = (now - d.getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} h ago`;
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
