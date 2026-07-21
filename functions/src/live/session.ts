/**
 * live.startSession / updatePosition / stopSession / hideMeNow —
 * live-location callables (contracts/functions/functions.json), Phase 10.
 *
 * All RTDB writes under liveLocation/ are backend-only (rules deny every
 * client write), so session integrity, the 60-second staleness
 * threshold, and marker shape are enforced here:
 *
 * - startSession (signedIn active, liveLocation flag): creates/replaces
 *   the caller's session (starting again restarts with a fresh id/expiry)
 *   and denormalizes displayName for markers, and clears any stale nearby-
 *   discovery doc so a restart isn't discoverable at the old position until
 *   its first fresh sample. Sharing your OWN location is FREE — no
 *   subscription. VIEWING OTHERS' markers is gated by the
 *   liveLocation/$uid/latest RTDB read rule, which today requires the VIEWER
 *   to be authenticated, non-suspended, and not blocked in either direction —
 *   NOT (yet) an activeMember: member gating is disabled repo-wide
 *   (shared/memberGating.ts, MEMBER_GATING_ENABLED=false), and the RTDB rule
 *   deliberately does not encode an activeMember gate. When paid viewing is
 *   re-locked, that gate is added to the RTDB rule; until then this is the
 *   honest description of what the rule enforces.
 * - updatePosition (signedIn active): requires an ACTIVE, unexpired
 *   session; writes the lean liveLocation/{uid}/latest marker node AND
 *   refreshes the queryable nearby-discovery doc (liveSessions/{uid}) so
 *   standalone sharers are findable by users nearby (live.listNearby).
 * - stopSession (signedIn active): requires an active, non-suspended caller
 *   (requireActiveActor), so it is NOT available while suspended/restricted —
 *   use hideMeNow as the privacy escape hatch in that case. Marks the session
 *   stopped and removes `latest` — the marker disappears immediately.
 * - hideMeNow (signedIn — works while SUSPENDED; removing your own
 *   position is a privacy action that must always be available): stops
 *   the session with reason hide_me_now and removes `latest`.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { adminRtdb, db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { readFeatureFlag } from '../shared/featureFlags';
import {
  LIVE_LOCATION_FLAG_KEY,
  buildLatestNode,
  buildSession,
  guardPositionFreshness,
  isSessionActive,
  parseStartSessionInput,
  parseStopSessionInput,
  parseUpdatePositionInput,
  toLiveMainCar,
  type LiveSession,
  type LiveStopReason,
} from './live-core';
import { buildDiscoveryFields, discoveryExpiresAt, shouldRefreshDiscovery } from './nearby-core';
import { MAX_VEHICLES_PER_USER } from '../garage/garage-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const sessionRef = (uid: string) => adminRtdb.ref(`liveLocation/${uid}/session`);
const latestRef = (uid: string) => adminRtdb.ref(`liveLocation/${uid}/latest`);
/** The queryable nearby-discovery doc (Firestore), one per active sharer. */
const discoveryRef = (uid: string) => db.collection('liveSessions').doc(uid);

export interface SessionResponse {
  sessionId: string;
  status: string;
  expiresAt?: string;
}

export const startSession = onCall(CALLABLE_OPTS, async (request): Promise<SessionResponse> => {
  // Sharing your OWN location is free: any authenticated, non-suspended user
  // can start a session. Only VIEWING OTHERS requires an active subscription
  // (enforced by the liveLocation/$uid/latest RTDB read rule + the Android
  // viewing gate). The LIVE_LOCATION feature flag still gates the whole thing.
  const actor = await requireActiveActor(request);

  const parsed = parseStartSessionInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  if (!(await readFeatureFlag(LIVE_LOCATION_FLAG_KEY))) {
    throw new HttpsError('failed-precondition', 'Live location feature is disabled.');
  }

  // Denormalize the caller's main car onto the session so viewers of the live
  // share see which car it is. The garage is capped at MAX_VEHICLES_PER_USER,
  // so the owner query (single-field userId index — no composite index needed)
  // is cheap; the main car is the one flagged isMainCar (max 1, enforced by
  // garage.setMainVehicle). The .limit() bounds Firestore reads to the cap even
  // if corrupt/legacy data ever leaves a user with more owned vehicles.
  // The profile read and the vehicles query are independent, so fetch them in
  // parallel to keep startSession latency low.
  const [profile, ownedVehicles] = await Promise.all([
    db.collection('users').doc(actor.uid).get(),
    db
      .collection('vehicles')
      .where('userId', '==', actor.uid)
      .limit(MAX_VEHICLES_PER_USER)
      .get(),
  ]);
  const mainCar = toLiveMainCar(
    ownedVehicles.docs.find((doc) => doc.data().isMainCar === true)?.data(),
  );
  const session = buildSession(
    db.collection('_ids').doc().id, // Firestore auto-ID as a cheap unique id
    parsed.input.duration,
    new Date(),
    (profile.data()?.displayName as string | undefined) ?? null,
    mainCar,
  );
  // Starting while a session is active RESTARTS it (fresh id + expiry).
  // Any previous marker is removed immediately — it carries the OLD
  // session's id/expiry and would render inconsistently until the first
  // position update of the new session. The stale nearby-discovery doc is
  // cleared for the same reason: a restart must not stay discoverable at the
  // OLD position until the first fresh sample re-creates the doc.
  await sessionRef(actor.uid).set(session);
  await latestRef(actor.uid).remove();
  await discoveryRef(actor.uid).delete();

  return { sessionId: session.id, status: 'active', expiresAt: session.expiresAt };
});

