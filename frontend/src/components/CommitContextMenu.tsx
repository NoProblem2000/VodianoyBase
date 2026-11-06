import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { Camera, ClipboardCopy, FileCode2, GitBranchPlus, Hash, RotateCcw, Trash2, Undo2 } from 'lucide-react';
import type { GraphCommit } from '../api/types';
import { firstLine, shortHash } from '../lib/format';
import { useI18n } from '../i18n';

export interface CommitMenuActions {
  /** null when the action is available; otherwise why it is not (shown as a tooltip, the item is disabled) */
  revertBlocked: string | null;
  rollbackBlocked: string | null;
  deleteBlocked: string | null;
  onRevert: () => void;
  onRollback: () => void;
  onCreateBranch: () => void;
  onExportSnapshot: () => void;
  onExportSql: () => void;
  onCopyHash: () => void;
  onCopyMessage: () => void;
  onDelete: () => void;
}

interface Props {
  commit: GraphCommit;
  /** viewport coordinates of the right click */
  x: number;
  y: number;
  actions: CommitMenuActions;
  onClose: () => void;
}

interface Item {
  id: string;
  icon: ReactNode;
  label: string;
  run: () => void;
  blocked?: string | null;
  danger?: boolean;
}

const MARGIN = 8;

/**
 * Right-click menu of a commit in the graph (instead of the browser's menu), dark GitKraken style: stays inside the
 * window, closes on a click outside, Escape, scrolling or resizing; arrow keys move between the items.
 */
export function CommitContextMenu({ commit, x, y, actions, onClose }: Props) {
  const { t } = useI18n();
  const menu = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: x, top: y });

  const groups: Item[][] = [
    [
      { id: 'revert', icon: <Undo2 size={14} />, label: t('ctx.revert'), run: actions.onRevert, blocked: actions.revertBlocked },
      { id: 'rollback', icon: <RotateCcw size={14} />, label: t('ctx.rollback'), run: actions.onRollback, blocked: actions.rollbackBlocked },
      { id: 'branch', icon: <GitBranchPlus size={14} />, label: t('ctx.branch'), run: actions.onCreateBranch },
    ],
    [
      { id: 'snapshot', icon: <Camera size={14} />, label: t('ctx.snapshot'), run: actions.onExportSnapshot },
      { id: 'sql', icon: <FileCode2 size={14} />, label: t('ctx.sql'), run: actions.onExportSql },
    ],
    [
      { id: 'hash', icon: <Hash size={14} />, label: t('ctx.copyHash'), run: actions.onCopyHash },
      { id: 'message', icon: <ClipboardCopy size={14} />, label: t('ctx.copyMessage'), run: actions.onCopyMessage },
    ],
    [{ id: 'delete', icon: <Trash2 size={14} />, label: t('ctx.delete'), run: actions.onDelete, blocked: actions.deleteBlocked, danger: true }],
  ];

  // keep the menu inside the window (open to the left / upwards near the edges)
  useLayoutEffect(() => {
    const el = menu.current;
    if (!el) return;
    const { width, height } = el.getBoundingClientRect();
    setPosition({
      left: Math.max(MARGIN, Math.min(x, window.innerWidth - width - MARGIN)),
      top: Math.max(MARGIN, y + height > window.innerHeight - MARGIN ? y - height : y),
    });
  }, [x, y]);

  useEffect(() => {
    const onPointer = (e: MouseEvent) => {
      if (!menu.current?.contains(e.target as globalThis.Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    const close = () => onClose();
    window.addEventListener('mousedown', onPointer, true);
    window.addEventListener('keydown', onKey, true);
    window.addEventListener('resize', close);
    window.addEventListener('blur', close);
    window.addEventListener('wheel', close, { passive: true });
    menu.current?.querySelector<HTMLButtonElement>('button:not([disabled])')?.focus();
    return () => {
      window.removeEventListener('mousedown', onPointer, true);
      window.removeEventListener('keydown', onKey, true);
      window.removeEventListener('resize', close);
      window.removeEventListener('blur', close);
      window.removeEventListener('wheel', close);
    };
  }, [onClose]);

  function moveFocus(step: number) {
    const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not([disabled])') ?? []);
    if (buttons.length === 0) return;
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
    buttons[(at + step + buttons.length) % buttons.length].focus();
  }

  return (
    <div
      ref={menu}
      role="menu"
      aria-label={t('ctx.title', { hash: shortHash(commit.hash) })}
      className="fixed z-[60] min-w-[260px] max-w-[340px] overflow-hidden rounded-lg border border-[#374151] bg-[#111827] py-1 text-[13px] text-gray-200 shadow-[0_12px_32px_rgba(0,0,0,0.55)]"
      style={{ left: position.left, top: position.top }}
      onContextMenu={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
      onKeyDown={(e) => {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          moveFocus(1);
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          moveFocus(-1);
        }
      }}
    >
      <div className="border-b border-[#374151] px-3 pb-1.5 pt-1 text-[11px] text-gray-500">
        <span className="font-mono text-gray-400">{shortHash(commit.hash)}</span>
        <span className="ml-1.5 inline-block max-w-[230px] truncate align-bottom">{firstLine(commit.message)}</span>
      </div>
      {groups.map((group, g) => (
        <div key={g} className={g > 0 ? 'mt-1 border-t border-[#374151] pt-1' : ''} role="group">
          {group.map((item) => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              className={`flex w-full items-center gap-2.5 px-3 py-[7px] text-left outline-none hover:bg-[#1f2937] focus-visible:bg-[#1f2937] disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:bg-transparent ${
                item.danger ? 'text-red-400 hover:text-red-300' : ''
              }`}
              disabled={!!item.blocked}
              title={item.blocked ?? undefined}
              onClick={() => {
                onClose();
                item.run();
              }}
            >
              <span className={`shrink-0 ${item.danger ? 'text-red-400' : 'text-gray-400'}`}>{item.icon}</span>
              <span className="truncate">{item.label}</span>
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}
