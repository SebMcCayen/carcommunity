/**
 * account.deleteAccount — signedIn callable
 * (contracts/functions/functions.json), Phase 9p stage 1: immediate soft
 * delete.
 *
 * Works while suspended (deletion is a support path, like
 * accountDeletionRequests and unregisterPushToken) but not after
 * deletion (idempotent: repeat calls return the existing pending
 * request).
 *
 * Fail-safe ordering mirrors admin.suspendUser: the Auth user is
 * DISABLED and refresh tokens revoked BEFORE the records commit — a
 * partial failure locks the account down rather than leaving a
 * half-deleted account signed in. The hard purge runs after the 30-day
 * retention window (scheduled account-purgeDeleted).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { adminAuth, db } from '../firebase';
import { parseDeleteAccountInput } from './deletion-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 60,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface DeleteAccountResponse {
  requestId: string;
  status: 'pending';
}

export const deleteAccount = onCall(
  CALLABLE_OPTS,
  async (request): Promise<DeleteAccountResponse> => {
    const uid = request.auth?.uid;
    if (!uid) {
      throw new HttpsError('unauthenticated', 'Sign in to continue.');
    }

    const parsed = parseDeleteAccountInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    const requestRef = db.collection('accountDeletionRequests').doc(uid);
    const existing = await requestRef.get();
    if (existing.exists) {
      // Idempotent: the account is already on the deletion track.
      return { requestId: uid, status: 'pending' };
    }

    // Lock down FIRST (fail-safe ordering): no further sign-ins, and
    // existing sessions cannot silently renew.
    await adminAuth.updateUser(uid, { disabled: true });
    await adminAuth.revokeRefreshTokens(uid);

    const batch = db.batch();
    batch.set(requestRef, {
      userId: uid,
      reason: parsed.input.reason ?? null,
      status: 'pending',
      createdAt: FieldValue.serverTimestamp(),
    });
    batch.set(
      db.collection('users').doc(uid),
      { deleted: true, updatedAt: FieldValue.serverTimestamp() },
      { merge: true },
    );
    await batch.commit();

    return { requestId: uid, status: 'pending' };
  },
);
