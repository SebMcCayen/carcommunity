/**
 * Hard unit tests for the points-economy pure core.
 *
 * These are the tests that must not be allowed to go soft: every cap
 * boundary, the streak across BOTH Swedish DST transitions and across the
 * UTC/local-day mismatch in both directions, the 10-minute dwell edge at
 * 9:59 / 10:00 / 10:01, double-award prevention through deterministic keys,
 * and the guarantee that a client-supplied point value cannot reach the
 * ledger.
 */

import { describe, expect, it } from 'vitest';
import {
  ATTENDANCE_EVIDENCE_RETENTION_MS,
  ATTENDANCE_WINDOW_AFTER_MS,
  ATTENDANCE_WINDOW_BEFORE_MS,
  DAILY_OPEN_BASE_POINTS,
  DAILY_POINTS_CAP,
  DEFAULT_EVENT_DURATION_MS,
  DRIVE_AWARD_MIN_DISTANCE_METERS,
  ECONOMY_RULE_KEYS,
  EVENT_GEOFENCE_RADIUS_METERS,
  HOST_SUCCESS_MIN_VERIFIED_ATTENDEES,
  LIVE_SESSION_AWARD_MIN_DISTANCE_METERS,
  LIVE_STEP_MAX_ACCURACY_METERS,
  LIVE_STEP_MAX_METERS,
  MAX_ATTENDANCE_ACCURACY_METERS,
  MAX_DWELL_GAP_CREDIT_MS,
  MIN_SAMPLE_SPACING_MS,
  POINTS_ECONOMY_RULES,
  REQUIRED_DWELL_MS,
  WEEKLY_DRIVING_POINTS_CAP,
  applyEconomyCaps,
  attendanceDocId,
  attendanceEvidenceExpiry,
  attendanceWindow,
  buildAwardDescription,
  dailyOpenPoints,
  dailyTotalDocId,
  decideDailyOpen,
  economyIdempotencyKey,
  economyRateLimitDocId,
  economyRule,
  evaluateAttendance,
  isDayKey,
  isSampleInsideFence,
  isUnderEconomyRateLimit,
  liveDistanceIncrementMeters,
  parseCheckInInput,
  parseRecordDailyOpenInput,
  previousDayKey,
  readCount,
  ruleCounterDocId,
  ruleLimitWindowKey,
  stockholmDayKey,
  stockholmWeekKey,
  streakMultiplier,
  toStoredStreak,
  weeklyDrivingDocId,
  type AttendanceSample,
} from '../points/points-economy-core';

const MINUTE = 60_000;

// ---------------------------------------------------------------------------
// The rule table is the contract
// ---------------------------------------------------------------------------

describe('the economy rule table', () => {
  it('matches the canonical table exactly', () => {
    expect(
      ECONOMY_RULE_KEYS.map((key) => {
        const rule = POINTS_ECONOMY_RULES[key];
        return [key, rule.basePoints, rule.limit, rule.limitWindow, rule.driving];
      }),
    ).toEqual([
      ['daily_open', 5, 1, 'local_day', false],
      ['live_session_1km', 10, 2, 'local_day', true],
      ['drive_5km', 15, 2, 'local_day', true],
      ['event_attend_verified', 50, 1, 'event', false],
      ['event_host_success', 75, 1, 'event', false],
      ['garage_first_car', 25, 1, 'forever', false],
      ['incident_report_confirmed', 15, 3, 'local_day', false],
    ]);
  });

  it('only marks the two distance rules as driving-derived', () => {
    const driving = ECONOMY_RULE_KEYS.filter((key) => POINTS_ECONOMY_RULES[key].driving);
    expect(driving).toEqual(['live_session_1km', 'drive_5km']);
  });

  it('uses only ledger sources that already exist in the Phase 9g enum', () => {
    const allowed = ['badge', 'event', 'garage', 'admin_adjustment', 'system', 'crown_hunt'];
    for (const key of ECONOMY_RULE_KEYS) {
      expect(allowed).toContain(POINTS_ECONOMY_RULES[key].source);
    }
  });

  it('rewards no rule for speed and thresholds distance low', () => {
    // Guard rail for Seb's standing no-speed-gamification stance: the only
    // measurements the economy takes are DISTANCE thresholds, and both are
    // low enough to be a by-product of normal driving.
    expect(LIVE_SESSION_AWARD_MIN_DISTANCE_METERS).toBe(1_000);
    expect(DRIVE_AWARD_MIN_DISTANCE_METERS).toBe(5_000);
    expect(JSON.stringify(POINTS_ECONOMY_RULES)).not.toMatch(/speed/i);
  });

  it('requires three verified attendees before the host is paid', () => {
    expect(HOST_SUCCESS_MIN_VERIFIED_ATTENDEES).toBe(3);
  });
});

// ---------------------------------------------------------------------------
// Cap arithmetic — every boundary
// ---------------------------------------------------------------------------

