/**
 * Shared authorization context for member-gated callables (Phase 9d).
 *
 * Loads the caller's backend-managed users/{uid} access state and applies
 * canAccessMemberFeatures (suspension always overrides entitlement, deleted
 * accounts have no access). Backend state is the source of truth —
 * client-supplied claims are never trusted for the decision.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import {
  canAccessMemberFeatures,
  isRestricted,
  toUserAccessState,
  type UserAccessState,
} from './access';

export interface AuthenticatedActor {
  uid: string;
  state: UserAccessState;
}

/** Asserts a signed-in, non-suspended, non-deleted caller. */
export async function requireActiveActor(request: CallableRequest): Promise<AuthenticatedActor> {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'Sign in to continue.');
  }
  const snap = await db.collection('users').doc(auth.uid).get();
  const state = toUserAccessState(snap.data());
  if (isRestricted(state)) {
    throw new HttpsError('permission-denied', 'Account access is restricted.');
  }
  return { uid: auth.uid, state };
}

/** Asserts an active member (requireActiveActor + activeMember entitlement). */
export async function requireMemberActor(request: CallableRequest): Promise<AuthenticatedActor> {
  const actor = await requireActiveActor(request);
  if (!canAccessMemberFeatures(actor.state)) {
    throw new HttpsError('permission-denied', 'Member subscription required.');
  }
  return actor;
}
