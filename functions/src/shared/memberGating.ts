/**
 * THE MEMBER-GATING SWITCH (callable layer).
 *
 * Subscriber gating is currently DISABLED so every feature is testable by
 * signed-in accounts that do not hold the `activeMember` entitlement. Nothing
 * about the gating logic has been deleted — it is bypassed by a single
 * constant, and re-locking is a one-value change per layer.
 *
 * ===========================================================================
 * HOW TO RE-LOCK (FIVE separate switches — ALL must be flipped together)
 * ===========================================================================
 * This file is the authoritative re-locking runbook. Neither rules files nor
 * Kotlin can read this TypeScript constant, so each enforcement layer carries
 * its own switch. They are NOT kept in sync automatically.
 *
 * FOUR BACKEND switches (1-4) + ONE ANDROID UI switch (5). Flip all five.
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
 *   4. firebase/database.rules.json — liveLocation/$uid/latest ".read".
 *      NOTE: that file is strict JSON and carries NO explanatory comment (it
 *      is the repo's only rules file that cannot), so this entry is the only
 *      documentation of the edit — keep it accurate.
 *      Restore the entitlement term at the START of the non-owner branch:
 *        "auth != null && (auth.uid == $uid || (auth.token.suspended != true
 *          && ...liveLocationBlocks checks...))"
 *        ->
 *        "auth != null && (auth.uid == $uid || (auth.token.activeMember == true
 *          && auth.token.suspended != true && ...liveLocationBlocks checks...))"
 *      The suspension term and BOTH liveLocationBlocks direction checks are
 *      already present and MUST stay — that single expression bundles all
 *      three guards, and only the entitlement term was removed.
 *
 *   5. apps/android/app/src/main/java/com/kungsbackacarcommunity/app/config/
 *      MemberGating.kt:
 *        const val ENABLED = false   ->   true
 *      ...then ship a new Android build. THIS ONE IS EASY TO FORGET AND
 *      FORGETTING IT IS NOT COSMETIC: the backend would refuse actions the UI
 *      still offers, which is precisely the bug class this PR was built to
 *      fix (a non-member could record a drive they could never save). If you
 *      cannot ship the app and backend together, re-lock the UI FIRST — an
 *      app that hides a working feature is recoverable; an app that offers a
 *      failing one is not.
 *
 * Deploy after flipping — the switches are inert until then:
 *   firebase deploy --only functions,firestore:rules,storage,database
 * ...plus a new Android release for (5).
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
 * Also untouched: ownership checks, rate limits, the per-user vehicle cap
 * (MAX_VEHICLES_PER_USER), admin-role gates (requireAdminActor), and App Check.
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
 * Set back to `true` to re-lock the callable layer — and flip the OTHER FOUR
 * switches listed in this module's docs (three rules files + the Android UI
 * switch in config/MemberGating.kt). They are separate and are not kept in
 * sync automatically.
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