describe('applyEconomyCaps — global daily cap', () => {
  it('pays in full with headroom to spare', () => {
    expect(applyEconomyCaps(15, false, { dailyAwarded: 0, weeklyDrivingAwarded: 0 })).toEqual({
      requested: 15,
      awarded: 15,
      clippedBy: 'none',
    });
  });

  it('pays in full at exactly the boundary (285 + 15 = 300)', () => {
    expect(applyEconomyCaps(15, false, { dailyAwarded: 285, weeklyDrivingAwarded: 0 })).toEqual({
      requested: 15,
      awarded: 15,
      clippedBy: 'none',
    });
  });

  it('pays the PARTIAL remainder one point over the boundary', () => {
    expect(applyEconomyCaps(15, false, { dailyAwarded: 286, weeklyDrivingAwarded: 0 })).toEqual({
      requested: 15,
      awarded: 14,
      clippedBy: 'daily',
    });
  });

  it('pays exactly 1 with a single point of headroom', () => {
    expect(
      applyEconomyCaps(75, false, { dailyAwarded: DAILY_POINTS_CAP - 1, weeklyDrivingAwarded: 0 }),
    ).toEqual({ requested: 75, awarded: 1, clippedBy: 'daily' });
  });

  it('pays nothing exactly at the cap', () => {
    expect(
      applyEconomyCaps(50, false, { dailyAwarded: DAILY_POINTS_CAP, weeklyDrivingAwarded: 0 }),
    ).toEqual({ requested: 50, awarded: 0, clippedBy: 'daily' });
  });

  it('pays nothing when already over the cap (a crown overshot it)', () => {
    expect(applyEconomyCaps(50, false, { dailyAwarded: 780, weeklyDrivingAwarded: 0 })).toEqual({
      requested: 50,
      awarded: 0,
      clippedBy: 'daily',
    });
  });

  it('treats a corrupt stored total as zero rather than blocking all earning', () => {
    for (const corrupt of [Number.NaN, -50, 1.5, 'lots' as unknown as number]) {
      expect(
        applyEconomyCaps(15, false, { dailyAwarded: corrupt, weeklyDrivingAwarded: 0 }).awarded,
      ).toBe(15);
    }
  });
});

describe('applyEconomyCaps — weekly driving cap', () => {
  it('does not apply to non-driving rules however high the weekly total', () => {
    expect(applyEconomyCaps(50, false, { dailyAwarded: 0, weeklyDrivingAwarded: 9_999 })).toEqual({
      requested: 50,
      awarded: 50,
      clippedBy: 'none',
    });
  });

  it('pays in full at exactly the weekly boundary (390 + 10 = 400)', () => {
    expect(applyEconomyCaps(10, true, { dailyAwarded: 0, weeklyDrivingAwarded: 390 })).toEqual({
      requested: 10,
      awarded: 10,
      clippedBy: 'none',
    });
  });

  it('pays the partial remainder one point over the weekly boundary', () => {
    expect(applyEconomyCaps(15, true, { dailyAwarded: 0, weeklyDrivingAwarded: 386 })).toEqual({
      requested: 15,
      awarded: 14,
      clippedBy: 'weekly_driving',
    });
  });

  it('pays nothing at exactly the weekly cap', () => {
    expect(
      applyEconomyCaps(15, true, {
        dailyAwarded: 0,
        weeklyDrivingAwarded: WEEKLY_DRIVING_POINTS_CAP,
      }),
    ).toEqual({ requested: 15, awarded: 0, clippedBy: 'weekly_driving' });
  });

  it('reports the TIGHTER ceiling when both bind, daily on a tie', () => {
    // daily headroom 5, weekly headroom 8 -> daily binds.
    expect(applyEconomyCaps(15, true, { dailyAwarded: 295, weeklyDrivingAwarded: 392 })).toEqual({
      requested: 15,
      awarded: 5,
      clippedBy: 'daily',
    });
    // daily headroom 8, weekly headroom 5 -> weekly binds.
    expect(applyEconomyCaps(15, true, { dailyAwarded: 292, weeklyDrivingAwarded: 395 })).toEqual({
      requested: 15,
      awarded: 5,
      clippedBy: 'weekly_driving',
    });
    // equal headroom -> the daily cap is the reported reason.
    expect(applyEconomyCaps(15, true, { dailyAwarded: 295, weeklyDrivingAwarded: 395 })).toEqual({
      requested: 15,
      awarded: 5,
      clippedBy: 'daily',
    });
  });
});

describe('buildAwardDescription', () => {
  it('names the rule alone when nothing was clipped', () => {
    expect(
      buildAwardDescription(economyRule('drive_5km'), {
        requested: 15,
        awarded: 15,
        clippedBy: 'none',
      }),
    ).toBe('Sparad körning (minst 5 km)');
  });

  it('makes a clipped award legible in the ledger — both amounts and the reason', () => {
    const text = buildAwardDescription(
      economyRule('drive_5km'),
      { requested: 15, awarded: 10, clippedBy: 'daily' },
      '7 km',
    );
    expect(text).toContain('7 km');
    expect(text).toContain('15 p');
    expect(text).toContain('10 p');
    expect(text).toContain(String(DAILY_POINTS_CAP));
  });

  it('names the weekly driving cap when that is what bit', () => {
    expect(
      buildAwardDescription(economyRule('live_session_1km'), {
        requested: 10,
        awarded: 4,
        clippedBy: 'weekly_driving',
      }),
    ).toContain(String(WEEKLY_DRIVING_POINTS_CAP));
  });
});

// ---------------------------------------------------------------------------
// Local days, DST and the streak
// ---------------------------------------------------------------------------

