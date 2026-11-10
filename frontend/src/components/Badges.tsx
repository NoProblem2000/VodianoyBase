import type { ChangeType } from '../api/types';
import { useI18n } from '../i18n';
import { CHANGE_CLASSES, CHANGE_LETTER } from '../lib/changes';

/**
 * Environment HEAD badge (like a branch label in GitKraken). The active environment is filled and marked "● NAME";
 * a {@code switchable} badge (in the commit graph) tells that a double click switches to its environment - the graph
 * handles the double click itself ({@code data-env-id} names the environment).
 */
export function EnvBadge({
  name,
  color,
  active,
  envId,
  switchable,
}: {
  name: string;
  color: string;
  active?: boolean;
  envId?: string;
  switchable?: boolean;
}) {
  const { t } = useI18n();
  const canSwitch = !!switchable && !active;
  return (
    <span
      className={`inline-flex h-[18px] shrink-0 items-center gap-1 rounded px-1.5 text-[10.5px] font-bold uppercase tracking-wide ${
        canSwitch ? 'cursor-pointer select-none hover:brightness-125' : ''
      }`}
      style={
        active
          ? { background: color, color: '#0b0f19', boxShadow: `0 0 0 2px ${color}55` }
          : { background: `${color}26`, color, boxShadow: `inset 0 0 0 1px ${color}80` }
      }
      title={
        active
          ? t('graph.badgeActive', { name })
          : canSwitch
            ? `${t('graph.badge', { name })}\n${t('graph.badgeSwitch', { name: name.toUpperCase() })}`
            : t('graph.badge', { name })
      }
      data-env-badge={name}
      data-env-id={envId}
      data-active={active || undefined}
    >
      {active && <span aria-hidden="true">●</span>}
      {name}
    </span>
  );
}

/** A / M / R / D change indicator; the tooltip names the change in the UI language. */
export function ChangeBadge({ type }: { type: ChangeType }) {
  const { t } = useI18n();
  return (
    <span
      className={`inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded border font-mono text-[11px] font-bold ${CHANGE_CLASSES[type]}`}
      title={t(`changeType.${type}`)}
      aria-label={t(`changeType.${type}`)}
    >
      {CHANGE_LETTER[type]}
    </span>
  );
}
