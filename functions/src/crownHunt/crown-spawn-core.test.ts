import { describe, expect, it } from 'vitest';

import {
  ACTIVITY_TAU_MS,
  ACTIVITY_WINDOW_MS,
  COLLECT_RADIUS_METERS,
  CROWN_BASELINE_TARGET_PER_CELL,
  CROWN_CELL_DEGREES,
  CROWN_RARITIES,
  CROWN_RARITY_TABLE,
  DENSITY_K,
  MAX_ACTIVITY_SPEED_MPS,
  MAX_COLLECT_SPEED_MPS,
  MAX_CROWNS_PER_CELL,
  MAX_STORED_COLLECT_RADIUS_METERS,
  MIN_CROWN_SEPARATION_METERS,
  MIN_DWELL_SECONDS,
  activityScore,
  activityWeight,
  buildCrownSpawnFields,
  createSeededRng,
  crownActivityUserHash,
  crownCellBounds,
  crownCellKey,
  crownCollectMode,
  crownExpiresAt,
  crownRewardPoints,
  crownTtlMs,
  evaluateStationaryCollection,
  isActivitySightingEligible,
  isFarEnoughFromAll,
  neighbourCrownCells,
  parseClaimSpawnInput,
  parseCrownCellKey,
  parseSetSpawnCellApprovalInput,
  pickCrownRarity,
  resolveCollectRadiusMeters,
  resolveSpawnCellKey,
  sampleCrownPosition,
  scopeSpawnClaimKey,
  shouldRecordCrownActivity,
  spawnDailyCounterDocId,
  targetCrownCount,
  type CrownFix,
  type CrownPosition,
  type CrownRarity,
} from './crown-spawn-core';
import { MAX_REPORTED_ACCURACY_METERS } from './crownhunt-core';
import { haversineDistanceMeters } from './crown-hunt-geo';
import { isFirestoreSafeId } from '../points/points-core';

const DAY_MS = 24 * 60 * 60 * 1000;

// Sergels torg, Stockholm — the reference point used across the suite.
const LAT = 59.3326;
const LON = 18.0649;

describe('crown spawn grid', () => {
  it('keys nearby coordinates into the same cell and distant ones apart', () => {
    const a = crownCellKey(59.3326, 18.0649);
    const b = crownCellKey(59.3329, 18.0651); // ~35 m away
    const c = crownCellKey(59.3426, 18.0649); // ~1.1 km north
    expect(a).toBe(b);
    expect(a).not.toBe(c);
  });

  it('floors both axes, including across zero', () => {
    expect(crownCellKey(0, 0)).toBe('0_0');
    expect(crownCellKey(CROWN_CELL_DEGREES + 0.001, 0)).toBe('1_0');
    expect(crownCellKey(-0.001, 0)).toBe('-1_0');
    expect(crownCellKey(0, -0.001)).toBe('0_-1');
  });

  it('round-trips a key through parse and bounds', () => {
    const key = crownCellKey(LAT, LON);
    const parsed = parseCrownCellKey(key)!;
    expect(parsed).not.toBeNull();
    const bounds = crownCellBounds(key)!;
    expect(bounds.minLat).toBeLessThanOrEqual(LAT);
    expect(bounds.maxLat).toBeGreaterThan(LAT);
    expect(bounds.minLon).toBeLessThanOrEqual(LON);
    expect(bounds.maxLon).toBeGreaterThan(LON);
    // The box is exactly one cell wide on both axes.
    expect(bounds.maxLat - bounds.minLat).toBeCloseTo(CROWN_CELL_DEGREES, 10);
    expect(bounds.maxLon - bounds.minLon).toBeCloseTo(CROWN_CELL_DEGREES, 10);
  });

  it('rejects malformed cell keys rather than guessing', () => {
    for (const bad of ['', 'abc', '1_', '_1', '1_2_3', '1.5_2', 'NaN_0', '1__2']) {
      expect(parseCrownCellKey(bad)).toBeNull();
      expect(crownCellBounds(bad)).toBeNull();
      expect(neighbourCrownCells(bad)).toEqual([]);
    }
  });

  it('rejects cell keys that are not anywhere on the globe', () => {
    // The regex alone accepts six digits per axis, and this reaches us from
    // admin input on setSpawnCellApproval. A key that is not a place has no
    // correct interpretation, so it is refused rather than clamped.
    for (const offGlobe of ['9001_0', '-9001_0', '0_18001', '0_-18001', '999999_999999']) {
      expect(parseCrownCellKey(offGlobe)).toBeNull();
      expect(crownCellBounds(offGlobe)).toBeNull();
    }
    // The extremes themselves are legal: crownCellKey clamps latitude 90 onto
    // the last row, so that row has to parse.
    expect(parseCrownCellKey('9000_0')).toEqual({ latIdx: 9000, lonIdx: 0 });
    expect(parseCrownCellKey('-9000_18000')).toEqual({ latIdx: -9000, lonIdx: 18000 });
  });

  it('never returns a bound outside the globe on EITHER axis', () => {
    // sampleCrownPosition draws uniformly inside these bounds, so an unclamped
    // top edge (90.01) would be written to a crown document as an invalid
    // WGS-84 coordinate.
    const polar = crownCellKey(90, 0);
    const bounds = crownCellBounds(polar)!;
    expect(bounds.maxLat).toBeLessThanOrEqual(90);
    expect(bounds.minLat).toBeGreaterThanOrEqual(-90);
    // There is no strip of Earth above 90, so the polar row is zero-height.
    expect(bounds.maxLat).toBe(90);
    expect(bounds.minLat).toBe(90);

    const antipolar = crownCellBounds(crownCellKey(-90, 0))!;
    expect(antipolar.minLat).toBeGreaterThanOrEqual(-90);
    expect(antipolar.maxLat).toBeLessThanOrEqual(90);

    // Longitude has the same last-column problem at the antimeridian: 180
    // floors to the final column whose unclamped upper edge would be 180.01.
    const antimeridian = crownCellKey(0, 180);
    const lonBounds = crownCellBounds(antimeridian)!;
    expect(lonBounds.maxLon).toBeLessThanOrEqual(180);
    expect(lonBounds.minLon).toBeGreaterThanOrEqual(-180);
    expect(lonBounds.maxLon).toBe(180);
    expect(lonBounds.minLon).toBe(180);

    // Sampling inside either edge cell still yields a valid coordinate.
    for (const key of [polar, antimeridian, crownCellKey(90, 180)]) {
      const sampled = sampleCrownPosition(key, [], createSeededRng(3));
      expect(sampled).not.toBeNull();
      expect(sampled!.latitude).toBeLessThanOrEqual(90);
      expect(sampled!.latitude).toBeGreaterThanOrEqual(-90);
      expect(sampled!.longitude).toBeLessThanOrEqual(180);
      expect(sampled!.longitude).toBeGreaterThanOrEqual(-180);
    }
  });

  it('only ever produces cell keys that parse', () => {
    // crownCellKey clamps both axes, so its output is always on the globe and
    // always round-trips through parseCrownCellKey — including for coordinates
    // outside the legal range, which is what makes the range check above safe
    // to apply to keys this function generated.
    for (const [lat, lon] of [
      [90, 180],
      [-90, -180],
      [91, 181],
      [-91, -181],
      [1e6, -1e6],
    ] as const) {
      expect(parseCrownCellKey(crownCellKey(lat, lon))).not.toBeNull();
    }
  });

  it('enumerates a 3x3 neighbourhood containing the cell itself', () => {
    const key = crownCellKey(LAT, LON);
    const neighbours = neighbourCrownCells(key);
    expect(neighbours).toHaveLength(9);
    expect(new Set(neighbours).size).toBe(9);
    expect(neighbours).toContain(key);
    // Within Firestore's `in` limit, which is why it can be one query.
    expect(neighbours.length).toBeLessThanOrEqual(30);
  });
});

