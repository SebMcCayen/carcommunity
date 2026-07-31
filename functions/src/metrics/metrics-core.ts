/**
 * Community growth metrics — shared types and pure helpers.
 *
 * WHY THIS EXISTS
 * ---------------
 * A scheduled job writes one small, bounded document per day into
 * `metrics/{YYYY-MM-DD}`, and the admin web app charts the series to show —
 * via screenshot — how the community is growing over time (Seb product
 * decision 2026-07). This file holds the SHAPE of that document and the pure
 * date helper, kept out of `scheduled.ts` so it is unit-testable without the
 * Admin SDK and so the admin app can mirror the same type by hand.
 *
 * NO PII, EVER
 * ------------
 * Every field below is a pure AGGREGATE — a total or a per-brand count. The
 * page is meant to be shown publicly, so a single member must never be
 * identifiable from a snapshot. Brand distribution is counts per brand id,
 * never who owns what. There is no per-user field anywhere in this shape, and
 * nothing here should ever gain one.
 *
 * SNAPSHOT SEMANTICS (no historical backfill)
 * -------------------------------------------
 * Each snapshot records CUMULATIVE totals AS OF its capture instant — it is a
 * point sample, not a per-day delta. "New users per day" is derived by the
 * reader as the delta between consecutive snapshots' `totalUsers`, so no
 * per-day counter is stored. The series starts empty on the day the job first
 * runs and fills in going forward; history is never reconstructed from
 * `createdAt`.
 */

/** Firestore collection holding the daily snapshots. */
export const METRICS_COLLECTION = 'metrics';

/** The reserved brand-distribution bucket for "Other / not listed" makes. */
export const METRICS_OTHER_MAKE_ID = 'other';

/**
 * One day's snapshot document (`metrics/{date}`). All counts are cumulative
 * totals as of `capturedAt`, except where noted.
 *
 * Kept small on purpose: short integer fields plus one bounded map. See the
 * storage note in `scheduled.ts`.
 */
export interface MetricsSnapshot {
  /** Doc id echoed into the body: `YYYY-MM-DD` in Europe/Stockholm. */
  date: string;
  /** When the job captured this snapshot (ms since epoch, UTC). */
  capturedAtMs: number;

  // --- Core totals (Seb named these) ---
  /** Total registered accounts. */
  totalUsers: number;
  /** Convoys ever created (cumulative; convoys are never deleted, only ended). */
  convoysCreated: number;
  /** Total distance across all saved drives, in metres. */
  totalDistanceMeters: number;
  /** Total events ever created. */
  eventsHeld: number;

  // --- "Fun" extras (cheap aggregate sources) ---
  /** Total saved drives. */
  drivesSaved: number;
  /** Total crowns collected in Kronjakt (claimed crown documents). */
  crownsCollected: number;
  /** Total friend connections (undirected; edge docs are bidirectional). */
  friendConnections: number;
  /** Convoys currently live (status == 'active'). */
  activeConvoys: number;
  /** Total private vehicle profiles across all garages. */
  vehicleProfiles: number;

  /**
   * Vehicle-brand distribution: `makeId -> count`. Only non-zero buckets are
   * stored (keeps the map bounded and the doc tiny), including the
   * `other` bucket when non-zero. Bounded by the catalogue size, never by the
   * number of vehicles.
   */
  brandDistribution: Record<string, number>;
}

/**
 * The stable Europe/Stockholm `YYYY-MM-DD` id for `date`. Used as BOTH the doc
 * id and the `date` field, which is what makes a re-run for the same day an
 * idempotent overwrite rather than an append.
 *
 * Uses `sv-SE` formatting (already `YYYY-MM-DD`) in the community's own
 * timezone so a screenshot's "as of" date matches the day Seb sees locally,
 * independent of the server's UTC clock.
 */
export function snapshotDateId(now: Date): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Europe/Stockholm',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}
