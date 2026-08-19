/**
 * User-reported POLICE sightings domain — constants, pure geo logic, input
 * parsing, builders, and the proximity-alert decision (the police-proximity
 * alert feature).
 *
 * WHY A SEPARATE DOMAIN (not the `police` incident type). The incidents layer
 * already carries a `police` category, but this feature is a different product:
 * a police report here is a SHORT-LIVED map pin (police move on — a ~40 min TTL,
 * not the incident layer's 1h) whose whole purpose is to drive a mid-screen
 * ReactionOverlay when a driver comes CLOSE to one. It is also RATE-LIMITED on
 * report — the shared `incidents.report` is not — so one member cannot flood the
 * map with fake police. Keeping it in its own collection means its TTL, its
 * report throttle, and its rules are all police-specific and cannot regress the
 * shared incidents layer (and cannot collide with the parallel wave-to-nearby
 * work, which also touches map proximity).
 *
 * Data model — `policeReports/{reportId}`:
 *  - `latitude` / `longitude` — WGS-84 report position.
 *  - `geoCell`   — coarse grid-cell key ({@link geoCellKey}) so `police.listNearby`
 *                  reads only the handful of cells covering the requested radius
 *                  instead of scanning the whole collection (mirrors incidents /
 *                  crownHunt: server-side Haversine, never a client distance).
 *  - `status`    — 'active' while live; the read rule additionally gates on
 *                  `expiresAt > request.time`, so an expired pin is never
 *                  readable even before the TTL policy reclaims it.
 *  - `reporterUid` — the reporting member.
 *  - `source`    — how the report was raised: 'manual' (the report-police action)
 *                  or 'convoy' (raised alongside a convoy police reaction). Kept
 *                  for analytics/moderation; it does not change liveness.
 *  - `createdAt` — server timestamp.
 *  - `expiresAt` — auto-expiry timestamp ({@link policeExpiryFor}); a Firestore
 *                  TTL policy reclaims docs past it (deploy note in report.ts).
 *
 * No confirm / clear-vote sub-collections and NO scheduled sweep: a police pin
 * is deliberately transient and low-volume, it has no sub-collections to
 * recursive-delete, and both the security read rule and `police.listNearby`
 * already hide an expired pin the instant it lapses — so the field-scoped TTL
 * policy on `expiresAt` is the only reclaim needed.
 *
 * Pure module — no Firebase Admin SDK imports. Great-circle distance and
 * coordinate validity reuse the crownHunt helpers (the single source of truth),
 * exactly as incidents-core does; only the tiny grid-cell helpers are restated
 * here so the domain stays self-contained in police-specific files.
 */

import { z } from 'zod';
import { haversineDistanceMeters, isValidCoordinate } from '../crownHunt/crown-hunt-geo';

// ---------------------------------------------------------------------------
// Status + source
// ---------------------------------------------------------------------------

export const POLICE_ACTIVE_STATUS = 'active' as const;

/** How a police report was raised. Recorded for analytics; never gates liveness. */
export const POLICE_REPORT_SOURCES = ['manual', 'convoy'] as const;
export type PoliceReportSource = (typeof POLICE_REPORT_SOURCES)[number];

// ---------------------------------------------------------------------------
// TTL
// ---------------------------------------------------------------------------

/**
 * How long a police pin lives before it lapses (ms). Police move on, so this is
 * deliberately SHORT — a stale pin is worse than no pin because it fires a false
 * proximity alert. 40 minutes sits in the requested 30–45 min band: long enough
 * that a report is still useful to a driver a few minutes behind, short enough
 * that a patrol that has left is gone from the map before it misleads anyone.
 * Unlike the incident layer there is no confirm-to-extend — a pin that is still
 * relevant is re-reported, and a fresh report proves the patrol is still THERE.
 */
export const POLICE_REPORT_TTL_MS = 40 * 60 * 1000;

/** Expiry instant for a freshly-reported police pin. */
export function policeExpiryFor(now: Date): Date {
  return new Date(now.getTime() + POLICE_REPORT_TTL_MS);
}

/**
 * Is a police pin still live at `nowMs`? The single liveness rule, mirroring the
 * security read rule (`expiresAt > request.time`) and `police.listNearby`: an
 * expired pin is invisible to everyone. Strictly greater — a pin expiring exactly
 * at `nowMs` is already gone. Guards a corrupt/absent expiry to "not live".
 */
export function isPoliceReportLive(status: unknown, expiresAtMs: number, nowMs: number): boolean {
  if (status !== POLICE_ACTIVE_STATUS) return false;
  if (!Number.isFinite(expiresAtMs) || !Number.isFinite(nowMs)) return false;
  return expiresAtMs > nowMs;
}