describe('activity decay', () => {
  const now = Date.UTC(2026, 6, 24, 12, 0, 0);

  it('weights a sighting right now at 1', () => {
    expect(activityWeight(now, now)).toBeCloseTo(1, 10);
  });

  it('weights a sighting one tau old at 1/e', () => {
    expect(activityWeight(now - ACTIVITY_TAU_MS, now)).toBeCloseTo(Math.exp(-1), 10);
  });

  it('decays monotonically with age', () => {
    const ages = [0, 1, 2, 3, 4, 5, 6].map((d) => activityWeight(now - d * DAY_MS, now));
    for (let i = 1; i < ages.length; i += 1) {
      expect(ages[i]!).toBeLessThan(ages[i - 1]!);
    }
  });

  it('drops sightings outside the 7-day window entirely', () => {
    expect(activityWeight(now - ACTIVITY_WINDOW_MS + 1000, now)).toBeGreaterThan(0);
    expect(activityWeight(now - ACTIVITY_WINDOW_MS - 1000, now)).toBe(0);
  });

  it('never lets a FUTURE timestamp outweigh a present one', () => {
    // exp(+x) > 1 would make a forged or clock-skewed future date the most
    // valuable input in the system.
    expect(activityWeight(now + DAY_MS, now)).toBe(0);
    expect(activityWeight(Number.NaN, now)).toBe(0);
    expect(activityWeight(now, Number.NaN)).toBe(0);
  });

  it('sums distinct users into A(cell)', () => {
    const score = activityScore([now, now - ACTIVITY_TAU_MS, now - 30 * DAY_MS], now);
    expect(score).toBeCloseTo(1 + Math.exp(-1), 10);
  });

  it('scores an empty cell at exactly zero', () => {
    expect(activityScore([], now)).toBe(0);
  });
});

describe('activity sighting eligibility (the motorway filter)', () => {
  it('counts slow presence', () => {
    expect(isActivitySightingEligible(0)).toBe(true);
    expect(isActivitySightingEligible(1.2)).toBe(true);
    expect(isActivitySightingEligible(MAX_ACTIVITY_SPEED_MPS)).toBe(true);
  });

  it('discards driving speeds, so a through-road cannot accumulate a score', () => {
    expect(isActivitySightingEligible(MAX_ACTIVITY_SPEED_MPS + 0.01)).toBe(false);
    expect(isActivitySightingEligible(13.9)).toBe(false); // urban 50 km/h
    expect(isActivitySightingEligible(30)).toBe(false); // motorway
  });

  it('discards unknown or invalid speeds (an unproven place is not a safe place)', () => {
    expect(isActivitySightingEligible(null)).toBe(false);
    expect(isActivitySightingEligible(undefined)).toBe(false);
    expect(isActivitySightingEligible(-1)).toBe(false);
    expect(isActivitySightingEligible(Number.NaN)).toBe(false);
  });
});

