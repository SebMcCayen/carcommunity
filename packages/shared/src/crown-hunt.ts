/**
 * Shared contracts for the Kronjakt (Crown Hunt) feature.
 *
 * Design rules encoded here:
 *  - Backend is the sole authority for eligibility, claims, and Kronpoäng awards.
 *  - Mobile clients must never calculate or award Kronpoäng.
 *  - Claims are never automatic — the user must press a collect button.
 *  - Points may only be placed at safe, suitable stopping locations.
 *  - No first-to-arrive rewards, speed bonuses, or route history.
 *  - No public leaderboards or public claim locations.
 *  - Blocking does not affect Kronjakt point availability.
 *  - Internal fraud metadata is never exposed to mobile clients.
 *  - Coordinates logged per claim are minimal evidence only.
 *
 * Safety copy (always use Swedish in user-facing text):
 *  - "Stanna säkert innan du samlar in belöningen."
 *  - "Kronjakt kan endast användas när du står stilla eller rör dig mycket långsamt."
 *
 * Excluded from public mobile contracts:
 *  - Internal fraud metadata
 *  - Other users' claims
 *  - Admin identities
 *  - Raw audit metadata
 *  - Exact claim coordinates
 *  - Provider identities
 *  - Session tokens
 *  - Anti-fraud thresholds
 */

// ---------------------------------------------------------------------------
// Kronjakt point status
// ---------------------------------------------------------------------------

export const CROWN_HUNT_POINT_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type CrownHuntPointStatus = (typeof CROWN_HUNT_POINT_STATUSES)[number];

// ---------------------------------------------------------------------------
// Repeat rule
// ---------------------------------------------------------------------------

export const CROWN_HUNT_REPEAT_RULES = ['once', 'daily', 'weekly'] as const;
export type CrownHuntRepeatRule = (typeof CROWN_HUNT_REPEAT_RULES)[number];

// ---------------------------------------------------------------------------
// Claim result
// ---------------------------------------------------------------------------

export const CROWN_HUNT_CLAIM_RESULTS = [
  'awarded',
  'already_claimed',
  'outside_geofence',
  'moving_too_fast',
  'position_too_old',
  'point_inactive',
  'cooldown_active',
  'daily_limit_reached',
  'risk_review',
  'feature_disabled',
  'not_eligible',
] as const;
export type CrownHuntClaimResult = (typeof CROWN_HUNT_CLAIM_RESULTS)[number];

// ---------------------------------------------------------------------------
// Route paths
// ---------------------------------------------------------------------------

export const CROWN_HUNT_ROUTE_PATHS = {
  points: '/v1/crown-hunt/points',
  myClaims: '/v1/crown-hunt/me/claims',
  adminPoints: '/v1/admin/crown-hunt/points',
  adminClaims: '/v1/admin/crown-hunt/claims',
} as const;

export function buildCrownHuntPointPath(pointId: string): string {
  return `/v1/crown-hunt/points/${pointId}`;
}

export function buildCrownHuntClaimPath(pointId: string): string {
  return `/v1/crown-hunt/points/${pointId}/claim`;
}

export function buildAdminCrownHuntPointPath(pointId: string): string {
  return `/v1/admin/crown-hunt/points/${pointId}`;
}

export function buildAdminCrownHuntActivatePath(pointId: string): string {
  return `/v1/admin/crown-hunt/points/${pointId}/activate`;
}

export function buildAdminCrownHuntPausePath(pointId: string): string {
  return `/v1/admin/crown-hunt/points/${pointId}/pause`;
}

// ---------------------------------------------------------------------------
// Limits and defaults
// ---------------------------------------------------------------------------

/** Minimum geofence radius in meters. */
export const MIN_GEOFENCE_RADIUS_METERS = 20;
/** Maximum geofence radius in meters. */
export const MAX_GEOFENCE_RADIUS_METERS = 150;
/** Minimum reward in KP. */
export const MIN_REWARD_POINTS = 1;
/** Maximum reward in KP. */
export const MAX_REWARD_POINTS = 1_000;
/** Maximum title length in characters. */
export const MAX_TITLE_LENGTH = 100;
/** Maximum description length in characters. */
export const MAX_DESCRIPTION_LENGTH = 500;

/** Maximum position age in seconds for a valid claim. */
export const MAX_POSITION_AGE_SECONDS = 60;
/**
 * Maximum allowed speed in meters per second (~5 km/h).
 * Users above this threshold must not be allowed to claim.
 */
export const MAX_CLAIM_SPEED_MPS = 1.4;

