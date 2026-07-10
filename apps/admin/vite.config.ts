import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  build: {
    // Do not emit source maps in production builds deployed to Firebase Hosting.
    sourcemap: false,
    rollupOptions: {
      output: {
        // Ensure hashed asset filenames for long-term caching.
        entryFileNames: 'assets/[name]-[hash].js',
        chunkFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
        // Split rarely-changing vendor code out of the app entry so its
        // hashed filename survives app-only deploys (better cache retention).
        //
        // Deliberately narrow: Firestore (and anything else only reachable
        // from lazy-loaded routes, e.g. @firebase/functions) must NOT be
        // grouped with the eagerly imported Firebase auth/app-check modules,
        // or it would be dragged back into the initial login-route load.
        // Unmatched modules keep the natural dynamic-import split.
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('firestore')) return undefined;
          if (
            /node_modules\/(react|react-dom|react-router|react-router-dom|scheduler)\//.test(id)
          ) {
            return 'vendor-react';
          }
          // Only the eager Firebase entrypoints (app, auth, app-check) and the
          // internal @firebase/* deps they share belong in the eager vendor
          // chunk. Matching the bare `firebase` umbrella package would also
          // catch lazy-only subpaths such as `firebase/functions`, dragging
          // them into the login-route load — so match its subpaths explicitly.
          // Firestore is already excluded above.
          if (
            /node_modules\/(firebase\/(app|auth|app-check)|@firebase\/(app|app-check|auth|util|component|logger))\//.test(
              id,
            )
          ) {
            return 'vendor-firebase-core';
          }
          return undefined;
        },
      },
    },
  },
});
