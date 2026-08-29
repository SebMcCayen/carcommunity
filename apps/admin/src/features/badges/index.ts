/**
 * Badges feature module for the admin portal (Phase 13g — Firebase migration).
 *
 * Migrated from the legacy `apiRequest` REST client to the Firebase callable
 * client (`callAdmin`). The page-facing function signatures and response
 * envelopes are unchanged, so `src/app/badges/` keeps working as-is.
 *
 * Security notes:
 *  - All admin operations are validated server-side (the callable independently
 *    verifies the `admin` custom claim). Client-side role checks are UX only.
 *  - Two badges are granted manually, each through its own dedicated callable:
 *    `helpful_member` (one target + required reason, via awardHelpfulMemberBadge)
 *    and `early_tester` / "Grundare" (a hand-picked UID list, via
 *    grantEarlyTesterBadge). Neither callable accepts an arbitrary badge key —
 *    each is fixed to its own badge server-side.
 *  - Grant/award operations are idempotent — a repeat for the same badge is a
 *    no-op (`alreadyAwarded` / `alreadyGranted`) with no duplicate record or audit.
 *  - Audit records for manual awards are written by the backend.
 *  - The summary exposes aggregate counts only (no rankings, no user lists).
 */

import {
  HELPFUL_MEMBER_BADGE,
  type AdminBadgeAggregateItem,
  type AdminBadgeSummaryResponse,
  type AwardHelpfulMemberRequest,
  type AwardHelpfulMemberResponse,
  type BadgeKey,
  type GrantEarlyTesterRequest,
  type GrantEarlyTesterResponse,
  type GrantEarlyTesterResultItem,
} from '@carcommunity/shared/badges';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';

export type {
  AdminBadgeSummaryResponse,
  AwardHelpfulMemberRequest,
  AwardHelpfulMemberResponse,
  GrantEarlyTesterRequest,
  GrantEarlyTesterResponse,
};
export { ApiError };

// ---------------------------------------------------------------------------
// Callable-backed data layer
// ---------------------------------------------------------------------------

/**
 * Loads aggregate badge statistics for the admin summary view via the
 * `badges-adminSummary` callable (an Admin-SDK collectionGroup aggregate —
 * badges stay owner-only for clients). Returns award counts per badge key.
 *
 * The callable returns the raw `{ summary }` payload; it is wrapped in the REST
 * envelope the page already consumes. The legacy `token` argument is retained
 * for signature parity but is unused (the callable carries the ID token).
 */
export async function loadAdminBadgeSummary(_token?: string): Promise<AdminBadgeSummaryResponse> {
  const result = await callAdmin<{ summary: AdminBadgeAggregateItem[] }>('badges-adminSummary', {});
  return { ok: true, data: { summary: result.summary } };
}

/**
 * Manually awards the `helpful_member` badge to a user via the
 * `badges-awardHelpfulMember` callable. Requires a reason (audited by the
 * backend). Idempotent: a repeat award returns `alreadyAwarded: true`.
 *
 * The callable returns only `{ alreadyAwarded }` (never the award document,
 * which is owner-only). The award definition comes from the shared
 * `HELPFUL_MEMBER_BADGE` constant (no local mirror of backend values). The
 * `awardedAt` timestamp is only meaningful for a FRESH award, where client-now
 * approximates the just-created server timestamp; for an idempotent repeat we
 * do NOT fabricate a "new award" time — it is left empty (the original award
 * time is not returned by the callable and badges are owner-only).
 */
export async function awardHelpfulMemberBadge(
  userId: string,
  request: AwardHelpfulMemberRequest,
  _token?: string,
): Promise<AwardHelpfulMemberResponse> {
  const result = await callAdmin<{
    targetUid: string;
    badgeKey: BadgeKey;
    alreadyAwarded: boolean;
  }>('badges-awardHelpfulMember', { targetUid: userId, reason: request.reason });

  return {
    ok: true,
    data: {
      badge: {
        key: HELPFUL_MEMBER_BADGE.key,
        name: HELPFUL_MEMBER_BADGE.name,
        description: HELPFUL_MEMBER_BADGE.description,
        iconIdentifier: HELPFUL_MEMBER_BADGE.iconIdentifier,
        awardedAt: result.alreadyAwarded ? '' : new Date().toISOString(),
      },
      alreadyAwarded: result.alreadyAwarded,
    },
  };
}

/**
 * Grants the exclusive `early_tester` ("Grundare") badge to a hand-picked list
 * of UIDs via the `badges-grantEarlyTester` callable. There are NO earning
 * criteria — an admin grants it on demand to the app's earliest testers. The
 * reason is optional (defaulted server-side) and audited. Idempotent per UID; a
 * missing/suspended/deleted target is skipped rather than failing the batch. The
 * callable returns per-UID status plus aggregate counts.
 */
export async function grantEarlyTesterBadge(
  request: GrantEarlyTesterRequest,
): Promise<GrantEarlyTesterResponse> {
  const payload: { uids: string[]; reason?: string } = { uids: request.uids };
  if (request.reason && request.reason.trim()) {
    payload.reason = request.reason.trim();
  }
  const result = await callAdmin<{
    badgeKey: 'early_tester';
    results: GrantEarlyTesterResultItem[];
    grantedCount: number;
    alreadyGrantedCount: number;
    skippedCount: number;
  }>('badges-grantEarlyTester', payload);

  return {
    ok: true,
    data: {
      badgeKey: result.badgeKey,
      results: result.results,
      grantedCount: result.grantedCount,
      alreadyGrantedCount: result.alreadyGrantedCount,
      skippedCount: result.skippedCount,
    },
  };
}
