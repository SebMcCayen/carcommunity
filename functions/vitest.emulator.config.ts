import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.emulator.test.ts'],
    testTimeout: 60_000,
    hookTimeout: 60_000,
    // The emulator suite shares one stateful Emulator Suite instance across
    // files. Run files sequentially: parallel workers hammer the Functions
    // emulator during its cold start, which makes trigger-propagation waits
    // (auth-onUserCreate) flaky on slow CI runners.
    fileParallelism: false,
  },
});
