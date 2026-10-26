import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// `npm run dev` serves the UI on :5173 and proxies the API to the Spring Boot backend on :8765.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8765' },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
