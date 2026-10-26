import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { loader } from '@monaco-editor/react';

// Bundle Monaco locally (no CDN): the app must work on isolated networks.
(self as unknown as { MonacoEnvironment: { getWorker: () => Worker } }).MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

monaco.editor.defineTheme('pgvc-dark', {
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
    'scrollbarSlider.background': '#1f293799',
  },
});

loader.config({ monaco });