describe('target density N_target = ceil(K * ln(1 + A))', () => {
  it('is ZERO below the activity floor — never spawn where nobody goes', () => {
    for (const a of [0, 0.001, 0.5, 0.99, 0.999999]) {
      expect(targetCrownCount(a)).toBe(0);
    }
  });

  it('follows the ln curve at and above the floor', () => {
    expect(targetCrownCount(1)).toBe(Math.ceil(DENSITY_K * Math.log(2))); // 2
    expect(targetCrownCount(1)).toBe(2);
    expect(targetCrownCount(5)).toBe(3);
    expect(targetCrownCount(10)).toBe(4);
    expect(targetCrownCount(20)).toBe(5);
  });

  it('never exceeds the per-cell cap, however busy the cell', () => {
    for (const a of [27, 100, 1_000, 1e6]) {
      expect(targetCrownCount(a)).toBe(MAX_CROWNS_PER_CELL);
    }
  });

  it('is monotonically non-decreasing in A', () => {
    let previous = 0;
    for (let a = 0; a <= 200; a += 0.25) {
      const target = targetCrownCount(a);
      expect(target).toBeGreaterThanOrEqual(previous);
      previous = target;
    }
  });

  it('rejects non-finite activity instead of spawning', () => {
    expect(targetCrownCount(Number.NaN)).toBe(0);
    expect(targetCrownCount(Number.POSITIVE_INFINITY)).toBe(0);
  });
});

describe('target density with a BASELINE (POI-anchored area path)', () => {
  it('defaults to no baseline, leaving the pure activity curve intact', () => {
    // No baseline option ⇒ identical to the activity-only formula. This is what
    // keeps the random single-cell path (which passes no baseline) unchanged.
    for (const a of [0, 0.5, 1, 5, 10, 20, 100]) {
      expect(targetCrownCount(a, {})).toBe(targetCrownCount(a));
    }
    expect(targetCrownCount(0, { baseline: 0 })).toBe(0);
  });

  it('places the baseline even with ZERO activity — the whole point of the change', () => {
    // A = 0 is below the floor, so activityDerived = 0; only the baseline remains.
    expect(targetCrownCount(0, { baseline: 1 })).toBe(1);
    expect(targetCrownCount(0.5, { baseline: 1 })).toBe(1);
    expect(targetCrownCount(0, { baseline: 2 })).toBe(2);
    // And the shipped constant behaves the same way at A = 0.
    expect(targetCrownCount(0, { baseline: CROWN_BASELINE_TARGET_PER_CELL })).toBe(
      CROWN_BASELINE_TARGET_PER_CELL,
    );
  });

  it('adds activity ON TOP of the baseline', () => {
    // activityDerived(1) = ceil(1.5*ln2) = 2, so baseline 1 ⇒ 3.
    expect(targetCrownCount(1, { baseline: 1 })).toBe(targetCrownCount(1) + 1);
    // activityDerived(5) = 3, so baseline 1 ⇒ 4.
    expect(targetCrownCount(5, { baseline: 1 })).toBe(4);
    expect(targetCrownCount(5, { baseline: 2 })).toBe(5);
  });

  it('clamps baseline + activity to the per-cell cap', () => {
    // activityDerived(20) already = 5 (the cap); any baseline cannot exceed it.
    expect(targetCrownCount(20, { baseline: 1 })).toBe(MAX_CROWNS_PER_CELL);
    expect(targetCrownCount(1000, { baseline: 5 })).toBe(MAX_CROWNS_PER_CELL);
    // A baseline larger than the cap is itself clamped, even at zero activity.
    expect(targetCrownCount(0, { baseline: 99 })).toBe(MAX_CROWNS_PER_CELL);
  });

  it('a baseline of 0 restores the activity floor (the disable guard)', () => {
    for (const a of [0, 0.001, 0.5, 0.999]) {
      expect(targetCrownCount(a, { baseline: 0 })).toBe(0);
    }
  });

  it('treats a malformed baseline as a value that can only LOWER the target', () => {
    // Non-finite, negative, or fractional baselines never inject crowns: they
    // clamp to a non-negative integer (floor), and non-finite ⇒ 0.
    expect(targetCrownCount(0, { baseline: -5 })).toBe(0);
    expect(targetCrownCount(0, { baseline: Number.NaN })).toBe(0);
    expect(targetCrownCount(0, { baseline: Number.POSITIVE_INFINITY })).toBe(0);
    expect(targetCrownCount(0, { baseline: 1.9 })).toBe(1); // floored to 1
    expect(targetCrownCount(0, { baseline: 0.9 })).toBe(0); // floored to 0
  });

  it('stays monotonically non-decreasing in A for a fixed baseline', () => {
    let previous = 0;
    for (let a = 0; a <= 200; a += 0.25) {
      const target = targetCrownCount(a, { baseline: CROWN_BASELINE_TARGET_PER_CELL });
      expect(target).toBeGreaterThanOrEqual(previous);
      expect(target).toBeGreaterThanOrEqual(CROWN_BASELINE_TARGET_PER_CELL);
      previous = target;
    }
  });

  it('ships a conservative, safe baseline constant', () => {
    // A guard on the shipped value: a positive baseline that is at most the cap.
    // (Set the constant to 0 to disable the baseline entirely.)
    expect(Number.isInteger(CROWN_BASELINE_TARGET_PER_CELL)).toBe(true);
    expect(CROWN_BASELINE_TARGET_PER_CELL).toBeGreaterThanOrEqual(0);
    expect(CROWN_BASELINE_TARGET_PER_CELL).toBeLessThanOrEqual(MAX_CROWNS_PER_CELL);
  });
});

