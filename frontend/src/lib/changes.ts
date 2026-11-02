import type { ChangeItem, ChangeType } from '../api/types';

export const CHANGE_LETTER: Record<ChangeType, string> = { ADDED: 'A', MODIFIED: 'M', RENAMED: 'R', DELETED: 'D' };

export const CHANGE_CLASSES: Record<ChangeType, string> = {
  ADDED: 'bg-change-added/15 text-change-added border-change-added/40',
  MODIFIED: 'bg-change-modified/15 text-change-modified border-change-modified/40',
  RENAMED: 'bg-change-renamed/15 text-change-renamed border-change-renamed/40',
  DELETED: 'bg-change-deleted/15 text-change-deleted border-change-deleted/40',
};

const TOP_LEVEL = new Set(['TABLE', 'PARTITION', 'VIEW', 'SEQUENCE', 'FUNCTION', 'PROCEDURE', 'TYPE', 'EXTENSION']);

/**
 * Change indicator per top-level object (schema.name, function key, extension name) for the Schema Explorer: the
 * object's own change, or "M" when only its columns / constraints / indexes / triggers / comments / grants changed.
 */
export function relationIndicators(changes: ChangeItem[]): Map<string, ChangeType> {
  const result = new Map<string, ChangeType>();
  for (const c of changes) {
    if (TOP_LEVEL.has(c.objectType) && (!c.rootName || c.rootName === c.objectName)) {
      if (!result.has(c.objectName) || c.changeType !== 'MODIFIED') result.set(c.objectName, c.changeType);
    }
  }
  for (const c of changes) {
    const owner = c.rootName ?? (c.objectType === 'COLUMN' || c.objectType === 'CONSTRAINT'
      ? c.objectName.split('.').slice(0, 2).join('.')
      : null);
    if (owner && owner !== c.objectName && !result.has(owner)) result.set(owner, 'MODIFIED');
  }
  return result;
}
