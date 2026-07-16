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
  canAccessAdminFeatures,
  canAccessMemberFeatures,
  hasBackendAccess,
  isRestricted,
  toUserAccessState,
  type UserAccessState,
} from './access';

export interface AuthenticatedActor {
  uid: string;
  state: UserAccessState;
}

/** An actor that is either an active member or an admin/owner. */
export interface MemberOrAdminActor extends AuthenticatedActor {
  /** True when the caller holds the admin or owner role. */
  isAdmin: boolean;
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

/**
 * Asserts an active member OR an admin/owner (hasBackendAccess: admins never
 * need a subscription; suspension/deletion closes both doors). Use for
 * callables a member may drive but where an admin caller takes a different
 * path — the returned `isAdmin` says which, decided from the backend-managed
 * users/{uid} role, never from a client-supplied claim.
 */
export async function requireMemberOrAdminActor(
  request: CallableRequest,
): Promise<MemberOrAdminActor> {
  const actor = await requireActiveActor(request);
  if (!hasBackendAccess(actor.state)) {
    throw new HttpsError('permission-denied', 'Member subscription required.');
  }
  return { ...actor, isAdmin: canAccessAdminFeatures(actor.state) };
}
