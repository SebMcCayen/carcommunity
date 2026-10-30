import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    // Existing node:test scripts keep their own runner; this harness uses Vitest.
    include: ['src/**/*.test.ts', 'scripts/backfill-club-invitations.test.mjs'],
    exclude: ['src/**/*.emulator.test.ts'],
  },
});
