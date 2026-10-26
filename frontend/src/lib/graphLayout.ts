import type { GraphCommit } from '../api/types';

export const LANE_COLORS = ['#2dd4bf', '#a78bfa', '#f59e0b', '#f472b6', '#38bdf8', '#a3e635', '#fb7185'];

export interface LaidOutCommit {
  commit: GraphCommit;
  lane: number;
  /** 0 = newest (top of the graph) */
  row: number;
}

export interface GraphLayout {
  rows: LaidOutCommit[];
  laneCount: number;
  byHash: Map<string, LaidOutCommit>;
}

/**
 * Orders commits children-before-parents (newest first among independent commits) and assigns lanes the way
 * `git log --graph` does. Row 0 is drawn at the top, so history grows upwards.
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
      if (left === 0) ready.push(byHash.get(c.parentHash)!);
    }
  }
  for (const c of [...commits].sort(newestFirst)) {
    if (!emitted.has(c.hash)) order.push(c);
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
      if (j !== lane && lanes[j] === c.hash) lanes[j] = null;
    }
    lanes[lane] = c.parentHash && byHash.has(c.parentHash) ? c.parentHash : null;
    rows.push({ commit: c, lane, row });
  });

  const laneCount = rows.reduce((m, r) => Math.max(m, r.lane + 1), 1);
  return { rows, laneCount, byHash: new Map(rows.map((r) => [r.commit.hash, r])) };
}

/** Hashes of `head` and all its ancestors. */
export function ancestorsOf(commits: Map<string, GraphCommit>, head: string | null): Set<string> {
  const result = new Set<string>();
  let current = head;
  while (current && !result.has(current)) {
    result.add(current);
    current = commits.get(current)?.parentHash ?? null;
  }
  return result;
}