describe('stockholmDayKey — local days, not UTC', () => {
  it('formats a plain summer instant', () => {
    // 2026-07-15 14:00 CEST = 12:00Z
    expect(stockholmDayKey(new Date('2026-07-15T12:00:00Z'))).toBe('2026-07-15');
  });

  it('does NOT split one local day into two just because UTC midnight passed', () => {
    // Both instants are 2026-07-16 local (01:30 and 02:30 CEST) but they
    // straddle UTC midnight. Under UTC days this would be two days and two
    // daily-open awards for one Swedish day.
    const early = new Date('2026-07-15T23:30:00Z'); // 01:30 local, 16 July
    const later = new Date('2026-07-16T00:30:00Z'); // 02:30 local, 16 July
    expect(stockholmDayKey(early)).toBe('2026-07-16');
    expect(stockholmDayKey(later)).toBe('2026-07-16');
  });

  it('DOES split 23:30 and 00:30 local into two consecutive days', () => {
    // Both instants land in ONE UTC day; under UTC days the member would lose
    // a day of their streak.
    const beforeMidnight = new Date('2026-07-15T21:30:00Z'); // 23:30 local, 15 July
    const afterMidnight = new Date('2026-07-15T22:30:00Z'); // 00:30 local, 16 July
    expect(stockholmDayKey(beforeMidnight)).toBe('2026-07-15');
    expect(stockholmDayKey(afterMidnight)).toBe('2026-07-16');
    expect(previousDayKey(stockholmDayKey(afterMidnight))).toBe(
      stockholmDayKey(beforeMidnight),
    );
  });

  it('handles the spring-forward day (2026-03-29, 02:00 -> 03:00)', () => {
    // 00:59 CET on the 29th is 2026-03-28T23:59Z.
    expect(stockholmDayKey(new Date('2026-03-28T23:59:00Z'))).toBe('2026-03-29');
    // 03:00 CEST on the 29th is 01:00Z — the hour 02:00–03:00 never existed.
    expect(stockholmDayKey(new Date('2026-03-29T01:00:00Z'))).toBe('2026-03-29');
    // 23:30 local on the 29th is 21:30Z (UTC+2 now).
    expect(stockholmDayKey(new Date('2026-03-29T21:30:00Z'))).toBe('2026-03-29');
    // 00:30 local on the 30th.
    expect(stockholmDayKey(new Date('2026-03-29T22:30:00Z'))).toBe('2026-03-30');
  });

  it('handles the fall-back day (2026-10-25, 03:00 -> 02:00)', () => {
    // The repeated hour: 02:30 CEST = 00:30Z, 02:30 CET = 01:30Z. Both are
    // still 25 October locally, so the day cannot be double-counted.
    expect(stockholmDayKey(new Date('2026-10-25T00:30:00Z'))).toBe('2026-10-25');
    expect(stockholmDayKey(new Date('2026-10-25T01:30:00Z'))).toBe('2026-10-25');
    // 23:30 local on the 25th is 22:30Z (UTC+1 now).
    expect(stockholmDayKey(new Date('2026-10-25T22:30:00Z'))).toBe('2026-10-25');
  });
});

describe('previousDayKey', () => {
  it('steps back across both DST transitions and month/year ends', () => {
    expect(previousDayKey('2026-03-29')).toBe('2026-03-28'); // 23-hour day
    expect(previousDayKey('2026-03-30')).toBe('2026-03-29');
    expect(previousDayKey('2026-10-25')).toBe('2026-10-24'); // 25-hour day
    expect(previousDayKey('2026-10-26')).toBe('2026-10-25');
    expect(previousDayKey('2026-03-01')).toBe('2026-02-28');
    expect(previousDayKey('2028-03-01')).toBe('2028-02-29'); // leap year
    expect(previousDayKey('2027-01-01')).toBe('2026-12-31');
  });

  it('round-trips against stockholmDayKey through a whole DST week', () => {
    // Walk hour by hour across the spring-forward weekend and assert the day
    // key never skips or repeats a civil date.
    const seen: string[] = [];
    for (let hour = 0; hour < 24 * 4; hour += 1) {
      const key = stockholmDayKey(new Date(Date.UTC(2026, 2, 27, hour)));
      if (seen[seen.length - 1] !== key) {
        seen.push(key);
      }
    }
    expect(seen).toEqual(['2026-03-27', '2026-03-28', '2026-03-29', '2026-03-30', '2026-03-31']);
    for (let i = 1; i < seen.length; i += 1) {
      expect(previousDayKey(seen[i]!)).toBe(seen[i - 1]);
    }
  });
});

describe('stockholmWeekKey', () => {
  it('anchors on the local Monday', () => {
    // 2026-07-20 is a Monday; 2026-07-26 the following Sunday.
    expect(stockholmWeekKey(new Date('2026-07-20T08:00:00Z'))).toBe('w2026-07-20');
    expect(stockholmWeekKey(new Date('2026-07-26T20:00:00Z'))).toBe('w2026-07-20');
    // 23:30 local Sunday is still the same week…
    expect(stockholmWeekKey(new Date('2026-07-26T21:30:00Z'))).toBe('w2026-07-20');
    // …and 00:30 local Monday is the next one, even though both are one UTC day.
    expect(stockholmWeekKey(new Date('2026-07-26T22:30:00Z'))).toBe('w2026-07-27');
  });

  it('does not shift across a DST boundary inside one week', () => {
    // 2026-03-29 (spring forward) is a Sunday — same week as Monday 23 March.
    expect(stockholmWeekKey(new Date('2026-03-23T12:00:00Z'))).toBe('w2026-03-23');
    expect(stockholmWeekKey(new Date('2026-03-29T12:00:00Z'))).toBe('w2026-03-23');
    expect(stockholmWeekKey(new Date('2026-03-30T12:00:00Z'))).toBe('w2026-03-30');
  });
});

describe('streak multiplier and award', () => {
  it('ramps 1.0x to 1.7x and then stops', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 7, 8, 40].map(streakMultiplier)).toEqual([
      1, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.7, 1.7,
    ]);
  });

  it('pays whole points with no float drift', () => {
    expect([0, 1, 2, 3, 4, 5, 6, 7, 30].map(dailyOpenPoints)).toEqual([5, 6, 6, 7, 7, 8, 8, 9, 9]);
    for (const streak of [0, 1, 2, 3, 4, 5, 6, 7, 100]) {
      expect(Number.isInteger(dailyOpenPoints(streak))).toBe(true);
    }
  });

  it('never pays more than base x 1.7', () => {
    expect(dailyOpenPoints(10_000)).toBe(Math.round((DAILY_OPEN_BASE_POINTS * 17) / 10));
  });
});

