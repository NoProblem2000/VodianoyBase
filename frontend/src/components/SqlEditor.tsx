import Editor from '@monaco-editor/react';

interface Props {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  language?: 'pgsql' | 'json';
  /** CSS height; the editor fills its container by default */
  height?: string;
}

export function SqlEditor({ value, onChange, readOnly = false, language = 'pgsql', height = '100%' }: Props) {
  return (
    <Editor
      height={height}
      language={language}
      theme="kraken"
      value={value}
      onChange={(v) => onChange?.(v ?? '')}
      loading={<div className="p-3 text-gray-500">Loading editor…</div>}
      options={{
        readOnly,
        minimap: { enabled: false },
        fontSize: 12.5,
        fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, 'Courier New', monospace",
        lineNumbersMinChars: 3,
        scrollBeyondLastLine: false,
        automaticLayout: true,
        tabSize: 4,
        padding: { top: 8, bottom: 8 },
        renderLineHighlight: 'line',
      }}
    />
  );
}
