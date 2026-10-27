import { useEffect, useMemo, useRef, useState } from 'react';
import {
  BaseEdge,
  Handle,
  PanOnScrollMode,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { EnvironmentHead, GraphCommit } from '../api/types';
import { LANE_COLORS, layoutGraph } from '../lib/graphLayout';
import { firstLine, relativeTime, shortHash } from '../lib/format';
import { useI18n } from '../i18n';
import { EnvBadge } from './Badges';

const ROW_H = 38;
const LANE_W = 22;
export const WORKING_TREE_ID = '__working_tree__';

type CommitNodeData = {
  kind: 'commit' | 'wip';
  color: string;
  labelLeft: number;
  labelWidth: number;
  message: string;
  author: string;
  date: string;
  hash: string;
  selected: boolean;
  badges: { id: string; name: string; color: string; active: boolean }[];
};
type CommitFlowNode = Node<CommitNodeData, 'commit'>;

function CommitNodeView({ data }: NodeProps<CommitFlowNode>) {
  const wip = data.kind === 'wip';
  return (
    <div className="relative h-[14px] w-[14px] cursor-pointer">
      <Handle type="target" position={Position.Top} isConnectable={false} />
      {wip && <span className="absolute inset-0 animate-wip-ring rounded-full" style={{ background: data.color }} />}
      <span
        className={`absolute inset-0 rounded-full border-[3px] ${wip ? 'border-dashed' : ''}`}
        style={{
          borderColor: data.color,
          background: data.selected || wip ? data.color : '#0b0f19',
          boxShadow: data.selected ? `0 0 0 4px ${data.color}40` : undefined,
        }}
      />
      <Handle type="source" position={Position.Bottom} isConnectable={false} />
      <div
        className={`absolute top-[-9px] flex h-8 items-center gap-2 rounded-md px-2.5 ${
          data.selected ? 'bg-[#16263a]' : 'hover:bg-kraken-hover'
        }`}
        style={{ left: data.labelLeft, width: data.labelWidth, boxShadow: data.selected ? `inset 2px 0 0 ${data.color}` : undefined }}
      >
        {data.badges.map((b) => (
          <EnvBadge key={b.id} name={b.name} color={b.color} active={b.active} />
        ))}
        <span className={`min-w-0 flex-1 truncate ${wip ? 'font-medium italic text-amber-200' : 'text-gray-100'}`}>
          {data.message}
        </span>
        {data.author && <span className="max-w-[140px] shrink-0 truncate text-xs text-gray-400">{data.author}</span>}
        {data.date && <span className="w-[86px] shrink-0 text-right text-xs text-gray-500">{data.date}</span>}
        {data.hash && <span className="w-[58px] shrink-0 text-right font-mono text-xs text-gray-500">{data.hash}</span>}
      </div>
    </div>
  );
}

const nodeTypes = { commit: CommitNodeView };

/**
 * Git-style edge: the line stays in the child's lane and only bends into the parent's lane right above the parent,
 * so a branch visibly starts at its real parent commit (smoothstep would bend half-way and point at a wrong commit).
 */
function LaneEdgeView({ id, sourceX, sourceY, targetX, targetY, style }: EdgeProps) {
  let path: string;
  if (Math.abs(sourceX - targetX) < 1) {
    path = `M ${sourceX},${sourceY} L ${targetX},${targetY}`;
  } else {
    const bendStart = Math.max(sourceY, targetY - 22);
    const mid = (bendStart + targetY) / 2;
    path = `M ${sourceX},${sourceY} L ${sourceX},${bendStart} C ${sourceX},${mid} ${targetX},${mid} ${targetX},${targetY}`;
  }
  return <BaseEdge id={id} path={path} style={style} />;
}

const edgeTypes = { lane: LaneEdgeView };

interface Props {
  commits: GraphCommit[];
  environments: EnvironmentHead[];
  activeEnvId: string | null;
  showWorkingTree: boolean;
  workingTreeChanges: number;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export function CommitGraph({ commits, environments, activeEnvId, showWorkingTree, workingTreeChanges, selectedId, onSelect }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(900);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const observer = new ResizeObserver((entries) => setWidth(entries[0].contentRect.width));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const { t, tn, locale } = useI18n();
  const layout = useMemo(() => layoutGraph(commits), [commits]);

  const { nodes, edges } = useMemo(() => {
    const offset = showWorkingTree ? 1 : 0;
    const color = (lane: number) => LANE_COLORS[lane % LANE_COLORS.length];
    const labelLeft = (lane: number) => (layout.laneCount - lane) * LANE_W + 8;
    const labelWidth = (lane: number) => Math.max(360, width - 40 - lane * LANE_W - labelLeft(lane));
    const nodes: CommitFlowNode[] = [];
    const edges: Edge[] = [];
    const activeHead = environments.find((e) => e.id === activeEnvId)?.head ?? null;

    if (showWorkingTree) {
      const lane = activeHead ? layout.byHash.get(activeHead)?.lane ?? 0 : 0;
      nodes.push({
        id: WORKING_TREE_ID,
        type: 'commit',
        position: { x: lane * LANE_W, y: 0 },
        draggable: false,
        data: {
          kind: 'wip',
          color: '#f59e0b',
          labelLeft: labelLeft(lane),
          labelWidth: labelWidth(lane),
          message: `${t('graph.workingTree')} · ${tn('plural.changes', workingTreeChanges)}`,
          author: '',
          date: '',
          hash: '',
          selected: selectedId === WORKING_TREE_ID,
          badges: [],
        },
      });
      if (activeHead && layout.byHash.has(activeHead)) {
        edges.push({
          id: `wip->${activeHead}`,
          source: WORKING_TREE_ID,
          target: activeHead,
          type: 'lane',
          animated: true,
          style: { stroke: '#f59e0b', strokeWidth: 2 },
        });
      }
    }

    for (const r of layout.rows) {
      const c = r.commit;
      nodes.push({
        id: c.hash,
        type: 'commit',
        position: { x: r.lane * LANE_W, y: (r.row + offset) * ROW_H },
        draggable: false,
        data: {
          kind: 'commit',
          color: color(r.lane),
          labelLeft: labelLeft(r.lane),
          labelWidth: labelWidth(r.lane),
          message: firstLine(c.message),
          author: c.author,
          date: relativeTime(c.createdAt, locale, t('time.justNow')),
          hash: shortHash(c.hash),
          selected: selectedId === c.hash,
          badges: environments
            .filter((e) => e.head === c.hash)
            .map((e) => ({ id: e.id, name: e.name, color: e.colorBadge, active: e.id === activeEnvId })),
        },
      });
      if (c.parentHash && layout.byHash.has(c.parentHash)) {
        edges.push({
          id: `${c.hash}->${c.parentHash}`,
          source: c.hash,
          target: c.parentHash,
          type: 'lane',
          style: { stroke: color(r.lane), strokeWidth: 2 },
        });
      }
    }
    return { nodes, edges };
  }, [layout, environments, activeEnvId, showWorkingTree, workingTreeChanges, selectedId, width, t, tn, locale]);

  return (
    <div ref={wrapRef} className="absolute inset-0">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnScroll
        panOnScrollMode={PanOnScrollMode.Vertical}
        zoomOnScroll={false}
        zoomOnDoubleClick={false}
        minZoom={0.6}
        maxZoom={1.5}
        defaultViewport={{ x: 20, y: 24, zoom: 1 }}
        onNodeClick={(_event, node) => onSelect(node.id)}
      />
    </div>
  );
}
