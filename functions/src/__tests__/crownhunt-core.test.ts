/**
 * Unit tests for the Kronjakt pure logic (crownhunt-core.ts + the geo/risk
 * modules ported from the legacy service). No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  MAX_EFFECTIVE_GEOFENCE_MULTIPLIER,
  MAX_GEOFENCE_ACCURACY_METERS,
  effectiveGeofenceRadiusMeters,
  haversineDistanceMeters,
  isPlausibleJump,
  isPositionFresh,
  isSpeedSafe,
  isValidCoordinate,
  isWithinGeofence,
} from '../crownHunt/crown-hunt-geo';
import { RISK_REVIEW_THRESHOLD, evaluateClaimRisk } from '../crownHunt/crown-hunt-risk';
import {
  MAX_REPORTED_ACCURACY_METERS,
  claimLedgerIdempotencyKey,
  getClaimMessage,
  guardPointFields,
  isPointCurrentlyAvailable,
  parseActivatePointInput,
  parseCreatePointInput,
  parseSubmitClaimInput,
  repeatRuleWindowStart,
  scopeClaimIdempotencyKey,
  startOfUtcDay,
  startOfUtcWeek,
} from '../crownHunt/crownhunt-core';

const NOW = new Date('2026-07-04T12:00:00Z');

describe('crown-hunt-geo (legacy port + deliberate safety deviations)', () => {
  it('validates WGS-84 coordinates', () => {
    expect(isValidCoordinate(59.33, 18.07)).toBe(true);
    expect(isValidCoordinate(91, 0)).toBe(false);
    expect(isValidCoordinate(0, 181)).toBe(false);
    expect(isValidCoordinate(Number.NaN, 0)).toBe(false);
  });

  it('rejects positions older than 60 seconds or from the future', () => {
    const nowMs = NOW.getTime();
    expect(isPositionFresh(new Date(nowMs - 30_000).toISOString(), nowMs)).toBe(true);
    expect(isPositionFresh(new Date(nowMs - 61_000).toISOString(), nowMs)).toBe(false);
    expect(isPositionFresh(new Date(nowMs + 5_000).toISOString(), nowMs)).toBe(false);
    expect(isPositionFresh('not-a-date', nowMs)).toBe(false);
  });

  it('allows claims only at walking pace; missing speed is safe, invalid is NOT', () => {
    expect(isSpeedSafe(1.4)).toBe(true);
    expect(isSpeedSafe(1.5)).toBe(false);
    expect(isSpeedSafe(null)).toBe(true);
    expect(isSpeedSafe(undefined)).toBe(true);
    // Deliberate deviation from legacy: a negative/non-finite speed is
    // client-controlled input and must not bypass the safety gate.
    expect(isSpeedSafe(-1)).toBe(false);
    expect(isSpeedSafe(Number.NaN)).toBe(false);
    expect(isSpeedSafe(Number.POSITIVE_INFINITY)).toBe(false);
  });

  it('buffers the geofence conservatively by half the GPS accuracy', () => {
    expect(isWithinGeofence(50, 50, null)).toBe(true);
    expect(isWithinGeofence(60, 50, 20)).toBe(true); // 50 + 20*0.5 = 60
    expect(isWithinGeofence(61, 50, 20)).toBe(false);
  });

  // Regression: `accuracyMeters` is client-supplied and used to be unbounded,
  // so a claim reporting a huge accuracy inflated a 75 m fence into kilometres
  // and let a member farm crowns from home. These cases FAIL on the unpatched
  // geofence (effectiveRadius = radius + accuracy * 0.5, uncapped).
  it('never lets a client-reported accuracy inflate the geofence', () => {
    const radius = 75;
    const fiveKm = 5_000;
    for (const accuracy of [
      50_000,
      1e9,
      Number.POSITIVE_INFINITY,
      Number.NaN,
      -1,
      Number.MAX_SAFE_INTEGER,
    ]) {
      expect(isWithinGeofence(fiveKm, radius, accuracy)).toBe(false);
    }
    // Even a modest overshoot beyond the cap is rejected.
    expect(isWithinGeofence(radius * MAX_EFFECTIVE_GEOFENCE_MULTIPLIER + 1, radius, 50_000)).toBe(
      false,
    );
  });

  it('caps the effective radius at the documented bound for every input', () => {
    for (const radius of [20, 50, 75, 100, 150]) {
      for (const accuracy of [
        null,
        undefined,
        0,
        -5,
        10,
        40,
        60,
        100,
        5_000,
        1e9,
        Number.POSITIVE_INFINITY,
        Number.NaN,
      ]) {
        const effective = effectiveGeofenceRadiusMeters(radius, accuracy);
        expect(effective).toBeGreaterThanOrEqual(radius);
        expect(effective).toBeLessThanOrEqual(
          Math.min(radius + MAX_GEOFENCE_ACCURACY_METERS * 0.5, radius * MAX_EFFECTIVE_GEOFENCE_MULTIPLIER),
        );
      }
    }
  });

  it('rejects the claim when the point has no usable geofence radius', () => {
    // submitClaim reads `point.geofenceRadiusMeters` off a Firestore document
    // behind a bare `as number` cast, so a legacy or corrupt point can hand
    // this a non-numeric radius. The KDoc promises that path fails CLOSED —
    // NaN must propagate to a reject, never to a pass.
    for (const radius of [
      Number.NaN,
      undefined as unknown as number,
      null as unknown as number,
      '75' as unknown as number,
      0,
      -50,
      Number.POSITIVE_INFINITY,
    ]) {
      // Distance 0 is the dangerous case: a spoofer knows the point's exact
      // coordinates, so a fence that collapses to 0 is still satisfiable.
      expect(isWithinGeofence(0, radius, null)).toBe(false);
      expect(isWithinGeofence(0, radius, 40)).toBe(false);
      expect(isWithinGeofence(1e9, radius, 50_000)).toBe(false);
      expect(Number.isNaN(effectiveGeofenceRadiusMeters(radius, 40))).toBe(true);
    }
  });

  it('still gives an honest poor-but-plausible fix its full buffer', () => {
    // 40 m accuracy at a 75 m point → 75 + 20 = 95 m, unchanged by the caps.
    expect(effectiveGeofenceRadiusMeters(75, 40)).toBe(95);
    expect(isWithinGeofence(95, 75, 40)).toBe(true);
    expect(isWithinGeofence(95.1, 75, 40)).toBe(false);
    // A 100 m accuracy (the clamp boundary) still buffers fully at a 150 m point.
    expect(effectiveGeofenceRadiusMeters(150, 100)).toBe(200);
    expect(isWithinGeofence(200, 150, 100)).toBe(true);
  });

  it('flags physically impossible jumps but allows fast driving', () => {
    const tenMinAgo = new Date(NOW.getTime() - 600_000).toISOString();
    // Stockholm → Uppsala (~65 km) in 10 min ≈ 108 m/s — implausible? 130 max: plausible.
    expect(isPlausibleJump(59.3293, 18.0686, tenMinAgo, 59.8586, 17.6389, NOW.getTime())).toBe(
      true,
    );
    // Same distance in 60 s ≈ 1000+ m/s — impossible.
    const oneMinAgo = new Date(NOW.getTime() - 60_000).toISOString();
    expect(isPlausibleJump(59.3293, 18.0686, oneMinAgo, 59.8586, 17.6389, NOW.getTime())).toBe(
      false,
    );
    expect(haversineDistanceMeters(59.3293, 18.0686, 59.3293, 18.0686)).toBe(0);
  });
});

describe('crown-hunt-risk (verbatim legacy port)', () => {
  const baseSignals = {
    positionStale: false,
    poorAccuracy: false,
    impossibleJump: false,
    duplicateIdempotencyKey: false,
    attemptsInLastMinute: 0,
    successfulClaimsInVelocityWindow: 0,
    geofenceEdgeAttempts: 0,
    accuracyMeters: 10,
    platformIntegrityPassed: null,
  };

  it('scores a clean claim as zero risk', () => {
    const clean = evaluateClaimRisk(baseSignals);
    expect(clean.riskScore).toBe(0);
    expect(clean.isHighRisk).toBe(false);
    expect(clean.riskReasons).toEqual([]);
  });

  it('combines signals and trips the review threshold at 60', () => {
    // impossible jump (40) + poor accuracy (10) = 50 — below threshold.
    const medium = evaluateClaimRisk({ ...baseSignals, impossibleJump: true, poorAccuracy: true });
    expect(medium.riskScore).toBe(50);
    expect(medium.isHighRisk).toBe(false);

    // + excessive attempts (25) = 75 — review.
    const high = evaluateClaimRisk({
      ...baseSignals,
      impossibleJump: true,
      poorAccuracy: true,
      attemptsInLastMinute: 4,
    });
    expect(high.riskScore).toBe(75);
    expect(high.isHighRisk).toBe(true);
    expect(high.riskReasons).toContain('impossible_jump');
    expect(high.riskScore).toBeGreaterThanOrEqual(RISK_REVIEW_THRESHOLD);
  });

  it('treats a platform-integrity failure as a strong signal', () => {
    const failed = evaluateClaimRisk({
      ...baseSignals,
      platformIntegrityPassed: false,
      poorAccuracy: true,
    });
    expect(failed.riskScore).toBe(50);
    expect(failed.riskReasons).toContain('platform_integrity_failed');
  });
});

describe('crownhunt-core inputs and helpers', () => {
  const validClaim = {
    pointId: 'p1',
    latitude: 59.33,
    longitude: 18.07,
    recordedAt: '2026-07-04T11:59:30.000Z',
    idempotencyKey: 'press-1',
  };

  it('parses claim input strictly', () => {
    expect(parseSubmitClaimInput(validClaim).ok).toBe(true);
    expect(parseSubmitClaimInput({ ...validClaim, extra: 1 }).ok).toBe(false);
    expect(parseSubmitClaimInput({ ...validClaim, recordedAt: 'yesterday' }).ok).toBe(false);
    expect(parseSubmitClaimInput({ ...validClaim, pointId: 'a/b' }).ok).toBe(false);
    // Negative speeds are rejected at the schema — the safety gate cannot be
    // bypassed with invalid input.
    expect(parseSubmitClaimInput({ ...validClaim, speedMetersPerSecond: -1 }).ok).toBe(false);
    expect(parseSubmitClaimInput({ ...validClaim, speedMetersPerSecond: 0 }).ok).toBe(true);
    // Accuracy is bounded at the input boundary too: it feeds the geofence
    // buffer, so non-finite/negative/absurd values are invalid-argument.
    expect(parseSubmitClaimInput({ ...validClaim, accuracyMeters: 40 }).ok).toBe(true);
    expect(parseSubmitClaimInput({ ...validClaim, accuracyMeters: null }).ok).toBe(true);
    expect(parseSubmitClaimInput({ ...validClaim, accuracyMeters: -1 }).ok).toBe(false);
    expect(parseSubmitClaimInput({ ...validClaim, accuracyMeters: Number.NaN }).ok).toBe(false);
    expect(
      parseSubmitClaimInput({ ...validClaim, accuracyMeters: Number.POSITIVE_INFINITY }).ok,
    ).toBe(false);
    expect(parseSubmitClaimInput({ ...validClaim, accuracyMeters: 50_000 }).ok).toBe(false);
    expect(
      parseSubmitClaimInput({ ...validClaim, accuracyMeters: MAX_REPORTED_ACCURACY_METERS }).ok,
    ).toBe(true);
  });

  it('validates point fields per the legacy limits', () => {
    const valid = {
      title: 'Kronan vid torget',
      latitude: 59.33,
      longitude: 18.07,
      geofenceRadiusMeters: 50,
      rewardPoints: 25,
      repeatRule: 'once',
    };
    expect(parseCreatePointInput(valid).ok).toBe(true);
    expect(parseCreatePointInput({ ...valid, geofenceRadiusMeters: 19 }).ok).toBe(false);
    expect(parseCreatePointInput({ ...valid, geofenceRadiusMeters: 151 }).ok).toBe(false);
    expect(parseCreatePointInput({ ...valid, rewardPoints: 0 }).ok).toBe(false);
    expect(parseCreatePointInput({ ...valid, rewardPoints: 1001 }).ok).toBe(false);
    expect(parseCreatePointInput({ ...valid, title: 'x'.repeat(101) }).ok).toBe(false);
    // Title is optional — a Crown point is just a collectable on the map.
    expect(parseCreatePointInput({ ...valid, title: undefined }).ok).toBe(true);
    expect(parseCreatePointInput({ ...valid, title: '' }).ok).toBe(true);
    expect(parseCreatePointInput({ ...valid, repeatRule: 'hourly' }).ok).toBe(false);
    expect(
      guardPointFields({
        latitude: 59.33,
        longitude: 18.07,
        availableFrom: '2026-07-05T00:00:00Z',
        availableUntil: '2026-07-04T00:00:00Z',
      }).ok,
    ).toBe(false);
  });

  it('requires the safety confirmation and approval note to activate', () => {
    expect(
      parseActivatePointInput({
        pointId: 'p1',
        safeLocationConfirmed: true,
        approvalNote: 'Trygg parkeringsficka.',
      }).ok,
    ).toBe(true);
    expect(
      parseActivatePointInput({ pointId: 'p1', safeLocationConfirmed: false, approvalNote: 'ok!' })
        .ok,
    ).toBe(false);
    expect(
      parseActivatePointInput({ pointId: 'p1', safeLocationConfirmed: true, approvalNote: 'ab' })
        .ok,
    ).toBe(false);
  });

  it('derives deterministic Firestore-safe idempotency keys', () => {
    const a = scopeClaimIdempotencyKey('user-1', 'press-1');
    expect(a).toMatch(/^[a-f0-9]{64}$/);
    expect(scopeClaimIdempotencyKey('user-1', 'press-1')).toBe(a);
    expect(scopeClaimIdempotencyKey('user-2', 'press-1')).not.toBe(a);
    expect(claimLedgerIdempotencyKey(a)).toBe(`crown-hunt-claim_${a}`);
    expect(claimLedgerIdempotencyKey(a)).toMatch(/^[A-Za-z0-9._-]+$/);
  });

  it('computes availability and repeat windows (UTC)', () => {
    expect(
      isPointCurrentlyAvailable(
        { availableFrom: new Date('2026-07-01T00:00:00Z'), availableUntil: null },
        NOW,
      ),
    ).toBe(true);
    expect(
      isPointCurrentlyAvailable({ availableFrom: new Date('2026-08-01T00:00:00Z') }, NOW),
    ).toBe(false);
    expect(startOfUtcDay(NOW).toISOString()).toBe('2026-07-04T00:00:00.000Z');
    // 2026-07-04 is a Saturday → ISO week starts Monday 2026-06-29.
    expect(startOfUtcWeek(NOW).toISOString()).toBe('2026-06-29T00:00:00.000Z');
    expect(repeatRuleWindowStart('once', NOW)).toBeNull();
    expect(repeatRuleWindowStart('daily', NOW)).toEqual(startOfUtcDay(NOW));
    expect(repeatRuleWindowStart('weekly', NOW)).toEqual(startOfUtcWeek(NOW));
  });

  it('has a Swedish message for every result code', () => {
    expect(getClaimMessage('awarded')).toContain('Kronpoäng');
    expect(getClaimMessage('moving_too_fast')).toContain('Stanna säkert');
    expect(getClaimMessage('not_eligible')).toContain('medlemskap');
  });
});
