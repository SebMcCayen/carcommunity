/**
 * Unit tests for the tiered-badge evaluation core (badge-tiers.ts) and the
 * ladder catalog it reads (badge-core.ts). No emulators required.
 *
 * These tests are the specification for the four properties the awarding layer
 * relies on: exact thresholds, multi-tier jumps, monotonicity, and idempotent
 * re-evaluation — plus the anti-abuse rule that a risk_review Kronjakt claim
 * never counts.
 */

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  BADGE_CATALOG,
  BADGE_CATALOG_ORDER,
  BADGE_ICON_SYSTEM,
  BADGE_KEYS,
  BADGE_LADDERS,
  LEGACY_BADGE_KEYS,
  TIER_BADGE_KEYS,
  TIER_POINTS_REWARD,
  buildBadgeDocument,
  isBadgeKey,
  ladderForBadgeKey,
  type BadgeKey,
} from '../badges/badge-core';
import {
  ZERO_BADGE_COUNTERS,
  advanceStreak,
  badgeAwardIdempotencyKey,
  claimUserId,
  convoyLedOwnerUid,
  crownClaimCrownDelta,
  highestHeldTierPerLadder,
  isNextDay,
  newlyEarnedTierBadges,
  qualifiedTierBadges,
  readBadgeCounters,
  readStreakState,
  rideDistanceDelta,
  streakDayKey,
  tierPointsReward,
  toCounter,
  type BadgeCounters,
} from '../badges/badge-tiers';

const counters = (overrides: Partial<BadgeCounters> = {}): BadgeCounters => ({
  ...ZERO_BADGE_COUNTERS,
  ...overrides,
});

// ---------------------------------------------------------------------------
// Catalog integrity
// ---------------------------------------------------------------------------

describe('extended badge catalog', () => {
  it('covers every key exactly once and keeps the display order complete', () => {
    expect(Object.keys(BADGE_CATALOG).sort()).toEqual([...BADGE_KEYS].sort());
    expect([...BADGE_CATALOG_ORDER].sort()).toEqual([...BADGE_KEYS].sort());
    expect(new Set(BADGE_CATALOG_ORDER).size).toBe(BADGE_CATALOG_ORDER.length);
  });

  it('keeps every pre-existing key, unrenamed, at the front of the order', () => {
    // Members already hold documents at these IDs — renaming one orphans them.
    expect(BADGE_CATALOG_ORDER.slice(0, 5)).toEqual([...LEGACY_BADGE_KEYS]);
    for (const key of LEGACY_BADGE_KEYS) {
      expect(BADGE_CATALOG[key].ladder).toBeNull();
      expect(BADGE_CATALOG[key].tier).toBeNull();
      expect(BADGE_CATALOG[key].pointsReward).toBe(0);
    }
    expect(BADGE_CATALOG.first_event.name).toBe('Första träffen');
    expect(BADGE_CATALOG.five_events.name).toBe('5 träffar');
    expect(BADGE_CATALOG.helpful_member.iconIdentifier).toBe('badge_helpful_member');
  });

  it('keeps helpful_member as the only manual badge', () => {
    const manual = BADGE_KEYS.filter((key) => !BADGE_CATALOG[key].isAutomatic);
    expect(manual).toEqual(['helpful_member']);
  });

  it('marks garage_created as historic, superseded by samlare_brons', () => {
    // Decision: BOTH are kept. garage_created stays live and unchanged so its
    // existing holders are untouched (no migration, no orphaned documents);
    // samlare_brons is the ladder rung measuring the same moment going forward.
    expect(BADGE_CATALOG.garage_created.isLegacy).toBe(true);
    expect(BADGE_CATALOG.garage_created.supersededBy).toBe('samlare_brons');
    // ...and it awards no points, so holding both is not a double payout.
    expect(BADGE_CATALOG.garage_created.pointsReward).toBe(0);
    expect(BADGE_CATALOG.samlare_brons.pointsReward).toBe(25);
  });

  it('gives every badge a badge_-prefixed icon identifier and an art brief', () => {
    for (const key of BADGE_KEYS) {
      const definition = BADGE_CATALOG[key];
      expect(definition.key).toBe(key);
      expect(definition.iconIdentifier).toBe(`badge_${key}`);
      expect(definition.name.length).toBeGreaterThan(0);
      expect(definition.description.length).toBeGreaterThan(0);
      expect(definition.iconDesign.length).toBeGreaterThan(40);
    }
  });

  it('never depicts speed anywhere in the art briefs (standing product stance)', () => {
    const forbidden = ['speedometer', 'motion line', 'speed line', 'chequered', 'checkered', 'needle', 'racing'];
    const briefs = [
      BADGE_ICON_SYSTEM,
      ...BADGE_KEYS.map((key) => BADGE_CATALOG[key].iconDesign),
    ].map((text) => text.toLowerCase());

    for (const brief of briefs) {
      for (const term of forbidden) {
        // A term may appear ONLY inside an explicit "no <term>" prohibition —
        // every occurrence must be one, so mentions must equal prohibitions.
        const mentions = brief.split(term).length - 1;
        const prohibitions = brief.split(`no ${term}`).length - 1;
        expect({ term, mentions }).toEqual({ term, mentions: prohibitions });
      }
    }

    // Vägfarare is the ladder most at risk of drifting into speed imagery, so
    // its brief must state the prohibition outright.
    const vagfarare = BADGE_CATALOG.vagfarare_brons.iconDesign.toLowerCase();
    expect(vagfarare).toContain('never speed');
    expect(vagfarare).toContain('no speedometer');
    expect(vagfarare).toContain('no motion line');
    expect(BADGE_ICON_SYSTEM.toLowerCase()).toContain('may depict or imply speed');
  });

  it('gives every ladder a unique glyph so tiers are not distinguished by colour alone', () => {
    const glyphs = BADGE_LADDERS.map((ladder) => ladder.glyphBrief);
    expect(new Set(glyphs).size).toBe(glyphs.length);
    // Tier is encoded by countable pips as well as ring colour.
    for (const tier of ['brons', 'silver', 'guld', 'platina'] as const) {
      expect(BADGE_ICON_SYSTEM.toLowerCase()).toContain(tier);
    }
    expect(BADGE_ICON_SYSTEM).toContain('pip');
  });
});

