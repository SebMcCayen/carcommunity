import { effectiveSubscriptionTierFromStoredRecord } from './subscription-core';

export const MAX_MAP_RING_REFRESHES = 200;

export interface MapRingRefreshDeps {
  queryDue: (now: Date, limit: number) => Promise<string[]>;
  refresh: (uid: string) => Promise<void>;
}

/** Cosmetic-only bounded sweep. Failed records retain their due field for retry. */
export async function runMapRingRefresh(deps: MapRingRefreshDeps, now: Date): Promise<number> {
  const due = (await deps.queryDue(now, MAX_MAP_RING_REFRESHES)).slice(0, MAX_MAP_RING_REFRESHES);
  let failed = 0;
  for (const uid of due) {
    try { await deps.refresh(uid); } catch { failed++; }
  }
  if (failed > 0) throw new Error(`Map-ring refresh: ${failed} of ${due.length} cosmetic repairs failed`);
  return due.length;
}

/** Public cosmetics only. Never use this projection to authorize a paid feature. */
export interface MembershipMapRingProjection {
  membershipMapRingEligible: boolean;
  /** Private scheduling metadata. Never copy this date to a public profile. */
  refreshAt: Date | null;
}

/**
 * Plus and Supporter use the same ring. Cancellation keeps it for the paid
 * period. Unlike the entitlement outage backstop, the cosmetic deadline does
 * not add 72 hours to that period. The server clears the public boolean at expiry;
 * only this scheduling metadata retains the private deadline. Visibility is a separate owner
 * preference and must never be initialized/reset by a renewal or backfill.
 */
export function membershipMapRingProjection(
  record: unknown,
  now: Date,
  expectedUserId?: string,
): MembershipMapRingProjection {
  const hidden: MembershipMapRingProjection = {
    membershipMapRingEligible: false,
    refreshAt: null,
  };
  if (!Number.isFinite(now.getTime())) return hidden;
  if (effectiveSubscriptionTierFromStoredRecord(record, expectedUserId) === 'community') {
    return hidden;
  }
  const data = record as Record<string, unknown>;
  if (data.expiresAt == null) {
    return data.platform === 'manual'
      ? { membershipMapRingEligible: true, refreshAt: null }
      : hidden;
  }
  const expiry = data.expiresAt;
  if (!(expiry instanceof Date) || !Number.isFinite(expiry.getTime()) || expiry <= now) {
    return hidden;
  }
  return { membershipMapRingEligible: true, refreshAt: expiry };
}
