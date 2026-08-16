/**
 * Kronjakt PERK-USAGE STATISTICS — pure core (admin-stats PR-A).
 *
 * The perk shop (buyPerk) + PvP (deployPerk / pvp-drain) already write, on every
 * event, the backend documents this aggregate is derived from. This module is
 * the single home for every decision the perk-stats layer makes that does NOT
 * need Firestore: the fixed perk-keyed count shape, the two scopes each event
 * folds into (all-time + its Europe/Stockholm season), and the fold-marker id
 * that makes each event count exactly once. It imports no Firebase Admin SDK and
 * reads no ambient clock, so every edge is unit-testable without an emulator
 * (perk-stats-core.test.ts). The Firestore-touching triggers live in
 * statsTriggers.ts, mirroring the spawn/leaderboard stats already there.
 *
 * DESIGN — one small admin-only document per scope:
 *   crownHuntPerkStats/{scope}  (scope = 'alltime' | 'YYYY-MM')
 *     {
 *       scope,
 *       usedByPerk:      { spike_strip, shield, boost },  // deploy/activation count
 *       purchasedByPerk: { spike_strip, shield, boost },  // perk_shop purchases
 *       trapTriggers,                                      // trap drains (spike_strip only)
 *       updatedAt,
 *     }
 * A small FIXED-KEY map (the three known perks), never a per-user explosion.
 *
 * THREE SOURCES, each a document the backend already writes:
 *   - perkDeploys/{deployId}  → usedByPerk[perkId]      (every deploy of any kind)
 *   - pointsLedger/.../entries with source 'perk_shop' → purchasedByPerk[perkId]
 *   - perkDrains/{drainId}    → trapTriggers            (each trap trigger)
 *
 * SEASON DERIVATION. Perk documents are NOT season-stamped (perks exist outside
 * the season model), so the season is derived from each event's `createdAt` via
 * `seasonIdForInstant` — the same Europe/Stockholm monthly bucketing the crown
 * leaderboard uses — reused here rather than re-implemented.
 *
 * INERT PRE-LAUNCH. Every source is gated on the contract-default-OFF
 * `crownHuntPerks` flag, so no perk event exists until an operator turns the
 * shop on. Until then the aggregate simply never gets written and reads as
 * "absent" (zeros), which is expected.
 */

import { ALL_TIME_SCOPE, seasonIdForInstant } from './crown-hunt-stats-core';
import { PERK_IDS, isPerkId, type PerkId } from './perks-core';

// ---------------------------------------------------------------------------
// Collection names
// ---------------------------------------------------------------------------

/** Per-scope admin-readable perk-usage aggregate: `crownHuntPerkStats/{scope}`. */
export const CROWN_PERK_STATS_COLLECTION = 'crownHuntPerkStats';

/**
 * Backend-only exactly-once markers for the perk-stats triggers. ONE marker per
 * source EVENT (not per scope): the marker guards the increment of BOTH scopes
 * inside a single transaction, exactly like `crownHuntSpawnFolds` /
 * `crownHuntStatFolds`. Never client-readable or client-writable.
 */
export const CROWN_PERK_STAT_FOLDS_COLLECTION = 'crownHuntPerkStatFolds';

// ---------------------------------------------------------------------------
// Perk-keyed counts
// ---------------------------------------------------------------------------

/** The perk ids the aggregate keys by — the full catalog, in catalog order. */
export const PERK_STAT_KEYS: readonly PerkId[] = PERK_IDS;

/** A zeroed per-perk histogram — the shape every `*ByPerk` map takes. */
export function zeroPerkCounts(): Record<PerkId, number> {
  const out = {} as Record<PerkId, number>;
  for (const id of PERK_STAT_KEYS) {
    out[id] = 0;
  }
  return out;
}

/** The doc-shape (minus `updatedAt`) an empty `crownHuntPerkStats/{scope}` holds. */
export interface PerkStatsDoc {
  scope: string;
  usedByPerk: Record<PerkId, number>;
  purchasedByPerk: Record<PerkId, number>;
  trapTriggers: number;
}

/**
 * The zeroed aggregate for a scope — a full fixed-key document. The triggers
 * write incrementally (blind `FieldValue.increment`s, never a read-modify-write)
 * so they never build this; it is the canonical shape the admin UI reads and the
 * reference an emulator/unit test asserts against.
 */
export function buildEmptyPerkStats(scope: string): PerkStatsDoc {
  return {
    scope,
    usedByPerk: zeroPerkCounts(),
    purchasedByPerk: zeroPerkCounts(),
    trapTriggers: 0,
  };
}

// ---------------------------------------------------------------------------
// Scopes + fold ids
// ---------------------------------------------------------------------------

/**
 * The two scopes a perk event at `instant` increments: the never-resetting
 * all-time board and the event's own Europe/Stockholm season (`YYYY-MM`).
 * Derived from `seasonIdForInstant` so the perk aggregate buckets identically to
 * the crown leaderboard.
 */
export function perkStatScopesFor(instant: Date): [string, string] {
  return [ALL_TIME_SCOPE, seasonIdForInstant(instant)];
}

/** The source kind a perk-stat fold marker was created for. */
export type PerkStatSource = 'deploy' | 'drain' | 'purchase';

/**
 * `crownHuntPerkStatFolds/{source__sourceDocId}` — the exactly-once guard for a
 * single source event. Namespaced by source so the deploy, drain and purchase
 * id spaces (which live in the one folds collection) can never collide. One
 * marker guards BOTH scope increments for that event, mirroring the crown folds.
 */
export function perkStatFoldId(source: PerkStatSource, sourceDocId: string): string {
  return `${source}__${sourceDocId}`;
}

export { isPerkId, type PerkId };