/** Default page size for point lists. */
export const DEFAULT_CROWN_HUNT_PAGE_SIZE = 20;
/** Maximum page size for point lists. */
export const MAX_CROWN_HUNT_PAGE_SIZE = 50;

// ---------------------------------------------------------------------------
// Mobile: Kronjakt point map summary
// ---------------------------------------------------------------------------

/**
 * Safe map summary for a Kronjakt point.
 *
 * Returned to mobile clients. Must not contain internal metadata,
 * fraud data, other users' claims, or exact claim coordinates.
 */
export interface CrownHuntPointSummary {
  /** Opaque point identifier. */
  pointId: string;
  /** Display title. Max 100 characters. */
  title: string;
  /** Short description, optional. Max 500 characters. */
  description: string | null;
  /** WGS-84 latitude of the point centre. */
  latitude: number;
  /** WGS-84 longitude of the point centre. */
  longitude: number;
  /** Geofence radius in meters that the user must be within to claim. */
  geofenceRadiusMeters: number;
  /** KP reward for a successful claim. */
  rewardPoints: number;
  status: CrownHuntPointStatus;
  /** ISO 8601 timestamp when the point becomes available, or null. */
  availableFrom: string | null;
  /** ISO 8601 timestamp when the point stops being available, or null. */
  availableUntil: string | null;
  /** Whether the current authenticated user has already claimed this point. */
  claimedByCurrentUser: boolean;
  repeatRule: CrownHuntRepeatRule;
}

// ---------------------------------------------------------------------------
// Mobile: Kronjakt point detail
// ---------------------------------------------------------------------------

/**
 * Full point detail for the mobile claim sheet.
 *
 * Includes safety instructions. Does not include internal risk rules.
 */
export interface CrownHuntPointDetail extends CrownHuntPointSummary {
  /**
   * Swedish safety instruction to display prominently on the claim sheet.
   * Always: "Stanna säkert innan du samlar in belöningen."
   */
  safetyInstruction: string;
}

// ---------------------------------------------------------------------------
// Mobile: paginated point list response
// ---------------------------------------------------------------------------

