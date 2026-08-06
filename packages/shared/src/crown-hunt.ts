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
 *
 * A Crown is a map COLLECTABLE (Pokémon GO–style), not a titled document, so
 * it carries no title or description — `rewardPoints` is chosen from a rarity
 * tier (Common/Rare/Epic/Legendary) in the admin UI.
 */
export interface AdminCreateCrownHuntPointRequest {
  latitude: number;
  longitude: number;
  geofenceRadiusMeters: number;
  rewardPoints: number;
  repeatRule: CrownHuntRepeatRule;
  availableFrom?: string | null;
  availableUntil?: string | null;
  /**
   * How many DISTINCT users may collect this crown before it is done.
   * `null` (or omitted) = unlimited — any number of distinct users may collect
   * it (one collect each per the repeat rule) until the availability window /
   * TTL ends; this is the default, best for events. A positive integer caps the
   * headcount: the first N distinct collectors succeed, then the point is
   * deactivated (status `ended`) so no one else can collect and it stops
   * rendering on the map. Independent of the rarity tier (which sets reward +
   * visual); this sets the headcount only.
   */
  maxCollectors?: number | null;
}

// ---------------------------------------------------------------------------
// Admin: update-point request
// ---------------------------------------------------------------------------

/**
 * Request body for PATCH /v1/admin/crown-hunt/points/:pointId.
 *
 * Only draft or paused points may be edited.
 * All fields are optional. Crowns carry no title/description (see the create
 * request).
 */
export interface AdminUpdateCrownHuntPointRequest {
  latitude?: number;
  longitude?: number;
  geofenceRadiusMeters?: number;
  rewardPoints?: number;
  repeatRule?: CrownHuntRepeatRule;
  availableFrom?: string | null;
  availableUntil?: string | null;
  /** See create request. `null` = unlimited; a positive integer caps the headcount. */
  maxCollectors?: number | null;
}

// ---------------------------------------------------------------------------
// Admin: delete-point request
// ---------------------------------------------------------------------------

/**
 * Request body for DELETE /v1/admin/crown-hunt/points/:pointId
 * (crownHunt.deletePoint).
 *
 * Hard-deletes a hand-placed point from ANY status (draft/active/paused/ended):
 * the crownHuntPoints/{id} doc and its distinct-collector markers are removed,
 * and a live active crown leaves the map immediately (members read only active
 * points). Unlike pause/end this is irreversible; historical claims are kept as
 * an audit trail. The optional reason is recorded in the admin audit entry.
 */
export interface AdminDeleteCrownHuntPointRequest {
  reason?: string;
}

