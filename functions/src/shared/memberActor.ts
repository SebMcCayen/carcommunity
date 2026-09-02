/**
 * Shared authorization context for member-gated callables (Phase 9d).
 *
 * Loads the caller's backend-managed users/{uid} access state and applies the
 * member gate (suspension always overrides entitlement, deleted accounts have
 * no access). Backend state is the source of truth — client-supplied claims
 * are never trusted for the decision.
 *
 * Member gating is currently DISABLED via shared/memberGating.ts, so the
 * "member" assertions below currently assert only signed-in + not suspended +
 * not deleted. Suspension and deletion still close every door. See
 * shared/memberGating.ts for the switch and the re-locking procedure.
 */

import { HttpsError } from 'firebase-functions/v2/https';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import {
  canAccessAdminFeatures,
  isRestricted,
  toUserAccessState,
  type UserAccessState,
} from './access';
import { backendGateAllows, memberGateAllows } from './memberGating';
import {
  effectiveSubscriptionTierFromStoredRecord,
  isPaidSubscriptionTier,
} from '../subscription/subscription-core';

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

/**
 * Asserts an active member (requireActiveActor + activeMember entitlement).
 *
 * NOTE: member gating is currently DISABLED (see shared/memberGating.ts), so
 * this presently asserts only requireActiveActor semantics — signed in,
 * not suspended, not deleted. The entitlement check returns when
 * MEMBER_GATING_ENABLED is flipped back to true; call sites need no change.
 */
export async function requireMemberActor(request: CallableRequest): Promise<AuthenticatedActor> {
  const actor = await requireActiveActor(request);
  if (!memberGateAllows(actor.state)) {
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
 *
 * NOTE: member gating is currently DISABLED (see shared/memberGating.ts), so
 * the entitlement half of that assertion is bypassed and every signed-in,
 * non-suspended, non-deleted caller passes. `isAdmin` is unaffected — it is
 * still decided from the backend role, so admin-only branches stay admin-only.
 */
export async function requireMemberOrAdminActor(
  request: CallableRequest,
): Promise<MemberOrAdminActor> {
  const actor = await requireActiveActor(request);
  if (!backendGateAllows(actor.state)) {
    throw new HttpsError('permission-denied', 'Member subscription required.');
  }
  return { ...actor, isAdmin: canAccessAdminFeatures(actor.state) };
}

/**
 * Asserts a caller entitled to MEMBER partner offers: an active PAID subscriber
 * (Plus OR Supporter) OR an admin/owner. Suspended and deleted accounts are
 * always denied (requireActiveActor closes those doors first).
 *
 * Unlike requireMemberActor, this gate is INDEPENDENT of the global
 * MEMBER_GATING switch (shared/memberGating.ts). Partner member offers are a
 * paid product: entitlement is read directly from the authoritative
 * subscriptions/{uid} record via effectiveSubscriptionTierFromStoredRecord, so
 * a free (Community) caller is refused member offers even while the global
 * member gate is relaxed for testing. The subscriptions record is written by
 * Cloud Functions only after verification; a missing/malformed record fails
 * closed to Community.
 */
export async function requirePaidOrAdminActor(
  request: CallableRequest,
): Promise<MemberOrAdminActor> {
  const actor = await requireActiveActor(request);
  const isAdmin = canAccessAdminFeatures(actor.state);
  if (isAdmin) {
    return { ...actor, isAdmin };
  }
  const subscriptionSnap = await db.collection('subscriptions').doc(actor.uid).get();
  const tier = effectiveSubscriptionTierFromStoredRecord(subscriptionSnap.data(), actor.uid);
  if (!isPaidSubscriptionTier(tier)) {
    throw new HttpsError('permission-denied', 'A paid subscription is required for member offers.');
  }
  return { ...actor, isAdmin };
}