// ---------------------------------------------------------------------------
// Nearby-query radius bounds (mirrors incidents-core)
// ---------------------------------------------------------------------------

export const DEFAULT_RADIUS_METERS = 15_000;
export const MIN_RADIUS_METERS = 100;
export const MAX_RADIUS_METERS = 50_000;

/** Clamps a requested radius into the supported window. */
export function clampRadiusMeters(radius: number | undefined): number {
  if (radius === undefined || !Number.isFinite(radius)) return DEFAULT_RADIUS_METERS;
  return Math.min(MAX_RADIUS_METERS, Math.max(MIN_RADIUS_METERS, radius));
}

// ---------------------------------------------------------------------------
// Proximity alert (client-side decision, unit-tested here)
// ---------------------------------------------------------------------------

/**
 * How close a driver must come to a police pin before the mid-screen alert
 * fires, in metres.
 *
 * 500 m: on a road taken at 70–90 km/h that is roughly 20–25 seconds of warning
 * — enough to register and react, without popping so early that the patrol is
 * still out of sight and the alert reads as noise. Comfortably wider than the
 * live-user nearby chip radius (which is about being able to say hello, not about
 * a hazard ahead), and wider than typical GPS error so a real approach is not
 * missed on a jittery fix.
 */
export const POLICE_PROXIMITY_ALERT_RADIUS_METERS = 500;

/**
 * Pure proximity-alert decision, shared by the client monitor and its tests.
 *
 * Fires for a pin when the driver is within {@link POLICE_PROXIMITY_ALERT_RADIUS_METERS}
 * AND that pin has NOT already alerted this driver. The `alreadyAlerted` set is
 * how the alert stays ONCE-PER-PIN: the caller records a pin's id after firing
 * and passes the accumulated set back on the next location update, so a driver
 * sitting next to a patrol is warned once, not on every GPS tick. Distance is a
 * great-circle Haversine from the STORED pin position — never a client-supplied
 * distance — so the same maths the server trusts elsewhere decides the alert.
 *
 * A pin that is out of range or already-alerted returns false; a corrupt
 * coordinate returns false (no alert) rather than throwing, so one bad pin can
 * never wedge the monitor.
 */
export function shouldAlertForPolice(params: {
  driverLat: number;
  driverLng: number;
  policeLat: number;
  policeLng: number;
  policeId: string;
  alreadyAlerted: ReadonlySet<string>;
  radiusMeters?: number;
}): boolean {
  const radius = params.radiusMeters ?? POLICE_PROXIMITY_ALERT_RADIUS_METERS;
  if (params.alreadyAlerted.has(params.policeId)) return false;
  if (!isValidCoordinate(params.driverLat, params.driverLng)) return false;
  if (!isValidCoordinate(params.policeLat, params.policeLng)) return false;
  const distance = haversineDistanceMeters(
    params.driverLat,
    params.driverLng,
    params.policeLat,
    params.policeLng,
  );
  return distance <= radius;
}

// ---------------------------------------------------------------------------
// Report rate limit (server-enforced anti-spam)
// ---------------------------------------------------------------------------
//
// A police report WRITES a pin every user sees and every nearby driver gets
// alerted for, so it must not be spammable — one member dropping ten fake pins
// would poison the map and hammer everyone with false alerts. This binds report
// FREQUENCY with the same cheap fixed-window counter the incidents limiters use:
// a DETERMINISTIC backend-only doc `policeReportRateLimits/{uid}_{epochMinute}`,
// read BY ID (no query, no index) and bumped with FieldValue.increment (a
// commutative, contention-free server op — no transaction). A rejected call
// costs exactly one get-by-id. `expireAt` carries a Firestore TTL policy so
// spent windows self-delete (deploy note in report.ts). Its OWN collection so it
// shares no budget with anything else.

/** Backend-only fixed-window report-rate-limit counter collection (client-denied). */
export const POLICE_REPORT_RATE_LIMIT_COLLECTION = 'policeReportRateLimits';

/**
 * Max admitted `police.report` calls per uid per fixed 60 s window.
 *
 * A police report is a deliberate human tap on a hazard you just drove past —
 * nobody honestly reports 5 different patrols in one minute. 5/min is generous
 * headroom for a fat-fingered double-tap or a quick correction while being
 * orders of magnitude below a flood. Tighter than the incident clear-vote's 6,
 * because a fake police pin actively pushes an alert at every nearby driver.
 */
export const POLICE_REPORT_RATE_LIMIT_MAX = 5;

/** Fixed window length: one minute. */
export const POLICE_REPORT_RATE_LIMIT_WINDOW_MS = 60_000;

/**
 * Grace added to a window's end before its counter doc is TTL-eligible. Only
 * affects cleanup timing, never the limit decision.
 */
export const POLICE_REPORT_RATE_LIMIT_TTL_GRACE_MS = 5 * 60_000;

