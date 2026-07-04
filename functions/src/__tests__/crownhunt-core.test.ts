/**
 * Unit tests for the Kronjakt pure logic (crownhunt-core.ts + the verbatim
 * geo/risk ports). No emulators required.
 */

import { describe, expect, it } from 'vitest';
import {
  haversineDistanceMeters,
  isPlausibleJump,
  isPositionFresh,
  isSpeedSafe,
  isValidCoordinate,
  isWithinGeofence,
} from '../crownHunt/crown-hunt-geo';
import { RISK_REVIEW_THRESHOLD, evaluateClaimRisk } from '../crownHunt/crown-hunt-risk';
import {
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

describe('crown-hunt-geo (verbatim legacy port)', () => {
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

  it('allows claims only at walking pace; missing speed is safe', () => {
    expect(isSpeedSafe(1.4)).toBe(true);
    expect(isSpeedSafe(1.5)).toBe(false);
    expect(isSpeedSafe(null)).toBe(true);
    expect(isSpeedSafe(undefined)).toBe(true);
    expect(isSpeedSafe(-1)).toBe(true); // invalid values treated as safe
  });

  it('buffers the geofence conservatively by half the GPS accuracy', () => {
    expect(isWithinGeofence(50, 50, null)).toBe(true);
    expect(isWithinGeofence(60, 50, 20)).toBe(true); // 50 + 20*0.5 = 60
    expect(isWithinGeofence(61, 50, 20)).toBe(false);
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
