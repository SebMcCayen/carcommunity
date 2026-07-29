/**
 * auth.recordLogin — active-account callable (contracts/functions/functions.json).
 *
 * Stamps `userLifecycle/{uid}.lastLoginAt = serverTimestamp()` on each
 * successful sign-in. This is a TRUSTED SERVER WRITE via the Admin SDK:
 * userLifecycle/{uid} denies all client writes in firestore.rules (owner +
 * admin may read, no one may write), so clients can never set it — only this
 * callable can. lastLoginAt lives in userLifecycle (not the public users/{uid}
 * profile, which is readable by any authenticated user) so a member's
 * activity timestamp is never exposed to other signed-in users.
 *
 * Why a Firestore field and not Firebase Auth's built-in lastSignInTime: the
 * Auth metadata timestamp is not queryable, so the scheduled inactive-account
 * sweep (functions/src/account/inactivityCleanup.ts) could not scan by it. The
 * Firestore field is both queryable AND directly displayable on the KCC admin
 * user profile.
 *
 * Active-account gated (requireActiveActor): ANY signed-in, non-suspended,
 * non-deleted account records lastLoginAt — NOT just active members. This is
 * deliberate: the inactive-account sweep resolves activity as
 * lastLoginAt ?? createdAt, so if non-members were excluded an actively
 * signing-in non-member older than 11 months would be treated as inactive
 * forever and eventually auto-deleted. Gating on active status (not membership)
 * keeps every real sign-in fresh. The fallback still covers accounts that have
 * never signed in (no lastLoginAt → createdAt). Suspended/deleted accounts are
 * rejected. The Android client calls this best-effort after auth completes; a
 * permission-denied is swallowed there and never blocks the UI.
 *
 * Idempotent by nature: a repeat call just refreshes the timestamp.
 */

import { onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface RecordLoginResponse {
  recorded: true;
}

export const recordLogin = onCall(CALLABLE_OPTS, async (request): Promise<RecordLoginResponse> => {
  const actor = await requireActiveActor(request);

  await db
    .collection('userLifecycle')
    .doc(actor.uid)
    .set({ lastLoginAt: FieldValue.serverTimestamp() }, { merge: true });

  return { recorded: true };
});