describe('rarity weighted pick', () => {
  it('has weights summing to exactly 1', () => {
    const total = CROWN_RARITIES.reduce((sum, r) => sum + CROWN_RARITY_TABLE[r].weight, 0);
    expect(total).toBeCloseTo(1, 12);
  });

  it('maps roll boundaries to the documented tiers', () => {
    expect(pickCrownRarity(0)).toBe('common');
    expect(pickCrownRarity(0.699)).toBe('common');
    expect(pickCrownRarity(0.7)).toBe('uncommon');
    expect(pickCrownRarity(0.919)).toBe('uncommon');
    expect(pickCrownRarity(0.92)).toBe('rare');
    expect(pickCrownRarity(0.989)).toBe('rare');
    expect(pickCrownRarity(0.99)).toBe('legendary');
    expect(pickCrownRarity(0.9999999)).toBe('legendary');
  });

  it('falls back to common on a bad roll rather than failing to place a crown', () => {
    expect(pickCrownRarity(-1)).toBe('common');
    expect(pickCrownRarity(1)).toBe('common');
    expect(pickCrownRarity(Number.NaN)).toBe('common');
  });

  it('converges on the declared distribution over many draws', () => {
    const rng = createSeededRng(20260724);
    const draws = 200_000;
    const counts: Record<CrownRarity, number> = {
      common: 0,
      uncommon: 0,
      rare: 0,
      legendary: 0,
    };
    for (let i = 0; i < draws; i += 1) {
      counts[pickCrownRarity(rng())] += 1;
    }
    for (const rarity of CROWN_RARITIES) {
      const expected = CROWN_RARITY_TABLE[rarity].weight;
      // 3 standard errors of a binomial proportion, plus a small floor so the
      // legendary tier (p = .01) is not asserted to impossible precision.
      const tolerance = 3 * Math.sqrt((expected * (1 - expected)) / draws) + 0.0005;
      expect(Math.abs(counts[rarity] / draws - expected)).toBeLessThan(tolerance);
    }
    // Every tier must actually be reachable, not merely within tolerance of 0.
    for (const rarity of CROWN_RARITIES) {
      expect(counts[rarity]).toBeGreaterThan(0);
    }
  });

  it('is deterministic for a given seed', () => {
    const draw = (seed: number) => {
      const rng = createSeededRng(seed);
      return Array.from({ length: 20 }, () => pickCrownRarity(rng()));
    };
    expect(draw(7)).toEqual(draw(7));
    expect(draw(7)).not.toEqual(draw(8));
  });

  it('produces a uniform-looking stream in [0, 1)', () => {
    const rng = createSeededRng(1);
    let min = 1;
    let max = 0;
    let sum = 0;
    const n = 50_000;
    // Range violations are COUNTED and asserted once after the loop rather than
    // asserted per iteration: 100k `expect()` calls cost seconds and pushed this
    // test past the 5s timeout whenever the full suite ran under load, while
    // adding no signal a single assertion does not already give.
    let outOfRange = 0;
    for (let i = 0; i < n; i += 1) {
      const value = rng();
      if (!(value >= 0 && value < 1)) {
        outOfRange += 1;
      }
      min = Math.min(min, value);
      max = Math.max(max, value);
      sum += value;
    }
    expect(outOfRange).toBe(0);
    expect(sum / n).toBeCloseTo(0.5, 2);
    expect(min).toBeLessThan(0.001);
    expect(max).toBeGreaterThan(0.999);
  });
});

describe('rarity value and TTL', () => {
  it('pays the documented points per tier', () => {
    expect(crownRewardPoints('common')).toBe(10);
    expect(crownRewardPoints('uncommon')).toBe(25);
    expect(crownRewardPoints('rare')).toBe(100);
    expect(crownRewardPoints('legendary')).toBe(500);
  });

  it('lives the documented hours per tier', () => {
    expect(crownTtlMs('common')).toBe(6 * 60 * 60 * 1000);
    expect(crownTtlMs('uncommon')).toBe(12 * 60 * 60 * 1000);
    expect(crownTtlMs('rare')).toBe(24 * 60 * 60 * 1000);
    expect(crownTtlMs('legendary')).toBe(48 * 60 * 60 * 1000);
  });

  it('makes rarer crowns worth more AND longer-lived', () => {
    for (let i = 1; i < CROWN_RARITIES.length; i += 1) {
      const prev = CROWN_RARITY_TABLE[CROWN_RARITIES[i - 1]!];
      const curr = CROWN_RARITY_TABLE[CROWN_RARITIES[i]!];
      expect(curr.points).toBeGreaterThan(prev.points);
      expect(curr.ttlHours).toBeGreaterThan(prev.ttlHours);
      expect(curr.weight).toBeLessThan(prev.weight);
    }
  });

  it('every tier expires — no crown becomes a permanent farmable fixture', () => {
    const now = new Date('2026-07-24T12:00:00.000Z');
    for (const rarity of CROWN_RARITIES) {
      const expiry = crownExpiresAt(rarity, now);
      expect(expiry.getTime()).toBeGreaterThan(now.getTime());
      expect(expiry.getTime() - now.getTime()).toBeLessThanOrEqual(48 * 60 * 60 * 1000);
    }
  });
});

