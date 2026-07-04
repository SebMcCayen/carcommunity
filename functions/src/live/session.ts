/**
 * live.startSession / updatePosition / stopSession / hideMeNow —
 * live-location callables (contracts/functions/functions.json), Phase 10.
 *
 * All RTDB writes under liveLocation/ are backend-only (rules deny every
 * client write), so session integrity, the 60-second staleness
 * threshold, and marker shape are enforced here:
 *
 * - startSession (member, liveLocation flag): creates/replaces the
 *   caller's session (starting again restarts with a fresh id/expiry)
 *   and denormalizes displayName for markers.
 * - updatePosition (member): requires an ACTIVE, unexpired session;
 *   writes the lean liveLocation/{uid}/latest marker node.
 * - stopSession (authenticated): marks the session stopped and removes
 *   `latest` — the marker disappears immediately.
 * - hideMeNow (signedIn — works while SUSPENDED; removing your own
 *   position is a privacy action that must always be available): stops
 *   the session with reason hide_me_now and removes `latest`.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { adminRtdb, db } from '../firebase';
import { requireActiveActor, requireMemberActor } from '../shared/memberActor';
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
  type LiveSession,
  type LiveStopReason,
} from './live-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const sessionRef = (uid: string) => adminRtdb.ref(`liveLocation/${uid}/session`);
const latestRef = (uid: string) => adminRtdb.ref(`liveLocation/${uid}/latest`);

export interface SessionResponse {
  sessionId: string;
  status: string;
  expiresAt?: string;
}

export const startSession = onCall(CALLABLE_OPTS, async (request): Promise<SessionResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseStartSessionInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  if (!(await readFeatureFlag(LIVE_LOCATION_FLAG_KEY))) {
    throw new HttpsError('failed-precondition', 'Live location feature is disabled.');
  }

  const session = buildSession(
    db.collection('_ids').doc().id, // Firestore auto-ID as a cheap unique id
    parsed.input.duration,
    new Date(),
  );
  // Starting while a session is active RESTARTS it (fresh id + expiry);
  // any previous marker is superseded on the next position update.
  await sessionRef(actor.uid).set(session);

  return { sessionId: session.id, status: 'active', expiresAt: session.expiresAt };
});

export interface UpdatePositionResponse {
  recordedAt: string;
}

export const updatePosition = onCall(
  CALLABLE_OPTS,
  async (request): Promise<UpdatePositionResponse> => {
    const actor = await requireMemberActor(request);

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

    const profile = await db.collection('users').doc(actor.uid).get();
    const displayName = (profile.data()?.displayName as string | undefined) ?? null;

    await latestRef(actor.uid).set(buildLatestNode(parsed.input.coordinate, session!, displayName));
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
  // The marker disappears immediately regardless of session state.
  await latestRef(uid).remove();
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
