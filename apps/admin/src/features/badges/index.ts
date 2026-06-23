/**
 * Badges feature module for the admin portal.
 *
 * Provides shared types, helpers, and API client functions for the
 * badge admin area. Pages in src/app/badges/ import from here.
 *
 * Security notes:
 *  - All admin operations are validated server-side. Client-side role checks
 *    are UX only and are NOT security boundaries.
 *  - Only `helpful_member` may be awarded manually. Arbitrary badge keys are
 *    rejected by the backend.
 *  - Award operations are idempotent — a second award for the same badge
 *    returns the existing record.
 *  - Audit records for manual awards are written by the backend.
 *  - No rankings, leaderboards, or user comparisons are exposed.
 *  - Do not include personal data beyond the userId in requests.
 */

import {
  BADGE_ROUTE_PATHS,
  buildAdminAwardHelpfulMemberPath,
  type AdminBadgeSummaryResponse,
  type AwardHelpfulMemberRequest,
  type AwardHelpfulMemberResponse,
} from '@carcommunity/shared/badges';

import { ApiError, apiRequest } from '../../lib/api';

export type { AdminBadgeSummaryResponse, AwardHelpfulMemberRequest, AwardHelpfulMemberResponse };
export { ApiError };

// ---------------------------------------------------------------------------
// API client helpers
// ---------------------------------------------------------------------------

/**
 * Loads aggregate badge statistics for the admin summary view.
 * Returns award counts per badge key. No individual user data.
 */
export async function loadAdminBadgeSummary(token?: string): Promise<AdminBadgeSummaryResponse> {
  return apiRequest<AdminBadgeSummaryResponse>(BADGE_ROUTE_PATHS.adminBadgeSummary, { token });
}

/**
 * Manually awards the `helpful_member` badge to a user.
 * Requires a reason. Action is audited by the backend.
 * Returns the existing award idempotently if already awarded.
 */
export async function awardHelpfulMemberBadge(
  userId: string,
  request: AwardHelpfulMemberRequest,
  token?: string,
): Promise<AwardHelpfulMemberResponse> {
  return apiRequest<AwardHelpfulMemberResponse>(buildAdminAwardHelpfulMemberPath(userId), {
    method: 'POST',
    body: request,
    token,
  });
}