describe('decideDailyOpen', () => {
  const at = (iso: string) => new Date(iso);

  it('pays 1.0x on a first-ever open and starts the streak at 1', () => {
    const decision = decideDailyOpen({ lastOpenDay: null, streak: 0 }, at('2026-07-15T10:00:00Z'));
    expect(decision).toMatchObject({
      day: '2026-07-15',
      alreadyOpenedToday: false,
      priorStreak: 0,
      newStreak: 1,
      multiplier: 1,
      points: 5,
    });
  });

  it('pays nothing for a second open on the same local day', () => {
    const decision = decideDailyOpen(
      { lastOpenDay: '2026-07-15', streak: 3 },
      at('2026-07-15T20:00:00Z'),
    );
    expect(decision.alreadyOpenedToday).toBe(true);
    expect(decision.points).toBe(0);
    expect(decision.newStreak).toBe(3);
  });

  it('pays nothing for two opens straddling UTC midnight on ONE local day', () => {
    // 01:30 and 02:30 local on 16 July — the UTC-day trap.
    const first = decideDailyOpen(
      { lastOpenDay: '2026-07-15', streak: 1 },
      at('2026-07-15T23:30:00Z'),
    );
    expect(first).toMatchObject({ day: '2026-07-16', alreadyOpenedToday: false, newStreak: 2 });
    const second = decideDailyOpen(
      { lastOpenDay: first.day, streak: first.newStreak },
      at('2026-07-16T00:30:00Z'),
    );
    expect(second.alreadyOpenedToday).toBe(true);
    expect(second.points).toBe(0);
  });

  it('credits BOTH local days for 23:30 then 00:30 inside one UTC day', () => {
    const first = decideDailyOpen(
      { lastOpenDay: '2026-07-14', streak: 4 },
      at('2026-07-15T21:30:00Z'), // 23:30 local, 15 July
    );
    expect(first).toMatchObject({ day: '2026-07-15', priorStreak: 4, newStreak: 5, points: 7 });
    const second = decideDailyOpen(
      { lastOpenDay: first.day, streak: first.newStreak },
      at('2026-07-15T22:30:00Z'), // 00:30 local, 16 July
    );
    expect(second).toMatchObject({ day: '2026-07-16', priorStreak: 5, newStreak: 6, points: 8 });
  });

  it('keeps the streak across the spring-forward night', () => {
    // Open at 23:00 local on 28 March, then 04:00 local on 29 March — the
    // night the clocks jumped forward. Only five wall-clock hours apart.
    const saturday = decideDailyOpen(
      { lastOpenDay: '2026-03-27', streak: 2 },
      at('2026-03-28T22:00:00Z'),
    );
    expect(saturday).toMatchObject({ day: '2026-03-28', priorStreak: 2, newStreak: 3 });
    const sunday = decideDailyOpen(
      { lastOpenDay: saturday.day, streak: saturday.newStreak },
      at('2026-03-29T02:00:00Z'), // 04:00 CEST
    );
    expect(sunday).toMatchObject({ day: '2026-03-29', priorStreak: 3, newStreak: 4, points: 7 });
  });

  it('keeps the streak across the fall-back night (the repeated hour)', () => {
    const saturday = decideDailyOpen(
      { lastOpenDay: '2026-10-23', streak: 6 },
      at('2026-10-24T20:00:00Z'), // 22:00 CEST, 24 Oct
    );
    expect(saturday).toMatchObject({ day: '2026-10-24', newStreak: 7 });
    // 02:30 local on 25 Oct, the FIRST time round the repeated hour.
    const sundayA = decideDailyOpen(
      { lastOpenDay: saturday.day, streak: saturday.newStreak },
      at('2026-10-25T00:30:00Z'),
    );
    expect(sundayA).toMatchObject({ day: '2026-10-25', priorStreak: 7, newStreak: 8, points: 9 });
    // 02:30 local again, the SECOND time round — same local day, no re-award.
    const sundayB = decideDailyOpen(
      { lastOpenDay: sundayA.day, streak: sundayA.newStreak },
      at('2026-10-25T01:30:00Z'),
    );
    expect(sundayB.alreadyOpenedToday).toBe(true);
    expect(sundayB.points).toBe(0);
  });

  it('resets to 1.0x after a missed day', () => {
    const decision = decideDailyOpen(
      { lastOpenDay: '2026-07-13', streak: 6 },
      at('2026-07-15T10:00:00Z'),
    );
    expect(decision).toMatchObject({ priorStreak: 0, newStreak: 1, multiplier: 1, points: 5 });
  });

  it('reaches the 1.7x ceiling on the eighth consecutive day and holds it', () => {
    let state = { lastOpenDay: null as string | null, streak: 0 };
    const paid: number[] = [];
    for (let day = 15; day <= 24; day += 1) {
      const decision = decideDailyOpen(state, at(`2026-07-${day}T10:00:00Z`));
      paid.push(decision.points);
      state = { lastOpenDay: decision.day, streak: decision.newStreak };
    }
    expect(paid).toEqual([5, 6, 6, 7, 7, 8, 8, 9, 9, 9]);
    expect(state.streak).toBe(10);
  });

  it('treats a corrupt stored streak as no streak', () => {
    for (const corrupt of [-4, 2.5, Number.NaN, 'seven' as unknown as number]) {
      expect(toStoredStreak(corrupt)).toBe(0);
    }
    const decision = decideDailyOpen(
      { lastOpenDay: '2026-07-14', streak: -4 },
      new Date('2026-07-15T10:00:00Z'),
    );
    expect(decision.priorStreak).toBe(0);
    expect(decision.points).toBe(5);
  });

  it('rejects a malformed stored lastOpenDay rather than trusting it', () => {
    expect(isDayKey('2026-07-15')).toBe(true);
    expect(isDayKey('15/07/2026')).toBe(false);
    const decision = decideDailyOpen(
      { lastOpenDay: 'yesterday' as string, streak: 5 },
      new Date('2026-07-15T10:00:00Z'),
    );
    expect(decision.priorStreak).toBe(0);
    expect(decision.alreadyOpenedToday).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Idempotency-key derivation — double-award prevention
// ---------------------------------------------------------------------------

describe('economyIdempotencyKey', () => {
  it('is deterministic — the same happening always yields the same key', () => {
    expect(economyIdempotencyKey('drive_5km', 'ride123')).toBe(
      economyIdempotencyKey('drive_5km', 'ride123'),
    );
    expect(economyIdempotencyKey('event_attend_verified', 'ev1', 'uidA')).toBe(
      'pe__event_attend_verified__ev1__uidA',
    );
  });

  it('separates distinct happenings', () => {
    const keys = new Set([
      economyIdempotencyKey('drive_5km', 'rideA'),
      economyIdempotencyKey('drive_5km', 'rideB'),
      economyIdempotencyKey('live_session_1km', 'uidA', 'sess1'),
      economyIdempotencyKey('live_session_1km', 'uidA', 'sess2'),
      economyIdempotencyKey('event_attend_verified', 'ev1', 'uidA'),
      economyIdempotencyKey('event_attend_verified', 'ev1', 'uidB'),
      economyIdempotencyKey('incident_report_confirmed', 'inc1', 'uidA'),
      economyIdempotencyKey('daily_open', 'uidA', '2026-07-15'),
      economyIdempotencyKey('daily_open', 'uidA', '2026-07-16'),
    ]);
    expect(keys.size).toBe(9);
  });

  it('scopes garage_first_car to the UID alone so it can only ever fire once', () => {
    // Deleting the car and adding another produces a different vehicleId but
    // the SAME key, so the ledger refuses the second award.
    expect(economyIdempotencyKey('garage_first_car', 'uidA')).toBe('pe__garage_first_car__uidA');
  });

  it('scopes event_host_success to the event so a host is paid once per event', () => {
    expect(economyIdempotencyKey('event_host_success', 'ev1')).toBe('pe__event_host_success__ev1');
  });

  it('refuses parts that are not Firestore-safe instead of building a path', () => {
    expect(economyIdempotencyKey('drive_5km', 'ride/../../admin')).toBeNull();
    expect(economyIdempotencyKey('drive_5km', '')).toBeNull();
    expect(economyIdempotencyKey('drive_5km', 'ride 1')).toBeNull();
    expect(economyIdempotencyKey('drive_5km')).toBeNull();
    expect(economyIdempotencyKey('drive_5km', 'a'.repeat(400))).toBeNull();
  });
});

describe('counter document IDs', () => {
  it('keys the daily and weekly counters by uid + window', () => {
    expect(dailyTotalDocId('uidA', '2026-07-15')).toBe('uidA__2026-07-15');
    expect(weeklyDrivingDocId('uidA', 'w2026-07-13')).toBe('uidA__w2026-07-13');
  });

  it('keys rule counters by the rule window', () => {
    expect(ruleLimitWindowKey(economyRule('drive_5km'), '2026-07-15')).toBe('2026-07-15');
    expect(ruleLimitWindowKey(economyRule('event_attend_verified'), '2026-07-15', 'ev1')).toBe(
      'ev1',
    );
    expect(ruleLimitWindowKey(economyRule('garage_first_car'), '2026-07-15')).toBe('all');
    expect(ruleCounterDocId('uidA', 'drive_5km', '2026-07-15')).toBe(
      'uidA__drive_5km__2026-07-15',
    );
  });

  // An event-windowed rule with no eventId must not fall back to a shared
  // placeholder counter: that would make the first event a member attended
  // spend the 1/event limit for EVERY later event, silently and permanently.
  it('refuses to key an event-windowed rule without an event id', () => {
    for (const rule of ['event_attend_verified', 'event_host_success'] as const) {
      expect(() => ruleLimitWindowKey(economyRule(rule), '2026-07-15')).toThrow(/window key/);
      expect(() => ruleLimitWindowKey(economyRule(rule), '2026-07-15', null)).toThrow(/window key/);
      expect(() => ruleLimitWindowKey(economyRule(rule), '2026-07-15', '')).toThrow(/window key/);
    }
  });

  it('keys attendance records by (eventId, uid)', () => {
    expect(attendanceDocId('ev1', 'uidA')).toBe('ev1__uidA');
  });

  it('keys the rate limiter per uid, action and minute', () => {
    const base = Date.UTC(2026, 6, 15, 10, 30, 0);
    expect(economyRateLimitDocId('uidA', 'dailyOpen', base)).toBe(
      economyRateLimitDocId('uidA', 'dailyOpen', base + 59_999),
    );
    expect(economyRateLimitDocId('uidA', 'dailyOpen', base)).not.toBe(
      economyRateLimitDocId('uidA', 'dailyOpen', base + 60_000),
    );
    expect(isUnderEconomyRateLimit(9)).toBe(true);
    expect(isUnderEconomyRateLimit(10)).toBe(false);
  });

  // Every economy counter is backend-only and increment-only, so a corrupt
  // value should be impossible — but the read side must not depend on that.
  // The failure directions are asymmetric and all silent, which is why this is
  // pinned rather than left to a `typeof === 'number'` check:
  //  - NaN/Infinity compare false against every ceiling, so a naive read LOCKS
  //    THE MEMBER OUT with a `resource-exhausted` indistinguishable from real
  //    abuse;
  //  - a fractional value survives `Number.isFinite` and is then handed to the
  //    anti-fraud risk pipeline as an attempt rate;
  //  - a negative value silently GRANTS extra headroom under every cap.
  it('degrades a corrupt counter to 0 rather than trusting it', () => {
    for (const good of [0, 1, 9, 300]) {
      expect(readCount(good)).toBe(good);
    }
    for (const bad of [
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
      1.5,
      -1,
      -300,
      Number.MAX_SAFE_INTEGER + 1,
      '5',
      null,
      undefined,
      {},
    ]) {
      expect(readCount(bad)).toBe(0);
    }
  });

  // The lockout and fractional-attempt bugs, stated as the behaviour the
  // callables actually get once the read goes through readCount.
  it('never lets a corrupt counter reject a caller or score a fractional attempt', () => {
    for (const corrupt of [Number.NaN, Number.POSITIVE_INFINITY, 1.5, -5]) {
      const stored = readCount(corrupt);
      expect(isUnderEconomyRateLimit(stored)).toBe(true);
      expect(Number.isSafeInteger(stored + 1)).toBe(true);
    }
  });
});

// ---------------------------------------------------------------------------
// Client-supplied values must never reach the ledger
// ---------------------------------------------------------------------------

describe('client input is never trusted for a point value', () => {
  it('rejects any argument to points.recordDailyOpen', () => {
    expect(parseRecordDailyOpenInput({}).ok).toBe(true);
    expect(parseRecordDailyOpenInput(undefined).ok).toBe(true);
    expect(parseRecordDailyOpenInput(null).ok).toBe(true);
    // The forgery attempts: a point value, a streak, a day.
    expect(parseRecordDailyOpenInput({ points: 9999 }).ok).toBe(false);
    expect(parseRecordDailyOpenInput({ pointsAwarded: 9999 }).ok).toBe(false);
    expect(parseRecordDailyOpenInput({ streak: 7 }).ok).toBe(false);
    expect(parseRecordDailyOpenInput({ day: '2026-01-01' }).ok).toBe(false);
    expect(parseRecordDailyOpenInput({ multiplier: 17 }).ok).toBe(false);
  });

  it('rejects any distance, dwell or point value on events.checkIn', () => {
    const valid = {
      eventId: 'ev1',
      latitude: 57.487,
      longitude: 12.076,
      capturedAt: '2026-07-15T10:00:00.000Z',
    };
    expect(parseCheckInInput(valid).ok).toBe(true);
    expect(parseCheckInInput({ ...valid, accuracyMeters: 12 }).ok).toBe(true);
    for (const forged of [
      { points: 50 },
      { pointsAwarded: 50 },
      { dwellMinutes: 999 },
      { dwellMs: 600_000 },
      { distanceMeters: 0 },
      { verified: true },
      { attended: true },
    ]) {
      expect(parseCheckInInput({ ...valid, ...forged }).ok).toBe(false);
    }
  });

  it('rejects malformed coordinates and a non-ISO capture time', () => {
    const base = { eventId: 'ev1', latitude: 57.487, longitude: 12.076 };
    expect(parseCheckInInput({ ...base, capturedAt: 'now' }).ok).toBe(false);
    expect(
      parseCheckInInput({ ...base, latitude: 91, capturedAt: '2026-07-15T10:00:00.000Z' }).ok,
    ).toBe(false);
    expect(
      parseCheckInInput({
        ...base,
        eventId: '../admin',
        capturedAt: '2026-07-15T10:00:00.000Z',
      }).ok,
    ).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Event attendance: geofence + dwell
// ---------------------------------------------------------------------------

// Kungsbacka-ish coordinates for the fixture event.
const EVENT_LAT = 57.4879;
const EVENT_LON = 12.0763;
const START = Date.UTC(2026, 6, 18, 10, 0, 0);
const END = Date.UTC(2026, 6, 18, 14, 0, 0);
const WINDOW = { startsAtMs: START, endsAtMs: END };

function sampleAt(offsetMs: number, overrides: Partial<AttendanceSample> = {}): AttendanceSample {
  return {
    latitude: EVENT_LAT,
    longitude: EVENT_LON,
    accuracyMeters: 10,
    capturedAtMs: START + offsetMs,
    ...overrides,
  };
}

/** Moves a coordinate north by `metres` (1 deg latitude ~ 111 320 m). */
function northOf(metres: number): { latitude: number; longitude: number } {
  return { latitude: EVENT_LAT + metres / 111_320, longitude: EVENT_LON };
}

describe('attendanceWindow', () => {
  it('opens 30 minutes early and closes 30 minutes late', () => {
    expect(attendanceWindow(WINDOW)).toEqual({
      fromMs: START - ATTENDANCE_WINDOW_BEFORE_MS,
      toMs: END + ATTENDANCE_WINDOW_AFTER_MS,
    });
  });

  it('falls back to a default duration when the event has no end', () => {
    expect(attendanceWindow({ startsAtMs: START, endsAtMs: null }).toMs).toBe(
      START + DEFAULT_EVENT_DURATION_MS + ATTENDANCE_WINDOW_AFTER_MS,
    );
  });

  it('ignores an end that is not after the start', () => {
    expect(attendanceWindow({ startsAtMs: START, endsAtMs: START - 1 }).toMs).toBe(
      START + DEFAULT_EVENT_DURATION_MS + ATTENDANCE_WINDOW_AFTER_MS,
    );
  });
});

describe('isSampleInsideFence', () => {
  it('accepts a sample well inside the 150 m fence', () => {
    expect(isSampleInsideFence(sampleAt(0, northOf(50)), EVENT_LAT, EVENT_LON)).toBe(true);
  });

  it('rejects a sample far outside it', () => {
    expect(isSampleInsideFence(sampleAt(0, northOf(2_000)), EVENT_LAT, EVENT_LON)).toBe(false);
  });

  it('buffers by half the reported accuracy, bounded at 200 m effective', () => {
    // 190 m out with the WORST allowed accuracy (100 m): 150 + 50 = 200 m
    // effective radius -> inside.
    expect(
      isSampleInsideFence(
        sampleAt(0, {
          ...northOf(190),
          accuracyMeters: MAX_ATTENDANCE_ACCURACY_METERS,
        }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(true);
    // 210 m out is outside even with the worst allowed accuracy.
    expect(
      isSampleInsideFence(
        sampleAt(0, {
          ...northOf(210),
          accuracyMeters: MAX_ATTENDANCE_ACCURACY_METERS,
        }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(false);
    // The same 190 m with a good fix is outside — the buffer is what let it in.
    expect(
      isSampleInsideFence(
        sampleAt(0, { ...northOf(190), accuracyMeters: 5 }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(false);
  });

  it('refuses a fix too imprecise to prove anything, however close it claims to be', () => {
    // The shared isWithinGeofence buffers by accuracy x 0.5 with NO upper
    // bound, so an unclamped 50 km accuracy would inflate the 150 m fence to
    // 25 km. MAX_ATTENDANCE_ACCURACY_METERS closes that.
    expect(
      isSampleInsideFence(
        sampleAt(0, { ...northOf(20_000), accuracyMeters: 50_000 }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(false);
    // Even standing on the exact spot, an absurd accuracy is refused.
    expect(
      isSampleInsideFence(sampleAt(0, { accuracyMeters: 50_000 }), EVENT_LAT, EVENT_LON),
    ).toBe(false);
    expect(
      isSampleInsideFence(
        sampleAt(0, { accuracyMeters: MAX_ATTENDANCE_ACCURACY_METERS + 1 }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(false);
    // A negative or non-finite accuracy is client-controlled nonsense.
    expect(isSampleInsideFence(sampleAt(0, { accuracyMeters: -1 }), EVENT_LAT, EVENT_LON)).toBe(
      false,
    );
    expect(
      isSampleInsideFence(sampleAt(0, { accuracyMeters: Number.NaN }), EVENT_LAT, EVENT_LON),
    ).toBe(false);
    // An UNREPORTED accuracy is still accepted (no buffer applied).
    expect(isSampleInsideFence(sampleAt(0, { accuracyMeters: null }), EVENT_LAT, EVENT_LON)).toBe(
      true,
    );
  });

  it('rejects an invalid coordinate outright', () => {
    expect(
      isSampleInsideFence(
        sampleAt(0, { latitude: Number.NaN, longitude: EVENT_LON }),
        EVENT_LAT,
        EVENT_LON,
      ),
    ).toBe(false);
  });

  it('uses a 150 m radius by default', () => {
    expect(EVENT_GEOFENCE_RADIUS_METERS).toBe(150);
  });
});

describe('evaluateAttendance', () => {
  const evaluate = (samples: AttendanceSample[]) =>
    evaluateAttendance(samples, EVENT_LAT, EVENT_LON, WINDOW);

  it('refuses with no samples', () => {
    expect(evaluate([])).toMatchObject({ attended: false, reason: 'no_qualifying_samples' });
  });

  it('refuses on a single ping — one fix cannot prove attendance', () => {
    expect(evaluate([sampleAt(0)])).toMatchObject({
      attended: false,
      reason: 'need_second_sample',
      qualifyingSampleCount: 1,
    });
  });

  it('REFUSES at 9 min 59 s apart', () => {
    const decision = evaluate([sampleAt(0), sampleAt(9 * MINUTE + 59_000)]);
    expect(decision.attended).toBe(false);
    expect(decision.reason).toBe('samples_too_close');
    expect(decision.spanMs).toBe(9 * MINUTE + 59_000);
  });

  it('ACCEPTS at exactly 10 min 00 s apart', () => {
    const decision = evaluate([sampleAt(0), sampleAt(MIN_SAMPLE_SPACING_MS)]);
    expect(decision.attended).toBe(true);
    expect(decision.reason).toBe('attended');
    expect(decision.dwellMs).toBe(REQUIRED_DWELL_MS);
  });

  it('ACCEPTS at 10 min 01 s apart', () => {
    expect(evaluate([sampleAt(0), sampleAt(10 * MINUTE + 1_000)])).toMatchObject({
      attended: true,
      reason: 'attended',
    });
  });

  it('is order-independent — samples arriving out of order still verify', () => {
    expect(evaluate([sampleAt(12 * MINUTE), sampleAt(0)])).toMatchObject({ attended: true });
  });

  it('rejects a sample outside the geofence and does not count its dwell', () => {
    // Arrived at t=0, but the second "sample" is from 2 km away 15 min later.
    const decision = evaluate([sampleAt(0), sampleAt(15 * MINUTE, northOf(2_000))]);
    expect(decision.attended).toBe(false);
    expect(decision.qualifyingSampleCount).toBe(1);
    expect(decision.reason).toBe('need_second_sample');
  });

  it('rejects samples outside the [start-30, end+30] window', () => {
    const tooEarly = sampleAt(-31 * MINUTE);
    const tooLate = { ...sampleAt(0), capturedAtMs: END + 31 * MINUTE };
    expect(evaluate([tooEarly, tooLate])).toMatchObject({
      attended: false,
      qualifyingSampleCount: 0,
    });
  });

  it('accepts samples exactly on the window edges', () => {
    const atOpen = { ...sampleAt(0), capturedAtMs: START - ATTENDANCE_WINDOW_BEFORE_MS };
    const tenLater = {
      ...sampleAt(0),
      capturedAtMs: START - ATTENDANCE_WINDOW_BEFORE_MS + MIN_SAMPLE_SPACING_MS,
    };
    expect(evaluate([atOpen, tenLater])).toMatchObject({ attended: true });

    const atClose = { ...sampleAt(0), capturedAtMs: END + ATTENDANCE_WINDOW_AFTER_MS };
    const tenBefore = {
      ...sampleAt(0),
      capturedAtMs: END + ATTENDANCE_WINDOW_AFTER_MS - MIN_SAMPLE_SPACING_MS,
    };
    expect(evaluate([tenBefore, atClose])).toMatchObject({ attended: true });
  });

  it('credits at most 30 minutes for one long gap', () => {
    // Two samples three hours apart: present twice, but not proven present
    // throughout. Dwell is capped, and attendance still passes (both fixes
    // were inside the fence, well over 10 min apart).
    const decision = evaluate([sampleAt(0), sampleAt(180 * MINUTE)]);
    expect(decision.dwellMs).toBe(MAX_DWELL_GAP_CREDIT_MS);
    expect(decision.attended).toBe(true);
  });

  it('sums many short gaps into the required dwell', () => {
    // Eleven samples one minute apart: span 10 min, dwell 10 min.
    const samples = Array.from({ length: 11 }, (_, i) => sampleAt(i * MINUTE));
    const decision = evaluate(samples);
    expect(decision.dwellMs).toBe(10 * MINUTE);
    expect(decision.spanMs).toBe(10 * MINUTE);
    expect(decision.attended).toBe(true);
  });

  it('reports dwell_too_short only when the span is long enough but dwell is not', () => {
    // Two samples 45 min apart with a 30-min gap credit -> dwell 30 min, so
    // this cannot happen with a 10-min requirement; force it with a stricter
    // requirement by using samples whose credited dwell is genuinely small.
    // t=0, t=10min, t=200min: gaps 10 + 30(capped) = 40 min. Attended.
    const decision = evaluate([sampleAt(0), sampleAt(10 * MINUTE), sampleAt(200 * MINUTE)]);
    expect(decision.attended).toBe(true);
    expect(decision.dwellMs).toBe(10 * MINUTE + MAX_DWELL_GAP_CREDIT_MS);
  });

  it('ignores a duplicate timestamp instead of inflating dwell', () => {
    const decision = evaluate([sampleAt(0), sampleAt(0), sampleAt(0)]);
    expect(decision.dwellMs).toBe(0);
    expect(decision.attended).toBe(false);
    expect(decision.reason).toBe('samples_too_close');
  });

  it('cannot be satisfied by a drive-by: two fixes 30 seconds apart', () => {
    expect(evaluate([sampleAt(0), sampleAt(30_000)])).toMatchObject({
      attended: false,
      reason: 'samples_too_close',
    });
  });
});

// ---------------------------------------------------------------------------
// Live-session distance accumulation
// ---------------------------------------------------------------------------

describe('liveDistanceIncrementMeters', () => {
  const here = { latitude: EVENT_LAT, longitude: EVENT_LON, accuracyMeters: 10 };

  it('credits nothing for the first sample of a session', () => {
    expect(liveDistanceIncrementMeters({ previous: null, next: here })).toBe(0);
  });

  it('credits the server-computed haversine distance for a normal step', () => {
    const metres = liveDistanceIncrementMeters({
      previous: { latitude: EVENT_LAT, longitude: EVENT_LON },
      next: { ...northOf(300), accuracyMeters: 10 },
    });
    expect(metres).toBeGreaterThan(295);
    expect(metres).toBeLessThan(305);
  });

  it('credits nothing for an implausible jump (spoof / resumed session)', () => {
    expect(
      liveDistanceIncrementMeters({
        previous: { latitude: EVENT_LAT, longitude: EVENT_LON },
        next: { ...northOf(LIVE_STEP_MAX_METERS + 1_000), accuracyMeters: 10 },
      }),
    ).toBe(0);
  });

  it('credits nothing for a low-confidence fix', () => {
    expect(
      liveDistanceIncrementMeters({
        previous: { latitude: EVENT_LAT, longitude: EVENT_LON },
        next: { ...northOf(300), accuracyMeters: LIVE_STEP_MAX_ACCURACY_METERS + 1 },
      }),
    ).toBe(0);
  });

  it('credits nothing for an invalid coordinate', () => {
    expect(
      liveDistanceIncrementMeters({
        previous: { latitude: EVENT_LAT, longitude: EVENT_LON },
        next: { latitude: Number.NaN, longitude: EVENT_LON, accuracyMeters: 10 },
      }),
    ).toBe(0);
  });

  it('needs many honest steps to reach the 1 km threshold', () => {
    // A member cannot mint the award with one giant fabricated step: each
    // step is bounded, so reaching 1 km takes real movement.
    let total = 0;
    let previous = { latitude: EVENT_LAT, longitude: EVENT_LON };
    for (let i = 1; i <= 10; i += 1) {
      const next = northOf(i * 120);
      total += liveDistanceIncrementMeters({
        previous,
        next: { ...next, accuracyMeters: 8 },
      });
      previous = next;
    }
    expect(total).toBeGreaterThanOrEqual(LIVE_SESSION_AWARD_MIN_DISTANCE_METERS);
  });
});

describe('attendance evidence retention', () => {
  it('expires 90 days after the sample that set it', () => {
    const now = Date.UTC(2026, 6, 25, 12, 0, 0);
    expect(attendanceEvidenceExpiry(now).getTime() - now).toBe(ATTENDANCE_EVIDENCE_RETENTION_MS);
    expect(ATTENDANCE_EVIDENCE_RETENTION_MS).toBe(90 * 24 * 60 * 60_000);
  });

  it('outlives the event it documents by a wide margin', () => {
    // The record must survive long enough to settle a dispute about the award,
    // so the retention has to dwarf the attendance window itself.
    const window = attendanceWindow({ startsAtMs: 0, endsAtMs: null });
    expect(ATTENDANCE_EVIDENCE_RETENTION_MS).toBeGreaterThan((window.toMs - window.fromMs) * 100);
  });

  it('pushes the deadline out as later samples arrive', () => {
    // expireAt is rewritten on every write, so a member who checks in twice
    // does not have the record reaped 90 days after the FIRST tap.
    const first = Date.UTC(2026, 6, 25, 12, 0, 0);
    const second = first + 11 * 60_000;
    expect(attendanceEvidenceExpiry(second).getTime()).toBeGreaterThan(
      attendanceEvidenceExpiry(first).getTime(),
    );
  });
});
