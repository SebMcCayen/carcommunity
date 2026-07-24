/**
 * `live_session_1km` — points for a live session that actually covered
 * ground, awarded from SERVER-MEASURED distance.
 *
 * WHY IT LIVES HERE AND IS CALLED FROM live.updatePosition:
 *
 * There is no "live session finished with N km" document to hang a Firestore
 * trigger on — live positions are RTDB-only and deliberately keep no history
 * (privacy: no permanent raw location trail). The three candidates were:
 *
 *  1. a client callable reporting the distance — rejected outright: a
 *     client-supplied distance is forgeable, which breaks the whole economy;
 *  2. an RTDB trigger on liveLocation/{uid}/latest — server-authoritative,
 *     but it bills a function invocation for EVERY position sample of every
 *     sharer (thousands per 4-hour session);
 *  3. accumulate inside live.updatePosition, which already runs once per
 *     sample and has ALREADY READ the session node.
 *
 * (3) wins on both cost and honesty, so this module is a small, best-effort
 * function that live/session.ts calls in one line. It adds NO extra reads:
 * the last counted position and the running total ride on the session node
 * the caller just read, and only the (cheap) RTDB write-back happens per
 * sample. The Firestore award transaction runs at most ONCE per session —
 * the first sample that crosses 1 km.
 *
 * Distance is computed with the shared haversine over positions the backend
 * received itself; steps that are implausibly long or from a low-confidence
 * fix contribute nothing (see liveDistanceIncrementMeters), so a spoofed
 * teleport cannot be converted into kilometres.
 *
 * NOT A SPEED FEATURE: only the DISTANCE threshold matters, the reward is
 * flat, it is capped at 2/day by the rule table and it is inside the weekly
 * driving cap. Nothing here reads, stores or rewards speed.
 */

import { logger } from 'firebase-functions';
import { adminRtdb } from '../firebase';
import { tryAwardEconomyPoints } from './economy-award';
import {
  LIVE_SESSION_AWARD_MIN_DISTANCE_METERS,
  economyIdempotencyKey,
  liveDistanceIncrementMeters,
} from './points-economy-core';

/**
 * The points-economy fields kept on `liveLocation/{uid}/session`:
 * `pointsDistanceMeters`, `pointsLastLatitude`, `pointsLastLongitude`,
 * `pointsAwarded`. Backend-only writes (RTDB rules deny every client write
 * under liveLocation/); the owner can READ their own session node, which
 * exposes only their own distance.
 *
 * Typed as a loose record on purpose: the caller passes the LiveSession node
 * it already read, live-core.ts owns that interface, and every field is read
 * defensively here — so this module adds nothing to the live domain's type.
 */
export type LivePointsSessionState = Record<string, unknown>;

export interface LivePositionSample {
  latitude: number;
  longitude: number;
  accuracyMeters?: number | null;
}

const toMeters = (value: unknown): number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0;

const toCoord = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) ? value : null;

/**
 * Accumulates one live-position sample into the session's server-measured
 * distance and, the first time the session crosses
 * LIVE_SESSION_AWARD_MIN_DISTANCE_METERS, awards `live_session_1km`.
 *
 * BEST-EFFORT BY CONTRACT: never throws. A live position update must not fail
 * because a gamification counter could not be written.
 *
 * @param sessionId the live session's id — also the award's idempotency
 *   scope, so restarting a session starts a new (rule-limited) award and
 *   replaying samples within one session cannot double-award.
 */
export async function trackLiveSessionDistance(
  uid: string,
  sessionId: string,
  session: LivePointsSessionState | null | undefined,
  sample: LivePositionSample,
): Promise<void> {
  try {
    if (session?.pointsAwarded === true) {
      return;
    }
    const previousLat = toCoord(session?.pointsLastLatitude);
    const previousLon = toCoord(session?.pointsLastLongitude);
    const increment = liveDistanceIncrementMeters({
      previous:
        previousLat !== null && previousLon !== null
          ? { latitude: previousLat, longitude: previousLon }
          : null,
      next: {
        latitude: sample.latitude,
        longitude: sample.longitude,
        accuracyMeters: sample.accuracyMeters ?? null,
      },
    });
    const total = toMeters(session?.pointsDistanceMeters) + increment;
    const reached = total >= LIVE_SESSION_AWARD_MIN_DISTANCE_METERS;

    await adminRtdb.ref(`liveLocation/${uid}/session`).update({
      pointsDistanceMeters: total,
      pointsLastLatitude: sample.latitude,
      pointsLastLongitude: sample.longitude,
      // Latched BEFORE the award so a burst of concurrent samples cannot each
      // start their own award attempt; the ledger idempotency key below is
      // the authoritative guard either way.
      ...(reached ? { pointsAwarded: true } : {}),
    });

    if (!reached) {
      return;
    }

    const idempotencyKey = economyIdempotencyKey('live_session_1km', uid, sessionId);
    if (!idempotencyKey) {
      return;
    }
    await tryAwardEconomyPoints({
      uid,
      rule: 'live_session_1km',
      now: new Date(),
      idempotencyKey,
      relatedEntityType: 'live_session',
      relatedEntityId: sessionId,
    });
  } catch (error) {
    logger.warn('Live-session distance tracking failed', { uid, error: String(error) });
  }
}
