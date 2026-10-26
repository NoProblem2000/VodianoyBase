import type { ChangeItem, ChangeType } from '../api/types';

export const CHANGE_LETTER: Record<ChangeType, string> = { ADDED: 'A', MODIFIED: 'M', RENAMED: 'R', DELETED: 'D' };

export const CHANGE_CLASSES: Record<ChangeType, string> = {
  ADDED: 'bg-change-added/15 text-change-added border-change-added/40',
  MODIFIED: 'bg-change-modified/15 text-change-modified border-change-modified/40',
  RENAMED: 'bg-change-renamed/15 text-change-renamed border-change-renamed/40',
  DELETED: 'bg-change-deleted/15 text-change-deleted border-change-deleted/40',
};

/**
 * Change indicator per relation (schema.name) for the Schema Explorer: the relation's own change, or "M" when only
 * its columns / constraints / indexes changed.
 */
export function relationIndicators(changes: ChangeItem[]): Map<string, ChangeType> {
  const result = new Map<string, ChangeType>();
  for (const c of changes) {
    if (c.objectType === 'TABLE' || c.objectType === 'PARTITION' || c.objectType === 'VIEW' || c.objectType === 'SEQUENCE') {
      result.set(c.objectName, c.changeType);
    }
  }
  for (const c of changes) {
    if (c.objectType === 'COLUMN' || c.objectType === 'CONSTRAINT') {
      const owner = c.objectName.split('.').slice(0, 2).join('.');
      if (!result.has(owner)) result.set(owner, 'MODIFIED');
    }
  }
  return result;
}
