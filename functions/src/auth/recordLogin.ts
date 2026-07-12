/**
 * auth.recordLogin — member-gated callable (contracts/functions/functions.json).
 *
 * Stamps `users/{uid}.lastLoginAt = serverTimestamp()` on each successful
 * sign-in. This is a TRUSTED SERVER WRITE via the Admin SDK: `lastLoginAt` is
 * omitted from the users/{uid} owner-write whitelist in firestore.rules, so
 * clients can never set it — only this callable can.
 *
 * Why a Firestore field and not Firebase Auth's built-in lastSignInTime: the
 * Auth metadata timestamp is not queryable, so the scheduled inactive-account
 * sweep (functions/src/account/inactivityCleanup.ts) could not scan by it. The
 * Firestore field is both queryable AND directly displayable on the KCC admin
 * user profile.
 *
 * Member-gated (requireMemberActor): suspension/deletion close the callable, and
 * a non-member simply never records a lastLoginAt — the sweep's documented
 * fallback (lastLoginAt ?? createdAt) covers those accounts. The Android client
 * calls this best-effort after auth completes; a permission-denied for a
 * non-member is swallowed there and never blocks the UI.
 *
 * Idempotent by nature: a repeat call just refreshes the timestamp.
 */

import { onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface RecordLoginResponse {
  recorded: true;
}

export const recordLogin = onCall(CALLABLE_OPTS, async (request): Promise<RecordLoginResponse> => {
  const actor = await requireMemberActor(request);

  await db
    .collection('users')
    .doc(actor.uid)
    .set({ lastLoginAt: FieldValue.serverTimestamp() }, { merge: true });

  return { recorded: true };
});