/** Epoch-minute index of the fixed window containing `nowMs`. */
export function policeReportRateLimitWindowIndex(nowMs: number): number {
  return Math.floor(nowMs / POLICE_REPORT_RATE_LIMIT_WINDOW_MS);
}

/**
 * Deterministic counter doc id for (uid, window): `{uid}_{epochMinute}`. A
 * Firebase uid contains no `/`, so this is a safe single-segment id; the same
 * uid in the same minute always maps to the same doc, which is what makes the
 * increment O(1) with no query.
 */
export function policeReportRateLimitDocId(uid: string, nowMs: number): string {
  return `${uid}_${policeReportRateLimitWindowIndex(nowMs)}`;
}

/**
 * `expireAt` instant for a window's counter doc: the window end plus a grace, so
 * a Firestore TTL policy reaps spent counters and the collection never grows.
 */
export function policeReportRateLimitExpiry(nowMs: number): Date {
  const windowEnd =
    (policeReportRateLimitWindowIndex(nowMs) + 1) * POLICE_REPORT_RATE_LIMIT_WINDOW_MS;
  return new Date(windowEnd + POLICE_REPORT_RATE_LIMIT_TTL_GRACE_MS);
}

/**
 * Pure limit decision: is a call ADMITTED given the counter value BEFORE it
 * (how many reports the uid already made this window)? A finite count at or above
 * `max` is throttled; anything else — absent (0), missing, or CORRUPT (NaN /
 * non-finite) — is admitted. Failing OPEN on a corrupt counter is deliberate: a
 * garbled rate-limit doc must never stop a member warning others about a patrol.
 */
export function isUnderPoliceReportRateLimit(
  currentCount: number,
  max: number = POLICE_REPORT_RATE_LIMIT_MAX,
): boolean {
  if (!Number.isFinite(currentCount)) return true;
  return currentCount < max;
}

// ---------------------------------------------------------------------------
// listNearby per-user rate limit (runaway / abuse guard)
// ---------------------------------------------------------------------------
//
// Same cheap fixed-window mechanism as the report limiter, in its OWN collection
// so a burst of map refreshes can never consume a member's ability to report a
// patrol (and vice versa). `police.listNearby` is a hot poll shared with the
// incidents map tick, so a client bug or valid-token abuser could run up
// invocation + read cost; this caps the FREQUENCY. Generous headroom (60/min)
// over the ~10–30 calls/min a legitimate polling+panning client makes.

/** Backend-only fixed-window list-rate-limit counter collection (client-denied). */
export const POLICE_LIST_RATE_LIMIT_COLLECTION = 'policeListRateLimits';

/** Max admitted `police.listNearby` calls per uid per fixed 60 s window. */
export const POLICE_LIST_RATE_LIMIT_MAX = 60;

/** Deterministic counter doc id for (uid, window) — shares the window index. */
export function policeListRateLimitDocId(uid: string, nowMs: number): string {
  return `${uid}_${policeReportRateLimitWindowIndex(nowMs)}`;
}

/** `expireAt` for a list-limit window's counter doc — shares the window/grace. */
export function policeListRateLimitExpiry(nowMs: number): Date {
  return policeReportRateLimitExpiry(nowMs);
}

/** Pure list-limit decision (fails OPEN on a corrupt counter, as report does). */
export function isUnderPoliceListRateLimit(
  currentCount: number,
  max: number = POLICE_LIST_RATE_LIMIT_MAX,
): boolean {
  return isUnderPoliceReportRateLimit(currentCount, max);
}

// ---------------------------------------------------------------------------
// Geo-cell indexing (self-contained; mirrors incidents-core's grid)
// ---------------------------------------------------------------------------

/**
 * Grid-cell edge length in degrees (~20 km of latitude). A pin's `geoCell` is
 * `${latIndex}_${lngIndex}`; a nearby query enumerates the cells overlapping the
 * requested bounding box and reads only those (chunked `in` queries), so there is
 * no full-collection scan. Restated here (rather than imported from incidents)
 * to keep the domain self-contained; the value matches so a shared map tick pays
 * the same cell granularity across both layers.
 */
export const CELL_SIZE_DEGREES = 0.18;

const METERS_PER_DEGREE_LAT = 111_320;

const clampLat = (lat: number) => Math.min(90, Math.max(-90, lat));

/** Deterministic grid-cell key for a coordinate. */
export function geoCellKey(latitude: number, longitude: number): string {
  const latIdx = Math.floor(clampLat(latitude) / CELL_SIZE_DEGREES);
  const lngIdx = Math.floor(longitude / CELL_SIZE_DEGREES);
  return `${latIdx}_${lngIdx}`;
}

export interface BoundingBox {
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
}

