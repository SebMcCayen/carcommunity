/**
 * Pure subscription policy for the saved-drive history read API.
 *
 * Community sees the five newest drives, Plus sees a rolling 90-day window,
 * and Supporter can page through the complete history. The policy controls
 * visibility only: old drives are never deleted and remain deletable through
 * drives.delete after a downgrade.
 */

import { z } from 'zod';
import type { SubscriptionTier } from '../subscription/subscription-core';

export const COMMUNITY_DRIVE_HISTORY_LIMIT = 5;
export const PLUS_DRIVE_HISTORY_DAYS = 90;
export const DRIVE_HISTORY_PAGE_SIZE_DEFAULT = 25;
export const DRIVE_HISTORY_PAGE_SIZE_MAX = 25;
export const DAY_MS = 24 * 60 * 60 * 1000;

const listDriveHistoryInputSchema = z
  .object({
    cursorRideId: z.string().trim().min(1).max(300).optional(),
    pageSize: z.number().int().min(1).max(DRIVE_HISTORY_PAGE_SIZE_MAX).optional(),
  })
  .strict();

export type ListDriveHistoryInput = z.infer<typeof listDriveHistoryInputSchema>;

export type DriveHistoryPolicy =
  | { kind: 'latest_count'; limit: typeof COMMUNITY_DRIVE_HISTORY_LIMIT }
  | { kind: 'rolling_days'; days: typeof PLUS_DRIVE_HISTORY_DAYS; cutoffMillis: number }
  | { kind: 'unlimited' };

export type ParseListDriveHistoryResult =
  { ok: true; input: ListDriveHistoryInput } | { ok: false; message: string };

export function parseListDriveHistoryInput(data: unknown): ParseListDriveHistoryResult {
  const parsed = listDriveHistoryInputSchema.safeParse(data ?? {});
  if (!parsed.success) {
    return {
      ok: false,
      message: `Expected { cursorRideId?: string, pageSize?: integer 1-${DRIVE_HISTORY_PAGE_SIZE_MAX} }.`,
    };
  }
  return { ok: true, input: parsed.data };
}

export function driveHistoryPolicyForTier(
  tier: SubscriptionTier,
  nowMillis: number,
): DriveHistoryPolicy {
  if (tier === 'community') {
    return { kind: 'latest_count', limit: COMMUNITY_DRIVE_HISTORY_LIMIT };
  }
  if (tier === 'plus') {
    return {
      kind: 'rolling_days',
      days: PLUS_DRIVE_HISTORY_DAYS,
      cutoffMillis: nowMillis - PLUS_DRIVE_HISTORY_DAYS * DAY_MS,
    };
  }
  return { kind: 'unlimited' };
}

export function driveHistoryPageSize(policy: DriveHistoryPolicy, requested?: number): number {
  if (policy.kind === 'latest_count') return policy.limit;
  return requested ?? DRIVE_HISTORY_PAGE_SIZE_DEFAULT;
}

/**
 * Paid tiers read one look-ahead document to determine whether another page
 * exists. Community cannot page, so reading beyond its five visible drives
 * would only add Firestore cost.
 */
export function driveHistoryReadLimit(policy: DriveHistoryPolicy, pageSize: number): number {
  return policy.kind === 'latest_count' ? pageSize : pageSize + 1;
}
