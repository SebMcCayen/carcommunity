import { effectiveSubscriptionTierFromStoredRecord } from './subscription-core';
import { subscriptionRevocationDeadline } from './expiry-core';

/** Cosmetic public projection only; never an access-control entitlement. */
export function supporterBadgeEligible(record: unknown, now: Date): boolean {
  if (effectiveSubscriptionTierFromStoredRecord(record) !== 'supporter') return false;
  const data = record as Record<string, unknown>;
  if (data.expiresAt == null) return data.platform === 'manual';
  const expiry = data.expiresAt;
  if (!(expiry instanceof Date) || !Number.isFinite(expiry.getTime())) return false;
  return (
    subscriptionRevocationDeadline(
      data.status as 'active' | 'grace_period' | 'cancelled',
      expiry,
    ).getTime() > now.getTime()
  );
}