describe('minimum separation', () => {
  /** A point `meters` due north of (LAT, LON). */
  const north = (meters: number): CrownPosition => ({
    latitude: LAT + meters / 111_320,
    longitude: LON,
  });

  it('accepts a candidate with no neighbours', () => {
    expect(isFarEnoughFromAll({ latitude: LAT, longitude: LON }, [])).toBe(true);
  });

  it('rejects anything inside the separation radius and accepts just outside', () => {
    const others = [{ latitude: LAT, longitude: LON }];
    expect(isFarEnoughFromAll(north(10), others)).toBe(false);
    expect(isFarEnoughFromAll(north(MIN_CROWN_SEPARATION_METERS - 5), others)).toBe(false);
    expect(isFarEnoughFromAll(north(MIN_CROWN_SEPARATION_METERS + 5), others)).toBe(true);
  });

  it('rejects when ANY neighbour is too close, not merely the nearest listed', () => {
    const others = [north(1000), north(20), north(2000)];
    expect(isFarEnoughFromAll({ latitude: LAT, longitude: LON }, others)).toBe(false);
  });

  it('keeps live crowns at least two collect radii apart', () => {
    // At exactly 2r the two collect circles are tangent, so a member standing
    // anywhere except the single midpoint is inside at most one of them. Any
    // SMALLER separation would make overlapping crowns routine, and one parked
    // car could sweep several rewards without moving.
    expect(MIN_CROWN_SEPARATION_METERS).toBeGreaterThanOrEqual(2 * COLLECT_RADIUS_METERS);
  });
});

describe('position sampling (rejection / dart throwing)', () => {
  const cellKey = crownCellKey(LAT, LON);

  it('always samples inside the requested cell', () => {
    const rng = createSeededRng(99);
    for (let i = 0; i < 500; i += 1) {
      const position = sampleCrownPosition(cellKey, [], rng)!;
      expect(position).not.toBeNull();
      expect(crownCellKey(position.latitude, position.longitude)).toBe(cellKey);
    }
  });

  it('never places a crown within the separation radius of an existing one', () => {
    const rng = createSeededRng(1234);
    const placed: CrownPosition[] = [];
    for (let i = 0; i < MAX_CROWNS_PER_CELL; i += 1) {
      const position = sampleCrownPosition(cellKey, placed, rng);
      if (!position) break;
      placed.push(position);
    }
    expect(placed.length).toBeGreaterThan(1);
    for (let i = 0; i < placed.length; i += 1) {
      for (let j = i + 1; j < placed.length; j += 1) {
        const distance = haversineDistanceMeters(
          placed[i]!.latitude,
          placed[i]!.longitude,
          placed[j]!.latitude,
          placed[j]!.longitude,
        );
        expect(distance).toBeGreaterThanOrEqual(MIN_CROWN_SEPARATION_METERS);
      }
    }
  });

  it('gives up (returns null) instead of looping when the cell is saturated', () => {
    // A separation larger than the cell itself can never be satisfied.
    const position = sampleCrownPosition(
      cellKey,
      [{ latitude: LAT, longitude: LON }],
      createSeededRng(5),
      { minSeparationMeters: 100_000 },
    );
    expect(position).toBeNull();
  });

  it('respects a bounded attempt budget', () => {
    let calls = 0;
    const countingRng = () => {
      calls += 1;
      return Math.random();
    };
    sampleCrownPosition(cellKey, [{ latitude: LAT, longitude: LON }], countingRng, {
      minSeparationMeters: 100_000,
      maxAttempts: 6,
    });
    // Two draws per attempt (lat, lon), never unbounded.
    expect(calls).toBeLessThanOrEqual(12);
  });

  it('returns null for a malformed cell key rather than sampling nowhere', () => {
    expect(sampleCrownPosition('not-a-cell', [], createSeededRng(1))).toBeNull();
  });
});