export interface UpdatePositionResponse {
  recordedAt: string;
}

export const updatePosition = onCall(
  CALLABLE_OPTS,
  async (request): Promise<UpdatePositionResponse> => {
    // Free to share your own position — see startSession for the rationale.
    const actor = await requireActiveActor(request);

    const parsed = parseUpdatePositionInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const now = new Date();
    const freshness = guardPositionFreshness(parsed.input.coordinate.recordedAt, now);
    if (!freshness.ok) {
      throw new HttpsError(freshness.code, freshness.message);
    }

    const session = (await sessionRef(actor.uid).get()).val() as LiveSession | null;
    if (!isSessionActive(session, now)) {
      throw new HttpsError('failed-precondition', 'No active live location session.');
    }

    // displayName is denormalized on the session at start — no extra
    // Firestore read on the (frequent) position-update hot path.
    await latestRef(actor.uid).set(buildLatestNode(parsed.input.coordinate, session!));

    // Refresh the queryable nearby-discovery doc (liveSessions/{uid}) so this
    // sharer is findable by live.listNearby. geoCell is recomputed from the new
    // position; expiresAt is pushed out (bounded by the session's own end) so an
    // actively-moving sharer never expires mid-drive, while a silent one ages
    // out on the same clock the RTDB marker does. displayName is the session's
    // start-time snapshot — no extra read on this hot path.
    //
    // THROTTLED: the RTDB marker above updates every sample, but the Firestore
    // discovery doc is a WRITE, so it is rewritten at most once per
    // MIN_DISCOVERY_REFRESH_MS (or immediately when the geoCell changes). The
    // throttle state lives on the RTDB session node we already read, so the
    // decision costs no extra Firestore read. Skipping the write is safe because
    // the doc's expiresAt is refreshed well inside its TTL on each write.
    const discoveryFields = buildDiscoveryFields({
      uid: actor.uid,
      latitude: parsed.input.coordinate.latitude,
      longitude: parsed.input.coordinate.longitude,
      displayName: session!.displayName ?? null,
    });
    const refreshDiscovery = shouldRefreshDiscovery(
      { refreshedAtIso: session!.discoveryRefreshedAt, geoCell: session!.discoveryGeoCell },
      discoveryFields.geoCell,
      now,
    );
    if (refreshDiscovery) {
      await discoveryRef(actor.uid).set({
        ...discoveryFields,
        updatedAt: FieldValue.serverTimestamp(),
        expiresAt: Timestamp.fromDate(discoveryExpiresAt(session!.expiresAt, now)),
      });
      // Record the throttle state on the session node (cheap RTDB update) so the
      // next samples can skip the Firestore write until the interval elapses or
      // the cell changes.
      await sessionRef(actor.uid).update({
        discoveryRefreshedAt: now.toISOString(),
        discoveryGeoCell: discoveryFields.geoCell,
      });
    }
    return { recordedAt: parsed.input.coordinate.recordedAt };
  },
);

async function stopAndClear(uid: string, reason: LiveStopReason): Promise<SessionResponse> {
  const session = (await sessionRef(uid).get()).val() as LiveSession | null;
  if (session && session.status === 'active') {
    await sessionRef(uid).update({
      status: 'stopped',
      stoppedAt: new Date().toISOString(),
      stopReason: reason,
    });
  }
  // The marker disappears immediately regardless of session state — and so does
  // the nearby-discovery doc, so "hide me now" / stop removes the sharer from
  // discovery at once, not just from the per-uid marker read. This is the
  // privacy escape hatch, so the discovery delete must not be conditional on the
  // session having been active.
  await latestRef(uid).remove();
  await discoveryRef(uid).delete();
  return { sessionId: session?.id ?? 'none', status: 'stopped' };
}

export const stopSession = onCall(CALLABLE_OPTS, async (request): Promise<SessionResponse> => {
  const actor = await requireActiveActor(request);
  const parsed = parseStopSessionInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  return stopAndClear(actor.uid, parsed.input.reason ?? 'user_stop');
});

export const hideMeNow = onCall(CALLABLE_OPTS, async (request): Promise<SessionResponse> => {
  // Deliberately NOT requireActiveActor: removing your own position must
  // work while suspended (privacy action, like unregisterPushToken).
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError('unauthenticated', 'Sign in to continue.');
  }
  return stopAndClear(uid, 'hide_me_now');
});
