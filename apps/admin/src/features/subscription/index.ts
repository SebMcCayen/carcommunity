/**
 * Admin subscription feature module (Phase 13k — Firebase migration).
 *
 * Backed by Firebase (no legacy REST route existed — this replaces the
 * placeholder):
 *  - Reads `subscriptions/{userId}` directly (admin-readable per firestore.rules)
 *    plus `users/{userId}` for the suspended flag → an AdminUserSubscriptionSummary.
 *  - subscription-grantEntitlement (callable) applies a manual grant/revoke:
 *    grant = member_monthly, revoke = none. The backend runs the full fail-safe
 *    entitlement chain (record + users.activeMember + claim; revoke also revokes
 *    refresh tokens) and writes an audit record with the mandatory reason.
 *
 * Security notes:
 *  - `subscriptions/{uid}` is written ONLY by Cloud Functions — the admin client
 *    never writes it; grant/revoke go through the callable.
 *  - Raw provider tokens are never stored or returned (only a hash, backend-side).
 *  - Store refunds/cancellations are provider-side (Apple/Google) and out of
 *    scope; this is the manual-entitlement operational path.
 */

import { doc, getDoc } from 'firebase/firestore';

import {
  isSubscriptionActiveStatus,
  SUBSCRIPTION_PLATFORMS,
  SUBSCRIPTION_STATUSES,
  type AdminUserSubscriptionSummary,
  type SubscriptionPlatform,
  type SubscriptionSourceSummary,
  type SubscriptionStatus,
} from '@carcommunity/shared/subscription';
import {
  SUBSCRIPTION_ENTITLEMENTS,
  type SubscriptionEntitlement,
} from '@carcommunity/shared/users';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';
import { getAdminFirestore } from '../../lib/firestore';

export type {
  AdminUserSubscriptionSummary,
  SubscriptionEntitlement,
  SubscriptionPlatform,
  SubscriptionSourceSummary,
  SubscriptionStatus,
};
export { ApiError, SUBSCRIPTION_ENTITLEMENTS };

interface GrantEntitlementResult {
  targetUid: string;
  entitlement: SubscriptionEntitlement;
}

/**
 * Normalizes a stored timestamp field to an ISO string. Permissive by design —
 * this module expects old/partial/hand-edited docs, so it accepts a Firestore
 * Timestamp (toDate()), a native Date, or an already-serialized date string,
 * and returns null only when the value is absent or unparseable.
 */
function toIso(value: unknown): string | null {
  if (value == null) return null;
  if (typeof (value as { toDate?: unknown }).toDate === 'function') {
    return (value as { toDate: () => Date }).toDate().toISOString();
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value.toISOString();
  }
  if (typeof value === 'string') {
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
  }
  return null;
}

/**
 * Coercion helpers. Firestore documents may be old, partial, or manually
 * edited, so each enum-typed field is validated against its known set before
 * use and falls back to a safe default when the stored value is unexpected.
 */
function coerceStatus(raw: unknown): SubscriptionStatus {
  return (SUBSCRIPTION_STATUSES as readonly string[]).includes(raw as string)
    ? (raw as SubscriptionStatus)
    : 'inactive';
}

function coercePlatform(raw: unknown): SubscriptionPlatform {
  return (SUBSCRIPTION_PLATFORMS as readonly string[]).includes(raw as string)
    ? (raw as SubscriptionPlatform)
    : 'manual';
}

function coerceEntitlement(raw: unknown): SubscriptionEntitlement {
  return (SUBSCRIPTION_ENTITLEMENTS as readonly string[]).includes(raw as string)
    ? (raw as SubscriptionEntitlement)
    : 'none';
}

/**
 * Reads a user's subscription entitlement summary. `subscriptions/{uid}` is
 * admin-readable; `users/{uid}` supplies the suspended flag so the "suspended
 * with an active subscription" warning can be surfaced. A missing subscription
 * document resolves to entitlement `none` with a null summary.
 */
export async function adminGetUserSubscription(
  userId: string,
): Promise<AdminUserSubscriptionSummary> {
  const db = getAdminFirestore();
  const [subSnap, userSnap] = await Promise.all([
    getDoc(doc(db, 'subscriptions', userId)),
    getDoc(doc(db, 'users', userId)),
  ]);
  const sub = subSnap.data() as Record<string, unknown> | undefined;
  const user = userSnap.data() as Record<string, unknown> | undefined;

  const subscription: SubscriptionSourceSummary | null = sub
    ? {
        platform: coercePlatform(sub.platform),
        status: coerceStatus(sub.status),
        entitlement: coerceEntitlement(sub.entitlement),
        startsAt: toIso(sub.startsAt),
        expiresAt: toIso(sub.expiresAt),
      }
    : null;

  const entitlement: SubscriptionEntitlement = subscription?.entitlement ?? 'none';
  const isSuspended = user?.suspended === true;
  const isSuspendedWithActiveSubscription =
    isSuspended && subscription != null && isSubscriptionActiveStatus(subscription.status);

  return { userId, entitlement, subscription, isSuspendedWithActiveSubscription };
}

/**
 * Grants the member_monthly entitlement to a user via
 * `subscription-grantEntitlement` (platform manual). Reason is mandatory and
 * audited server-side.
 */
export async function adminGrantMembership(
  targetUid: string,
  reason: string,
): Promise<GrantEntitlementResult> {
  return callAdmin<GrantEntitlementResult>('subscription-grantEntitlement', {
    targetUid,
    entitlement: 'member_monthly',
    reason,
  });
}

/**
 * Revokes a user's entitlement (sets it to none) via
 * `subscription-grantEntitlement`. The backend revokes refresh tokens as part
 * of the fail-safe privilege-decrease chain. Reason is mandatory and audited.
 */
export async function adminRevokeMembership(
  targetUid: string,
  reason: string,
): Promise<GrantEntitlementResult> {
  return callAdmin<GrantEntitlementResult>('subscription-grantEntitlement', {
    targetUid,
    entitlement: 'none',
    reason,
  });
}
