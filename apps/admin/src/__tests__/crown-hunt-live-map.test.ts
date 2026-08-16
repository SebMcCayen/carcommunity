import { describe, it, expect, vi } from 'vitest';

// Stub the firebase seams the barrel imports; these tests exercise the PURE
// mappers + feature builders only (no live listener is opened).
vi.mock('../lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  collection: (_db: unknown, path: string) => ({ path }),
  doc: (_db: unknown, path: string, id?: string) => ({ path, id }),
  getDoc: vi.fn(),
  getDocs: vi.fn(),
  onSnapshot: vi.fn(),
  query: (t: unknown, ...c: unknown[]) => ({ t, c }),
  where: (f: string, op: string, v: unknown) => ({ f, op, v }),
  orderBy: (f: string, d: string) => ({ f, d }),
  limit: (n: number) => ({ n }),
}));

import { isLiveNow, toLiveCrownSpawn, toLiveTrap } from '@/features/crown-hunt';
import { crownsToFeatures, trapsToFeatures } from '@/components/map/LiveGameMap';

/** A minimal Firestore Timestamp-like value (only toMillis is consulted). */
const ts = (ms: number) => ({ toMillis: () => ms, toDate: () => new Date(ms) });

describe('toLiveCrownSpawn', () => {
  it('maps latitude/longitude/rarity/reward and expiry to millis', () => {
    const c = toLiveCrownSpawn('c1', {
      latitude: 57.5,
      longitude: 12.1,
      rarity: 'epic',
      rewardPoints: 100,
      status: 'live',
      expiresAt: ts(1000),
    });
    expect(c).toMatchObject({
      id: 'c1',
      latitude: 57.5,
      longitude: 12.1,
      rarity: 'epic',
      rewardPoints: 100,
      expiresAtMs: 1000,
    });
  });

  it('nulls out non-finite coordinates and missing fields', () => {
    const c = toLiveCrownSpawn('c2', { latitude: 'x', rarity: 42 });
    expect(c.latitude).toBeNull();
    expect(c.longitude).toBeNull();
    expect(c.rarity).toBeNull();
    expect(c.rewardPoints).toBeNull();
    expect(c.expiresAtMs).toBeNull();
  });
});

describe('toLiveTrap', () => {
  it('reads coordinates from lat/lng and defaults victimCount to 0', () => {
    const trap = toLiveTrap('t1', { lat: 57.6, lng: 12.2, expiresAt: ts(2000) });
    expect(trap).toMatchObject({ id: 't1', latitude: 57.6, longitude: 12.2, victimCount: 0, expiresAtMs: 2000 });
  });

  it('keeps a finite victimCount', () => {
    const trap = toLiveTrap('t2', { lat: 1, lng: 2, victimCount: 4, expiresAt: ts(5) });
    expect(trap.victimCount).toBe(4);
  });
});

describe('isLiveNow', () => {
  const base = { latitude: 1, longitude: 2 };
  it('is false without a coordinate', () => {
    expect(isLiveNow({ ...base, latitude: null, expiresAtMs: null }, 0)).toBe(false);
  });
  it('is true when not yet expired', () => {
    expect(isLiveNow({ ...base, expiresAtMs: 100 }, 50)).toBe(true);
  });
  it('is false once expired', () => {
    expect(isLiveNow({ ...base, expiresAtMs: 100 }, 200)).toBe(false);
  });
  it('treats a missing expiry as live (does not hide the record)', () => {
    expect(isLiveNow({ ...base, expiresAtMs: null }, 200)).toBe(true);
  });
});

describe('feature builders', () => {
  it('crownsToFeatures emits [lon, lat] points and drops non-finite coords', () => {
    const fc = crownsToFeatures([
      { id: 'a', latitude: 57.5, longitude: 12.1, rarity: 'rare', rewardPoints: 25, expiresAtMs: null },
      // Non-finite coord → dropped.
      { id: 'b', latitude: Number.NaN, longitude: 12.1, rarity: null, rewardPoints: null, expiresAtMs: null },
    ]);
    expect(fc.features).toHaveLength(1);
    const [feat] = fc.features;
    expect(feat).toBeDefined();
    expect(feat?.geometry.coordinates).toEqual([12.1, 57.5]);
    expect(feat?.properties.id).toBe('a');
  });

  it('trapsToFeatures emits [lon, lat] points with the victim count', () => {
    const fc = trapsToFeatures([
      { id: 't', latitude: 57.6, longitude: 12.2, victimCount: 3, expiresAtMs: null },
    ]);
    expect(fc.features).toHaveLength(1);
    const [feat] = fc.features;
    expect(feat).toBeDefined();
    expect(feat?.geometry.coordinates).toEqual([12.2, 57.6]);
    expect(feat?.properties.victimCount).toBe(3);
  });
});
