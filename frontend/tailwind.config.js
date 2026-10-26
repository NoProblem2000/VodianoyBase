/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // GitKraken-like dark palette from the specification
        kraken: {
          bg: '#0b0f19',
          panel: '#111827',
          raised: '#1f2937',
          line: '#273244',
          hover: '#172133',
        },
        change: {
          added: '#22c55e',
          modified: '#eab308',
          renamed: '#3b82f6',
          deleted: '#ef4444',
        },
      },
      fontFamily: {
        // offline-friendly system stacks (no web fonts: isolated sites have no internet)
        sans: ['"Segoe UI Variable Text"', '"Segoe UI"', 'system-ui', '-apple-system', '"Helvetica Neue"', 'Arial', 'sans-serif'],
        mono: ['"Cascadia Code"', '"JetBrains Mono"', 'Consolas', '"Courier New"', 'monospace'],
      },
      keyframes: {
        'wip-ring': {
          '0%': { transform: 'scale(1)', opacity: '0.7' },
          '100%': { transform: 'scale(2.4)', opacity: '0' },
        },
      },
      animation: {
        'wip-ring': 'wip-ring 1.6s ease-out infinite',
      },
    },
  },
  plugins: [],
};
