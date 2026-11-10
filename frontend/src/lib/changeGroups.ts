import type { ChangeItem, ChangeType } from '../api/types';

/** Changes of one entity: the tables of one base name in every schema and everything that belongs to them. */
export interface ChangeGroup {
  /** base name of the entity, e.g. "mnemo_schemes" (or the object name of a change that belongs to no table) */
  key: string;
  /** positions in the full change list, in list order */
  indexes: number[];
  /** schemas the changes of the group are in, in order of appearance */
  schemas: string[];
  /** number of changes per change type */
  counts: Partial<Record<ChangeType, number>>;
}

const IDENT = String.raw`(?:"(?:[^"]|"")+"|[A-Za-z_][A-Za-z0-9_$]*)`;
const QNAME = String.raw`${IDENT}(?:\s*\.\s*${IDENT})?`;
const REFERENCES = new RegExp(String.raw`\bREFERENCES\s+(?:ONLY\s+)?(${QNAME})`, 'gi');
const PARTITION_OF = new RegExp(String.raw`\bPARTITION\s+OF\s+(${QNAME})`, 'i');
const ATTACH = new RegExp(String.raw`^\s*ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(${QNAME})\s+(?:ATTACH|DETACH)\s+PARTITION\b`, 'im');
const RELATIONS = new RegExp(String.raw`\b(?:FROM|JOIN)\s+(?:ONLY\s+)?(${QNAME})`, 'gi');

/** Last part of a (possibly quoted) qualified name: scada."Mnemo" -> Mnemo, public.t -> t. */
export function baseName(qualified: string): string {
  const parts: string[] = [];
  const re = new RegExp(IDENT, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(qualified)) !== null) {
    const p = m[0];
    parts.push(p.startsWith('"') ? p.slice(1, -1).replace(/""/g, '"') : p.toLowerCase());
  }
  return parts.length ? parts[parts.length - 1] : qualified;
}

/** Schema of an object name schema.object[.part] (the first part). */
function schemaOf(objectName: string): string {
  const dot = objectName.indexOf('.');
  return dot < 0 ? '' : objectName.slice(0, dot);
}

function tableOf(c: ChangeItem): string | null {
  if (c.objectType === 'TABLE' || c.objectType === 'PARTITION') return c.objectName;
  if (c.rootName && (c.rootType === 'TABLE' || c.rootType === 'PARTITION' || c.rootType === undefined)) return c.rootName;
  return null;
}

/** Bases of the tables a SQL text references with REFERENCES / FROM / JOIN. */
function referenced(sql: string, re: RegExp): string[] {
  const out: string[] = [];
  const scan = new RegExp(re.source, re.flags);
  let m: RegExpExecArray | null;
  while ((m = scan.exec(sql)) !== null) out.push(baseName(m[1]));
  return out;
}

/** Longest known base the name contains as a whole word part ("seq_mnemo_schemes_id_seq" contains "mnemo_schemes"). */
function containedBase(name: string, bases: string[]): string | null {
  let best: string | null = null;
  for (const b of bases) {
    if (b.length === 0 || (best && best.length >= b.length)) continue;
    const at = name.indexOf(b);
    if (at < 0) continue;
    const before = at === 0 ? '' : name[at - 1];
    const after = name[at + b.length] ?? '';
    if ((before === '' || before === '_') && (after === '' || after === '_')) best = b;
  }
  return best;
}

/**
 * Groups the changes by entity ("📦 mnemo_schemes"): the tables of one base name in every schema (scada, mdc, public),
 * their partitions, columns, constraints, indexes, triggers and comments, their sequences (*_mnemo_schemes_*_seq),
 * the foreign keys of other tables that reference them, and the views of the same name or reading from them.
 * Everything else forms a group of its own. Groups keep the order of the list (first change of each group).
 */
export function groupChanges(changes: ChangeItem[]): ChangeGroup[] {
  // 1. entities: base names of the changed tables (partitions count for their parent)
  const bases = new Set<string>();
  const parentOf = new Map<string, string>();
  for (const c of changes) {
    if (c.objectType === 'PARTITION') {
      const sql = `${c.upSql ?? ''}\n${c.downSql ?? ''}`;
      const m = PARTITION_OF.exec(sql) ?? ATTACH.exec(sql);
      if (m) {
        parentOf.set(c.objectName, baseName(m[1]));
        bases.add(baseName(m[1]));
        continue;
      }
    }
    const table = tableOf(c);
    if (table) bases.add(baseName(table));
  }
  const baseList = [...bases];

  // 2. the entity of every change
  const keyOf = (c: ChangeItem): string => {
    const sql = `${c.upSql ?? ''}\n${c.downSql ?? ''}`;
    if (c.objectType === 'CONSTRAINT' || c.objectType === 'TABLE') {
      // a foreign key of another table belongs to the table it references
      const refs = referenced(sql, REFERENCES).filter((r) => bases.has(r));
      const own = tableOf(c);
      if (c.objectType === 'CONSTRAINT' && refs.length > 0 && (!own || !refs.includes(baseName(own)))) return refs[0];
    }
    if (c.objectType === 'PARTITION' && parentOf.has(c.objectName)) return parentOf.get(c.objectName)!;
    const table = tableOf(c);
    if (table) {
      const parent = parentOf.get(table);
      return parent ?? baseName(table);
    }
    const own = baseName(c.rootName ?? c.objectName);
    if (c.objectType === 'SEQUENCE') {
      const found = bases.has(own) ? own : containedBase(own, baseList);
      if (found) return found;
    }
    if (c.objectType === 'VIEW' || c.rootType === 'VIEW') {
      if (bases.has(own)) return own;
      const reads = referenced(sql, RELATIONS).find((r) => bases.has(r));
      if (reads) return reads;
    }
    if (c.objectType === 'TRIGGER' || c.objectType === 'COMMENT' || c.objectType === 'INDEX' || c.objectType === 'DATA') {
      const found = bases.has(own) ? own : null;
      if (found) return found;
    }
    return c.rootName ?? c.objectName;
  };

  const groups = new Map<string, ChangeGroup>();
  changes.forEach((c, i) => {
    const key = keyOf(c);
    let g = groups.get(key);
    if (!g) {
      g = { key, indexes: [], schemas: [], counts: {} };
      groups.set(key, g);
    }
    g.indexes.push(i);
    const schema = schemaOf(c.objectName);
    if (schema && !g.schemas.includes(schema)) g.schemas.push(schema);
    g.counts[c.changeType] = (g.counts[c.changeType] ?? 0) + 1;
  });
  return [...groups.values()];
}