/** Response for crownHunt.deletePoint. */
export interface AdminDeleteCrownHuntPointResponse {
  ok: true;
  data: {
    pointId: string;
    deleted: true;
    /** Distinct-collector markers removed alongside the point (0 for unlimited). */
    removedCollectors: number;
  };
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
  /** Distinct-collector cap; `null` = unlimited (see the create request). */
  maxCollectors: number | null;
  /** Distinct users who have collected this crown so far (0 for unlimited/new points). */
  collectorCount: number;
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
  /**
   * The rarest auto-spawned crown ever collected. OPTIONAL to match the schema
   * and Firestore reality: a member who has only collected hand-placed crowns
   * (the ledger-only path never writes these) has no rarest crown, so the
   * fields are absent — a consumer must treat absent as "none yet", not assume
   * they are present.
   */
  rarestRarity?: CrownHuntRarity | null;
  rarestAt?: string | null;
  /** Consecutive-day collection streak (Europe/Stockholm days). */
  streakCurrent: number;
  streakBest: number;
  /**
   * Time of the last collection. OPTIONAL: the crownSpawns collect trigger can
   * create the stats doc before the ledger trigger (which owns this field)
   * runs, so it may be absent on a just-created doc.
   */
  lastCollectionAt?: string | null;
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

/**
 * One grid cell's spawn/collect counts, for the admin heat/points map. This
 * maps the raw `crownHuntCellStats/{cellKey}` document, whose triggers write
 * fields sparsely: only `cellKey`/`spawned`/`collected` are guaranteed (the
 * schema's required set). The rarity maps and last-event timestamps are OPTIONAL
 * — a cell that has spawned but never been collected has no `collected*` fields
 * yet, and a rarity map only carries the rarities that actually occurred there.
 */
export interface CrownHuntCellStat {
  cellKey: string;
  spawned: number;
  collected: number;
  spawnedByRarity?: CrownHuntRarityCounts;
  collectedByRarity?: CrownHuntRarityCounts;
  lastSpawnAt?: string | null;
  lastCollectAt?: string | null;
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

// ===========================================================================
// AUTO-SPAWN (Kronjakt) — ephemeral machine-placed crowns
// ===========================================================================
//
// A SECOND source of crowns, distinct from the hand-placed `crownHuntPoints`
// above: short-lived `crownSpawns` placed automatically near recent member
// activity, inside admin-approved AREAS. Backend is behind the `crownHuntSpawn`
// feature flag (contract default OFF). Everything below is the STABLE contract
// the later admin-UI and Android slices consume — the backend types in
// functions/src/crownHunt/* are the runtime authority and must stay in sync.

// ---------------------------------------------------------------------------
// Rarity, collection mode, and the member-facing crown read shape
// ---------------------------------------------------------------------------

const CROWN_SPAWN_RARITIES = ['common', 'uncommon', 'rare', 'legendary'] as const;
export type CrownSpawnRarity = (typeof CROWN_SPAWN_RARITIES)[number];

/**
 * How a spawned crown is collected — stamped on the crown at spawn time:
 *  - `shared`: many DISTINCT members may each collect it ONCE; it stays on the
 *    map until its TTL. A member's second attempt is refused.
 *  - `exclusive`: the FIRST member to collect it takes it and it is REMOVED
 *    immediately, gone for everyone. The client may render exclusive crowns
 *    specially ("first to catch").
 *
 * Derived from rarity by the backend (a single cutoff constant): today
 * common/uncommon/rare are shared and legendary is exclusive.
 */
export const CROWN_COLLECT_MODES = ['shared', 'exclusive'] as const;
export type CrownCollectMode = (typeof CROWN_COLLECT_MODES)[number];

/**
 * Member-facing view of a live auto-spawned crown (`crownSpawns/{spawnId}`).
 * Excludes every backend-only field (cellKey, areaId, approvedCellBy,
 * safeLocationConfirmed, claim/risk metadata) — the client needs only where the
 * crown is, what it is worth, how it is collected, and when it disappears.
 */
export interface CrownSpawnView {
  spawnId: string;
  latitude: number;
  longitude: number;
  rarity: CrownSpawnRarity;
  /** SHARED vs EXCLUSIVE — the client distinguishes exclusive crowns visually. */
  collectMode: CrownCollectMode;
  rewardPoints: number;
  collectRadiusMeters: number;
  /** ISO 8601. The crown is gone after this instant. */
  expiresAt: string;
}

const CROWN_SPAWN_CLAIM_RESULTS = [
  'awarded',
  'already_taken',
  'already_collected',
  'outside_radius',
  'must_be_stationary',
  'position_too_old',
  'crown_expired',
  'daily_limit_reached',
  'risk_review',
  'feature_disabled',
  'not_eligible',
] as const;
export type CrownSpawnClaimResult = (typeof CROWN_SPAWN_CLAIM_RESULTS)[number];

// ---------------------------------------------------------------------------
// Marked spawn areas — the admin-drawn shapes the spawner may place in
// ---------------------------------------------------------------------------

const CROWN_SPAWN_AREA_SHAPE_TYPES = ['polygon', 'circle', 'rectangle'] as const;
export type CrownSpawnAreaShapeType = (typeof CROWN_SPAWN_AREA_SHAPE_TYPES)[number];

/** A WGS-84 vertex. `lon`, not `lng`, to match the rest of these contracts. */
export interface CrownSpawnAreaVertex {
  lat: number;
  lon: number;
}

/** A closed GeoJSON-style ring: first and last vertex equal, >= 3 distinct. */
export interface CrownSpawnPolygonShape {
  type: 'polygon';
  vertices: CrownSpawnAreaVertex[];
}

export interface CrownSpawnCircleShape {
  type: 'circle';
  center: CrownSpawnAreaVertex;
  /** Metres; backend bounds are 10 .. 50 000. */
  radiusMeters: number;
}

/** Axis-aligned bounds; `north > south` and `east > west` (no antimeridian wrap). */
export interface CrownSpawnRectangleShape {
  type: 'rectangle';
  bounds: { north: number; south: number; east: number; west: number };
}

export type CrownSpawnAreaShape =
  CrownSpawnPolygonShape | CrownSpawnCircleShape | CrownSpawnRectangleShape;

/**
 * Admin view of a marked area (`crownSpawnAreas/{areaId}`). Not member-readable.
 * An area only spawns while `active` AND `safeAreaConfirmed` are both true, and
 * ACTIVATING it requires the safety confirmation in the same request.
 */
export interface AdminCrownSpawnArea {
  areaId: string;
  name: string | null;
  shape: CrownSpawnAreaShape;
  active: boolean;
  safeAreaConfirmed: boolean;
  createdByUserId: string;
  createdAt: string | null;
  updatedAt: string | null;
  approvedByUserId: string | null;
  approvedAt: string | null;
  /**
   * Count of cached OpenStreetMap safe-stop POIs (parking / fuel / charging)
   * inside this area — the anchor points the spawner places crowns at. 0 until
   * the area's POI ingestion has run. Show it as "N safe spots found in this
   * area", and whenever it is surfaced show {@link OSM_ATTRIBUTION} alongside.
   */
  poiCount: number;
  /** When the POI cache was last refreshed (ISO 8601), or null if never. */
  poisRefreshedAt: string | null;
}

/** A safe-stop POI category the area spawner anchors crowns to. */
export const CROWN_SPAWN_POI_CATEGORIES = ['parking', 'fuel', 'charging'] as const;
export type CrownSpawnPoiCategory = (typeof CROWN_SPAWN_POI_CATEGORIES)[number];

/**
 * ODbL attribution required wherever OpenStreetMap-derived data (the safe-stop
 * POIs behind area spawning, and an area's `poiCount`) is shown. Conventionally
 * kept in English in every locale; mirrored in contracts/localization under
 * `crownHunt.safeSpotAttribution`. Mirrors the Trafikverket "Källa: Trafikverket"
 * precedent — the credit is owed wherever the data is on screen, and only there.
 */
export const OSM_ATTRIBUTION = '© OpenStreetMap contributors';

/** Input for crownHunt.createSpawnArea (admin). `active` requires `safeAreaConfirmed: true`. */
export interface AdminCreateCrownSpawnAreaRequest {
  shape: CrownSpawnAreaShape;
  name?: string | null;
  active?: boolean;
  safeAreaConfirmed?: boolean;
}

/** Input for crownHunt.updateSpawnArea (admin). Activating requires `safeAreaConfirmed: true`. */
export interface AdminUpdateCrownSpawnAreaRequest {
  areaId: string;
  shape?: CrownSpawnAreaShape;
  name?: string | null;
  active?: boolean;
  safeAreaConfirmed?: boolean;
}

/** Input for crownHunt.deleteSpawnArea (admin). Drains the area's live crowns. */
export interface AdminDeleteCrownSpawnAreaRequest {
  areaId: string;
  reason?: string;
}

/** Response shape of the four area-mutation callables (create/update/delete). */
export interface AdminCrownSpawnAreaMutationResponse {
  areaId: string;
  active: boolean;
  safeAreaConfirmed: boolean;
  /** Live crowns removed by a deactivation/deletion/reshape (0 otherwise). */
  removedCrowns: number;
}

/** Input for crownHunt.listSpawnAreas (admin). */
export interface AdminListCrownSpawnAreasRequest {
  activeOnly?: boolean;
  limit?: number;
}

export interface AdminListCrownSpawnAreasResponse {
  areas: AdminCrownSpawnArea[];
}
