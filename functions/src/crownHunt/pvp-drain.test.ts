/**
 * Unit test for the crownHuntPerks flag cache on the trap-drain hot path
 * (pvp-drain.ts). No emulator — `../firebase` and `../shared/featureFlags` are
 * mocked so the OFF path (early return, no Firestore access) can be exercised
 * in isolation.
 *
 * The point under test: processTrapDrains runs on every accepted live-position
 * sample, so it must NOT pay a fresh config/featureFlags read each time — the
 * gate is cached in-memory for a short TTL per warm instance.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';

const readFeatureFlagMock = vi.fn();

vi.mock('../firebase', () => ({ db: {}, adminRtdb: {} }));
vi.mock('../shared/featureFlags', () => ({
  readFeatureFlag: (...args: unknown[]) => readFeatureFlagMock(...args),
}));

import { processTrapDrains, __resetPerksFlagCacheForTest } from './pvp-drain';

const sample = (nowMs: number) => ({
  victimUid: 'victim',
  latitude: 59.3,
  longitude: 18.1,
  now: new Date(nowMs),
});

afterEach(() => {
  __resetPerksFlagCacheForTest();
  readFeatureFlagMock.mockReset();
});

describe('processTrapDrains flag cache', () => {
  it('reads the flag ONCE across many samples within the TTL (flag OFF)', async () => {
    readFeatureFlagMock.mockResolvedValue(false);
    const base = 1_000_000;
    for (let i = 0; i < 6; i += 1) {
      await processTrapDrains(sample(base + i * 5_000)); // 6 samples over 25s
    }
    // One underlying read despite six hot-path calls — the rest hit the cache.
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
  });

  it('OFF holds for the long 60s TTL', async () => {
    readFeatureFlagMock.mockResolvedValue(false);
    await processTrapDrains(sample(0));
    await processTrapDrains(sample(59_000)); // still inside the long TTL
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
    await processTrapDrains(sample(60_001)); // past the long TTL → fresh read
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(2);
  });

  it('ON re-reads after the short 5s TTL (fast kill-switch)', async () => {
    // Flag ON. crownHuntPerksEnabled resolves true, then runTrapDrains throws on
    // the mocked db and is swallowed by processTrapDrains — the flag read count
    // is unaffected, which is what this asserts.
    readFeatureFlagMock.mockResolvedValue(true);
    await processTrapDrains(sample(0));
    await processTrapDrains(sample(4_000)); // still inside the short TTL → cached
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
    await processTrapDrains(sample(5_001)); // past the short TTL → fresh read
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(2);
  });
});
