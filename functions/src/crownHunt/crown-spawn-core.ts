/**
 * Kronjakt AUTO-SPAWN engine — pure core (constants, maths, builders).
 *
 * Today's Kronjakt points (`crownHuntPoints`) are placed BY HAND by admins
 * through the safety-gated `crownHunt.*` callables. This module is the maths
 * behind the second, automatic source: short-lived crowns that appear on their
 * own near where members actually are, in the spirit of a location game, and
 * disappear again after a rarity-dependent lifetime.
 *
 * Auto-spawned crowns live in their OWN collection (`crownSpawns`) and are
 * claimed through their OWN callable (`crownHunt.claimSpawn`). The admin
 * `crownHuntPoints` model is deliberately untouched: those points are curated,
 * permanent, safety-approved locations with repeat rules; these are ephemeral,
 * machine-placed, first-come-first-served pickups. Mixing the two into one
 * document shape would have forced every admin safety guarantee (approval note,
 * safe-location confirmation) to become optional.
 *
 * SAFETY / PRIVACY RULES ENCODED HERE
 *  - A HUMAN still decides which AREAS are safe. The hand-placed flow has a
 *    `safeLocationConfirmed` gate; auto-spawn cannot have a per-crown one, so
 *    the approval moves up a level: a cell spawns nothing until an admin has
 *    approved it (`crownSpawnCells`, see spawnCells.ts). The algorithm decides
 *    HOW MANY and WHERE-ISH inside an approved area; it never opens a new area.
 *  - Never spawn where nobody goes, UNLESS a human has vetted the exact stop.
 *    On the RANDOM-placement single-cell path, an activity score below
 *    {@link MIN_ACTIVITY_FOR_SPAWN} yields a target of ZERO crowns, so the engine
 *    cannot lure a member to an empty field or an industrial estate at night just
 *    because the grid cell was approved. The POI-ANCHORED marked-area path adds a
 *    small unconditional {@link CROWN_BASELINE_TARGET_PER_CELL} on top of the
 *    activity-derived amount, but only ever places it AT a cached safe-stop POI
 *    inside the approved area — so a baseline crown still lands on a vetted
 *    parking/fuel/charging stop, never a random coordinate, and a cell with no
 *    cached POI gets nothing to place on and spawns nothing.
 *  - Never spawn where people only DRIVE PAST: a sighting counts toward the
 *    activity score only when the member was moving slowly
 *    ({@link MAX_ACTIVITY_SPEED_MPS}). "A > 0" alone would rate a motorway as
 *    the busiest place in the country and invite someone to stop on the hard
 *    shoulder; requiring slow presence means a cell only scores where people
 *    park, queue, walk, or crawl.
 *  - Never reward speed: collection requires being STOPPED
 *    ({@link MAX_COLLECT_SPEED_MPS}) and DWELLING ({@link MIN_DWELL_SECONDS}).
 *    There is no time bonus, no streak for collecting fast, and no
 *    fastest-collector ranking anywhere in this engine — by standing product
 *    decision.
 *  - Activity is measured in AGGREGATE ONLY. This module never models an
 *    individual's route; the only per-user value it produces is
 *    {@link crownActivityUserHash}, a CELL-SCOPED digest used purely to
 *    de-duplicate "distinct users seen in this cell".
 *  - Distances are always server-computed (Haversine, reused from
 *    crown-hunt-geo). A client-supplied distance is never an input here.
 *
 * Pure module — no Firebase Admin SDK imports, no I/O, no clock reads except
 * through explicitly passed `now` values. Everything below is unit-tested in
 * the COLOCATED sibling ./crown-spawn-core.test.ts — the single home for this
 * module's unit tests. Do not start a second suite elsewhere: vitest collects
 * every `.test.ts` anywhere under src/, so a same-named file in src/__tests__
 * would also run and silently split the coverage.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';
import { haversineDistanceMeters, isSpeedSafe, isWithinGeofence } from './crown-hunt-geo';
import { MAX_REPORTED_ACCURACY_METERS } from './crownhunt-core';

// ---------------------------------------------------------------------------
// Feature flag
// ---------------------------------------------------------------------------

/**
 * Flag key gating the AUTOMATIC half of Kronjakt (spawning, the activity
 * aggregate, and claiming a spawned crown). Separate from the `crownHunt` flag,
 * which continues to gate the hand-placed admin points: turning the automatic
 * engine off must not take the curated points off the map, and vice versa.
 *
 * Contract default is OFF (contracts/features/feature-flags.json). This is a
 * system that writes map content and collects activity data on its own, so it
 * stays dark until it is deliberately switched on via `admin.setFeatureFlag`.
 */
export const CROWN_SPAWN_FLAG_KEY = 'crownHuntSpawn';

// ---------------------------------------------------------------------------
// Spawn grid
// ---------------------------------------------------------------------------

/**
 * Spawn grid cell edge, in degrees (~1.1 km of latitude; ~570 m of longitude at
 * Swedish latitudes). Much finer than the incidents/live discovery grid
 * (`CELL_SIZE_DEGREES = 0.18`, ~20 km) because those grids answer "give me
 * everything roughly near this map viewport", while this one is the unit that
 * carries a DENSITY BUDGET: a cell has to be small enough that "5 crowns in
 * here" describes a neighbourhood a person could plausibly walk or drive
 * around, not a whole municipality.
 */
export const CROWN_CELL_DEGREES = 0.01;

const clampLat = (lat: number) => Math.min(90, Math.max(-90, lat));
const clampLon = (lon: number) => Math.min(180, Math.max(-180, lon));

/**
 * Deterministic spawn-grid key for a coordinate — `${latIdx}_${lonIdx}`.
 *
 * Same construction as `incidents-core.geoCellKey` (floor-divide both axes by
 * the cell size), deliberately mirrored rather than reused: that helper hard-
 * codes the 0.18° incidents grid, and the two grids must be free to move
 * independently.
 */
export function crownCellKey(latitude: number, longitude: number): string {
  const latIdx = Math.floor(clampLat(latitude) / CROWN_CELL_DEGREES);
  const lonIdx = Math.floor(clampLon(longitude) / CROWN_CELL_DEGREES);
  return `${latIdx}_${lonIdx}`;
}

export interface CrownCellBounds {
  minLat: number;
  maxLat: number;
  minLon: number;
  maxLon: number;
}

/**
 * Widest grid indices that correspond to somewhere on the globe.
 *
 * `crownCellKey` clamps both axes before flooring, so latitude 90 maps to
 * `MAX_LAT_IDX` and longitude 180 maps to `MAX_LON_IDX`. Both ends are
 * therefore inclusive, and every key `crownCellKey` produces parses.
 */
