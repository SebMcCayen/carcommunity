/**
 * Kronjakt auto-spawn — the ACTIVITY SIGNAL writer.
 *
 * The spawn engine needs to know where members actually are, so it can put
 * crowns there and nowhere else. This module is the only thing that produces
 * that knowledge, and it is deliberately the narrowest possible pipe.
 *
 * ## Why `live.updatePosition` is the signal
 * The app already produces exactly one continuous stream of "a member is here
 * right now": the live-location session. `live.updatePosition` is called on
 * every GPS sample, already computes a coarse grid cell, already throttles a
 * Firestore write behind that, and already requires an active, non-suspended
 * account with an explicit, user-started, time-limited sharing session. That
 * last part matters most: a member who has not opted into live sharing
 * contributes NOTHING here. Consent for the signal is the consent they already
 * gave for the feature.
 *
 * The alternatives were worse. A drive save (`drives.saveDrive`) carries a
 * whole route — far more location than an activity heat count needs, and it
 * arrives in a burst after the fact, so the "where are people NOW" question it
 * answers is hours stale. A dedicated client ping would be a new tracking
 * endpoint whose only purpose is tracking.
 *
 * ## Only SLOW sightings count (safety, not tuning)
 * A sample is recorded only when the device reported a speed at or below
 * `MAX_ACTIVITY_SPEED_MPS` (8 m/s ≈ 29 km/h). Raw presence would make a
 * motorway the highest-scoring place in the country, and a crown placed beside
 * one is an invitation to stop on a hard shoulder. Requiring slow presence
 * means a cell can only earn a score from places people are actually AT — car
 * parks, meets, queues, on foot — and a cell nobody ever slows down in scores
 * exactly zero however much traffic passes through it. This runs UNDER the
 * admin cell allow-list (spawnCells.ts), not instead of it: it is what stops an
 * approved area that happens to contain a through-road from spawning beside it.
 *
 * ## What is actually stored
 * `crownCellActivity/{cellKey}`                    — one doc per ~1.1 km cell:
 *                                                    `lastActivityAt` only.
 * `crownCellActivity/{cellKey}/recentUsers/{hash}` — one doc per DISTINCT user
 *                                                    seen in that cell:
 *                                                    `lastSeenAt` only.
 *
 * There is no UID, no coordinate, no heading, no speed, no timestamp series —
 * one overwritten `lastSeenAt` per person per cell. The document ID is a
 * CELL-SCOPED digest (`crownActivityUserHash`), so the same member is a
 * different identifier in every cell and the collection cannot be joined into a
 * route by anyone, including us. Both collections are denied to every client in
 * firestore.rules; only the Admin SDK touches them.
 *
 * ## Cost on the hot path
 * At most ONE flag read plus TWO small writes per user per cell per
 * `CROWN_ACTIVITY_MIN_INTERVAL_MS` (10 minutes), with an immediate write when
 * the member crosses into a new cell so the aggregate follows them. The
 * throttle state rides on the RTDB session node `live.updatePosition` has
 * already read, so deciding to skip costs nothing. Every failure is swallowed:
 * a game's heat map must never be able to break live location sharing.
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  ACTIVITY_WINDOW_MS,
  CROWN_SPAWN_FLAG_KEY,
  crownActivityUserHash,
  crownCellKey,
  isActivitySightingEligible,
  shouldRecordCrownActivity,
} from './crown-spawn-core';

/** The throttle state this module keeps on the caller's RTDB session node. */
export interface CrownActivityThrottleState {
  crownActivityAt?: string | null;
  crownActivityCell?: string | null;
}

/** What the caller should merge back onto the session node after a write. */
export interface CrownActivityRecorded {
  crownActivityAt: string;
  crownActivityCell: string;
}

/**
 * Records one aggregate activity sighting, honouring the throttle.
 *
 * Returns the new throttle state when a write happened, or null when the
 * sample was throttled, the feature is off, or the write failed — the caller
 * only persists throttle state on a real write, so a failed write is retried on
 * the next sample rather than silently suppressed for 10 minutes.
 *
 * BEST EFFORT BY CONTRACT: never throws. `live.updatePosition` is a
 * high-frequency safety-adjacent call; a Kronjakt heat-map write is not
 * allowed to fail it.
 */
export async function recordCrownActivity(params: {
  uid: string;
  latitude: number;
  longitude: number;
  /** Device-reported speed for this sample; absent means the sighting is skipped. */
  speedMetersPerSecond: number | null | undefined;
  now: Date;
  throttle: CrownActivityThrottleState | null | undefined;
}): Promise<CrownActivityRecorded | null> {
  try {
    // Checked FIRST — before the throttle and before the flag read — so a
    // moving member costs nothing at all and can never contribute a sighting.
    if (!isActivitySightingEligible(params.speedMetersPerSecond)) return null;

    const cellKey = crownCellKey(params.latitude, params.longitude);
    const shouldRecord = shouldRecordCrownActivity(
      { recordedAtIso: params.throttle?.crownActivityAt, cellKey: params.throttle?.crownActivityCell },
      cellKey,
      params.now,
    );
    if (!shouldRecord) return null;

    // Flag read happens ONLY when a write is otherwise due, so the cost is one
    // extra read per 10 minutes per sharer, not one per GPS sample. While the
    // flag is off, nothing is collected at all — the aggregate does not quietly
    // fill up ahead of a launch.
    if (!(await readFeatureFlag(CROWN_SPAWN_FLAG_KEY))) return null;

    const cellRef = db.collection('crownCellActivity').doc(cellKey);
    const userRef = cellRef.collection('recentUsers').doc(crownActivityUserHash(cellKey, params.uid));
    const expireAt = Timestamp.fromMillis(params.now.getTime() + ACTIVITY_WINDOW_MS);

    // Two independent merges, not a transaction: these are last-write-wins
    // timestamps with no invariant between them, and a transaction on the cell
    // document would serialise every sharer in the cell against each other on
    // the position hot path.
    await Promise.all([
      cellRef.set(
        {
          cellKey,
          lastActivityAt: FieldValue.serverTimestamp(),
          // Backstop for the sweeper AND a ready-made Firestore TTL field, so
          // an abandoned cell disappears even if the sweep is paused.
          expireAt,
        },
        { merge: true },
      ),
      userRef.set(
        {
          // No uid, no coordinate — the document ID is already the (cell-scoped)
          // pseudonym, and `lastSeenAt` is the entire payload the score needs.
          lastSeenAt: FieldValue.serverTimestamp(),
          expireAt,
        },
        { merge: true },
      ),
    ]);

    return { crownActivityAt: params.now.toISOString(), crownActivityCell: cellKey };
  } catch (error) {
    logger.warn('Crown activity aggregate write failed; ignoring', { error: String(error) });
    return null;
  }
}
