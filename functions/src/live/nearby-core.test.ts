import { describe, expect, it } from 'vitest';
import {
  DISCOVERY_TTL_MS,
  buildDiscoveryFields,
  discoveryExpiresAt,
  parseListNearbyInput,
} from './nearby-core';
import { geoCellKey } from '../incidents/incidents-core';

describe('nearby-core: parseListNearbyInput', () => {
  it('accepts a valid centre + optional radius', () => {
    const r = parseListNearbyInput({ latitude: 59.334, longitude: 18.063, radiusMeters: 5000 });
    expect(r.ok).toBe(true);
  });

  it('rejects out-of-range / missing coordinates and unknown keys', () => {
    expect(parseListNearbyInput({ latitude: 200, longitude: 0 }).ok).toBe(false);
    expect(parseListNearbyInput({ longitude: 0 }).ok).toBe(false);
    expect(parseListNearbyInput({ latitude: 0, longitude: 0, junk: 1 }).ok).toBe(false);
    expect(parseListNearbyInput(undefined).ok).toBe(false);
  });
});

describe('nearby-core: buildDiscoveryFields', () => {
  it('computes the SAME geoCell the incidents index uses (single source of truth)', () => {
    const fields = buildDiscoveryFields({
      uid: 'u1',
      latitude: 59.334,
      longitude: 18.063,
      displayName: 'Sebbe',
    });
    expect(fields).toMatchObject({
      uid: 'u1',
      latitude: 59.334,
      longitude: 18.063,
      displayName: 'Sebbe',
      status: 'active',
      geoCell: geoCellKey(59.334, 18.063),
    });
  });

  it('carries a null displayName through rather than dropping the field', () => {
    expect(buildDiscoveryFields({ uid: 'u', latitude: 0, longitude: 0, displayName: null }).displayName).toBeNull();
  });
});

describe('nearby-core: discoveryExpiresAt', () => {
  const now = new Date('2026-07-21T12:00:00.000Z');

  it('uses now + DISCOVERY_TTL when the session ends later', () => {
    const sessionEnd = new Date(now.getTime() + 4 * 3600_000).toISOString();
    expect(discoveryExpiresAt(sessionEnd, now).getTime()).toBe(now.getTime() + DISCOVERY_TTL_MS);
  });

  it('is clamped to the session end when that is sooner than the TTL', () => {
    const sessionEnd = new Date(now.getTime() + 60_000).toISOString(); // 1 min out
    expect(discoveryExpiresAt(sessionEnd, now).getTime()).toBe(Date.parse(sessionEnd));
  });

  it('degrades to the plain TTL on a missing/malformed session expiry (never non-expiring)', () => {
    expect(discoveryExpiresAt(null, now).getTime()).toBe(now.getTime() + DISCOVERY_TTL_MS);
    expect(discoveryExpiresAt('not-a-date', now).getTime()).toBe(now.getTime() + DISCOVERY_TTL_MS);
  });
});