const MAX_LAT_IDX = Math.round(90 / CROWN_CELL_DEGREES);
const MAX_LON_IDX = Math.round(180 / CROWN_CELL_DEGREES);

/**
 * Parses a cell key back to its grid indices; null when malformed OR off the
 * globe.
 *
 * The range check is not cosmetic. `cellKey` reaches this from admin input on
 * `setSpawnCellApproval`, and the regex alone accepts six digits per axis — so
 * without it, `"50000_0"` is an approvable cell whose bounds are latitude 500,
 * and `sampleCrownPosition` would happily write a crown there. Rejecting the
 * key at the boundary is better than clamping it later, because a key that is
 * not a place on Earth has no correct interpretation to fall back to.
 */
export function parseCrownCellKey(cellKey: string): { latIdx: number; lonIdx: number } | null {
  const match = /^(-?\d{1,6})_(-?\d{1,6})$/.exec(cellKey);
  if (!match) return null;
  const latIdx = Number(match[1]);
  const lonIdx = Number(match[2]);
  if (!Number.isSafeInteger(latIdx) || !Number.isSafeInteger(lonIdx)) return null;
  if (latIdx < -MAX_LAT_IDX || latIdx > MAX_LAT_IDX) return null;
  if (lonIdx < -MAX_LON_IDX || lonIdx > MAX_LON_IDX) return null;
  return { latIdx, lonIdx };
}

/**
 * The half-open [min, max) coordinate box a cell key covers; null when
 * malformed or off the globe.
 *
 * BOTH axes are clamped on the way out — latitude to [-90, 90], longitude to
 * [-180, 180]. The last row and the last column are the cases that need it:
 * latitude 90 floors to the final row whose unclamped upper edge is 90.01, and
 * longitude 180 floors to the final column whose unclamped upper edge is
 * 180.01. `sampleCrownPosition` draws uniformly inside these bounds, so an
 * unclamped edge is not a cosmetic detail — it is an invalid WGS-84 coordinate
 * written to a crown document and then handed to a map client. Both edge cells
 * degenerate to a zero-width box, which is the right answer: there is no strip
 * of Earth above 90, and none east of 180.
 */
export function crownCellBounds(cellKey: string): CrownCellBounds | null {
  const parsed = parseCrownCellKey(cellKey);
  if (!parsed) return null;
  return {
    minLat: clampLat(parsed.latIdx * CROWN_CELL_DEGREES),
    maxLat: clampLat((parsed.latIdx + 1) * CROWN_CELL_DEGREES),
    minLon: clampLon(parsed.lonIdx * CROWN_CELL_DEGREES),
    maxLon: clampLon((parsed.lonIdx + 1) * CROWN_CELL_DEGREES),
  };
}

/**
 * The 3x3 block of cell keys centred on `cellKey` (itself included), in a
 * stable order.
 *
 * The minimum-separation rule must hold across cell BOUNDARIES, not only
 * inside a cell: two crowns 20 m apart on either side of a boundary are just as
 * clumped as two in the same cell. The spawner therefore loads live crowns from
 * the whole neighbourhood before sampling. Nine keys stays well inside
 * Firestore's `in` limit, so this is one query.
 */
export function neighbourCrownCells(cellKey: string): string[] {
  const parsed = parseCrownCellKey(cellKey);
  if (!parsed) return [];
  const keys: string[] = [];
  for (let dLat = -1; dLat <= 1; dLat += 1) {
    for (let dLon = -1; dLon <= 1; dLon += 1) {
      keys.push(`${parsed.latIdx + dLat}_${parsed.lonIdx + dLon}`);
    }
  }
  return keys;
}

// ---------------------------------------------------------------------------
// Activity score A(cell)
// ---------------------------------------------------------------------------

/** Only presence within this window counts toward a cell's activity. */
export const ACTIVITY_WINDOW_DAYS = 7;
export const ACTIVITY_WINDOW_MS = ACTIVITY_WINDOW_DAYS * 24 * 60 * 60 * 1000;

/**
 * Exponential decay constant, in days. A member seen 3 days ago counts
 * `1/e ≈ 0.37` of one seen right now; at the 7-day window edge they count
 * ~0.10, which is why the window can be cut there without a visible cliff.
 */
export const ACTIVITY_TAU_DAYS = 3;
export const ACTIVITY_TAU_MS = ACTIVITY_TAU_DAYS * 24 * 60 * 60 * 1000;

/**
 * The weight one DISTINCT user's most recent sighting contributes:
 * `w = exp(-Δt / τ)`.
 *
 * Zero outside the window, and zero for a future timestamp (clock skew or a
 * forged `lastSeenAt` must never be worth MORE than a present-moment sighting —
 * `exp(+x) > 1` would make a future date the most valuable input in the
 * system).
 */
export function activityWeight(
  lastSeenMs: number,
  nowMs: number,
  tauMs: number = ACTIVITY_TAU_MS,
  windowMs: number = ACTIVITY_WINDOW_MS,
): number {
  if (!Number.isFinite(lastSeenMs) || !Number.isFinite(nowMs)) return 0;
  const deltaMs = nowMs - lastSeenMs;
  if (deltaMs < 0) return 0;
  if (deltaMs > windowMs) return 0;
  return Math.exp(-deltaMs / tauMs);
}

/**
 * Speed ceiling for a sighting to COUNT toward a cell's activity: 8 m/s
 * (28.8 km/h).
 *
 * This is a safety filter, not a performance one, and it is the cheapest
 * defence available against the worst failure mode this engine has. Raw
 * presence would rank a motorway cell far above any car park — thousands of
 * people pass through it every day — and a crown placed there is an invitation
 * to stop on a hard shoulder. Requiring that a member was moving slowly when
 * seen means a cell can only earn a score from places people are actually AT:
 * car parks, meets, queues, residential streets, on foot. A cell nobody ever
 * slows down in scores exactly zero and can never reach the spawn floor, even
 * if a hundred thousand cars a day pass through it.
 *
 * The threshold sits above walking/cycling and car-park crawl but well below
 * an urban 50 km/h limit (13.9 m/s), so ordinary through-traffic does not
 * qualify either.
 *
 * A device that does not report speed is NOT counted (unlike `isSpeedSafe`,
 * which treats an absent speed as safe): here an unknown speed is an unproven
 * claim about a place's safety, and the conservative reading is to ignore the
 * sighting. The only cost of a false negative is a slightly lower score.
 */
export const MAX_ACTIVITY_SPEED_MPS = 8;

/**
 * Whether one position sample may be recorded as activity for spawn purposes.
 * See {@link MAX_ACTIVITY_SPEED_MPS} — absent or invalid speeds do not count.
 */
