/**
 * Shared authorization context for admin-domain callables.
 *
 * Two-layer check, both required:
 * 1. The `admin` custom claim on the verified ID token (cheap, free read) —
 *    set exclusively by admin.setAdminRole via the Admin SDK.
 * 2. The authoritative Firestore `users/{uid}` document — backend source of
 *    truth. Catches stale claims (e.g. an admin suspended less than an hour
 *    ago whose token has not refreshed yet).
 *
 * Never trusts client-supplied role/status values.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { guardActorIsActiveAdmin } from './claims-core';
import { toUserAccessState, type UserAccessState } from '../shared/access';

export interface AdminActor {
  uid: string;
  state: UserAccessState;
}

/**
 * Asserts the caller is a signed-in, non-suspended, non-deleted admin or
 * owner. Throws HttpsError with codes from contracts/errors/errors.json.
 */
export async function requireAdminActor(request: CallableRequest): Promise<AdminActor> {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'Sign in to perform admin operations.');
  }

  // Fast reject for callers without the server-managed admin claim. A
  // suspended claim also rejects immediately — suspension overrides all
  // feature access, including admin access.
  if (auth.token.admin !== true || auth.token.suspended === true) {
    throw new HttpsError('permission-denied', 'Admin privileges are required for this operation.');
  }

  const actorSnap = await db.collection('users').doc(auth.uid).get();
  const state = toUserAccessState(actorSnap.data());
  const guard = guardActorIsActiveAdmin(state);
  if (!guard.ok) {
    throw new HttpsError(guard.code, guard.message);
  }

  return { uid: auth.uid, state };
}
