import Editor from '@monaco-editor/react';

interface Props {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  language?: string;
}

export function SqlEditor({ value, onChange, readOnly = false, language = 'sql' }: Props) {
  return (
    <div className="editor-frame">
      <Editor
        height="100%"
        language={language}
        theme="pgvc-dark"
        value={value}
        onChange={(v) => onChange?.(v ?? '')}
        options={{
          readOnly,
          minimap: { enabled: false },
          fontSize: 13,
          fontFamily: "'Cascadia Code', 'JetBrains Mono', Consolas, 'Courier New', monospace",
          lineNumbersMinChars: 3,
          scrollBeyondLastLine: false,
          automaticLayout: true,
          renderLineHighlight: 'line',
          tabSize: 4,
          padding: { top: 10, bottom: 10 },
        }}
      />
    </div>
  );
}