export function isActivitySightingEligible(
  speedMetersPerSecond: number | null | undefined,
  maxSpeedMps: number = MAX_ACTIVITY_SPEED_MPS,
): boolean {
  if (speedMetersPerSecond === null || speedMetersPerSecond === undefined) return false;
  if (!Number.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0) return false;
  return speedMetersPerSecond <= maxSpeedMps;
}

/**
 * `A(cell)` — the sum of {@link activityWeight} over DISTINCT users.
 *
 * Distinctness is a property of the caller's input, not of this function: the
 * aggregate stores one document per cell-scoped user hash, so reading that
 * collection yields at most one `lastSeenAt` per user by construction. Passing
 * two entries for the same person would double-count them, which is exactly
 * why the store is keyed by hash rather than append-only.
 */
export function activityScore(lastSeenMsValues: readonly number[], nowMs: number): number {
  let total = 0;
  for (const lastSeenMs of lastSeenMsValues) {
    total += activityWeight(lastSeenMs, nowMs);
  }
  return total;
}

// ---------------------------------------------------------------------------
// Target density N_target(cell)
// ---------------------------------------------------------------------------

/** Density coefficient in `N = ceil(K * ln(1 + A))`. */
export const DENSITY_K = 1.5;

/** Hard ceiling on live crowns per cell, whatever the activity. */
export const MAX_CROWNS_PER_CELL = 5;

/**
 * Activity floor. Below this, the target is ZERO — not one.
 *
 * This is the engine's most important safety property, not a tuning knob. A
 * cell scoring under 1.0 is a place no distinct member has been recently
 * enough to matter: an empty stretch of road, a closed industrial park, a
 * layby someone drove past once. Spawning there would invite a member to
 * travel somewhere unverified and stop. The rule "crowns appear where people
 * ALREADY are" is what keeps every spawn location implicitly vouched for by
 * real presence.
 */
export const MIN_ACTIVITY_FOR_SPAWN = 1;

/**
 * BASELINE crowns per approved cell — the floor a POI-anchored, admin-approved
 * area receives even with ZERO recent member activity (`A = 0`), placed ON TOP
 * of the activity-derived amount and clamped to {@link MAX_CROWNS_PER_CELL}.
 *
 * Why it exists: the activity-derived target `ceil(K·ln(1+A))` is 0 whenever
 * `A < 1`, so during a low-usage launch an admin who has marked whole safe areas
 * sees crowns only in the cells they personally drove through recently. A small
 * unconditional baseline lets a freshly-approved area populate its safe stops
 * before it has any traffic; activity still adds richness on top, up to the cap.
 *
 * SAFETY — the baseline does NOT relax any placement guarantee. It only raises
 * the per-cell TARGET; WHERE a crown may be placed is unchanged. A baseline crown
 * is therefore only ever created by the POI-ANCHORED marked-area pass
 * (`runCrownAreaSpawnPass`), AT a cached safe-stop POI inside the drawn area — a
 * cell with no cached POI has nothing to anchor to and still spawns nothing. (The
 * former hand-approved single-cell path sampled RANDOM in-cell coordinates and so
 * was deliberately given NO baseline — an unconditional crown at a random point is
 * exactly the "invite a stop somewhere unvetted" outcome the whole engine exists
 * to prevent — but that path has since been removed; only the POI-anchored area
 * pass remains.)
 *
 * Conservative on purpose: 1 crown. Set to 0 to disable the baseline entirely,
 * which restores the pure activity-derived target for every caller.
 */
export const CROWN_BASELINE_TARGET_PER_CELL = 1;

/**
 * `N_target(cell) = min(max, baseline + activityDerived)`, where
 * `activityDerived = ceil(K · ln(1 + A))` for `A ≥ minActivity` and 0 below the
 * floor.
 *
 * With the default `baseline` of 0 this is the original pure activity curve: 0
 * below the floor, logarithmic above it (the log curve reaches the cap around
 * `A ≈ 27` and never exceeds it), capped at {@link MAX_CROWNS_PER_CELL}. The
 * POI-anchored marked-area pass passes {@link CROWN_BASELINE_TARGET_PER_CELL},
 * so an approved area receives that many crowns even at `A = 0`, with activity
 * adding on top up to the same cap. See that constant for why a baseline is safe
 * ONLY on the POI-anchored path.
 *
 * Logarithmic on purpose: a city-centre cell with 50 recent visitors should feel
 * a bit richer than a quiet suburb with 5, not ten times richer.
 */
export function targetCrownCount(
  activity: number,
  options: { k?: number; max?: number; minActivity?: number; baseline?: number } = {},
): number {
  const k = options.k ?? DENSITY_K;
  const max = options.max ?? MAX_CROWNS_PER_CELL;
  const minActivity = options.minActivity ?? MIN_ACTIVITY_FOR_SPAWN;
  // Clamp the baseline to a non-negative integer so a malformed option can only
  // ever LOWER the target, never inject a fractional or negative crown count.
  // A non-finite or absent baseline is 0 (the pure activity curve).
  const baseline = Number.isFinite(options.baseline)
    ? Math.max(0, Math.floor(options.baseline as number))
    : 0;
  // Below the floor (or a non-finite activity), the activity term contributes
  // nothing — only the baseline, if any, remains.
  const activityDerived =
    Number.isFinite(activity) && activity >= minActivity
      ? Math.ceil(k * Math.log(1 + activity))
      : 0;
  return Math.min(max, baseline + activityDerived);
}

// ---------------------------------------------------------------------------
// Rarity
// ---------------------------------------------------------------------------

export const CROWN_RARITIES = ['common', 'uncommon', 'rare', 'legendary'] as const;
export type CrownRarity = (typeof CROWN_RARITIES)[number];

export interface CrownRaritySpec {
  /** Probability of this tier on one draw; the four sum to exactly 1. */
  weight: number;
  /** Kronpoäng awarded on a successful claim. */
  points: number;
  /** How long the crown stays on the map before it is swept. */
  ttlHours: number;
}

/**
 * The rarity table. TTL rises with value on purpose: a legendary is worth
 * travelling for, so it must survive long enough to be reachable, while a
 * common should churn quickly to keep the map moving.
 *
 * Every tier expires. A crown that never expired would become a fixed,
 * publicly known coordinate that a determined user could farm on a schedule
 * (and that neighbours would learn to expect traffic at) — which is precisely
 * what a hand-placed admin point is FOR, with the human safety review that
 * implies. Automatic placements get a lifetime instead.
 */
export const CROWN_RARITY_TABLE: Record<CrownRarity, CrownRaritySpec> = {
  common: { weight: 0.7, points: 10, ttlHours: 6 },
  uncommon: { weight: 0.22, points: 25, ttlHours: 12 },
  rare: { weight: 0.07, points: 100, ttlHours: 24 },
  legendary: { weight: 0.01, points: 500, ttlHours: 48 },
};