/**
 * Lat/lng bounding box covering every point within `radiusMeters` of the centre.
 * Longitude degrees shrink with latitude, so the longitude delta is scaled by
 * cos(latitude) (clamped near the poles to avoid blow-up).
 */
export function boundingBox(latitude: number, longitude: number, radiusMeters: number): BoundingBox {
  const latDelta = radiusMeters / METERS_PER_DEGREE_LAT;
  const cos = Math.max(0.01, Math.cos((clampLat(latitude) * Math.PI) / 180));
  const lngDelta = radiusMeters / (METERS_PER_DEGREE_LAT * cos);
  return {
    minLat: clampLat(latitude - latDelta),
    maxLat: clampLat(latitude + latDelta),
    minLng: longitude - lngDelta,
    maxLng: longitude + lngDelta,
  };
}

/** Every grid-cell key overlapping `box`. */
export function geoCellsForBounds(box: BoundingBox): string[] {
  const latStart = Math.floor(box.minLat / CELL_SIZE_DEGREES);
  const latEnd = Math.floor(box.maxLat / CELL_SIZE_DEGREES);
  const lngStart = Math.floor(box.minLng / CELL_SIZE_DEGREES);
  const lngEnd = Math.floor(box.maxLng / CELL_SIZE_DEGREES);
  const cells: string[] = [];
  for (let latIdx = latStart; latIdx <= latEnd; latIdx += 1) {
    for (let lngIdx = lngStart; lngIdx <= lngEnd; lngIdx += 1) {
      cells.push(`${latIdx}_${lngIdx}`);
    }
  }
  return cells;
}

/** Cells overlapping the circle of `radiusMeters` around a point. */
export function geoCellsForRadius(
  latitude: number,
  longitude: number,
  radiusMeters: number,
): string[] {
  return geoCellsForBounds(boundingBox(latitude, longitude, radiusMeters));
}

/** Splits an array into chunks (Firestore `in` supports up to 30 values). */
export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    out.push(items.slice(i, i + size));
  }
  return out;
}

export const FIRESTORE_IN_CHUNK = 30;

/** True when a candidate coordinate lies within `radiusMeters` of the centre. */
export function isWithinRadius(
  centerLat: number,
  centerLng: number,
  lat: number,
  lng: number,
  radiusMeters: number,
): boolean {
  return haversineDistanceMeters(centerLat, centerLng, lat, lng) <= radiusMeters;
}

// ---------------------------------------------------------------------------
// Inputs (Zod)
// ---------------------------------------------------------------------------

const reportInputSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    // How the report was raised. Optional; defaults to 'manual' (the standalone
    // report-police action). The convoy police button passes 'convoy'.
    source: z.enum(POLICE_REPORT_SOURCES).optional(),
  })
  .strict();

const listNearbyInputSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    radiusMeters: z.number().positive().optional(),
  })
  .strict();

export type ReportInput = z.infer<typeof reportInputSchema>;
export type ListNearbyInput = z.infer<typeof listNearbyInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseReportInput = (d: unknown) =>
  parse(reportInputSchema, d, 'Expected { latitude, longitude, source? }.');
export const parseListNearbyInput = (d: unknown) =>
  parse(listNearbyInputSchema, d, 'Expected { latitude, longitude, radiusMeters? }.');

// ---------------------------------------------------------------------------
// Builders + views
// ---------------------------------------------------------------------------

/**
 * The non-timestamp portion of a `policeReports/{id}` document. The callable adds
 * `createdAt` (server timestamp) and `expiresAt` (a Firestore Timestamp) so this
 * stays a pure, testable object.
 */
export interface PoliceReportFields {
  latitude: number;
  longitude: number;
  geoCell: string;
  status: typeof POLICE_ACTIVE_STATUS;
  reporterUid: string;
  source: PoliceReportSource;
}

export function buildPoliceReportFields(params: {
  latitude: number;
  longitude: number;
  reporterUid: string;
  source?: PoliceReportSource | null;
}): PoliceReportFields {
  return {
    latitude: params.latitude,
    longitude: params.longitude,
    geoCell: geoCellKey(params.latitude, params.longitude),
    status: POLICE_ACTIVE_STATUS,
    reporterUid: params.reporterUid,
    source: params.source ?? 'manual',
  };
}

/** Shape returned to clients by `police.report` / `police.listNearby`. */
export interface PoliceReportView {
  id: string;
  latitude: number;
  longitude: number;
  reporterUid: string | null;
  source: PoliceReportSource;
  createdAt: string | null;
  expiresAt: string | null;
}

// ---------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------

/** Defensive coordinate re-check (Zod bounds already applied; belt-and-braces). */
export function isReportable(latitude: number, longitude: number): boolean {
  return isValidCoordinate(latitude, longitude);
}
