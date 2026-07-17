/**
 * THE MEMBER-GATING SWITCH (callable layer).
 *
 * Subscriber gating is currently DISABLED so every feature is testable by
 * signed-in accounts that do not hold the `activeMember` entitlement. Nothing
 * about the gating logic has been deleted — it is bypassed by a single
 * constant, and re-locking is a one-value change per layer.
 *
 * ===========================================================================
 * HOW TO RE-LOCK (four separate switches — ALL must be flipped together)
 * ===========================================================================
 * Rules files cannot read this TypeScript constant, so each enforcement layer
 * carries its own switch. They are NOT kept in sync automatically. Flip all
 * four, then redeploy functions AND rules (see docs below).
 *
 *   1. Callables (this file):
 *        MEMBER_GATING_ENABLED = false   ->   true
 *
 *   2. firebase/firestore.rules — restore the entitlement term in
 *      isActiveMember():
 *        return isAuthenticated() && isNotSuspended();
 *        ->
 *        return isAuthenticated() && request.auth.token.activeMember == true
 *          && isNotSuspended();
 *
 *   3. firebase/storage.rules — the identical isActiveMember() edit as (2).
 *
 *   4. firebase/database.rules.json — liveLocation/$uid/latest ".read":
 *      restore the `auth.token.activeMember == true &&` term (the suspension
 *      and liveLocationBlocks terms are already there and must stay).
 *
 * Deploy after flipping — the switches are inert until then:
 *   firebase deploy --only functions,firestore:rules,storage,database
 *
 * ===========================================================================
 * WHAT IS *NOT* BYPASSED (safety guards that always apply)
 * ===========================================================================
 * Only the subscription entitlement is bypassed. Suspended and soft-deleted
 * accounts remain fully blocked: every helper below re-checks isRestricted()
 * FIRST and returns false regardless of the switch.
 *
 * This re-check is deliberate and load-bearing. The suspension guard used to
 * ride along inside canAccessMemberFeatures(); PR #428 ungated the garage and
 * silently dropped suspension protection with it, because dropping the member
 * check also dropped the bundled suspension check. Encoding isRestricted()
 * separately here means the switch cannot take suspension down with it.
 *
 * Also untouched: ownership checks, rate limits, the 5-car cap, admin-role
 * gates (requireAdminActor), and App Check.
 *
 * ===========================================================================
 * TRAP FOR FUTURE-YOU: canShareLive / drives.save must be re-locked TOGETHER
 * ===========================================================================
 * Live recording is gated on canShareLive (never member-gated) while
 * drives.save was member-gated. That let a user record a drive and then be
 * refused when saving it — an unrecoverable prompt with no way to keep the
 * recording. Ungating removes the asymmetry; re-locking MUST re-align both
 * gates (gate the *entry* to recording, not just the save) or the bug returns.
 */

import {
  canAccessMemberFeatures,
  hasBackendAccess,
  isRestricted,
  type UserAccessState,
} from './access';

/**
 * The single switch for every member-gated callable. `false` = all features
 * unlocked for any signed-in, non-suspended, non-deleted account.
 *
 * Set back to `true` to re-lock the callable layer (and flip the three rules
 * switches listed in this module's docs — they are separate).
 */
export const MEMBER_GATING_ENABLED = false;

/**
 * Member-feature gate. Suspended/deleted accounts are ALWAYS denied; the
 * entitlement requirement applies only while MEMBER_GATING_ENABLED is true.
 *
 * Semantics when gating is enabled are exactly canAccessMemberFeatures().
 */
export function memberGateAllows(state: UserAccessState): boolean {
  if (isRestricted(state)) {
    return false;
  }
  return !MEMBER_GATING_ENABLED || canAccessMemberFeatures(state);
}

/**
 * Member-or-admin gate. Suspended/deleted accounts are ALWAYS denied; the
 * "admin bypasses the subscription" rule applies only while
 * MEMBER_GATING_ENABLED is true (with gating off, everyone passes anyway).
 *
 * Semantics when gating is enabled are exactly hasBackendAccess().
 */
export function backendGateAllows(state: UserAccessState): boolean {
  if (isRestricted(state)) {
    return false;
  }
  return !MEMBER_GATING_ENABLED || hasBackendAccess(state);
}
