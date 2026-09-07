/**
 * Legacy compatibility gates. These are NOT the current subscription policy.
 *
 * Approved 2026-09-05: details/RSVP, social features, convoys, drive saving,
 * incidents and driving statistics are free for unrestricted signed-in users.
 * Their call sites use explicit active-account checks. Event check-in, attendee
 * identities and member partner offers use independent verified-paid guards.
 * Crown Hunt participation is free with a separate tiered daily KP allowance.
 *
 * Do NOT enable MEMBER_GATING_ENABLED or blanket-relock Firestore, Storage,
 * RTDB and Android. That would contradict the approved feature policy and break
 * compatibility with existing clients. Other-user live-map access and saved
 * route replay retain their existing behavior in this release.
 *
 * These helpers remain for legacy/out-of-scope callers. Restriction checks,
 * ownership, blocking, admin authorization, App Check and rate limits must never
 * be bypassed when removing a subscription condition. See
 * docs/play/subscription-access-rollout.md for review and deployment checks.
 */

import {
  canAccessMemberFeatures,
  hasBackendAccess,
  isRestricted,
  type UserAccessState,
} from './access';

/**
 * NARROW Kronjakt-only member gate — deliberately NOT wired to
 * [MEMBER_GATING_ENABLED].
 *
 * The Kronjakt paywall (`crownHuntRequirePaid`, contract default OFF) is a
 * single feature going paid ahead of the rest of the app, so it cannot ride the
 * global switch: flipping [MEMBER_GATING_ENABLED] re-locks EVERY feature at
 * once, which is exactly what the paywall rollout must avoid. Callers gate the
 * CHOICE of gate on the flag themselves — while the flag is off they keep using
 * [memberGateAllows] (today's relaxed behaviour), and only while it is on do
 * they switch to this — so this function always applies the real entitlement
 * requirement regardless of the global switch.
 *
 * Suspended/deleted accounts are ALWAYS denied (isRestricted first, same as
 * every gate here); otherwise it requires the `activeMember` entitlement (any
 * active paid tier — Plus or Supporter both resolve to activeMember). Semantics
 * are exactly canAccessMemberFeatures(). Admins are NOT specially admitted here:
 * an admin without the entitlement is treated like any other free member for
 * COLLECTION, matching what [memberGateAllows] does when the global switch is on
 * (the crownSpawns READ rule keeps its own `|| isAdmin()` so an admin can still
 * see and moderate crowns).
 */
export function crownHuntGateAllows(state: UserAccessState): boolean {
  if (isRestricted(state)) {
    return false;
  }
  return canAccessMemberFeatures(state);
}

/**
 * The single switch for every member-gated callable. `false` = all features
 * unlocked for any signed-in, non-suspended, non-deleted account.
 *
 * Kept false for legacy compatibility. Do not enable this broad gate to roll
 * out narrow paid capabilities; each capability has its own explicit guard.
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
