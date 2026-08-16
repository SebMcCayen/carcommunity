/**
 * PURE unit test for the crownHuntPerks flag cache on the trap-drain hot path
 * (pvp-drain.ts). No emulator, no Firestore — `../firebase` and
 * `../shared/featureFlags` are mocked, the clock is injected via `nowMs`, and
 * the flag reader is a vi.fn(). This gives the asymmetric-TTL / cache-hit logic
 * coverage that does NOT depend on the emulator exercising it.
 *
 * crownHuntPerksEnabled is tested DIRECTLY (rather than only through
 * processTrapDrains) so the TTL semantics are pinned in isolation.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const readFeatureFlagMock = vi.fn();

vi.mock('../firebase', () => ({ db: {}, adminRtdb: {} }));
vi.mock('../shared/featureFlags', () => ({
  readFeatureFlag: (...args: unknown[]) => readFeatureFlagMock(...args),
}));

import { crownHuntPerksEnabled, __resetPerksFlagCacheForTest } from './pvp-drain';

const originalEmulatorEnv = process.env.FUNCTIONS_EMULATOR;

beforeEach(() => {
  // These cases pin the CACHE behaviour, which is bypassed under the emulator —
  // so ensure the emulator flag is OFF for the pure test regardless of the host.
  delete process.env.FUNCTIONS_EMULATOR;
});

afterEach(() => {
  __resetPerksFlagCacheForTest();
  readFeatureFlagMock.mockReset();
  if (originalEmulatorEnv === undefined) {
    delete process.env.FUNCTIONS_EMULATOR;
  } else {
    process.env.FUNCTIONS_EMULATOR = originalEmulatorEnv;
  }
});

describe('crownHuntPerksEnabled — asymmetric TTL cache', () => {
  it('serves many calls within the TTL from ONE underlying read', async () => {
    readFeatureFlagMock.mockResolvedValue(false);
    for (let i = 0; i < 6; i += 1) {
      await crownHuntPerksEnabled(1_000_000 + i * 5_000); // 6 calls over 25s
    }
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
  });

  it('OFF holds for the long 60s TTL', async () => {
    readFeatureFlagMock.mockResolvedValue(false);
    expect(await crownHuntPerksEnabled(0)).toBe(false);
    expect(await crownHuntPerksEnabled(59_000)).toBe(false); // inside the long TTL
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
    expect(await crownHuntPerksEnabled(60_001)).toBe(false); // past it → fresh read
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(2);
  });

  it('ON re-reads after the short 5s TTL (fast kill-switch)', async () => {
    readFeatureFlagMock.mockResolvedValue(true);
    expect(await crownHuntPerksEnabled(0)).toBe(true);
    expect(await crownHuntPerksEnabled(4_000)).toBe(true); // inside the short TTL
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(1);
    expect(await crownHuntPerksEnabled(5_001)).toBe(true); // past it → fresh read
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(2);
  });

  it('picks the TTL from the freshly-read VALUE, not the previous one', async () => {
    // First read ON (short TTL), then the flag flips OFF: the disable is seen at
    // the next read past the 5s ON-TTL, and thereafter holds for the long TTL.
    readFeatureFlagMock.mockResolvedValueOnce(true).mockResolvedValue(false);
    expect(await crownHuntPerksEnabled(0)).toBe(true); // read 1 (ON, 5s)
    expect(await crownHuntPerksEnabled(6_000)).toBe(false); // read 2 (OFF, 60s)
    expect(await crownHuntPerksEnabled(60_000)).toBe(false); // still cached OFF
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(2);
  });
});

describe('crownHuntPerksEnabled — emulator bypass', () => {
  it('reads fresh every call (no cache) when FUNCTIONS_EMULATOR is set', async () => {
    process.env.FUNCTIONS_EMULATOR = 'true';
    readFeatureFlagMock.mockResolvedValue(true);
    await crownHuntPerksEnabled(0);
    await crownHuntPerksEnabled(1); // would be cached in prod; here it re-reads
    await crownHuntPerksEnabled(2);
    expect(readFeatureFlagMock).toHaveBeenCalledTimes(3);
  });
});
