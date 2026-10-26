import type { ChangeType } from '../api/types';
import { CHANGE_CLASSES, CHANGE_LETTER } from '../lib/changes';

/** Environment HEAD badge (like a branch label in GitKraken). */
export function EnvBadge({ name, color, active }: { name: string; color: string; active?: boolean }) {
  return (
    <span
      className="inline-flex h-[18px] shrink-0 items-center rounded px-1.5 text-[10.5px] font-bold uppercase tracking-wide"
      style={
        active
          ? { background: color, color: '#0b0f19' }
          : { background: `${color}26`, color, boxShadow: `inset 0 0 0 1px ${color}80` }
      }
      title={active ? `${name} (active environment) is at this commit` : `${name} is at this commit`}
    >
      {name}
    </span>
  );
}

/** A / M / R / D change indicator. */
export function ChangeBadge({ type }: { type: ChangeType }) {
  return (
    <span
      className={`inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded border font-mono text-[11px] font-bold ${CHANGE_CLASSES[type]}`}
      title={type.toLowerCase()}
    >
      {CHANGE_LETTER[type]}
    </span>
  );
}
