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

const CROWN_HUNT_POINT_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type CrownHuntPointStatus = (typeof CROWN_HUNT_POINT_STATUSES)[number];

// ---------------------------------------------------------------------------
// Repeat rule
// ---------------------------------------------------------------------------

const CROWN_HUNT_REPEAT_RULES = ['once', 'daily', 'weekly'] as const;
export type CrownHuntRepeatRule = (typeof CROWN_HUNT_REPEAT_RULES)[number];

// ---------------------------------------------------------------------------
// Claim result
// ---------------------------------------------------------------------------

const CROWN_HUNT_CLAIM_RESULTS = [
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
// Admin: create-point request
// ---------------------------------------------------------------------------

/**
 * Request body for POST /v1/admin/crown-hunt/points.
 *
 * New points always start as `draft`.
 * Activation is a separate action requiring safety confirmation.
 */
export interface AdminCreateCrownHuntPointRequest {
  /** Optional — a Crown Hunt point is just a collectable on the map. */
  title?: string;
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
