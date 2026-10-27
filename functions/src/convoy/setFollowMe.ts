/**
 * convoy.setFollowMe — an ACCEPTED convoy member toggles the shared "Follow me"
 * LEADER TRAIL on or off (contracts/functions/functions.json).
 *
 * Deployed via the `convoy` export group (functions/src/index.ts) as
 * `convoy-setFollowMe`. It owns ONLY the leadership pointer on the per-convoy
 * `convoys/{convoyId}/followMe/current` document; the trail POINTS themselves are
 * written directly by the leader's client (Firestore-rules-gated to the current
 * leader — see firebase/firestore.rules), NOT through this callable, so the
 * ~3-5s trail updates never pay a function invocation.
 *
 * Behaviour (the exclusivity + takeover + toggle rules), all inside ONE
 * transaction reading both the parent convoy and followMe doc:
 *  - active=true  → SET leaderUid = caller and RESET the polyline. This is a
 *                   TAKEOVER: it overwrites whoever was leader before, so there is
 *                   ever only ONE trail per convoy and the newest presser wins.
 *  - active=false → CLEAR the trail, but ONLY when the caller is the current
 *                   leader (decideSetFollowMe → 'clear'); a non-leader toggling
 *                   off is a no-op, so one member can never wipe another's trail.
 *
 * Membership and active status are checked INSIDE that transaction, so a
 * concurrent leave/end invalidates its reads. A missing convoy or an outsider is
 * not-found so a convoy can't be probed; a still-invited/declined member is
 * failed-precondition. An ENDED convoy is rejected — there is no live trail to
 * toggle on a finished drive.
 *
 * The callable writes under the Admin SDK, which BYPASSES the followMe write
 * rule, so the takeover (which changes leaderUid to the caller) is allowed here
 * while the rule denies any client from changing leaderUid directly.
 */

import { randomUUID } from 'node:crypto';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  CONVOY_NOT_FOUND_MESSAGE,
  NOT_CONVOY_MEMBER_MESSAGE,
  isAcceptedConvoyMember,
} from '../chatchannels/chat-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { CONVOY_ENDED_MESSAGE } from './convoy-core';
import {
  FOLLOW_ME_DOC_ID,
  decideSetFollowMe,
  parseSetFollowMeInput,
} from './followMe-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

function followMeRef(convoyId: string) {
  return db.collection('convoys').doc(convoyId).collection('followMe').doc(FOLLOW_ME_DOC_ID);
}

export interface SetFollowMeResponse {
  /** True when the caller is now the trail leader; false when the trail is off. */
  leading: boolean;
  /** The current leader uid, or null when no trail is active after this call. */
  leaderUid: string | null;
}

export const setFollowMe = onCall(
  CALLABLE_OPTS,
  async (request): Promise<SetFollowMeResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseSetFollowMeInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { convoyId, active } = parsed.input;

    const convoyRef = db.collection('convoys').doc(convoyId);
    const ref = followMeRef(convoyId);

    const result = await db.runTransaction(async (tx): Promise<SetFollowMeResponse> => {
      // These reads must share the leadership transaction: checking membership
      // before it would let an in-flight activation recreate a cleared trail
      // after the caller leaves or the convoy ends.
      const convoySnap = await tx.get(convoyRef);
      const convoy = convoySnap.data();
      if (!convoySnap.exists || !isAcceptedConvoyMember(convoy, actor.uid)) {
        if (Array.isArray(convoy?.memberUids) && convoy.memberUids.includes(actor.uid)) {
          throw new HttpsError('failed-precondition', NOT_CONVOY_MEMBER_MESSAGE);
        }
        throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
      }
      if (convoy?.status !== 'active') {
        throw new HttpsError(
          'failed-precondition',
          convoy?.status === 'ended' ? CONVOY_ENDED_MESSAGE : 'This convoy is not active.',
        );
      }
      const snap = await tx.get(ref);
      const currentLeaderUid =
        snap.exists && typeof snap.data()?.leaderUid === 'string'
          ? (snap.data()!.leaderUid as string)
          : null;

      const action = decideSetFollowMe(currentLeaderUid, actor.uid, active);
      switch (action.kind) {
        case 'set':
          // Takeover / activation: overwrite the whole doc so any prior leader's
          // polyline is discarded and the new leader starts from an empty trail.
          tx.set(ref, {
            leaderUid: action.leaderUid,
            // Only a real activation/takeover gets a new generation. A current
            // leader's retry below preserves both this id and their polyline.
            activationId: randomUUID(),
            polyline: '',
            updatedAt: FieldValue.serverTimestamp(),
          });
          return { leading: true, leaderUid: action.leaderUid };
        case 'clear':
          // Toggle-off by the current leader.
          tx.delete(ref);
          return { leading: false, leaderUid: null };
        case 'noop':
          // Either the caller asked to turn off a trail they don't own, OR the
          // current leader re-activated (idempotent — must not reset their own
          // polyline). Leave whatever is there untouched and report the real
          // current leader.
          return { leading: currentLeaderUid === actor.uid, leaderUid: currentLeaderUid };
      }
    });

    return result;
  },
);