export interface PaginatedCrownHuntPointsResponse {
  ok: true;
  data: {
    points: CrownHuntPointSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Mobile: point detail response
// ---------------------------------------------------------------------------

export interface CrownHuntPointDetailResponse {
  ok: true;
  data: CrownHuntPointDetail;
}

// ---------------------------------------------------------------------------
// Mobile: claim request
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/crown-hunt/points/:pointId/claim.
 *
 * - pointId is provided through the route parameter, not the body.
 * - userId is derived from the authenticated session.
 * - Reward amount must NOT be provided — backend determines the reward.
 * - Client-calculated distances and eligibility results are not accepted.
 * - idempotencyKey prevents duplicate awards for the same button press.
 */
export interface CrownHuntClaimRequest {
  /** WGS-84 latitude reported by the device GPS. */
  latitude: number;
  /** WGS-84 longitude reported by the device GPS. */
  longitude: number;
  /** Horizontal accuracy in meters, if available. */
  accuracyMeters?: number | null;
  /** Speed in meters per second at the time of the claim, if available. */
  speedMetersPerSecond?: number | null;
  /**
   * ISO 8601 timestamp when this position was recorded by the device GPS.
   * Must not be older than MAX_POSITION_AGE_SECONDS.
   */
  recordedAt: string;
  /**
   * Client-generated idempotency key for this specific claim attempt.
   * Must be unique per button press. UUID recommended.
   */
  idempotencyKey: string;
}

// ---------------------------------------------------------------------------
// Mobile: claim response
// ---------------------------------------------------------------------------

/**
 * Result of a claim attempt.
 *
 * - Successful `awarded` claims include the awarded KP and new balance.
 * - All other results include a safe user-facing message in Swedish.
 * - Anti-fraud thresholds and internal reason metadata are never returned.
 * - The `risk_review` result awards no points and shows a neutral message.
 */
export interface CrownHuntClaimResponse {
  ok: true;
  data: {
    result: CrownHuntClaimResult;
    /** KP awarded for an `awarded` claim. Null for all other results. */
    pointsAwarded: number | null;
    /** New KP balance after a successful `awarded` claim. Null otherwise. */
    newBalance: number | null;
    /** Safe Swedish user-facing message. Does not expose anti-fraud details. */
    message: string;
  };
}

// ---------------------------------------------------------------------------
// Mobile: user claim history summary
// ---------------------------------------------------------------------------

/**
 * A single entry in the current user's Kronjakt claim history.
 *
 * Does not include exact claim coordinates or internal fraud metadata.
 */
export interface CrownHuntClaimHistoryEntry {
  /** Opaque claim identifier. */
  claimId: string;
  /** Opaque point identifier. */
  pointId: string;
  /** Point title at time of claim. */
  pointTitle: string;
  result: CrownHuntClaimResult;
  /** KP awarded. Zero for non-awarded results. */
  pointsAwarded: number;
  /** ISO 8601 timestamp when the claim was made. */
  claimedAt: string;
}

export interface PaginatedCrownHuntClaimHistoryResponse {
  ok: true;
  data: {
    claims: CrownHuntClaimHistoryEntry[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

// ---------------------------------------------------------------------------
// Admin: create-point request
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/admin/crown-hunt/points.
 *
 * New points always start as `draft`.
 * Activation is a separate action requiring safety confirmation.
 */
export interface AdminCreateCrownHuntPointRequest {
  title: string;
  description?: string | null;
  latitude: number;
  longitude: number;
  geofenceRadiusMeters: number;
  rewardPoints: number;
  repeatRule: CrownHuntRepeatRule;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

// ---------------------------------------------------------------------------
// Admin: update-point request
// ---------------------------------------------------------------------------

/**
 * Request body for PATCH /v1/admin/crown-hunt/points/:pointId.
 *
 * Only draft or paused points may be edited.
 * All fields are optional.
 */
export interface AdminUpdateCrownHuntPointRequest {
  title?: string;
  description?: string | null;
  latitude?: number;
  longitude?: number;
  geofenceRadiusMeters?: number;
  rewardPoints?: number;
  repeatRule?: CrownHuntRepeatRule;
  availableFrom?: string | null;
  availableUntil?: string | null;
}

// ---------------------------------------------------------------------------
// Admin: activate request
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/admin/crown-hunt/points/:pointId/activate.
 *
 * Requires a safety confirmation checkbox and an approval note.
 */
export interface AdminActivateCrownHuntPointRequest {
  /** Must be true. The admin confirms this is a safe stopping location. */
  safeLocationConfirmed: boolean;
  /** Mandatory note explaining why this location was approved as safe. */
  approvalNote: string;
}

// ---------------------------------------------------------------------------
// Admin: Kronjakt point summary (admin view)
// ---------------------------------------------------------------------------

/**
 * Admin-level point summary including internal metadata.
 *
 * Not returned to mobile clients.
 * Does not expose exact user claim coordinates.
 */
export interface AdminCrownHuntPointSummary {
  pointId: string;
  title: string;
  description: string | null;
  latitude: number;
  longitude: number;
  geofenceRadiusMeters: number;
  rewardPoints: number;
  status: CrownHuntPointStatus;
  repeatRule: CrownHuntRepeatRule;
  availableFrom: string | null;
  availableUntil: string | null;
  approvedAt: string | null;
  approvedByUserId: string | null;
  createdByUserId: string;
  createdAt: string;
  updatedAt: string;
  /** Total successful claims for this point. */
  totalClaims: number;
}

export interface PaginatedAdminCrownHuntPointsResponse {
  ok: true;
  data: {
    points: AdminCrownHuntPointSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}

export interface AdminCrownHuntPointResponse {
  ok: true;
  data: AdminCrownHuntPointSummary;
}

// ---------------------------------------------------------------------------
// Admin: Kronjakt summary stats
// ---------------------------------------------------------------------------

export interface AdminCrownHuntSummary {
  ok: true;
  data: {
    totalPoints: number;
    activePoints: number;
    totalClaims: number;
    pendingReviewClaims: number;
  };
}

// ---------------------------------------------------------------------------
// Admin: anti-fraud claim summary (minimal, read-only)
// ---------------------------------------------------------------------------

/**
 * Admin view of a single claim for risk review.
 *
 * Does not expose exact claim coordinates.
 * Does not expose anti-fraud thresholds.
 * Risk reason categories are safe summaries only.
 */
export interface AdminCrownHuntClaimSummary {
  claimId: string;
  pointId: string;
  pointTitle: string;
  /** Safe reference to the user (opaque user ID). Never expose display names in risk view. */
  userId: string;
  result: CrownHuntClaimResult;
  /** Coarse distance bucket, not exact coordinates. */
  distanceMeters: number | null;
  /** Safe category labels for risk reasons. Not raw signal values. */
  riskReasonCategories: string[];
  claimedAt: string;
}

export interface PaginatedAdminCrownHuntClaimsResponse {
  ok: true;
  data: {
    claims: AdminCrownHuntClaimSummary[];
  };
  meta: {
    page: number;
    pageSize: number;
    total: number;
    hasNext: boolean;
  };
}
