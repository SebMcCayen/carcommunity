/**
 * Unit tests for the claimSpawn input boundary (crown-spawn-core.ts). No
 * emulators required.
 *
 * Focus: `accuracyMeters` is client-controlled and feeds both the geofence
 * buffer and the risk scorer, so it is bounded at the schema exactly as the
 * submitClaim path bounds it (crownhunt-core.ts). These tests pin that the two
 * claim paths agree — the drift they guard against is a bound added to one
 * path and forgotten on the other.
 */

import { describe, expect, it } from 'vitest';
import { parseClaimSpawnInput } from '../crownHunt/crown-spawn-core';
import { MAX_REPORTED_ACCURACY_METERS } from '../crownHunt/crownhunt-core';

/** Minimal payload that parses; individual tests override one field. */
const validClaim = {
  spawnId: 'spawn-abc123',
  latitude: 59.33,
  longitude: 18.07,
  recordedAt: '2026-07-04T12:00:00.000Z',
  previousFix: {
    latitude: 59.33,
    longitude: 18.07,
    recordedAt: '2026-07-04T11:59:00.000Z',
  },
  idempotencyKey: 'idem-1',
};

describe('parseClaimSpawnInput — accuracyMeters bounds', () => {
  it('accepts the unreported and ordinary cases', () => {
    expect(parseClaimSpawnInput(validClaim).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: null }).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: undefined }).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: 0 }).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: 40 }).ok).toBe(true);
  });

  it('accepts exactly the limit and rejects one over', () => {
    expect(
      parseClaimSpawnInput({ ...validClaim, accuracyMeters: MAX_REPORTED_ACCURACY_METERS }).ok,
    ).toBe(true);
    expect(
      parseClaimSpawnInput({ ...validClaim, accuracyMeters: MAX_REPORTED_ACCURACY_METERS + 1 }).ok,
    ).toBe(false);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: 50_000 }).ok).toBe(false);
  });

  it('rejects negative and non-finite accuracy', () => {
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: -1 }).ok).toBe(false);
    expect(parseClaimSpawnInput({ ...validClaim, accuracyMeters: Number.NaN }).ok).toBe(false);
    expect(
      parseClaimSpawnInput({ ...validClaim, accuracyMeters: Number.POSITIVE_INFINITY }).ok,
    ).toBe(false);
    expect(
      parseClaimSpawnInput({ ...validClaim, accuracyMeters: Number.NEGATIVE_INFINITY }).ok,
    ).toBe(false);
  });

  it('applies the same bounds to previousFix.accuracyMeters', () => {
    const withPrevAccuracy = (accuracyMeters: unknown) =>
      parseClaimSpawnInput({
        ...validClaim,
        previousFix: { ...validClaim.previousFix, accuracyMeters },
      }).ok;

    expect(withPrevAccuracy(null)).toBe(true);
    expect(withPrevAccuracy(40)).toBe(true);
    expect(withPrevAccuracy(MAX_REPORTED_ACCURACY_METERS)).toBe(true);
    expect(withPrevAccuracy(MAX_REPORTED_ACCURACY_METERS + 1)).toBe(false);
    expect(withPrevAccuracy(-1)).toBe(false);
    expect(withPrevAccuracy(Number.NaN)).toBe(false);
    expect(withPrevAccuracy(Number.POSITIVE_INFINITY)).toBe(false);
  });

  it('keeps the sibling speed field non-finite-safe', () => {
    expect(parseClaimSpawnInput({ ...validClaim, speedMetersPerSecond: 0 }).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...validClaim, speedMetersPerSecond: -1 }).ok).toBe(false);
    expect(parseClaimSpawnInput({ ...validClaim, speedMetersPerSecond: Number.NaN }).ok).toBe(false);
    expect(
      parseClaimSpawnInput({ ...validClaim, speedMetersPerSecond: Number.POSITIVE_INFINITY }).ok,
    ).toBe(false);
  });
});
