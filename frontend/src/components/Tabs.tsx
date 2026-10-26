interface Props<T extends string> {
  tabs: { id: T; label: string; count?: number }[];
  active: T;
  onChange: (id: T) => void;
}

export function Tabs<T extends string>({ tabs, active, onChange }: Props<T>) {
  return (
    <div className="flex gap-1 border-b border-kraken-line px-3" role="tablist">
      {tabs.map((t) => (
        <button
          key={t.id}
          role="tab"
          aria-selected={t.id === active}
          onClick={() => onChange(t.id)}
          className={`-mb-px border-b-2 px-2.5 py-2 text-[12.5px] transition-colors ${
            t.id === active ? 'border-teal-400 text-gray-100' : 'border-transparent text-gray-400 hover:text-gray-200'
          }`}
        >
          {t.label}
          {t.count !== undefined && t.count > 0 && (
            <span className="ml-1.5 rounded-full bg-kraken-raised px-1.5 text-[10.5px] text-gray-300">{t.count}</span>
          )}
        </button>
      ))}
    </div>
  );
}