describe('stationary collection rule', () => {
  const base = Date.UTC(2026, 6, 24, 12, 0, 0);

  const fix = (overrides: Partial<CrownFix> = {}): CrownFix => ({
    distanceMeters: 20,
    speedMetersPerSecond: 0,
    accuracyMeters: 8,
    recordedAtMs: base,
    ...overrides,
  });

  const evaluate = (
    current: Partial<CrownFix>,
    previous: Partial<CrownFix>,
    movedMeters = 1,
  ) =>
    evaluateStationaryCollection({
      current: fix({ recordedAtMs: base + 6000, ...current }),
      previous: fix(previous),
      movedMeters,
    });

  it('accepts a member standing still inside the radius', () => {
    expect(evaluate({}, {})).toEqual({ ok: true });
  });

  it('rejects when EITHER fix is outside the collect radius', () => {
    expect(evaluate({ distanceMeters: 500, accuracyMeters: 0 }, {})).toEqual({
      ok: false,
      result: 'outside_radius',
    });
    expect(evaluate({}, { distanceMeters: 500, accuracyMeters: 0 })).toEqual({
      ok: false,
      result: 'outside_radius',
    });
  });

  it('rejects a dwell shorter than the minimum', () => {
    expect(evaluate({ recordedAtMs: base + (MIN_DWELL_SECONDS - 1) * 1000 }, {})).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
  });

  it('accepts a dwell exactly at the minimum', () => {
    expect(evaluate({ recordedAtMs: base + MIN_DWELL_SECONDS * 1000 }, {})).toEqual({ ok: true });
  });

  it('rejects two fixes in the same instant, and a reversed pair', () => {
    expect(evaluate({ recordedAtMs: base }, {})).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
    expect(evaluate({ recordedAtMs: base - 10_000 }, {})).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
  });

  it('rejects a stale pair whose earlier fix is too old to prove anything', () => {
    expect(evaluate({ recordedAtMs: base + 10 * 60 * 1000 }, {})).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
  });

  it('rejects a REPORTED speed above the ceiling on either fix', () => {
    expect(evaluate({ speedMetersPerSecond: MAX_COLLECT_SPEED_MPS + 0.5 }, {})).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
    expect(evaluate({}, { speedMetersPerSecond: MAX_COLLECT_SPEED_MPS + 0.5 })).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
  });

  it('accepts a reported speed exactly at the ceiling', () => {
    expect(
      evaluate(
        { speedMetersPerSecond: MAX_COLLECT_SPEED_MPS },
        { speedMetersPerSecond: MAX_COLLECT_SPEED_MPS },
        // 6 s of dwell at 2.0 m/s is 12 m, so the derived speed also passes.
        12,
      ),
    ).toEqual({ ok: true });
  });

  it('rejects a MOVING claim on the derived speed even when the client omits speed', () => {
    // The whole point of the derived check: a client can send null speeds, but
    // it cannot send two coordinates 60 m apart 6 s apart and call it stopped.
    expect(
      evaluate(
        { speedMetersPerSecond: null },
        { speedMetersPerSecond: null },
        60, // 10 m/s
      ),
    ).toEqual({ ok: false, result: 'must_be_stationary' });
  });

  it('rejects a moving claim even when the client LIES about speed', () => {
    expect(evaluate({ speedMetersPerSecond: 0 }, { speedMetersPerSecond: 0 }, 200)).toEqual({
      ok: false,
      result: 'must_be_stationary',
    });
  });

  it('buffers for reported GPS accuracy at the radius edge, conservatively', () => {
    // Half the reported accuracy is added, exactly as the hand-placed points do.
    const justOutside = COLLECT_RADIUS_METERS + 10;
    expect(evaluate({ distanceMeters: justOutside, accuracyMeters: 0 }, {}).ok).toBe(false);
    expect(
      evaluate(
        { distanceMeters: justOutside, accuracyMeters: 40 },
        { distanceMeters: justOutside, accuracyMeters: 40 },
      ).ok,
    ).toBe(true);
  });

  it('checks the radius BEFORE the dwell, so a far-away member is told the useful thing', () => {
    expect(
      evaluate({ distanceMeters: 5000, accuracyMeters: 0, recordedAtMs: base }, {}),
    ).toEqual({ ok: false, result: 'outside_radius' });
  });
});

describe('resolveCollectRadiusMeters', () => {
  it('uses a sane stored radius as-is', () => {
    expect(resolveCollectRadiusMeters(COLLECT_RADIUS_METERS)).toBe(COLLECT_RADIUS_METERS);
    expect(resolveCollectRadiusMeters(120)).toBe(120);
    expect(resolveCollectRadiusMeters(MAX_STORED_COLLECT_RADIUS_METERS)).toBe(
      MAX_STORED_COLLECT_RADIUS_METERS,
    );
  });

  it('falls back to the default when the field is missing', () => {
    expect(resolveCollectRadiusMeters(undefined)).toBe(COLLECT_RADIUS_METERS);
    expect(resolveCollectRadiusMeters(null)).toBe(COLLECT_RADIUS_METERS);
  });

  it('NEVER widens the gate from a corrupt document', () => {
    // An oversized radius is the only corruption that fails open — every value
    // here must come back as the 75 m default, never as the stored one.
    for (const corrupt of [
      MAX_STORED_COLLECT_RADIUS_METERS + 1,
      1e9,
      Number.MAX_SAFE_INTEGER,
      Number.POSITIVE_INFINITY,
      '1e9',
      '999999',
      {},
      [],
      true,
    ]) {
      expect(resolveCollectRadiusMeters(corrupt)).toBe(COLLECT_RADIUS_METERS);
    }
  });

  it('rejects zero, negatives and NaN rather than producing a degenerate gate', () => {
    for (const bad of [0, -1, -1000, Number.NaN, Number.NEGATIVE_INFINITY]) {
      expect(resolveCollectRadiusMeters(bad)).toBe(COLLECT_RADIUS_METERS);
    }
  });
});