/**
 * Weighted pick from a uniform roll in [0, 1).
 *
 * Walks the tiers in table order accumulating weight, so the mapping from roll
 * to tier is a deterministic function of the roll alone — a seeded generator
 * therefore produces a reproducible sequence of rarities, which is how the
 * distribution is asserted in tests. Out-of-range or non-finite rolls fall back
 * to `common` (the engine must never fail to place a crown because of a bad
 * random draw).
 */
export function pickCrownRarity(roll: number): CrownRarity {
  if (!Number.isFinite(roll) || roll < 0) return 'common';
  let cumulative = 0;
  for (const rarity of CROWN_RARITIES) {
    cumulative += CROWN_RARITY_TABLE[rarity].weight;
    if (roll < cumulative) return rarity;
  }
  return 'common';
}

export function crownRewardPoints(rarity: CrownRarity): number {
  return CROWN_RARITY_TABLE[rarity].points;
}

export function crownTtlMs(rarity: CrownRarity): number {
  return CROWN_RARITY_TABLE[rarity].ttlHours * 60 * 60 * 1000;
}

/** The instant a crown of this rarity, spawned at `now`, stops being claimable. */
export function crownExpiresAt(rarity: CrownRarity, now: Date): Date {
  return new Date(now.getTime() + crownTtlMs(rarity));
}

// ---------------------------------------------------------------------------
// Collection mode: SHARED vs EXCLUSIVE
// ---------------------------------------------------------------------------

/**
 * How a spawned crown is collected, stamped on the document at spawn time so the
 * claim path and the map client agree without re-deriving it:
 *
 *  - `shared`   — many DISTINCT members may each collect the crown ONCE, and it
 *                 STAYS on the map until its TTL expires. A member's second
 *                 attempt on the same crown is refused (`already_collected`).
 *                 This is the low-value common case: a crown by a busy car park
 *                 is a small reward everyone who passes may pick up, not a race.
 *  - `exclusive`— the FIRST member to collect it takes it and the crown is
 *                 REMOVED immediately, gone for everyone else. This is the
 *                 high-value jackpot: being first has to mean something, and a
 *                 leaked coordinate must pay out once, not once per member.
 */
export const CROWN_COLLECT_MODES = ['shared', 'exclusive'] as const;
export type CrownCollectMode = (typeof CROWN_COLLECT_MODES)[number];

/**
 * Rarity ordering, low → high. The single knob the exclusive cutoff turns on.
 */
export const CROWN_RARITY_RANK: Record<CrownRarity, number> = {
  common: 0,
  uncommon: 1,
  rare: 2,
  legendary: 3,
};

/**
 * The cutoff: a crown whose rarity rank is at or above this is EXCLUSIVE, and
 * everything below it is SHARED.
 *
 * Today that makes only `legendary` exclusive and common/uncommon/rare shared,
 * which realises the product model "the top tier is a first-come jackpot,
 * everything else is a shared pickup" against the rarity set that exists in the
 * code. THE CUTOFF IS THIS ONE CONSTANT ON PURPOSE: introduce an `epic` tier
 * above `rare` and it becomes exclusive automatically; drop this to
 * `CROWN_RARITY_RANK.rare` to make rare exclusive too, or raise it to keep only
 * the very top exclusive — a one-line change with no claim-path edits.
 */
export const MIN_EXCLUSIVE_CROWN_RANK = CROWN_RARITY_RANK.legendary;

/** Derives a crown's collection mode from its rarity via {@link MIN_EXCLUSIVE_CROWN_RANK}. */
export function crownCollectMode(rarity: CrownRarity): CrownCollectMode {
  return CROWN_RARITY_RANK[rarity] >= MIN_EXCLUSIVE_CROWN_RANK ? 'exclusive' : 'shared';
}

/**
 * Resolves the collection mode to use for a crown document, tolerating an
 * absent field (a crown written before this model, or by a test fixture) by
 * falling back to the rarity-derived mode. An explicit, valid stored value wins;
 * anything else is re-derived — never trusted blindly, since the mode decides
 * whether a crown is removed on first claim.
 */
export function resolveCollectMode(stored: unknown, rarity: CrownRarity): CrownCollectMode {
  if (stored === 'shared' || stored === 'exclusive') return stored;
  return crownCollectMode(rarity);
}

// ---------------------------------------------------------------------------
// Seedable RNG
// ---------------------------------------------------------------------------

/**
 * mulberry32 — a tiny, fast, well-distributed 32-bit PRNG returning values in
 * [0, 1).
 *
 * NOT cryptographic and not meant to be: the spawner needs a random-looking
 * but REPRODUCIBLE stream so the rarity distribution and the dart-throwing
 * rejection sampler can be asserted deterministically in unit tests. Production
 * seeds it from the wall clock; nothing about the game's integrity depends on
 * a spawn position being unpredictable (positions are public the moment they
 * are written).
 */
