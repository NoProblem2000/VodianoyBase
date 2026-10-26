import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import { loader } from '@monaco-editor/react';

// Monaco is bundled with the application instead of being loaded from a CDN: isolated sites have no internet.
(self as unknown as { MonacoEnvironment: { getWorker: (id: string, label: string) => Worker } }).MonacoEnvironment = {
  getWorker: (_id, label) => (label === 'json' ? new JsonWorker() : new EditorWorker()),
};

monaco.editor.defineTheme('kraken', {
  base: 'vs-dark',
  inherit: true,
  rules: [
    { token: 'keyword', foreground: '7dd3fc' },
    { token: 'comment', foreground: '6b7280', fontStyle: 'italic' },
    { token: 'string', foreground: 'fcd34d' },
    { token: 'number', foreground: 'c4b5fd' },
  ],
  colors: {
    'editor.background': '#0b0f19',
    'editor.lineHighlightBackground': '#111827',
    'editorLineNumber.foreground': '#374151',
    'editorLineNumber.activeForeground': '#9ca3af',
    'editorGutter.background': '#0b0f19',
    'editor.selectionBackground': '#1e3a5f',
    'editorWidget.background': '#111827',
  },
});

loader.config({ monaco });
