import { defineConfig } from 'vite';

export default defineConfig({
  root: '.',
  build: {
    outDir: 'dist',
    emptyOutDir: false, // don't delete embeddings.json / metadata.json
    rollupOptions: {
      input: 'index.html',
    },
  },
  publicDir: false,    // we manage public assets manually
});
