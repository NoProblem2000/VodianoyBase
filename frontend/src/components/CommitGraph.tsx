import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Handle,
  PanOnScrollMode,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { EnvironmentStatus, GraphCommit } from '../types';
import { LANE_COLORS, formatTime, layoutGraph, shortHash } from '../layout';
import { EnvBadge } from './EnvBadge';

const ROW_H = 34;
const LANE_W = 20;
export const WIP_ID = '__working_tree__';

type GraphNodeData = {
  kind: 'commit' | 'wip';
  color: string;
  labelLeft: number;
  labelWidth: number;
  title: string;
  meta: string;
  time: string;
  hash: string;
  selected: boolean;
  inRange: boolean;
  badges: { id: string; name: string; color: string; active: boolean; title: string }[];
};
type GraphNode = Node<GraphNodeData, 'commit'>;

function CommitNode({ data }: NodeProps<GraphNode>) {
  const cls = ['gnode', data.kind, data.selected ? 'selected' : '', data.inRange ? 'in-range' : '']
    .filter(Boolean)
    .join(' ');
  return (
    <div className={cls} style={{ ['--lane' as string]: data.color }}>
      <Handle type="target" position={Position.Top} className="ghandle" isConnectable={false} />
      <span className="gdot" />
      <Handle type="source" position={Position.Bottom} className="ghandle" isConnectable={false} />
      <div className="glabel" style={{ left: data.labelLeft, width: data.labelWidth }}>
        {data.badges.map((b) => (
          <EnvBadge key={b.id} name={b.name} color={b.color} active={b.active} title={b.title} />
        ))}
        <span className="gmsg">{data.title}</span>
        <span className="gmeta">{data.meta}</span>
        {data.time && <span className="gtime">{data.time}</span>}
        {data.hash && <span className="ghash">{data.hash}</span>}
      </div>
    </div>
  );
}

const nodeTypes = { commit: CommitNode };

interface Props {
  commits: GraphCommit[];
  environments: EnvironmentStatus[];
  activeEnvId: string | null;
  showWorkingTree: boolean;
  workingTreeCount: number;
  selectedId: string | null;
  rangeHashes: Set<string>;
  onSelect: (id: string, extend: boolean) => void;
}

export function CommitGraph({
  commits,
  environments,
  activeEnvId,
  showWorkingTree,
  workingTreeCount,
  selectedId,
  rangeHashes,
  onSelect,
}: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(900);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const layout = useMemo(() => layoutGraph(commits), [commits]);

  const { nodes, edges } = useMemo(() => {
    const offset = showWorkingTree ? 1 : 0;
    const labelLeftFor = (lane: number) => (layout.laneCount - lane) * LANE_W + 6;
    const labelWidthFor = (lane: number) => Math.max(320, width - 24 - (lane * LANE_W + labelLeftFor(lane)));
    const color = (lane: number) => LANE_COLORS[lane % LANE_COLORS.length];

    const nodes: GraphNode[] = [];
    const edges: Edge[] = [];
    const activeHead = environments.find((e) => e.id === activeEnvId)?.head ?? null;

    if (showWorkingTree) {
      const lane = activeHead ? layout.byHash.get(activeHead)?.lane ?? 0 : 0;
      nodes.push({
        id: WIP_ID,
        type: 'commit',
        position: { x: lane * LANE_W, y: 0 },
        draggable: false,
        data: {
          kind: 'wip',
          color: color(lane),
          labelLeft: labelLeftFor(lane),
          labelWidth: labelWidthFor(lane),
          title: 'Working Tree (Uncommitted Changes)',
          meta: `${workingTreeCount} change${workingTreeCount === 1 ? '' : 's'}`,
          time: '',
          hash: '',
          selected: selectedId === WIP_ID,
          inRange: false,
          badges: [],
        },
      });
      if (activeHead && layout.byHash.has(activeHead)) {
        edges.push({
          id: `${WIP_ID}->${activeHead}`,
          source: WIP_ID,
          target: activeHead,
          type: 'smoothstep',
          animated: true,
          style: { stroke: color(lane), strokeWidth: 2, strokeDasharray: '4 4' },
        });
      }
    }

    for (const r of layout.rows) {
      const c = r.commit;
      const badges = environments
        .filter((e) => e.head === c.hash)
        .map((e) => ({
          id: e.id,
          name: e.name,
          color: e.color,
          active: e.id === activeEnvId,
          title: `${e.name} is at this commit${e.id === activeEnvId ? ' (active environment)' : ''}`,
        }));
      nodes.push({
        id: c.hash,
        type: 'commit',
        position: { x: r.lane * LANE_W, y: (r.row + offset) * ROW_H },
        draggable: false,
        data: {
          kind: 'commit',
          color: color(r.lane),
          labelLeft: labelLeftFor(r.lane),
          labelWidth: labelWidthFor(r.lane),
          title: c.message.split('\n')[0],
          meta: c.author,
          time: formatTime(c.createdAt),
          hash: shortHash(c.hash),
          selected: selectedId === c.hash,
          inRange: rangeHashes.has(c.hash),
          badges,
        },
      });
      if (c.parentHash && layout.byHash.has(c.parentHash)) {
        edges.push({
          id: `${c.hash}->${c.parentHash}`,
          source: c.hash,
          target: c.parentHash,
          type: 'smoothstep',
          style: { stroke: color(r.lane), strokeWidth: 2 },
        });
      }
    }
    return { nodes, edges };
  }, [layout, environments, activeEnvId, showWorkingTree, workingTreeCount, selectedId, rangeHashes, width]);

  return (
    <div className="graph-wrap" ref={wrapRef}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnScroll
        panOnScrollMode={PanOnScrollMode.Vertical}
        zoomOnScroll={false}
        zoomOnDoubleClick={false}
        minZoom={0.6}
        maxZoom={1.5}
        defaultViewport={{ x: 18, y: 22, zoom: 1 }}
        onNodeClick={(event, node) => onSelect(node.id, event.shiftKey)}
      />
    </div>
  );
}
