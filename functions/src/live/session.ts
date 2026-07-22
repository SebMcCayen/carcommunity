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
  type LiveSessionDuration,
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

/**
 * The live-session duration a convoy-auto session is started with. A convoy has
 * no fixed length, so the longest supported duration is used; the session is
 * stopped explicitly when the user leaves/ends the convoy (stopConvoyAutoSession)
 * and, as a backstop, expires via the TTL sweep after this window regardless.
 */
export const CONVOY_AUTO_SESSION_DURATION: LiveSessionDuration = '4h';

export interface SessionResponse {
  sessionId: string;
  status: string;
  expiresAt?: string;
}

/**
 * Loads the two fields a live session denormalizes at start — the caller's
 * displayName and their main car — shared by the manual startSession callable
 * and the convoy auto-start producer so both build identical sessions. The
 * profile read and the vehicles query are independent, so they run in parallel.
 * The garage is capped at MAX_VEHICLES_PER_USER, so the owner query (single-field
 * userId index) is cheap and .limit() bounds it even against corrupt data.
 */
async function loadSessionDenorm(
  uid: string,
): Promise<{ displayName: string | null; mainCar: ReturnType<typeof toLiveMainCar> }> {
  const [profile, ownedVehicles] = await Promise.all([
    db.collection('users').doc(uid).get(),
    db.collection('vehicles').where('userId', '==', uid).limit(MAX_VEHICLES_PER_USER).get(),
  ]);
  const mainCar = toLiveMainCar(
    ownedVehicles.docs.find((doc) => doc.data().isMainCar === true)?.data(),
  );
  return { displayName: (profile.data()?.displayName as string | undefined) ?? null, mainCar };
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

  // Denormalize the caller's displayName + main car onto the session so viewers
  // of the live share see who and which car it is (shared with the convoy
  // auto-start producer below).
  const { displayName, mainCar } = await loadSessionDenorm(actor.uid);
  const session = buildSession(
    db.collection('_ids').doc().id, // Firestore auto-ID as a cheap unique id
    parsed.input.duration,
    new Date(),
    displayName,
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

// ---------------------------------------------------------------------------
// Convoy auto-started live sessions (item 2 — "starting a convoy auto-starts a
// live session so everyone in the convoy can see you").
//
// These are the PRODUCER side of live-share, reused by the convoy callables
// (functions/src/convoy/manageConvoy.ts). They write ONLY the backend-owned
// liveLocation/{uid}/session node (plus clearing latest, exactly as the manual
// startSession does) — never the marker read path or its rules. Visibility of
// the resulting session is the EXISTING live-share audience: any non-suspended
// activeMember (minus blocks) can read the marker, convoy members among them.
// There is no per-convoy audience scoping in the read rules today; making the
// auto-session visible ONLY to convoy members would require a scoped-audience
// change to the marker read/rules path, which is owned by the separate
// live-visibility (item 6) investigation. The convoyId is stamped on the session
// so that scoping can filter on it later with no producer change.
// ---------------------------------------------------------------------------

export type ConvoyAutoStartOutcome = 'started' | 'skipped-existing' | 'flag-off';

/**
 * Auto-starts a convoy-scoped live session for `uid`, UNLESS they already have
 * an active session — in which case it is left exactly as-is (the crucial rule
 * that stops a convoy from clobbering, re-tagging, or later killing a session
 * the user started MANUALLY: only sessions this function actually creates are
 * tagged convoyAutoStarted, and only tagged sessions are torn down). An already
 * active session — manual OR from this same convoy — already makes the user
 * visible to the convoy, so there is nothing to do.
 *
 * Best-effort by contract: the caller (convoy.start / convoy.respond) treats a
 * throw as non-fatal, because a convoy must still start even if live-share is
 * unavailable. The liveLocation feature flag is honoured (flag-off → no session).
 */
/** Whether the live-location feature is enabled (single flag read). */
export function isLiveShareEnabled(): Promise<boolean> {
  return readFeatureFlag(LIVE_LOCATION_FLAG_KEY);
}

export async function startConvoyAutoSession(
  uid: string,
  convoyId: string,
  // Pre-resolved flag decision. convoy.start fans out to up to MAX_CONVOY_SIZE
  // members, so it reads the flag ONCE and passes it here rather than paying a
  // Firestore read of config/featureFlags per member. Omitted on the single-call
  // path (convoy.respond late-join), where reading it here is one read.
  liveEnabled?: boolean,
): Promise<ConvoyAutoStartOutcome> {
  const enabled = liveEnabled ?? (await isLiveShareEnabled());
  if (!enabled) {
    return 'flag-off';
  }
  const now = new Date();
  // Cheap fast-path: skip the denormalization reads when a session is already
  // active. NOT the authoritative check — that is the transaction below.
  const existing = (await sessionRef(uid).get()).val() as LiveSession | null;
  if (isSessionActive(existing, now)) {
    return 'skipped-existing';
  }

  const { displayName, mainCar } = await loadSessionDenorm(uid);
  const session: LiveSession = {
    ...buildSession(
      db.collection('_ids').doc().id,
      CONVOY_AUTO_SESSION_DURATION,
      now,
      displayName,
      mainCar,
    ),
    convoyAutoStarted: true,
    convoyId,
  };
  // ATOMIC check-and-set (not a plain read-then-set): the write only lands when
  // there is STILL no active session at commit time, so a live.startSession that
  // raced in between — a MANUAL share the user just began — is never clobbered
  // by the convoy auto-start. Aborting (returning undefined) leaves that session
  // untouched and untagged, preserving the "don't clobber / don't stop a manual
  // session" guarantee even under the race. The denorm reads above are pure input
  // to `session`; discarding them on an abort is a cheap, rare cost.
  const { committed } = await sessionRef(uid).transaction((current) => {
    if (isSessionActive(current as LiveSession | null, now)) {
      return; // abort — keep the existing active (manual or prior) session
    }
    return session;
  });
  if (!committed) {
    return 'skipped-existing';
  }
  // Only clear the stale state once we actually took over the session node. Both
  // the RTDB marker AND the Firestore nearby-discovery doc are cleared — exactly
  // as the manual startSession does — so a take-over from a prior (expired)
  // session cannot leave the user discoverable at the OLD position in
  // live.listNearby until the first fresh updatePosition of the new session
  // re-creates the doc.
  await latestRef(uid).remove();
  await discoveryRef(uid).delete();
  return 'started';
}

export type ConvoyAutoStopOutcome = 'stopped' | 'left-untouched';

/**
 * Stops the live session `uid` had auto-started FOR this convoy, and only that:
 * the session must currently be active, flagged convoyAutoStarted, and carry the
 * matching convoyId. A manually-started session (no flag), a session auto-started
 * for a DIFFERENT convoy, or one already stopped/expired is left untouched. Like
 * a normal stop it marks the session stopped and removes the marker immediately.
 *
 * ATOMIC check-and-update (an RTDB transaction, not a read-then-update): the
 * stop only applies to a session that STILL matches (active + convoyAutoStarted +
 * this convoyId) at commit time, so a manual live.startSession — or a different
 * convoy's auto-session — that raced in between the read and the write is never
 * mutated to 'stopped'. Mirror of startConvoyAutoSession's atomic write.
 *
 * Best-effort: a throw here must not fail convoy.leave / convoy.end.
 */
export async function stopConvoyAutoSession(
  uid: string,
  convoyId: string,
): Promise<ConvoyAutoStopOutcome> {
  const { committed, snapshot } = await sessionRef(uid).transaction((current) => {
    const s = current as (LiveSession & { convoyAutoStarted?: boolean }) | null;
    // NULL is returned unchanged rather than aborted: RTDB runs the update
    // function optimistically (it may see null before the server value loads),
    // and returning `undefined` there would ABORT before ever seeing the real
    // session. Returning null instead forces a compare-and-set that retries with
    // the server value when a session actually exists. `undefined` (abort) is
    // reached ONLY for a REAL, non-matching session — so a manual live session
    // that raced in is left exactly as-is, never mutated to 'stopped'.
    if (s === null) {
      return null;
    }
    if (s.status === 'active' && s.convoyAutoStarted === true && s.convoyId === convoyId) {
      return {
        ...s,
        status: 'stopped',
        stoppedAt: new Date().toISOString(),
        stopReason: 'user_stop' satisfies LiveStopReason,
      };
    }
    return; // abort — a real non-matching session (manual / other convoy / already stopped)
  });
  // We stopped it only when the commit wrote our stopped session; an abort or a
  // no-op on a raced-in manual session leaves the node untouched.
  const after = snapshot.val() as (LiveSession & { convoyAutoStarted?: boolean }) | null;
  if (committed && after?.status === 'stopped' && after?.convoyAutoStarted === true) {
    // Mirror the manual stop/hide teardown: remove BOTH the RTDB marker and the
    // Firestore nearby-discovery doc so ending/leaving the convoy takes the user
    // out of live.listNearby at once, rather than leaving them discoverable at a
    // stale position until the discovery doc's TTL sweep.
    await latestRef(uid).remove();
    await discoveryRef(uid).delete();
    return 'stopped';
  }
  return 'left-untouched';
}
