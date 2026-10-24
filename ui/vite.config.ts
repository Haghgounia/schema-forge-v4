import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Development proxy only. The Java application.yml default is server.port=9090.
// Accept a numeric loopback port override; never permit arbitrary proxy hosts or URLs.
const rawPort = process.env.SCHEMAFORGE_API_PORT ?? '9090';
const port = Number(rawPort);
if (!/^\d{1,5}$/.test(rawPort) || !Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('SCHEMAFORGE_API_PORT must be an integer from 1 to 65535');
}
const backendTarget = `http://127.0.0.1:${port}`;

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: backendTarget, changeOrigin: false },
      '/swagger-ui.html': { target: backendTarget, changeOrigin: false },
      '/swagger-ui': { target: backendTarget, changeOrigin: false },
      '/v3/api-docs': { target: backendTarget, changeOrigin: false },
    },
  },
  preview: { host: '127.0.0.1' },
  // Validate the eager import graph independently; large rendering-engine chunks are
  // allowed only when dynamically loaded by the diagram viewer. Raising Vite's
  // advisory threshold alone is not sufficient; scripts/check-lazy-build.mjs
  // enforces a strict initial-load budget and excludes Graphviz/Mermaid from it.
  build: { outDir: 'dist', sourcemap: false, manifest: true, chunkSizeWarningLimit: 1400 },
});
