/**
 * Shared blocking contract for API, mobile, and admin.
 *
 * Backend is the source of truth for all block/visibility decisions.
 * Client-side checks are for user experience only — never for security enforcement.
 *
 * Privacy rules:
 * - Never reveal whether another user has blocked the current user.
 * - Blocked-user list returns only blocks created by the current user.
 * - Blocked user summaries include minimal safe fields only.
 */

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const BLOCKING_ROUTE_PATHS = {
  /**
   * POST   /v1/users/:userId/block  — block the target user
   * DELETE /v1/users/:userId/block  — unblock the target user
   */
  userBlock: (userId: string) => `/v1/users/${userId}/block`,
  myBlockedUsers: '/v1/users/me/blocked-users',
} as const;

// ---------------------------------------------------------------------------
// Pagination
// ---------------------------------------------------------------------------

export const DEFAULT_BLOCKED_USERS_PAGE_SIZE = 20;
export const MAX_BLOCKED_USERS_PAGE_SIZE = 50;

// ---------------------------------------------------------------------------
// Contracts
// ---------------------------------------------------------------------------

/**
 * Minimal safe summary of a blocked user.
 * Does not include email, provider identity, subscription, or sensitive metadata.
 */
export interface BlockedUserSummary {
  /** Opaque user identifier. Do not expose raw in UX text. */
  userId: string;
  /** Display name if available. May be null. */
  displayName?: string | null;
  /** ISO 8601 timestamp when the block was created by the current user. */
  blockedAt: string;
}

/** Response from POST /v1/users/:userId/block */
export interface BlockUserResponse {
  ok: true;
  data: {
    block: BlockedUserSummary;
    /**
     * Hint for the client to refresh its protected data (e.g. marker list).
     * Blocking takes effect immediately on the backend; the client should
     * clear or refresh marker data on the next polling cycle.
     */
    shouldRefreshMarkers: true;
  };
}

/** Response from DELETE /v1/users/:userId/block */
export interface UnblockUserResponse {
  ok: true;
  data: {
    unblocked: boolean;
  };
}

/** Response from GET /v1/users/me/blocked-users */
export interface BlockedUsersListResponse {
  ok: true;
  data: {
    /** Only users blocked by the current user. Never includes users who blocked the caller. */
    blockedUsers: BlockedUserSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

/**
 * Result of checking whether a blocking relationship exists between two users.
 * Intentionally does not indicate direction to preserve caller privacy.
 */
export interface BlockingRelationshipResult {
  /** True if either user has blocked the other. */
  isBlocked: boolean;
}

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/**
 * Returns true if either user has blocked the other, preventing visibility.
 *
 * A block in either direction prevents the target from being visible to the viewer.
 * This must be used together with backend enforcement; client-side use is UX only.
 *
 * @param viewerBlockedIds - IDs of users that the viewer has blocked
 * @param viewerBlockedByIds - IDs of users that have blocked the viewer
 * @param targetUserId - ID of the user to check visibility for
 */
export function isVisibilityBlocked(
  viewerBlockedIds: ReadonlySet<string>,
  viewerBlockedByIds: ReadonlySet<string>,
  targetUserId: string,
): boolean {
  return viewerBlockedIds.has(targetUserId) || viewerBlockedByIds.has(targetUserId);
}

/**
 * Returns true if two users can interact (neither has blocked the other).
 *
 * TODO: Enforce the same blocking relationship for:
 *   - event chat
 *   - group driving interactions
 *   - mentions
 *   - private interactions if ever introduced
 *   - partner/community interaction features where relevant
 *
 * @param viewerBlockedIds - IDs of users that the viewer has blocked
 * @param viewerBlockedByIds - IDs of users that have blocked the viewer
 * @param targetUserId - ID of the user to check interaction with
 */
export function canUsersInteract(
  viewerBlockedIds: ReadonlySet<string>,
  viewerBlockedByIds: ReadonlySet<string>,
  targetUserId: string,
): boolean {
  return !isVisibilityBlocked(viewerBlockedIds, viewerBlockedByIds, targetUserId);
}