export function createSeededRng(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------
// Minimum separation / position sampling
// ---------------------------------------------------------------------------

/**
 * No two live crowns may be closer than this.
 *
 * Without it, uniform sampling inside a ~1.1 x 0.57 km cell regularly puts two
 * crowns within a few metres of each other, which reads as a bug and lets one
 * stop collect several rewards. 150 m is also comfortably outside the 75 m
 * collect radius, so a single stationary position can never be inside two
 * crowns at once.
 */
export const MIN_CROWN_SEPARATION_METERS = 150;

/** Dart-throwing attempts before a cell is left short of its target. */
export const MAX_SPAWN_SAMPLE_ATTEMPTS = 24;

export interface CrownPosition {
  latitude: number;
  longitude: number;
}

/** True when `candidate` clears `minMeters` from every position in `others`. */
export function isFarEnoughFromAll(
  candidate: CrownPosition,
  others: readonly CrownPosition[],
  minMeters: number = MIN_CROWN_SEPARATION_METERS,
): boolean {
  for (const other of others) {
    const distance = haversineDistanceMeters(
      candidate.latitude,
      candidate.longitude,
      other.latitude,
      other.longitude,
    );
    if (distance < minMeters) return false;
  }
  return true;
}

/**
 * Rejection-samples ("dart throwing") one position inside `cellKey` that clears
 * {@link MIN_CROWN_SEPARATION_METERS} from every position in `occupied`.
 *
 * Returns null when the attempt budget runs out — a saturated cell is expected
 * and fine; the run simply leaves it below target and tries again next time.
 * Looping until success would let a cell that is geometrically full (5 crowns
 * plus neighbours) spin forever inside a scheduled function.
 *
 * The sampled point is re-keyed and checked against `cellKey` before being
 * accepted, so floating-point drift at a cell edge can never emit a position
 * that belongs to the neighbouring cell (which would corrupt that cell's
 * density accounting).
 */
export function sampleCrownPosition(
  cellKey: string,
  occupied: readonly CrownPosition[],
  rng: () => number,
  options: {
    minSeparationMeters?: number;
    maxAttempts?: number;
    /**
     * Optional extra acceptance test applied to every candidate BEFORE the
     * separation check. The area spawner passes an in-shape predicate here so a
     * cell that only partially overlaps a marked area places crowns only in the
     * part that is actually inside the shape — a candidate drawn from the
     * cell's rectangle but outside the polygon/circle/rectangle is rejected and
     * re-drawn, exactly like a candidate that fails separation. Absent (the
     * hand-approved cell path), every in-cell candidate is accepted.
     */
    accept?: (position: CrownPosition) => boolean;
  } = {},
): CrownPosition | null {
  const bounds = crownCellBounds(cellKey);
  if (!bounds) return null;
  const minSeparation = options.minSeparationMeters ?? MIN_CROWN_SEPARATION_METERS;
  const maxAttempts = Math.max(1, options.maxAttempts ?? MAX_SPAWN_SAMPLE_ATTEMPTS);
  const accept = options.accept;

  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const candidate: CrownPosition = {
      latitude: bounds.minLat + rng() * (bounds.maxLat - bounds.minLat),
      longitude: bounds.minLon + rng() * (bounds.maxLon - bounds.minLon),
    };
    if (crownCellKey(candidate.latitude, candidate.longitude) !== cellKey) continue;
    if (accept && !accept(candidate)) continue;
    if (isFarEnoughFromAll(candidate, occupied, minSeparation)) return candidate;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Collection: radius + the stationary rule
// ---------------------------------------------------------------------------

/** How close a member must be to collect. Always server-computed. */
export const COLLECT_RADIUS_METERS = 75;

/**
 * Hard ceiling on a collect radius read back off a crown document.
 *
 * The spawner only ever writes {@link COLLECT_RADIUS_METERS}, and clients cannot
 * write `crownSpawns` at all (firestore.rules), so a stored radius outside this
 * bound means the document is wrong — a console edit, a migration, or a future
 * bug. This exists so that "wrong" can only ever mean a SMALLER gate.
 */
export const MAX_STORED_COLLECT_RADIUS_METERS = 250;

/**
 * Resolves the collect radius to use for a crown, from whatever its document
 * actually holds.
 *
 * Every other way this value can be wrong already fails closed: a non-numeric
 * latitude makes the Haversine distance NaN and `NaN <= radius` is false, so
 * the claim is refused. An oversized radius is the one corruption that fails
 * OPEN — it widens the geofence instead of shutting it — and on a claim path
 * "fail open" means paying out to someone who was never there. So a stored
 * radius is used only when it is a finite, positive number inside
 * {@link MAX_STORED_COLLECT_RADIUS_METERS}; anything else (undefined, null,
 * NaN, a string, zero, negative, absurd) falls back to the 75 m default rather
 * than being trusted.
 */
export function resolveCollectRadiusMeters(stored: unknown): number {
  if (
    typeof stored === 'number' &&
    Number.isFinite(stored) &&
    stored > 0 &&
    stored <= MAX_STORED_COLLECT_RADIUS_METERS
  ) {
    return stored;
  }
  return COLLECT_RADIUS_METERS;
}

/**
 * Collection speed ceiling: 2.0 m/s = 7.2 km/h.
 *
 * Slightly more permissive than the hand-placed points' 1.4 m/s so a member
 * shuffling around a car park is not rejected, but far below any speed at
 * which a person is DRIVING. The point of the whole rule is that nobody ever
 * has a reason to reach for their phone while the car is moving.
 */
export const MAX_COLLECT_SPEED_MPS = 2.0;

/**
 * The dwell window: two position fixes must be at least this far apart in time.
 *
 * A single instantaneous fix is trivially satisfiable by anyone rolling past at
 * walking-pace GPS jitter, and a reported speed of 0 is just a number the
 * client sent. Requiring two fixes separated by a few seconds, both inside the
 * radius, both slow, AND with a slow SERVER-DERIVED speed between them, is what
 * actually distinguishes "stopped" from "passing through".
 */
export const MIN_DWELL_SECONDS = 4;

/**
 * Upper bound on the gap between the two fixes. Beyond this the earlier fix is
 * too old to say anything about where the member is now — someone could park,
 * drive away, and still hold a "valid" pair.
 */
export const MAX_DWELL_SECONDS = 300;

export interface CrownFix {
  /** Server-computed metres from this fix to the crown. Never client-supplied. */
  distanceMeters: number;
  /** Device-reported speed; null when the device did not report one. */
  speedMetersPerSecond: number | null;
  /** Device-reported horizontal accuracy; null when unknown. */
  accuracyMeters: number | null;
  /** Epoch ms of the fix. */
  recordedAtMs: number;
}

export type StationaryRejection = 'outside_radius' | 'must_be_stationary';

export type StationaryEvaluation = { ok: true } | { ok: false; result: StationaryRejection };

/**
 * The stationary-collection gate.
 *
 * A claim passes only when ALL of the following hold:
 *  1. both fixes are inside the collect radius (accuracy-buffered exactly like
 *     the hand-placed points, via `isWithinGeofence`);
 *  2. the fixes are {@link MIN_DWELL_SECONDS}..{@link MAX_DWELL_SECONDS} apart;
 *  3. both DEVICE-REPORTED speeds are at or below
 *     {@link MAX_COLLECT_SPEED_MPS};
 *  4. the speed DERIVED by the server from the two positions and the elapsed
 *     time is also at or below that ceiling.
 *
 * (4) is what makes (3) meaningful. `isSpeedSafe` treats a null speed as safe —
 * correct for a device that genuinely cannot report one — so a client could
 * otherwise omit the field entirely. The derived speed needs no cooperation
 * from the client beyond two coordinates it has already committed to.
 *
 * Failing this is a PLAIN REFUSAL, not a fraud signal: an honest member
 * rolling slowly through a car park should be told "stop first", not silently
 * scored as suspicious. Nothing here feeds the risk score.
 */
export function evaluateStationaryCollection(params: {
  current: CrownFix;
  previous: CrownFix;
  /** Server-computed metres between the two fixes. */
  movedMeters: number;
  collectRadiusMeters?: number;
  maxSpeedMps?: number;
}): StationaryEvaluation {
  const radius = params.collectRadiusMeters ?? COLLECT_RADIUS_METERS;
  const maxSpeed = params.maxSpeedMps ?? MAX_COLLECT_SPEED_MPS;

  const insideNow = isWithinGeofence(
    params.current.distanceMeters,
    radius,
    params.current.accuracyMeters,
  );
  const insideBefore = isWithinGeofence(
    params.previous.distanceMeters,
    radius,
    params.previous.accuracyMeters,
  );
  if (!insideNow || !insideBefore) {
    return { ok: false, result: 'outside_radius' };
  }

  const dwellSeconds = (params.current.recordedAtMs - params.previous.recordedAtMs) / 1000;
  if (!Number.isFinite(dwellSeconds) || dwellSeconds < MIN_DWELL_SECONDS) {
    return { ok: false, result: 'must_be_stationary' };
  }
  if (dwellSeconds > MAX_DWELL_SECONDS) {
    return { ok: false, result: 'must_be_stationary' };
  }

  if (
    !isSpeedSafe(params.current.speedMetersPerSecond, maxSpeed) ||
    !isSpeedSafe(params.previous.speedMetersPerSecond, maxSpeed)
  ) {
    return { ok: false, result: 'must_be_stationary' };
  }

  const derivedSpeed = params.movedMeters / dwellSeconds;
  if (!Number.isFinite(derivedSpeed) || derivedSpeed > maxSpeed) {
    return { ok: false, result: 'must_be_stationary' };
  }

  return { ok: true };
}

// ---------------------------------------------------------------------------
// Claim results + Swedish messages
// ---------------------------------------------------------------------------

export const CROWN_SPAWN_CLAIM_RESULTS = [
  'awarded',
  'already_taken',
  'already_collected',
  'outside_radius',
  'must_be_stationary',
  'position_too_old',
  'crown_expired',
  'daily_limit_reached',
  'risk_review',
  'feature_disabled',
  'not_eligible',
] as const;
export type CrownSpawnClaimResult = (typeof CROWN_SPAWN_CLAIM_RESULTS)[number];

export function getSpawnClaimMessage(result: CrownSpawnClaimResult): string {
  switch (result) {
    case 'awarded':
      return 'Kronan är din! Poängen har lagts till i ditt Kronpoäng-saldo.';
    case 'already_taken':
      return 'Någon hann före — den här kronan är redan upphämtad.';
    case 'already_collected':
      // SHARED crown: this member has had their one pickup; the crown stays on
      // the map for others. Deliberately distinct from `already_taken` (someone
      // ELSE got an exclusive crown) — the honest reading is "you already got
      // this one", not "you lost a race".
      return 'Du har redan hämtat den här kronan.';
    case 'outside_radius':
      return 'Du är för långt från kronan.';
    case 'must_be_stationary':
      // Deliberately explicit: the member must understand this is a stop rule,
      // not a "you were unlucky" or a fraud accusation.
      return 'Du måste stå stilla för att hämta kronan. Stanna säkert, vänta några sekunder och försök igen.';
    case 'position_too_old':
      return 'Din position är för gammal. Vänta en stund och försök igen.';
    case 'crown_expired':
      return 'Kronan har försvunnit.';
    case 'daily_limit_reached':
      return 'Du har nått dagens gräns för Kronjakt. Försök igen imorgon.';
    case 'risk_review':
      return 'Claimen behöver granskas och inga poäng har delats ut ännu.';
    case 'feature_disabled':
      return 'Kronjakt är för tillfället inte tillgängligt.';
    case 'not_eligible':
      return 'Du behöver ett aktivt Kronjakt-medlemskap för att delta.';
  }
}

// ---------------------------------------------------------------------------
// Limits
// ---------------------------------------------------------------------------

/**
 * Per-user daily cap on AUTO-SPAWN claims, counted separately from the
 * hand-placed points' `MAX_DAILY_SUCCESSFUL_CLAIMS`.
 *
 * Separate on purpose: the two are different economies (curated 1–1000 KP
 * points versus a 10/25/100/500 spawn table), and sharing one counter would
 * mean a busy hunting afternoon silently locked a member out of an admin's
 * event point — a confusing failure with no upside.
 */
export const MAX_DAILY_SPAWN_CLAIMS = 20;

// ---------------------------------------------------------------------------
// Deterministic identifiers
// ---------------------------------------------------------------------------

/**
 * Length-prefixed SHA-256 over a tuple → an injective, Firestore-safe (hex)
 * document ID. Length-prefixing means no input value can forge a field
 * boundary, so distinct tuples never collide regardless of the characters they
 * contain. Same construction as `crownhunt-core.hashDocId`, duplicated rather
 * than exported across modules because it is three lines and the two callers
 * must be free to change their tuple shapes independently.
 */
function hashDocId(namespace: string, parts: readonly string[]): string {
  const hash = createHash('sha256');
  hash.update(`${namespace.length}:${namespace}`);
  for (const part of parts) {
    hash.update(`${part.length}:${part}`);
  }
  return hash.digest('hex');
}

/**
 * Scopes a client idempotency key to the user — the `crownSpawnClaims`
 * document ID, so a resubmission replays the stored result.
 *
 * NAMESPACED away from `crownhunt-core.scopeClaimIdempotencyKey`: both take
 * (uid, clientKey), so without the namespace a member who reused one key across
 * both flows would land on the same digest, and a spawn claim could replay a
 * hand-placed point's stored result (or vice versa).
 */
export function scopeSpawnClaimKey(uid: string, idempotencyKey: string): string {
  return hashDocId('crown-spawn-claim', [uid, idempotencyKey]);
}

/** Ledger idempotency key for a spawn claim's award (Firestore-safe). */
export function spawnClaimLedgerIdempotencyKey(scopedKey: string): string {
  return `crown-spawn-claim_${scopedKey}`;
}

/**
 * Deterministic `crownSpawnCollectors/{id}` document ID for a (crown, user)
 * pair — the per-crown, per-user record that makes a SHARED crown collectable
 * exactly once by each distinct member.
 *
 * Independent of the client idempotency key ON PURPOSE: the once-per-user
 * guarantee for a shared crown must hold across a member's DIFFERENT claim
 * requests (a fresh idempotency key each time), so it cannot key off the client
 * key the way `scopeSpawnClaimKey` does. A length-prefixed digest keeps the ID
 * Firestore-safe whatever the inputs contain, and is created transactionally in
 * the award (create-if-absent), so two concurrent taps by the same member on
 * the same shared crown serialize on it and exactly one awards.
 */
export function spawnCollectorDocId(spawnId: string, uid: string): string {
  return hashDocId('crown-spawn-collector', [spawnId, uid]);
}

/** Start of the UTC calendar day — the daily-cap bucket. */
export function startOfUtcDay(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

/** UTC calendar-day key (YYYY-MM-DD). */
export function utcDayKey(date: Date): string {
  return startOfUtcDay(date).toISOString().slice(0, 10);
}

/**
 * Deterministic `crownSpawnDailyClaims` counter ID for a (user, UTC day). Read
 * and incremented INSIDE the award transaction, so the daily cap holds under
 * concurrent claims. Derived only from server-trusted values — never from the
 * client idempotency key.
 */
export function spawnDailyCounterDocId(uid: string, now: Date): string {
  return hashDocId('crown-spawn-daily', [uid, utcDayKey(now)]);
}

/**
 * CELL-SCOPED pseudonym for a user in the activity aggregate.
 *
 * The raw UID never reaches `crownCellActivity`. Scoping the digest to the cell
 * key means the SAME person produces a DIFFERENT identifier in every cell, so
 * even a reader with full backend access cannot join the aggregate's documents
 * together into a route — the collection can answer "how many distinct people
 * have been in this square recently" and literally nothing else.
 *
 * Honest limit: someone holding the full UID list could recompute the digest
 * for a specific (cell, uid) pair and confirm a guess. That is why the
 * collection is denied to every client in firestore.rules and is written only
 * by the Admin SDK. The defence this hash provides is against CORRELATION, not
 * against an actor who already has the UID.
 */
export function crownActivityUserHash(cellKey: string, uid: string): string {
  return hashDocId('crown-cell-activity', [cellKey, uid]);
}

// ---------------------------------------------------------------------------
// Activity write throttle
// ---------------------------------------------------------------------------

/**
 * Minimum interval between activity-aggregate writes for the same user in the
 * same cell.
 *
 * `live.updatePosition` fires as fast as the device streams GPS. The aggregate
 * only needs to know "this person was here recently", at day-scale resolution
 * (τ = 3 days), so one write per user per cell per 10 minutes is already far
 * more resolution than the score can use. Crossing into a NEW cell always
 * writes immediately — otherwise a member driving through would be recorded in
 * whichever cell they happened to be in when the timer elapsed.
 */
export const CROWN_ACTIVITY_MIN_INTERVAL_MS = 10 * 60 * 1000;

/**
 * Whether `live.updatePosition` should touch the activity aggregate this
 * sample. Pure so the throttle is unit-testable without the emulator; mirrors
 * `nearby-core.shouldRefreshDiscovery`, including its fail-toward-writing
 * behaviour on missing/unparseable state.
 */
export function shouldRecordCrownActivity(
  prev: { recordedAtIso?: string | null; cellKey?: string | null } | null | undefined,
  currentCellKey: string,
  now: Date,
  minIntervalMs: number = CROWN_ACTIVITY_MIN_INTERVAL_MS,
): boolean {
  if (!prev) return true;
  if (prev.cellKey !== currentCellKey) return true;
  const last = prev.recordedAtIso ? Date.parse(prev.recordedAtIso) : NaN;
  if (!Number.isFinite(last)) return true;
  return now.getTime() - last >= minIntervalMs;
}

// ---------------------------------------------------------------------------
// claimSpawn input
// ---------------------------------------------------------------------------

const fixSchema = z
  .object({
    latitude: z.number(),
    longitude: z.number(),
    // Bounded exactly like the submitClaim path (crownhunt-core.ts). `.finite()`
    // restates an invariant zod's base number check already enforces; `.max()`
    // is the bound that actually adds a ceiling, keeping an absurd-but-finite
    // accuracy from being accepted here while submitClaim rejects it.
    accuracyMeters: z
      .number()
      .finite()
      .nonnegative()
      .max(MAX_REPORTED_ACCURACY_METERS)
      .nullable()
      .optional(),
    speedMetersPerSecond: z.number().finite().nonnegative().nullable().optional(),
    recordedAt: z.string().datetime(),
  })
  .strict();

const claimSpawnInputSchema = z
  .object({
    spawnId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .regex(/^[A-Za-z0-9._-]+$/)
      .refine((id) => id !== '.' && id !== '..'),
    latitude: z.number(),
    longitude: z.number(),
    /** Bounded as on `fixSchema` above and the submitClaim path. */
    accuracyMeters: z
      .number()
      .finite()
      .nonnegative()
      .max(MAX_REPORTED_ACCURACY_METERS)
      .nullable()
      .optional(),
    speedMetersPerSecond: z.number().finite().nonnegative().nullable().optional(),
    recordedAt: z.string().datetime(),
    /**
     * The EARLIER of the two fixes proving the member is dwelling. Required —
     * a claim cannot be evaluated for stationarity from one sample.
     */
    previousFix: fixSchema,
    idempotencyKey: z.string().trim().min(1).max(128),
    /**
     * Android `Location.isMock` (or the iOS equivalent) as reported by the
     * client. A client can always lie by omitting it or sending false, so it is
     * a one-way signal: `true` is trusted and heavily penalised, `false` and
     * absent are treated identically (as "no information").
     */
    isMockLocation: z.boolean().nullable().optional(),
    /** Platform integrity placeholder — null until native integration. */
    platformIntegrityPassed: z.boolean().nullable().optional(),
  })
  .strict();

export type ClaimSpawnInput = z.infer<typeof claimSpawnInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseClaimSpawnInput(data: unknown): ParseResult<ClaimSpawnInput> {
  const result = claimSpawnInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { spawnId, latitude, longitude, recordedAt, previousFix: { latitude, longitude, recordedAt, accuracyMeters?, speedMetersPerSecond? }, idempotencyKey, accuracyMeters?, speedMetersPerSecond?, isMockLocation?, platformIntegrityPassed? }.',
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Spawn document builder
// ---------------------------------------------------------------------------

export const CROWN_SPAWN_STATUSES = ['live', 'claimed'] as const;
export type CrownSpawnStatus = (typeof CROWN_SPAWN_STATUSES)[number];

/** Marks a document as machine-placed, so admin tooling can tell the two apart. */
export const CROWN_SPAWN_SOURCE = 'auto';

/**
 * The non-Timestamp portion of a `crownSpawns/{spawnId}` document. The
 * scheduled spawner stamps `createdAt`/`expiresAt` as Firestore Timestamps so
 * this stays pure and testable.
 */
export interface CrownSpawnFields {
  cellKey: string;
  latitude: number;
  longitude: number;
  rarity: CrownRarity;
  rewardPoints: number;
  collectRadiusMeters: number;
  /**
   * SHARED or EXCLUSIVE, derived from rarity at spawn time (see
   * {@link crownCollectMode}). Stamped on the document so the claim path decides
   * removal-on-claim without re-deriving, and the map client can flag an
   * exclusive crown ("first to catch") without knowing the rarity cutoff.
   */
  collectMode: CrownCollectMode;
  status: CrownSpawnStatus;
  source: typeof CROWN_SPAWN_SOURCE;
  /**
   * ALWAYS false on an auto-spawned crown, and stored explicitly rather than
   * omitted.
   *
   * A hand-placed point carries `approvedByUserId` because a named admin
   * confirmed that exact spot is safe to stop at. No human ever saw this
   * coordinate. The honest record of that is a field that says so, so an admin
   * review screen, a report follow-up, or an incident investigation can tell at
   * a glance which crowns had a person behind them and which had an algorithm
   * inside a human-approved AREA (`approvedCellBy` below).
   */
  safeLocationConfirmed: false;
  /**
   * The admin who approved the AREA this crown was placed in — either the
   * single grid CELL (`crownSpawnCells`, the hand-approved path) or the marked
   * AREA (`crownSpawnAreas`, whose activator is recorded here). Named
   * `approvedCellBy` for back-compat with the cell path that shipped first; it
   * is the approving admin for whichever allow-list source placed the crown.
   */
  approvedCellBy: string | null;
  /**
   * The marked AREA this crown was placed in, or null for a crown placed by the
   * hand-approved single-cell path (`crownSpawnCells`).
   *
   * Stored explicitly so deactivating or deleting an area can DRAIN exactly its
   * live crowns (`crownSpawns where areaId == X and status == 'live'`), mirroring
   * the way revoking a cell drains that cell — without it, a crown placed inside
   * an area an admin has just declared unsafe would stand for its full TTL.
   */
  areaId: string | null;
  claimedByUid: string | null;
}

export function buildCrownSpawnFields(params: {
  cellKey: string;
  position: CrownPosition;
  rarity: CrownRarity;
  approvedCellBy: string | null;
  /** Set for the marked-area spawner; omitted/null for the single-cell path. */
  areaId?: string | null;
}): CrownSpawnFields {
  return {
    cellKey: params.cellKey,
    latitude: params.position.latitude,
    longitude: params.position.longitude,
    rarity: params.rarity,
    rewardPoints: crownRewardPoints(params.rarity),
    collectRadiusMeters: COLLECT_RADIUS_METERS,
    collectMode: crownCollectMode(params.rarity),
    status: 'live',
    source: CROWN_SPAWN_SOURCE,
    safeLocationConfirmed: false,
    approvedCellBy: params.approvedCellBy,
    areaId: params.areaId ?? null,
    claimedByUid: null,
  };
}

// ---------------------------------------------------------------------------
// Admin cell allow-list input
// ---------------------------------------------------------------------------

/** Minimum length of the free-text safety note an approval must carry. */
export const SPAWN_CELL_NOTE_MIN_LENGTH = 3;
export const SPAWN_CELL_NOTE_MAX_LENGTH = 2000;

/**
 * `lastSpawnPassAt` seed for a cell the spawner has never served (epoch, i.e.
 * 1970-01-01T00:00:00Z).
 *
 * The field has to EXIST on approval, because the (now-removed) single-cell spawn
 * pass ordered the allow-list by it and Firestore excludes documents missing the
 * orderBy field — a cell without it would never be served at all. The single-cell
 * pass is gone, but `setSpawnCellApproval` still writes this field (it is kept
 * deployed but dormant), and the value still has to be honest about what it
 * means. `lastSpawnPassAt` records when the spawner last
 * looked at this cell; a brand-new cell has never been looked at, so seeding it
 * with "now" would state the opposite of the truth and sort the cell to the
 * BACK of a least-recently-served queue — the one place a never-served cell
 * does not belong. The epoch sentinel is both true and correctly ordered: never
 * served sorts ahead of every cell that has been.
 *
 * Re-approval reseeds it deliberately. A revocation deletes that cell's live
 * crowns, so a re-approved area starts empty and should be repopulated on the
 * next pass rather than waiting out a full round-robin cycle.
 */
export const SPAWN_CELL_NEVER_SERVED_AT_MS = 0;

const cellKeySchema = z
  .string()
  .trim()
  .min(1)
  .max(32)
  .refine((key) => parseCrownCellKey(key) !== null, {
    message: 'cellKey must be a spawn-grid key, e.g. "5933_1806".',
  });

/**
 * Approving a cell mirrors `activatePoint`'s safety gate one level up: an
 * explicit `safeAreaConfirmed: true` literal (so it can never be satisfied by a
 * default or a truthy accident) plus a note that lands in the audit record.
 * Revoking needs neither — turning an area OFF must never be harder than
 * turning it on.
 */
const setSpawnCellApprovalInputSchema = z
  .discriminatedUnion('approved', [
    z
      .object({
        approved: z.literal(true),
        cellKey: cellKeySchema.optional(),
        latitude: z.number().optional(),
        longitude: z.number().optional(),
        safeAreaConfirmed: z.literal(true),
        approvalNote: z
          .string()
          .trim()
          .min(SPAWN_CELL_NOTE_MIN_LENGTH)
          .max(SPAWN_CELL_NOTE_MAX_LENGTH),
      })
      .strict(),
    z
      .object({
        approved: z.literal(false),
        cellKey: cellKeySchema.optional(),
        latitude: z.number().optional(),
        longitude: z.number().optional(),
        reason: z.string().trim().max(SPAWN_CELL_NOTE_MAX_LENGTH).optional(),
      })
      .strict(),
  ])
  // The cell can be named directly or picked off a map; exactly one of the two
  // must be present, so a request can never silently target a default cell.
  .refine(
    (input) =>
      (input.cellKey !== undefined) !==
      (input.latitude !== undefined && input.longitude !== undefined),
    { message: 'Provide either cellKey or both latitude and longitude.' },
  )
  .refine(
    (input) =>
      input.latitude === undefined ||
      (input.latitude >= -90 &&
        input.latitude <= 90 &&
        input.longitude !== undefined &&
        input.longitude >= -180 &&
        input.longitude <= 180),
    { message: 'Coordinates out of range.' },
  );

export type SetSpawnCellApprovalInput = z.infer<typeof setSpawnCellApprovalInputSchema>;

export function parseSetSpawnCellApprovalInput(
  data: unknown,
): ParseResult<SetSpawnCellApprovalInput> {
  const result = setSpawnCellApprovalInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        'Expected { approved: true, safeAreaConfirmed: true, approvalNote (>=3 chars) } or { approved: false, reason? }, plus EITHER cellKey OR { latitude, longitude }.',
    };
  }
  return { ok: true, input: result.data };
}

/** Resolves the targeted cell key from either input form. */
export function resolveSpawnCellKey(input: SetSpawnCellApprovalInput): string | null {
  if (input.cellKey !== undefined) return input.cellKey;
  if (input.latitude === undefined || input.longitude === undefined) return null;
  return crownCellKey(input.latitude, input.longitude);
}