describe('ladder definitions', () => {
  it('matches the canonical thresholds exactly', () => {
    const byLadder = Object.fromEntries(
      BADGE_LADDERS.map((ladder) => [
        ladder.ladder,
        ladder.tiers.map((tier) => tier.threshold),
      ]),
    );
    expect(byLadder).toEqual({
      kronjagare: [10, 50, 250, 1_000],
      vagfarare: [100_000, 500_000, 2_000_000, 10_000_000],
      traffrav: [3, 10, 25, 60],
      trogen: [7, 30, 100, 365],
      konvojledare: [1, 5, 20, 50],
      samlare: [1, 3, 5],
    });
  });

  it('has strictly increasing thresholds and canonical tier order', () => {
    for (const ladder of BADGE_LADDERS) {
      const thresholds = ladder.tiers.map((tier) => tier.threshold);
      expect(thresholds).toEqual([...thresholds].sort((a, b) => a - b));
      expect(new Set(thresholds).size).toBe(thresholds.length);
      expect(ladder.tiers.map((tier) => tier.tier)).toEqual(
        ['brons', 'silver', 'guld', 'platina'].slice(0, ladder.tiers.length),
      );
    }
  });

  it('caps Samlare at three tiers because the garage caps at five vehicles', () => {
    const samlare = BADGE_LADDERS.find((ladder) => ladder.ladder === 'samlare')!;
    expect(samlare.tiers).toHaveLength(3);
    expect(samlare.tiers.at(-1)!.threshold).toBe(5);
  });

  it('awards 25/75/200/500 Kronpoäng per tier', () => {
    expect(TIER_POINTS_REWARD).toEqual({ brons: 25, silver: 75, guld: 200, platina: 500 });
    for (const key of TIER_BADGE_KEYS) {
      expect(tierPointsReward(key)).toBe(TIER_POINTS_REWARD[BADGE_CATALOG[key].tier!]);
    }
  });

  it('renders Vägfarare descriptions in kilometres from metre thresholds', () => {
    expect(BADGE_CATALOG.vagfarare_brons.description).toBe('Kört 100 km totalt.');
    expect(BADGE_CATALOG.vagfarare_platina.description).toBe('Kört 10000 km totalt.');
    expect(BADGE_CATALOG.kronjagare_guld.description).toBe('Samlat 250 kronor i Kronjakten.');
  });

  it('resolves a tier key back to its ladder', () => {
    expect(ladderForBadgeKey('trogen_guld')!.ladder).toBe('trogen');
    expect(ladderForBadgeKey('first_event')).toBeNull();
    expect(isBadgeKey('kronjagare_platina')).toBe(true);
    expect(isBadgeKey('kronjagare_diamant')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Threshold boundaries — one test per ladder, at the exact edge
// ---------------------------------------------------------------------------

describe('threshold boundaries', () => {
  it('Kronjägare: 9 crowns earns nothing, 10 earns Brons', () => {
    expect(qualifiedTierBadges(counters({ crownsCollected: 9 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ crownsCollected: 10 }))).toEqual(['kronjagare_brons']);
  });

  it('Vägfarare: 99.9 km earns nothing, exactly 100 km earns Brons', () => {
    expect(qualifiedTierBadges(counters({ lifetimeDistanceMeters: 99_900 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ lifetimeDistanceMeters: 99_999 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ lifetimeDistanceMeters: 100_000 }))).toEqual([
      'vagfarare_brons',
    ]);
  });

  it('Träffräv: 2 events earns nothing, 3 earns Brons', () => {
    expect(qualifiedTierBadges(counters({ verifiedEventsAttended: 2 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ verifiedEventsAttended: 3 }))).toEqual([
      'traffrav_brons',
    ]);
  });

  it('Trogen: a 6-day streak earns nothing, 7 earns Brons', () => {
    expect(qualifiedTierBadges(counters({ bestDayStreak: 6 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ bestDayStreak: 7 }))).toEqual(['trogen_brons']);
  });

  it('Konvojledare: 0 convoys earns nothing, the first one earns Brons', () => {
    expect(qualifiedTierBadges(counters({ convoysLed: 0 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ convoysLed: 1 }))).toEqual(['konvojledare_brons']);
  });

  it('Samlare: the first car earns Brons and five cars earn all three tiers', () => {
    expect(qualifiedTierBadges(counters({ vehiclesInGarage: 0 }))).toEqual([]);
    expect(qualifiedTierBadges(counters({ vehiclesInGarage: 1 }))).toEqual(['samlare_brons']);
    expect(qualifiedTierBadges(counters({ vehiclesInGarage: 5 }))).toEqual([
      'samlare_brons',
      'samlare_silver',
      'samlare_guld',
    ]);
  });

  it('qualifies at exactly every published threshold and one short of it', () => {
    for (const ladder of BADGE_LADDERS) {
      for (const spec of ladder.tiers) {
        const atThreshold = qualifiedTierBadges(counters({ [ladder.metric]: spec.threshold }));
        expect(atThreshold).toContain(spec.key);
        const justBelow = qualifiedTierBadges(
          counters({ [ladder.metric]: spec.threshold - 1 }),
        );
        expect(justBelow).not.toContain(spec.key);
      }
    }
  });
});

// ---------------------------------------------------------------------------
// Multi-tier jumps, monotonicity, idempotence
// ---------------------------------------------------------------------------

describe('multi-tier jumps', () => {
  it('0 → 300 crowns in one step awards Brons, Silver AND Guld together', () => {
    expect(newlyEarnedTierBadges(counters({ crownsCollected: 300 }), [])).toEqual([
      'kronjagare_brons',
      'kronjagare_silver',
      'kronjagare_guld',
    ]);
  });

  it('a single huge drive can award every Vägfarare tier at once', () => {
    expect(qualifiedTierBadges(counters({ lifetimeDistanceMeters: 12_000_000 }))).toEqual([
      'vagfarare_brons',
      'vagfarare_silver',
      'vagfarare_guld',
      'vagfarare_platina',
    ]);
  });

  it('awards across several ladders in one evaluation, in catalog order', () => {
    const earned = newlyEarnedTierBadges(
      counters({ crownsCollected: 50, verifiedEventsAttended: 3, convoysLed: 5 }),
      [],
    );
    expect(earned).toEqual([
      'kronjagare_brons',
      'kronjagare_silver',
      'traffrav_brons',
      'konvojledare_brons',
      'konvojledare_silver',
    ]);
    // Catalog order means a partial failure leaves a PREFIX of a ladder, never
    // a hole (Guld without Silver).
    const indices = earned.map((key) => BADGE_CATALOG_ORDER.indexOf(key));
    expect(indices).toEqual([...indices].sort((a, b) => a - b));
  });
});

describe('monotonicity', () => {
  it('reaching Guld never stops qualifying for Brons and Silver', () => {
    const atGuld = qualifiedTierBadges(counters({ crownsCollected: 250 }));
    expect(atGuld).toEqual([
      'kronjagare_brons',
      'kronjagare_silver',
      'kronjagare_guld',
    ]);
  });

  it('never proposes revoking a badge the member holds but no longer qualifies for', () => {
    // A counter that somehow reads lower than when a badge was earned (a
    // repaired document, a metric change) yields NOTHING new — and the module
    // has no concept of removal at all, so held tiers survive untouched.
    const held = ['kronjagare_brons', 'kronjagare_silver', 'kronjagare_guld'];
    expect(newlyEarnedTierBadges(counters({ crownsCollected: 0 }), held)).toEqual([]);
    expect(newlyEarnedTierBadges(counters({ crownsCollected: 12 }), held)).toEqual([]);
  });

  it('adds only the newly crossed rung as a member climbs', () => {
    const held = new Set<string>();
    const seen: BadgeKey[] = [];
    for (const crowns of [10, 50, 250, 1_000]) {
      const earned = newlyEarnedTierBadges(counters({ crownsCollected: crowns }), held);
      expect(earned).toHaveLength(1);
      seen.push(...earned);
      earned.forEach((key) => held.add(key));
    }
    expect(seen).toEqual([
      'kronjagare_brons',
      'kronjagare_silver',
      'kronjagare_guld',
      'kronjagare_platina',
    ]);
  });
});

describe('idempotent re-evaluation', () => {
  it('re-running with the same counters awards nothing the second time', () => {
    const state = counters({ crownsCollected: 300, bestDayStreak: 40 });
    const first = newlyEarnedTierBadges(state, []);
    expect(first.length).toBeGreaterThan(0);
    expect(newlyEarnedTierBadges(state, first)).toEqual([]);
    // ...and a third pass, and a pass with the badges listed in a different
    // order, are equally empty.
    expect(newlyEarnedTierBadges(state, [...first].reverse())).toEqual([]);
  });

  it('is a pure function of the counters — no hidden history', () => {
    const state = counters({ verifiedEventsAttended: 25, vehiclesInGarage: 3 });
    expect(qualifiedTierBadges(state)).toEqual(qualifiedTierBadges({ ...state }));
  });

  it('derives a stable, Firestore-safe ledger idempotency key per badge', () => {
    for (const key of TIER_BADGE_KEYS) {
      const idempotencyKey = badgeAwardIdempotencyKey(key);
      expect(idempotencyKey).toBe(`badge_award_${key}`);
      expect(idempotencyKey).toMatch(/^[A-Za-z0-9._-]+$/);
    }
    expect(new Set(TIER_BADGE_KEYS.map(badgeAwardIdempotencyKey)).size).toBe(
      TIER_BADGE_KEYS.length,
    );
  });
});

// ---------------------------------------------------------------------------
// Counter sanitisation
// ---------------------------------------------------------------------------

describe('counter sanitisation', () => {
  it('treats missing, negative, non-numeric and non-finite counters as zero', () => {
    expect(toCounter(undefined)).toBe(0);
    expect(toCounter(null)).toBe(0);
    expect(toCounter('9999')).toBe(0);
    expect(toCounter(-5)).toBe(0);
    expect(toCounter(Number.NaN)).toBe(0);
    expect(toCounter(Number.POSITIVE_INFINITY)).toBe(0);
    expect(toCounter(12.9)).toBe(12);
  });

  it('cannot be tricked into qualifying by a corrupt counter document', () => {
    expect(
      qualifiedTierBadges(
        readBadgeCounters({
          crownsCollected: '1000000',
          lifetimeDistanceMeters: Number.POSITIVE_INFINITY,
          completedEventsAttended: -3,
          bestDayStreak: Number.NaN,
        }),
      ),
    ).toEqual([]);
  });

  it('reads Träffräv from the pre-existing completedEventsAttended field', () => {
    expect(readBadgeCounters({ completedEventsAttended: 10 }).verifiedEventsAttended).toBe(10);
    expect(readBadgeCounters(undefined)).toEqual(ZERO_BADGE_COUNTERS);
  });
});

// ---------------------------------------------------------------------------
// Anti-abuse: source-event guards
// ---------------------------------------------------------------------------

describe('crown claim credit (anti-abuse)', () => {
  it('credits exactly one crown when a claim resolves to awarded', () => {
    expect(crownClaimCrownDelta(undefined, { result: 'awarded' })).toBe(1);
  });

  it('NEVER credits a risk_review claim', () => {
    expect(crownClaimCrownDelta(undefined, { result: 'risk_review' })).toBe(0);
    expect(crownClaimCrownDelta({ result: 'risk_review' }, { result: 'risk_review' })).toBe(0);
  });

  it('credits no other claim outcome', () => {
    for (const result of [
      'too_far',
      'already_claimed',
      'daily_limit_reached',
      'not_available',
      'position_stale',
      'moving_too_fast',
    ]) {
      expect(crownClaimCrownDelta(undefined, { result })).toBe(0);
    }
  });

  it('credits nothing for a rewrite of an already-awarded claim', () => {
    expect(crownClaimCrownDelta({ result: 'awarded' }, { result: 'awarded' })).toBe(0);
    expect(crownClaimCrownDelta({ result: 'awarded' }, undefined)).toBe(0);
  });

  it('credits a claim that is later cleared from risk_review to awarded', () => {
    expect(crownClaimCrownDelta({ result: 'risk_review' }, { result: 'awarded' })).toBe(1);
  });

  it('reads the claim owner defensively', () => {
    expect(claimUserId({ userId: 'u1' })).toBe('u1');
    expect(claimUserId({ userId: '' })).toBeNull();
    expect(claimUserId({ userId: 42 })).toBeNull();
    expect(claimUserId(undefined)).toBeNull();
  });
});

describe('drive distance credit', () => {
  it('credits the server-computed distance and ignores an uncomputable one', () => {
    expect(rideDistanceDelta({ distanceMeters: 12_345.6 })).toBe(12_345.6);
    expect(rideDistanceDelta({ distanceMeters: null })).toBe(0);
    expect(rideDistanceDelta({ distanceMeters: '999999' })).toBe(0);
    expect(rideDistanceDelta({ distanceMeters: -50 })).toBe(0);
    expect(rideDistanceDelta({ distanceMeters: Number.POSITIVE_INFINITY })).toBe(0);
    expect(rideDistanceDelta(undefined)).toBe(0);
  });
});

describe('convoy led credit', () => {
  it('credits the owner when a convoy goes live', () => {
    expect(convoyLedOwnerUid(undefined, { ownerUid: 'u1', startedAt: 1 })).toBe('u1');
    expect(convoyLedOwnerUid({ ownerUid: 'u1', startedAt: null }, { ownerUid: 'u1', startedAt: 1 })).toBe(
      'u1',
    );
  });

  it('credits nothing for a convoy that never started, or for later writes', () => {
    expect(convoyLedOwnerUid(undefined, { ownerUid: 'u1', startedAt: null })).toBeNull();
    expect(
      convoyLedOwnerUid({ ownerUid: 'u1', startedAt: 1 }, { ownerUid: 'u1', startedAt: 1, endedAt: 2 }),
    ).toBeNull();
    expect(convoyLedOwnerUid({ ownerUid: 'u1', startedAt: 1 }, undefined)).toBeNull();
    expect(convoyLedOwnerUid(undefined, { startedAt: 1 })).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Streak (Trogen)
// ---------------------------------------------------------------------------

describe('streak day keys', () => {
  it('uses local Swedish calendar days, not UTC days', () => {
    // 22:30 UTC on 1 July is already 00:30 on 2 July in Stockholm (CEST).
    expect(streakDayKey(new Date('2026-07-01T22:30:00Z'))).toBe('2026-07-02');
    expect(streakDayKey(new Date('2026-07-01T21:59:00Z'))).toBe('2026-07-01');
    // Winter (CET, +01:00).
    expect(streakDayKey(new Date('2026-01-01T23:30:00Z'))).toBe('2026-01-02');
  });

  it('recognises consecutive calendar days across month, year and DST edges', () => {
    expect(isNextDay('2026-07-01', '2026-07-02')).toBe(true);
    expect(isNextDay('2026-07-31', '2026-08-01')).toBe(true);
    expect(isNextDay('2026-12-31', '2027-01-01')).toBe(true);
    // Stockholm DST changeovers: 23-hour and 25-hour days.
    expect(isNextDay('2026-03-28', '2026-03-29')).toBe(true);
    expect(isNextDay('2026-10-24', '2026-10-25')).toBe(true);
    expect(isNextDay('2026-07-01', '2026-07-03')).toBe(false);
    expect(isNextDay('2026-07-02', '2026-07-01')).toBe(false);
    expect(isNextDay('not-a-day', '2026-07-01')).toBe(false);
  });
});

describe('streak advancement', () => {
  it('starts a streak at one on the first ever app open', () => {
    const { state, changed } = advanceStreak(readStreakState(undefined), '2026-07-01');
    expect(changed).toBe(true);
    expect(state).toEqual({
      currentDayStreak: 1,
      bestDayStreak: 1,
      lastStreakDayKey: '2026-07-01',
    });
  });

  it('does not grow, and writes nothing, on a second open the same day', () => {
    const day1 = advanceStreak(readStreakState(undefined), '2026-07-01').state;
    const again = advanceStreak(day1, '2026-07-01');
    expect(again.changed).toBe(false);
    expect(again.state).toEqual(day1);
  });

  it('grows by one per consecutive day and reaches Trogen Brons on day 7', () => {
    let state = readStreakState(undefined);
    for (let day = 1; day <= 7; day += 1) {
      state = advanceStreak(state, `2026-07-0${day}`).state;
    }
    expect(state.currentDayStreak).toBe(7);
    expect(state.bestDayStreak).toBe(7);
    expect(qualifiedTierBadges(counters({ bestDayStreak: state.bestDayStreak }))).toEqual([
      'trogen_brons',
    ]);
  });

  it('resets the current run on a gap but NEVER lowers the best run', () => {
    let state = readStreakState(undefined);
    for (let day = 1; day <= 9; day += 1) {
      state = advanceStreak(state, `2026-07-${String(day).padStart(2, '0')}`).state;
    }
    expect(state.bestDayStreak).toBe(9);
    const afterGap = advanceStreak(state, '2026-07-20').state;
    expect(afterGap.currentDayStreak).toBe(1);
    // The earned Trogen Brons survives the broken streak — this is the whole
    // point of measuring bestDayStreak rather than the live run.
    expect(afterGap.bestDayStreak).toBe(9);
    expect(qualifiedTierBadges(counters({ bestDayStreak: afterGap.bestDayStreak }))).toContain(
      'trogen_brons',
    );
  });

  it('restarts rather than rewinds when a day key goes backwards', () => {
    const state = advanceStreak(readStreakState(undefined), '2026-07-10').state;
    const back = advanceStreak(state, '2026-07-09');
    expect(back.changed).toBe(true);
    expect(back.state.currentDayStreak).toBe(1);
    expect(back.state.bestDayStreak).toBe(1);
  });

  it('reads a corrupt stored streak state as empty', () => {
    expect(readStreakState({ currentDayStreak: 'many', bestDayStreak: -1, lastStreakDayKey: 7 })).toEqual(
      { currentDayStreak: 0, bestDayStreak: 0, lastStreakDayKey: null },
    );
  });
});

// ---------------------------------------------------------------------------
// Presentation helpers + document shape
// ---------------------------------------------------------------------------

describe('presentation', () => {
  it('reports the highest tier held per ladder without implying the others are lost', () => {
    expect(
      highestHeldTierPerLadder([
        'first_event',
        'kronjagare_brons',
        'kronjagare_silver',
        'samlare_brons',
      ]),
    ).toEqual({ kronjagare: 'kronjagare_silver', samlare: 'samlare_brons' });
    expect(highestHeldTierPerLadder([])).toEqual({});
  });

  it('denormalizes ladder and tier onto the award document', () => {
    const document = buildBadgeDocument(
      'vagfarare_guld',
      { source: 'automatic', awardedByUserId: null },
      () => 'TS',
    );
    expect(document).toMatchObject({
      badgeKey: 'vagfarare_guld',
      name: 'Vägfarare Guld',
      iconIdentifier: 'badge_vagfarare_guld',
      ladder: 'vagfarare',
      tier: 'guld',
      source: 'automatic',
      awardedByUserId: null,
      awardedAt: 'TS',
    });
  });

  it('writes null ladder/tier for the standalone badges', () => {
    const document = buildBadgeDocument(
      'helpful_member',
      { source: 'admin_manual', awardedByUserId: 'admin1' },
      () => 'TS',
    );
    expect(document).toMatchObject({ ladder: null, tier: null, awardedByUserId: 'admin1' });
  });
});

// ---------------------------------------------------------------------------
// Localization parity
//
// The Android badge screen looks a badge name up by key and falls back to the
// denormalized (always Swedish) name from the document when the key is unknown
// (apps/android/.../badges/BadgeStrings.kt). A badge added to the catalog
// without localization entries therefore ships Swedish text to English-locale
// members SILENTLY. These tests make that impossible rather than asserting it
// in prose.
// ---------------------------------------------------------------------------

describe('localization parity', () => {
  // Walk up from the working directory to the repo root rather than using
  // `import.meta.url`: this file is type-checked under tsconfig.test.json,
  // whose `module` setting does not permit import.meta.
  const repoRoot = (() => {
    let dir = process.cwd();
    for (let up = 0; up < 6; up += 1) {
      if (existsSync(join(dir, 'contracts', 'localization', 'sv.json'))) {
        return dir;
      }
      dir = dirname(dir);
    }
    throw new Error(`Could not locate the repo root from ${process.cwd()}`);
  })();
  const repoFile = (...segments: string[]): string => join(repoRoot, ...segments);
  const badgeNames = (locale: 'sv' | 'en'): Record<string, string> =>
    JSON.parse(readFileSync(repoFile('contracts', 'localization', `${locale}.json`), 'utf8')).badges
      .badgeNames;

  it('localizes every catalog key in both Swedish and English', () => {
    const sv = badgeNames('sv');
    const en = badgeNames('en');
    expect(Object.keys(sv)).toEqual([...BADGE_CATALOG_ORDER]);
    // Identical key SETS in both locales, so neither can drift ahead.
    expect(Object.keys(en)).toEqual(Object.keys(sv));
    for (const key of BADGE_CATALOG_ORDER) {
      expect(sv[key]?.length ?? 0).toBeGreaterThan(0);
      expect(en[key]?.length ?? 0).toBeGreaterThan(0);
    }
  });

  it('matches the Swedish catalog names exactly for the tier ladders', () => {
    const sv = badgeNames('sv');
    for (const key of TIER_BADGE_KEYS) {
      expect(sv[key]).toBe(BADGE_CATALOG[key].name);
    }
  });

  it('uses genuinely English tier words in the English locale', () => {
    const en = badgeNames('en');
    for (const key of TIER_BADGE_KEYS) {
      expect(en[key]).toBe(BADGE_CATALOG[key].nameEn);
      // "Crown Hunter brons" would be half-translated; catch it.
      expect(en[key]).not.toMatch(/\b(brons|guld|platina)\b/i);
    }
  });

  it('maps every catalog key in the Android lookup', () => {
    // Cross-lane guard: the `when` in BadgeStrings.kt is the only thing that
    // turns a localized string into a rendered badge name.
    const kotlin = readFileSync(
      repoFile(
        'apps/android/app/src/main/java/com/kungsbackacarcommunity/app/badges/BadgeStrings.kt',
      ),
      'utf8',
    );
    for (const key of BADGE_CATALOG_ORDER) {
      expect(kotlin).toContain(`"${key}" -> R.string.badges_badgeNames_${key}`);
    }
  });
});
