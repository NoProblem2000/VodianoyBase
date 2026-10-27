import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Eye, Hash, Layers, Search, Table2 } from 'lucide-react';
import type { ChangeItem, ChangeType, DatabaseSnapshot, TableModel } from '../api/types';
import { relationIndicators } from '../lib/changes';
import { useI18n, type TKey } from '../i18n';
import { ChangeBadge } from './Badges';

interface Props {
  snapshot: DatabaseSnapshot | null;
  changes: ChangeItem[];
}

type GroupId = 'tables' | 'partitions' | 'views' | 'sequences';

interface Entry {
  key: string;
  schema: string;
  name: string;
  hint?: string;
  change?: ChangeType;
  deleted?: boolean;
  table?: TableModel;
}

const GROUPS: { id: GroupId; label: TKey; icon: typeof Table2 }[] = [
  { id: 'tables', label: 'explorer.tables', icon: Table2 },
  { id: 'partitions', label: 'explorer.partitions', icon: Layers },
  { id: 'views', label: 'explorer.views', icon: Eye },
  { id: 'sequences', label: 'explorer.sequences', icon: Hash },
];

export function SchemaExplorer({ snapshot, changes }: Props) {
  const { t, tn } = useI18n();
  const [filter, setFilter] = useState('');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [openTable, setOpenTable] = useState<string | null>(null);

  const groups = useMemo(() => {
    const indicators = relationIndicators(changes);
    const result: Record<GroupId, Entry[]> = { tables: [], partitions: [], views: [], sequences: [] };
    if (snapshot) {
      for (const [key, tbl] of Object.entries(snapshot.tables)) {
        const entry: Entry = { key, schema: tbl.schemaName, name: tbl.name, change: indicators.get(key), table: tbl };
        if (tbl.parentTable) {
          result.partitions.push({ ...entry, hint: `↳ ${tbl.parentTable.split('.').slice(1).join('.')}` });
        } else {
          result.tables.push({ ...entry, hint: tbl.partitioned ? t('explorer.partitioned') : undefined });
        }
      }
      for (const [key, v] of Object.entries(snapshot.views)) {
        result.views.push({ key, schema: v.schemaName, name: v.name, change: indicators.get(key), hint: v.materialized ? t('explorer.materialized') : undefined });
      }
      for (const [key, s] of Object.entries(snapshot.sequences)) {
        result.sequences.push({ key, schema: s.schemaName, name: s.name, change: indicators.get(key), hint: s.dataType });
      }
    }
    // objects deleted since HEAD are no longer in the live snapshot
    for (const c of changes) {
      if (c.changeType !== 'DELETED') continue;
      const [schema, ...rest] = c.objectName.split('.');
      const entry: Entry = { key: c.objectName, schema, name: rest.join('.'), change: 'DELETED', deleted: true };
      if (c.objectType === 'TABLE') result.tables.push(entry);
      if (c.objectType === 'PARTITION') result.partitions.push(entry);
      if (c.objectType === 'VIEW') result.views.push(entry);
      if (c.objectType === 'SEQUENCE') result.sequences.push(entry);
    }
    const needle = filter.trim().toLowerCase();
    for (const id of Object.keys(result) as GroupId[]) {
      result[id] = result[id]
        .filter((e) => !needle || e.key.toLowerCase().includes(needle))
        .sort((a, b) => a.key.localeCompare(b.key));
    }
    return result;
  }, [snapshot, changes, filter, t]);

  return (
    <section className="flex min-h-0 flex-1 flex-col" aria-label={t('explorer.title')}>
      <div className="flex items-center justify-between px-3 pb-2 pt-3">
        <h2 className="panel-title">{t('explorer.title')}</h2>
        {snapshot && <span className="text-[11px] text-gray-500">{tn('plural.schemas', snapshot.schemas.length)}</span>}
      </div>
      <div className="relative mx-3 mb-2">
        <Search size={13} className="pointer-events-none absolute left-2.5 top-2.5 text-gray-500" />
        <input
          className="input pl-8"
          placeholder={t('explorer.filter')}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label={t('explorer.filterLabel')}
        />
      </div>
      <div className="min-h-0 flex-1 overflow-auto px-1.5 pb-2">
        {!snapshot && <p className="px-2 py-1 text-gray-500">{t('explorer.noSchema')}</p>}
        {snapshot &&
          GROUPS.map(({ id, label, icon: Icon }) => {
            const entries = groups[id];
            const isCollapsed = collapsed[id] ?? false;
            return (
              <div key={id} className="mb-1">
                <button
                  className="flex w-full items-center gap-1.5 rounded px-1.5 py-1 text-left text-gray-300 hover:bg-kraken-hover"
                  onClick={() => setCollapsed({ ...collapsed, [id]: !isCollapsed })}
                >
                  {isCollapsed ? <ChevronRight size={13} /> : <ChevronDown size={13} />}
                  <Icon size={13} className="text-gray-500" />
                  <span className="font-medium">{t(label)}</span>
                  <span className="ml-auto text-[11px] text-gray-500">{entries.length}</span>
                </button>
                {!isCollapsed && (
                  <ul>
                    {entries.map((e) => (
                      <li key={e.key}>
                        <button
                          className="flex w-full items-center gap-2 rounded py-[3px] pl-7 pr-1.5 text-left hover:bg-kraken-hover"
                          onClick={() => e.table && setOpenTable(openTable === e.key ? null : e.key)}
                          title={e.key}
                        >
                          <span className={`min-w-0 flex-1 truncate ${e.deleted ? 'text-gray-500 line-through' : ''}`}>
                            <span className="text-gray-500">{e.schema}.</span>
                            <span className="text-gray-200">{e.name}</span>
                            {e.hint && <span className="ml-1.5 text-[11px] text-gray-500">{e.hint}</span>}
                          </span>
                          {e.change ? <ChangeBadge type={e.change} /> : <span className="w-[18px]" />}
                        </button>
                        {openTable === e.key && e.table && (
                          <ul className="mb-1 ml-9 border-l border-kraken-line pl-2">
                            {Object.values(e.table.columns)
                              .sort((a, b) => a.attnum - b.attnum)
                              .map((c) => (
                                <li key={c.name} className="flex gap-2 py-px text-[12px]">
                                  <span className="truncate text-gray-300">{c.name}</span>
                                  <span className="ml-auto shrink-0 font-mono text-[11px] text-gray-500">
                                    {c.dataType}
                                    {c.nullable ? '' : ` ${t('explorer.notNull')}`}
                                  </span>
                                </li>
                              ))}
                          </ul>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            );
          })}
      </div>
    </section>
  );
}
