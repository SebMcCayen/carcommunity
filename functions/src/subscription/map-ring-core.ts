import { effectiveSubscriptionTierFromStoredRecord } from './subscription-core';

/** Public cosmetics only. Never use this projection to authorize a paid feature. */
export interface MembershipMapRingProjection {
  membershipMapRingEligible: boolean;
  /** A display deadline, not a receipt or renewal setting. Null is a manual perpetual grant. */
  membershipMapRingUntil: Date | null;
}

/**
 * Plus and Supporter use the same ring. Cancellation keeps it for the paid
 * period. Unlike the entitlement outage backstop, the cosmetic deadline does
 * not add 72 hours to that period. Clients can hide it at this deadline even
 * while a reconciliation worker is delayed. Visibility is a separate owner
 * preference and must never be initialized/reset by a renewal or backfill.
 */
export function membershipMapRingProjection(
  record: unknown,
  now: Date,
  expectedUserId?: string,
): MembershipMapRingProjection {
  const hidden: MembershipMapRingProjection = {
    membershipMapRingEligible: false,
    membershipMapRingUntil: null,
  };
  if (!Number.isFinite(now.getTime())) return hidden;
  if (effectiveSubscriptionTierFromStoredRecord(record, expectedUserId) === 'community') {
    return hidden;
  }
  const data = record as Record<string, unknown>;
  if (data.expiresAt == null) {
    return data.platform === 'manual'
      ? { membershipMapRingEligible: true, membershipMapRingUntil: null }
      : hidden;
  }
  const expiry = data.expiresAt;
  if (!(expiry instanceof Date) || !Number.isFinite(expiry.getTime()) || expiry <= now) {
    return hidden;
  }
  return { membershipMapRingEligible: true, membershipMapRingUntil: expiry };
}