describe('activity write throttle', () => {
  const now = new Date('2026-07-24T12:00:00.000Z');

  it('writes on the first sample of a session', () => {
    expect(shouldRecordCrownActivity(null, 'a_b', now)).toBe(true);
    expect(shouldRecordCrownActivity(undefined, 'a_b', now)).toBe(true);
  });

  it('always writes immediately when the cell changes', () => {
    expect(
      shouldRecordCrownActivity(
        { recordedAtIso: now.toISOString(), cellKey: 'a_b' },
        'a_c',
        now,
      ),
    ).toBe(true);
  });

  it('skips inside the interval and writes once it elapses', () => {
    const prev = { recordedAtIso: now.toISOString(), cellKey: 'a_b' };
    expect(shouldRecordCrownActivity(prev, 'a_b', new Date(now.getTime() + 60_000))).toBe(false);
    expect(shouldRecordCrownActivity(prev, 'a_b', new Date(now.getTime() + 11 * 60_000))).toBe(
      true,
    );
  });

  it('fails toward writing on unparseable state', () => {
    expect(shouldRecordCrownActivity({ recordedAtIso: 'nonsense', cellKey: 'a_b' }, 'a_b', now)).toBe(
      true,
    );
    expect(shouldRecordCrownActivity({ cellKey: 'a_b' }, 'a_b', now)).toBe(true);
  });
});

