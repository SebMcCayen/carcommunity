/** Separate QA entrypoint: production vite.config.ts and auth remain untouched. */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: [
      {
        find: /^@\/features\/partners(?:\/portal)?$/,
        replacement: resolve(__dirname, 'fixture-api.ts'),
      },
      {
        find: /^@\/components\/auth\/FirebaseAuthProvider$/,
        replacement: resolve(__dirname, 'fixture-auth.ts'),
      },
      { find: /^@\/lib\/auth$/, replacement: resolve(__dirname, 'fixture-auth.ts') },
      { find: '@', replacement: resolve(__dirname, '../src') },
    ],
  },
  server: { host: '127.0.0.1', port: 4317, strictPort: true },
  build: { rollupOptions: { input: resolve(__dirname, 'partners.html') } },
});
