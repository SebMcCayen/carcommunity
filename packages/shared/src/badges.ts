/**
 * Shared contracts for the badge system (Utmärkelser).
 *
 * Design rules encoded here:
 *  - No rankings, points, or competitive fields.
 *  - No speed, distance, or unsafe-driving data.
 *  - No other users' information.
 *  - No internal eligibility logic or rule details exposed.
 *  - No provider identity, session data, or private moderation data.
 *  - Badge awards are private to the user (no public profile in this step).
 *  - Backend remains the authoritative source for all badge eligibility.
 *  - Clients must never award badges directly.
 *
 * Future preparation:
 *  - TODO: Add public badge profile once privacy policy allows.
 *  - TODO: Add progress/progress-hint fields per badge once designed safely.
 */

// ---------------------------------------------------------------------------
// Badge keys
// ---------------------------------------------------------------------------

const BADGE_KEYS = [
  'first_event',
  'five_events',
  'helpful_member',
  'early_member',
  'garage_created',
] as const;
export type BadgeKey = (typeof BADGE_KEYS)[number];

// ---------------------------------------------------------------------------
// Catalog summary (static, returned alongside awarded badges)
// ---------------------------------------------------------------------------

/**
 * Static definition of a badge as seen by the client.
 * Does not expose internal eligibility rules or rule implementation details.
 */
interface BadgeSummary {
  key: BadgeKey;
  /** Swedish display name. */
  name: string;
  /** Swedish description of how the badge is earned. */
  description: string;
  /** Identifier referencing the badge icon in the client icon set. */
  iconIdentifier: string;
  /** True if badge is awarded automatically by the backend; false if admin-only. */
  isAutomatic: boolean;
}

/**
 * The `helpful_member` badge definition — the frontend source of truth
 * (mirrors the backend catalog in functions/src/badges/badge-core.ts). It is
 * the only manually awardable badge; the admin award callable returns just
 * `{ alreadyAwarded }` (badges are owner-only, so no award document is read
 * back), so frontends shape the award response from this constant.
 */
export const HELPFUL_MEMBER_BADGE: BadgeSummary = {
  key: 'helpful_member',
  name: 'Hjälpsam medlem',
  description: 'Har bidragit positivt och hjälpsamt i communityn.',
  iconIdentifier: 'badge_helpful_member',
  isAutomatic: false,
};

// ---------------------------------------------------------------------------
// Awarded badge (returned to the user)
// ---------------------------------------------------------------------------

/**
 * A badge that has been awarded to the current user.
 * Excludes other users' data, session data, and internal eligibility logic.
 */
interface AwardedBadge {
  key: BadgeKey;
  /** Swedish display name. */
  name: string;
  /** Swedish description. */
  description: string;
  /** Identifier referencing the badge icon in the client icon set. */
  iconIdentifier: string;
  /** ISO 8601 timestamp when the badge was awarded. */
  awardedAt: string;
}

// ---------------------------------------------------------------------------
// Admin badge aggregate summary
// ---------------------------------------------------------------------------

/**
 * Aggregate badge statistics for admin use.
 * Does not expose a leaderboard or individual user data.
 */
export interface AdminBadgeAggregateItem {
  key: BadgeKey;
  /** Swedish display name. */
  name: string;
  /** Total number of times this badge has been awarded across all users. */
  totalCount: number;
  /** Number of awards in the last 30 days. */
  recentCount: number;
}

export interface AdminBadgeSummaryResponse {
  ok: true;
  data: {
    summary: AdminBadgeAggregateItem[];
  };
}

// ---------------------------------------------------------------------------
// Admin: manual helpful-member award
// ---------------------------------------------------------------------------

/**
 * Request body for admin awarding the helpful_member badge.
 * Reason is mandatory and will be written to the audit log.
 * Only the helpful_member badge is allowed through this endpoint.
 */
export interface AwardHelpfulMemberRequest {
  /** Mandatory reason, recorded in the audit log. */
  reason: string;
}

export interface AwardHelpfulMemberResponse {
  ok: true;
  data: {
    badge: AwardedBadge;
    /** True when the badge was already awarded before this request. */
    alreadyAwarded: boolean;
  };
}
