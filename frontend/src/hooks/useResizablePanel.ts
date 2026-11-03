import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { loadSetting, saveSetting } from '../lib/storage';

const MIN_WIDTH = 400;
/** room left for the schema explorer (280px) and a usable commit graph */
const RESERVED = 280 + 340;
const STEP = 32;

/** 36% of the window, at least 480px and at most 760px. */
function defaultWidth(viewport: number): number {
  return Math.round(Math.min(760, Math.max(480, viewport * 0.36)));
}

function clamp(width: number, viewport: number): number {
  return Math.round(Math.max(MIN_WIDTH, Math.min(width, Math.max(MIN_WIDTH, viewport - RESERVED))));
}

/**
 * Width of the right-hand panel, changed by dragging its left edge (or with the arrow keys on the handle) and kept in
 * localStorage. Double-click on the handle restores the default width.
 */
export function useResizablePanel(storageKey: string) {
  const [viewport, setViewport] = useState(() => window.innerWidth);
  const [stored, setStored] = useState<number | null>(() => {
    const n = Number(loadSetting(storageKey, ''));
    return Number.isFinite(n) && n > 0 ? n : null;
  });
  const dragging = useRef(false);

  useEffect(() => {
    const onResize = () => setViewport(window.innerWidth);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const width = clamp(stored ?? defaultWidth(viewport), viewport);

  const set = useCallback(
    (next: number | null) => {
      setStored(next);
      saveSetting(storageKey, next === null ? '' : String(next));
    },
    [storageKey],
  );

  const handleProps = {
    role: 'separator' as const,
    'aria-orientation': 'vertical' as const,
    'aria-valuenow': width,
    'aria-valuemin': MIN_WIDTH,
    'aria-valuemax': Math.max(MIN_WIDTH, viewport - RESERVED),
    tabIndex: 0,
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      dragging.current = true;
      e.currentTarget.setPointerCapture(e.pointerId);
      e.preventDefault();
    },
    onPointerMove: (e: PointerEvent<HTMLElement>) => {
      if (!dragging.current) return;
      setStored(clamp(window.innerWidth - e.clientX, window.innerWidth));
    },
    onPointerUp: (e: PointerEvent<HTMLElement>) => {
      if (!dragging.current) return;
      dragging.current = false;
      e.currentTarget.releasePointerCapture(e.pointerId);
      set(clamp(window.innerWidth - e.clientX, window.innerWidth));
    },
    onDoubleClick: () => set(null),
    onKeyDown: (e: KeyboardEvent<HTMLElement>) => {
      if (e.key === 'ArrowLeft') set(clamp(width + STEP, viewport));
      else if (e.key === 'ArrowRight') set(clamp(width - STEP, viewport));
      else return;
      e.preventDefault();
    },
  };

  return { width, handleProps };
}
