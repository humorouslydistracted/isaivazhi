import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/vitest.setup.js'],
    include: ['./tests/**/*.test.js'],
    fileParallelism: false,
    clearMocks: true,
    restoreMocks: true,
  },
});