describe('deterministic identifiers', () => {
  it('produces Firestore-safe claim keys', () => {
    const key = scopeSpawnClaimKey('user-1', 'client-key');
    expect(key).toMatch(/^[0-9a-f]{64}$/);
    expect(isFirestoreSafeId(key)).toBe(true);
  });

  it('is stable for the same input and distinct for different ones', () => {
    expect(scopeSpawnClaimKey('u', 'k')).toBe(scopeSpawnClaimKey('u', 'k'));
    expect(scopeSpawnClaimKey('u', 'k')).not.toBe(scopeSpawnClaimKey('u2', 'k'));
    expect(scopeSpawnClaimKey('u', 'k')).not.toBe(scopeSpawnClaimKey('u', 'k2'));
  });

  it('cannot be forged by moving a separator between the parts', () => {
    // Length prefixing is what makes the encoding injective.
    expect(scopeSpawnClaimKey('ab', 'c')).not.toBe(scopeSpawnClaimKey('a', 'bc'));
  });

  it('buckets daily counters by UTC day, not local midnight', () => {
    const a = spawnDailyCounterDocId('u', new Date('2026-07-24T00:00:00.000Z'));
    const b = spawnDailyCounterDocId('u', new Date('2026-07-24T23:59:59.999Z'));
    const c = spawnDailyCounterDocId('u', new Date('2026-07-25T00:00:00.000Z'));
    expect(a).toBe(b);
    expect(a).not.toBe(c);
  });

  it('scopes the activity pseudonym PER CELL so it cannot be joined into a route', () => {
    const uid = 'user-1';
    expect(crownActivityUserHash('a_b', uid)).toBe(crownActivityUserHash('a_b', uid));
    expect(crownActivityUserHash('a_b', uid)).not.toBe(crownActivityUserHash('a_c', uid));
    expect(crownActivityUserHash('a_b', uid)).not.toBe(crownActivityUserHash('a_b', 'user-2'));
  });

  it('never embeds the raw uid in the activity pseudonym', () => {
    expect(crownActivityUserHash('a_b', 'user-1')).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe('claimSpawn input parsing', () => {
  const valid = {
    spawnId: 'spawn-1',
    latitude: LAT,
    longitude: LON,
    accuracyMeters: 10,
    speedMetersPerSecond: 0,
    recordedAt: '2026-07-24T12:00:06.000Z',
    previousFix: {
      latitude: LAT,
      longitude: LON,
      accuracyMeters: 10,
      speedMetersPerSecond: 0,
      recordedAt: '2026-07-24T12:00:00.000Z',
    },
    idempotencyKey: 'key-1',
  };

  it('accepts a well-formed claim', () => {
    expect(parseClaimSpawnInput(valid).ok).toBe(true);
  });

  it('REQUIRES the second fix — one sample cannot prove a member is stopped', () => {
    const withoutPrevious: Record<string, unknown> = { ...valid };
    delete withoutPrevious.previousFix;
    expect(parseClaimSpawnInput(withoutPrevious).ok).toBe(false);
  });

  it('rejects negative and non-finite speeds outright', () => {
    expect(parseClaimSpawnInput({ ...valid, speedMetersPerSecond: -1 }).ok).toBe(false);
    expect(parseClaimSpawnInput({ ...valid, accuracyMeters: -1 }).ok).toBe(false);
  });

  it('rejects a spawnId that is not a safe document id', () => {
    for (const spawnId of ['', '.', '..', 'a/b', 'a b']) {
      expect(parseClaimSpawnInput({ ...valid, spawnId }).ok).toBe(false);
    }
  });

  it('rejects unknown fields rather than ignoring them', () => {
    expect(parseClaimSpawnInput({ ...valid, distanceMeters: 1 }).ok).toBe(false);
  });

  it('accepts the optional mock-location and integrity signals', () => {
    expect(parseClaimSpawnInput({ ...valid, isMockLocation: true }).ok).toBe(true);
    expect(parseClaimSpawnInput({ ...valid, platformIntegrityPassed: false }).ok).toBe(true);
  });
});

/**
 * `accuracyMeters` is client-controlled and feeds both the geofence buffer and
 * the risk scorer, so it is bounded at the schema exactly as the submitClaim
 * path bounds it (crownhunt-core.ts). These cases pin that the two claim paths
 * agree — the drift they guard against is a bound added to one path and
 * forgotten on the other.
 */
describe('claimSpawn input parsing — accuracyMeters bounds', () => {
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

describe('spawn cell approval input (the admin safety gate)', () => {
  it('accepts an approval with the explicit confirmation and a note', () => {
    const parsed = parseSetSpawnCellApprovalInput({
      approved: true,
      cellKey: '5933_1806',
      safeAreaConfirmed: true,
      approvalNote: 'Parkering vid torget, trygg att stanna på.',
    });
    expect(parsed.ok).toBe(true);
  });

  it('REFUSES an approval without the explicit safe-area confirmation', () => {
    expect(
      parseSetSpawnCellApprovalInput({
        approved: true,
        cellKey: '5933_1806',
        approvalNote: 'Ser bra ut.',
      }).ok,
    ).toBe(false);
    expect(
      parseSetSpawnCellApprovalInput({
        approved: true,
        cellKey: '5933_1806',
        safeAreaConfirmed: false,
        approvalNote: 'Ser bra ut.',
      }).ok,
    ).toBe(false);
  });

  it('refuses an approval with no note or a token note', () => {
    expect(
      parseSetSpawnCellApprovalInput({
        approved: true,
        cellKey: '5933_1806',
        safeAreaConfirmed: true,
      }).ok,
    ).toBe(false);
    expect(
      parseSetSpawnCellApprovalInput({
        approved: true,
        cellKey: '5933_1806',
        safeAreaConfirmed: true,
        approvalNote: 'ok',
      }).ok,
    ).toBe(false);
  });

  it('lets a revocation through with no ceremony — turning an area off must be easy', () => {
    expect(parseSetSpawnCellApprovalInput({ approved: false, cellKey: '5933_1806' }).ok).toBe(true);
  });

  it('requires exactly one of cellKey or coordinates, never both or neither', () => {
    expect(parseSetSpawnCellApprovalInput({ approved: false }).ok).toBe(false);
    expect(
      parseSetSpawnCellApprovalInput({
        approved: false,
        cellKey: '5933_1806',
        latitude: LAT,
        longitude: LON,
      }).ok,
    ).toBe(false);
  });

  it('rejects a malformed cell key and out-of-range coordinates', () => {
    expect(parseSetSpawnCellApprovalInput({ approved: false, cellKey: 'nope' }).ok).toBe(false);
    expect(
      parseSetSpawnCellApprovalInput({ approved: false, latitude: 999, longitude: 0 }).ok,
    ).toBe(false);
  });

  it('resolves coordinates to the same cell key the grid would', () => {
    const parsed = parseSetSpawnCellApprovalInput({
      approved: false,
      latitude: LAT,
      longitude: LON,
    });
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(resolveSpawnCellKey(parsed.input)).toBe(crownCellKey(LAT, LON));
    }
  });
});

describe('collect mode by rarity', () => {
  it('makes RARE and LEGENDARY exclusive, COMMON and UNCOMMON shared', () => {
    expect(crownCollectMode('common')).toBe('shared');
    expect(crownCollectMode('uncommon')).toBe('shared');
    expect(crownCollectMode('rare')).toBe('exclusive');
    expect(crownCollectMode('legendary')).toBe('exclusive');
  });
});

describe('spawn document builder', () => {
  it('records that NO human confirmed this coordinate', () => {
    const fields = buildCrownSpawnFields({
      cellKey: crownCellKey(LAT, LON),
      position: { latitude: LAT, longitude: LON },
      rarity: 'rare',
      approvedCellBy: 'admin-1',
    });
    expect(fields.source).toBe('auto');
    expect(fields.safeLocationConfirmed).toBe(false);
    expect(fields.approvedCellBy).toBe('admin-1');
    expect(fields.status).toBe('live');
    expect(fields.claimedByUid).toBeNull();
    expect(fields.rewardPoints).toBe(crownRewardPoints('rare'));
    expect(fields.collectRadiusMeters).toBe(COLLECT_RADIUS_METERS);
    // Rare is now stamped exclusive: removed on first claim.
    expect(fields.collectMode).toBe('exclusive');
  });

  it('stamps a shared collect mode on an everyday (common) crown', () => {
    const fields = buildCrownSpawnFields({
      cellKey: crownCellKey(LAT, LON),
      position: { latitude: LAT, longitude: LON },
      rarity: 'common',
      approvedCellBy: 'admin-1',
    });
    expect(fields.collectMode).toBe('shared');
  });
});
