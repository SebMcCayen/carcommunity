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

// ===========================================================================
// STATISTICS + LEADERBOARD + SEASONS (read contracts)
// ===========================================================================
//
// The backend maintains these aggregates incrementally on every crown
// collection (Firestore triggers) and closes each season with a scheduled
// rollover. The admin dashboard and the Android social screen READ them; no
// client ever writes them. Backing Firestore collections:
//
//   crownHuntLeaderboardEntries/{scope}__{uid}   { scope, uid, points, crownsCollected }
//       scope = 'alltime' | seasonId ('YYYY-MM'). Member-readable. Read ranked
//       via `where scope==X orderBy points desc, crownsCollected desc` (uid is
//       the final, implicit tiebreak). RANK ORDERING is "strictly better": B
//       outranks A iff B.points > A.points, OR (points equal AND
//       B.crownsCollected > A.crownsCollected), OR (both equal AND B.uid <
//       A.uid). A member's rank = (count of strictly-better members) + 1.
//       Firestore has no OR, so a bare `points > mine` count is only a lower
//       bound at a points tie; a client wanting the exact rank pages the ordered
//       board and reads its own position (same three-key order). The server's
//       authoritative ranking (rankLeaderboard) applies exactly this ordering.
//   crownHuntUserStats/{uid}                      personal rich stats. Owner + admin.
//   crownHuntSeasons/{seasonId}                   season metadata + finalized standings. Member-readable.
//   crownHuntSpawnStats/{scope}                   admin totals (spawned/collected). Admin only.
//   crownHuntCellStats/{cellKey}                  per-cell heat-map counts. Admin only.
//
// AUTHORITY SPLIT: points / crowns / rank / streak come from the Kronpoäng
// ledger (both hand-placed and auto-spawned crowns count). The rarity
// breakdown and the heat-map come from the auto-spawned crown documents (the
// only ones carrying a rarity and a grid cell), so they cover auto-spawned
// crowns only; `crownsCollected - sum(byRarity)` is the hand-placed remainder.
//
// PRIVACY: the leaderboard is public within the app. Ranks are global — a
// member the viewer has blocked is NOT removed from the ranking (their standing
// is a fact), and no special block filtering is applied to the board.

/** Crown rarity tiers, ascending. Auto-spawned crowns only. */
export const CROWN_HUNT_RARITIES = ['common', 'uncommon', 'rare', 'legendary'] as const;
export type CrownHuntRarity = (typeof CROWN_HUNT_RARITIES)[number];

/**
 * A per-rarity histogram. `Partial` because writers update buckets SPARSELY
 * (e.g. a cell's `spawnedByRarity` only carries the rarities that spawned there)
 * and the JSON schema lists every key as optional — a consumer must read an
 * ABSENT key as 0, never assume the key is present. (The per-user `byRarity` on
 * `crownHuntUserStats` is additionally initialised to a full four-key map by the
 * collection triggers, but the type stays permissive for the sparse maps that
 * share it.)
 */
export type CrownHuntRarityCounts = Partial<Record<CrownHuntRarity, number>>;

/** Which board a leaderboard read is scoped to. */
export type CrownHuntLeaderboardScope = 'alltime' | 'season';

/** The reserved scope id for the never-resetting all-time board. */
export const CROWN_HUNT_ALL_TIME_SCOPE = 'alltime';

/** The Kronjakt grid cell size in degrees (0.01° lat × 0.01° lon), so the admin
 * heat-map can derive a cell's bounds/centre from its `cellKey` (`latIdx_lonIdx`). */
export const CROWN_HUNT_CELL_DEGREES = 0.01;

/**
 * One ranked row of a leaderboard. `displayName` is resolved from the public
 * user profile at read time (respecting the same read rules as any profile);
 * `seasonsWon` is the reader's lifetime championship count, for "N-time
 * champion" display next to their name.
 */
export interface CrownHuntLeaderboardEntry {
  rank: number;
  uid: string;
  displayName: string;
  points: number;
  crownsCollected: number;
  seasonsWon: number;
}

/** A leaderboard page for one scope, plus the viewer's own standing. */
export interface CrownHuntLeaderboardView {
  scope: CrownHuntLeaderboardScope;
  /** Present when `scope === 'season'`: the season this board is for. */
  seasonId: string | null;
  entries: CrownHuntLeaderboardEntry[];
  /** The viewer's rank in this scope, or null if they have never collected. */
  viewerRank: number | null;
}

/** A member's own Kronjakt statistics (readable by that member and admins). */
export interface CrownHuntPersonalStats {
  uid: string;
  allTime: {
    points: number;
    crownsCollected: number;
    /** Global rank on the all-time board, or null if never collected. */
    rank: number | null;
  };
  currentSeason: {
    seasonId: string;
    points: number;
    crownsCollected: number;
    rank: number | null;
  };
  /** Auto-spawned crowns collected, by rarity. */
  byRarity: CrownHuntRarityCounts;
  /** The rarest auto-spawned crown ever collected, or null. */
  rarestRarity: CrownHuntRarity | null;
  rarestAt: string | null;
  /** Consecutive-day collection streak (Europe/Stockholm days). */
  streakCurrent: number;
  streakBest: number;
  lastCollectionAt: string | null;
  /** Lifetime season victories (first-place finishes) — grows every win. */
  seasonsWon: number;
}

// --- Admin aggregates ------------------------------------------------------

/** Dashboard totals for one scope (all-time or a season). */
export interface CrownHuntAdminStats {
  scope: string;
  spawnedTotal: number;
  collectedTotal: number;
  /** collectedTotal / spawnedTotal, 0..1; 0 when nothing has spawned. */
  collectionRate: number;
  spawnedByRarity: CrownHuntRarityCounts;
  collectedByRarity: CrownHuntRarityCounts;
  /** Distinct members who collected a crown in the last 7 / 30 days. */
  activePlayers7d: number;
  activePlayers30d: number;
}

/** One grid cell's spawn/collect counts, for the admin heat/points map. */
export interface CrownHuntCellStat {
  cellKey: string;
  spawned: number;
  collected: number;
  spawnedByRarity: CrownHuntRarityCounts;
  collectedByRarity: CrownHuntRarityCounts;
  lastSpawnAt: string | null;
  lastCollectAt: string | null;
}

// --- Seasons ---------------------------------------------------------------

export type CrownHuntSeasonStatus = 'active' | 'ended';

/** A finalized podium finisher (top 3), with a name snapshot for the app. */
export interface CrownHuntSeasonWinner {
  rank: number;
  uid: string;
  displayName: string;
  points: number;
  crownsCollected: number;
}

/** A ranked standing on a finalized season (no name; resolved by the client). */
export interface CrownHuntSeasonStanding {
  rank: number;
  uid: string;
  points: number;
  crownsCollected: number;
}

/**
 * Season metadata. An ACTIVE season carries only the lifecycle fields; the
 * finalized fields below (`finalizedAt`, `participantCount`, `winners`,
 * `topStandings`) are written ONLY when the scheduled rollover flips the season
 * to `ended`. They are therefore optional — a consumer reading an active season
 * must not assume `winners`/`topStandings` are present. Use `status === 'ended'`
 * as the presence guard (the social screen shows "last month's champions" only
 * for ended seasons).
 */
export interface CrownHuntSeason {
  seasonId: string;
  period: 'month';
  status: CrownHuntSeasonStatus;
  startAt: string;
  endAt: string;
  finalizedAt?: string | null;
  participantCount?: number | null;
  winners?: CrownHuntSeasonWinner[];
  topStandings?: CrownHuntSeasonStanding[];
}
