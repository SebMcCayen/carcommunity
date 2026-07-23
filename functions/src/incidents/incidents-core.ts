/**
 * Crowd-sourced incidents / roadwork domain — constants, pure geo logic,
 * input parsing, and builders (navigation feature).
 *
 * A NEW backend domain (additive). Members report short-lived road incidents
 * (accident, roadwork, hazard, police, road_closed); every signed-in user
 * reads the ACTIVE, unexpired ones near a point so the Waze-style map layer is
 * shared by all users.
 *
 * Data model — `incidents/{incidentId}`:
 *  - `type`        — one of {@link INCIDENT_TYPES}.
 *  - `latitude` / `longitude` — WGS-84 report position.
 *  - `geoCell`     — a coarse grid-cell key ({@link geoCellKey}) so
 *                    `incident.listNearby` queries only the handful of cells
 *                    covering the requested radius instead of scanning the
 *                    whole collection (mirrors the crownHunt geo approach:
 *                    server-side Haversine, never a client-supplied distance).
 *  - `status`      — 'active' while live; the read rule additionally gates on
 *                    `expiresAt > request.time`, so an expired doc is never
 *                    readable even before the sweep deletes it.
 *  - `source`      — 'user' (a member report) or 'trafikverket' (the Swedish
 *                    open-data importer).
 *  - `reporterUid` — the reporting member (null for imported roadwork).
 *  - `note`        — optional free-text (bounded, sanitized client-side).
 *  - `createdAt`   — server timestamp.
 *  - `expiresAt`   — auto-expiry timestamp (per-type TTL, {@link expiryFor});
 *                    a scheduled sweep deletes docs past it. Confirmations push
 *                    it out ({@link extendedExpiryFor}) up to a hard lifetime
 *                    cap, so a confirmed incident persists but never forever.
 *  - `confirmationCount` — how many other members have confirmed it is still
 *                    there. Maintained ONLY by `incidents.confirm`, in the same
 *                    transaction that claims the confirmation doc.
 *
 * Sub-collection — `incidents/{incidentId}/confirmations/{uid}`:
 *  The confirmation ledger. The document id IS the confirming uid, which makes
 *  "one confirmation per user per incident" a primary-key property rather than
 *  a scan: the claim is a `tx.create` that fails if the doc already exists, so
 *  concurrent double-taps cannot both win. Callable-only (denied by the rules'
 *  deny-all catch-all — the `match /incidents/{incidentId}` block does not
 *  recurse into sub-collections).
 *
 * Pure module — no Firebase Admin SDK imports. Geo maths reuse the crownHunt
 * Haversine helper (single source of truth for great-circle distance).
 */

import { z } from 'zod';
import { haversineDistanceMeters, isValidCoordinate } from '../crownHunt/crown-hunt-geo';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Reportable incident categories. */
export const INCIDENT_TYPES = ['accident', 'roadwork', 'hazard', 'police', 'road_closed'] as const;
export type IncidentType = (typeof INCIDENT_TYPES)[number];

/** Where an incident came from. */
export const INCIDENT_SOURCES = ['user', 'trafikverket'] as const;
export type IncidentSource = (typeof INCIDENT_SOURCES)[number];

export const INCIDENT_ACTIVE_STATUS = 'active' as const;

/** Maximum length of the optional free-text note. */
export const MAX_NOTE_LENGTH = 200;

/**
 * Per-type time-to-live (ms). Incidents are transient: a police sighting ages
 * out fast, roadwork lingers. The sweep deletes docs past `expiresAt`.
 */
export const INCIDENT_TTL_MS: Record<IncidentType, number> = {
  accident: 2 * 60 * 60 * 1000, // 2h
  roadwork: 12 * 60 * 60 * 1000, // 12h
  hazard: 4 * 60 * 60 * 1000, // 4h
  police: 60 * 60 * 1000, // 1h
  road_closed: 8 * 60 * 60 * 1000, // 8h
};

/**
 * TTL applied to importer (Trafikverket) roadwork/incidents. Refreshed on each
 * sync, so a situation that vanishes upstream ages out within one window.
 */
export const IMPORT_TTL_MS = 6 * 60 * 60 * 1000; // 6h

// ---------------------------------------------------------------------------
// Nearby-query radius bounds
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
// listNearby per-user rate limit (runaway / abuse guard)
// ---------------------------------------------------------------------------
//
// listNearby is a FREQUENTLY-called READ. Its per-CALL cost is already bounded
// (App Check + requireActiveActor, MAX_RESULTS = 200, radius clamped to 50 km,
// reads only the covering geoCells — never a full-collection scan). The only
// remaining cost gap is per-user call FREQUENCY: a client bug (hot poll loop)
// or a member with a valid account + App Check token calling it in a tight loop
// could run up invocation + Firestore-read cost. This gate binds that.
//
// Mechanism — a FIXED-WINDOW counter keyed by a DETERMINISTIC document id
// `incidentListRateLimits/{uid}_{epochMinute}` (see incidentListRateLimitDocId).
// Each admitted call bumps `count` with FieldValue.increment(1); the callable
// reads the doc BY ID (no query, no index) before the expensive geoCell reads
// and rejects once the window count reaches the cap. This is deliberately
// CHEAPER than the codebase's other rate limiters (feedback.reportIssue,
// errors.reportClientError, moderation reports), which run a windowed count()
// aggregation inside a transaction per call. That read-then-count-in-a-
// transaction shape is fine for LOW-frequency WRITES but too costly for a hot
// read path: it needs a composite index and, under a runaway that hammers a
// single uid, a transactional read-modify-write of one hot doc contends and
// retries (amplifying cost). FieldValue.increment is a commutative server-side
// op that needs NO transaction and does NOT contend, and a rejected call costs
// exactly one get-by-id — so the guard stays much cheaper than the read it
// protects. An in-memory/instance-local counter is NOT usable here: Cloud Run
// runs this callable at concurrency 80 across multiple instances, so only a
// shared Firestore counter binds a user reliably.
//
// The counter is written ONLY by this callable via the Admin SDK and is never
// client-readable/writable (firebase/firestore.rules denies it). `expireAt`
// carries a Firestore TTL policy so spent windows self-delete (deploy note in
// listNearby.ts) — the collection never accumulates.

/** Backend-only fixed-window rate-limit counter collection (client-denied). */
export const INCIDENT_LIST_RATE_LIMIT_COLLECTION = 'incidentListRateLimits';

/**
 * Max admitted `incidents.listNearby` calls per uid per fixed 60 s window.
 *
 * Sized for generous headroom so legitimate use NEVER trips it: the client
 * polls on a ~15 s keep-alive (~4/min) plus a debounced (500 ms) camera-idle
 * re-query on meaningful map moves (a handful per minute of active panning), so
 * realistic peak is ~10–30 calls/min/user. 60/min is ~2–6× that headroom while
 * still being orders of magnitude below a runaway (hundreds–thousands/min) — it
 * catches the RUNAWAY, it does not throttle real use.
 */
export const INCIDENT_LIST_RATE_LIMIT_MAX = 60;

/** Fixed window length: one minute. */
export const INCIDENT_LIST_RATE_LIMIT_WINDOW_MS = 60_000;

/**
 * Grace added to a window's end before its counter doc is eligible for TTL
 * deletion. Only affects cleanup timing (Firestore TTL is best-effort anyway),
 * never the limit decision.
 */
export const INCIDENT_LIST_RATE_LIMIT_TTL_GRACE_MS = 5 * 60_000;

/** Epoch-minute index of the fixed window containing `nowMs`. */
export function incidentListRateLimitWindowIndex(nowMs: number): number {
  return Math.floor(nowMs / INCIDENT_LIST_RATE_LIMIT_WINDOW_MS);
}

/**
 * Deterministic counter doc id for (uid, window): `{uid}_{epochMinute}`. A
 * Firebase uid contains no `/`, so this is a safe single-segment id; the same
 * uid in the same minute always maps to the same doc, which is what makes the
 * increment O(1) with no query.
 */
export function incidentListRateLimitDocId(uid: string, nowMs: number): string {
  return `${uid}_${incidentListRateLimitWindowIndex(nowMs)}`;
}

/**
 * `expireAt` instant for a window's counter doc: the window end plus a grace,
 * so a Firestore TTL policy on this field reaps spent counters and the
 * collection never grows unbounded.
 */
export function incidentListRateLimitExpiry(nowMs: number): Date {
  const windowEnd =
    (incidentListRateLimitWindowIndex(nowMs) + 1) * INCIDENT_LIST_RATE_LIMIT_WINDOW_MS;
  return new Date(windowEnd + INCIDENT_LIST_RATE_LIMIT_TTL_GRACE_MS);
}

/**
 * Pure limit decision: is a call ADMITTED given the counter's value BEFORE this
 * call (i.e. how many calls the uid already made in the current window)?
 *
 * A finite count at or above `max` is throttled; anything else — including an
 * absent (0), missing, or CORRUPT (NaN / non-finite) counter — is admitted.
 * Failing OPEN on a corrupt counter is deliberate: a garbled rate-limit doc
 * must never lock a legitimate user out of the shared map; the worst case is
 * that one bad window is not throttled.
 */
export function isUnderIncidentListRateLimit(
  currentCount: number,
  max: number = INCIDENT_LIST_RATE_LIMIT_MAX,
): boolean {
  if (!Number.isFinite(currentCount)) return true;
  return currentCount < max;
}

// ---------------------------------------------------------------------------
// Geo-cell indexing (the crownHunt-style "cell" for nearby queries)
// ---------------------------------------------------------------------------

/**
 * Grid-cell edge length in degrees (~20 km of latitude). A report's `geoCell`
 * is `${latIndex}_${lngIndex}`; a nearby query enumerates the cells overlapping
 * the requested bounding box and reads only those (chunked `in` queries), so
 * there is no full-collection scan.
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
 * Latitude/longitude bounding box covering every point within `radiusMeters`
 * of the centre. Longitude degrees shrink with latitude, so the longitude
 * delta is scaled by cos(latitude) (clamped near the poles to avoid blow-up).
 */
export function boundingBox(
  latitude: number,
  longitude: number,
  radiusMeters: number,
): BoundingBox {
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

/**
 * Every grid-cell key overlapping `box`. Bounded by the radius cap, so the
 * count stays small (a handful up to a few dozen at the widest radius); the
 * callable chunks these into Firestore `in` queries.
 */
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
// Expiry
// ---------------------------------------------------------------------------

/**
 * Whether a stored `confirmationCount` is a value the contract can honour.
 *
 * `typeof x === 'number'` is not enough: Firestore stores doubles, so NaN and
 * ±Infinity are storable, and both survive a `FieldValue.increment` unchanged
 * (NaN + 1 is NaN). Neither is JSON-representable either — the callable
 * framework serialises them to `null`, so a client typed against
 * `confirmationCount: number` would receive `null` and violate its own contract
 * without ever seeing an error.
 *
 * ABSENT is not invalid: the field is unwritten until the first confirmation,
 * which is the normal state of every fresh report. Callers treat `undefined` as
 * 0 and use this only to judge a value that IS present.
 */
export function isValidConfirmationCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0;
}

/**
 * A stored `confirmationCount` normalised for a READ path: absent or corrupt
 * becomes 0.
 *
 * Deliberately more forgiving than the write path. `incidents.confirm` refuses
 * outright on a corrupt count, because it is about to write a derived value
 * back and must not build on a number it cannot trust. `listNearby` renders a
 * shared map layer in bulk, where one corrupt document must not blank out
 * everyone's map or fail the whole batch — so it degrades that single marker to
 * "0 confirmations", exactly as it already skips documents with a malformed
 * `expiresAt` or coordinates rather than aborting.
 */
export function readConfirmationCount(value: unknown): number {
  return isValidConfirmationCount(value) ? value : 0;
}

/** Expiry instant for a freshly-reported incident of `type`. */
export function expiryFor(type: IncidentType, now: Date): Date {
  return new Date(now.getTime() + INCIDENT_TTL_MS[type]);
}

// ---------------------------------------------------------------------------
// Confirmation ("is this still there?") expiry extension
// ---------------------------------------------------------------------------

/**
 * Hard ceiling on an incident's total lifetime, as a multiple of its per-type
 * TTL. A confirmed incident lives longer, but it can NEVER become immortal: no
 * number of confirmations pushes `expiresAt` past
 * `createdAt + LIFETIME_CAP_MULTIPLIER × INCIDENT_TTL_MS[type]`.
 *
 * Read "pushes past" precisely — it is a bound on what a confirmation MOVES the
 * expiry to, not a post-condition on the stored value. A document that already
 * carries an over-cap expiry from outside this module keeps it; see
 * {@link extendedExpiryFor} for why clamping down would be the wrong trade.
 *
 * 3× is the deliberate trade-off: a genuinely persistent situation (12h
 * roadwork) can be kept alive for a day and a half by passers-by, which covers
 * a real multi-day roadwork's useful window, while a stale police sighting
 * (1h TTL) dies within 3h even if a handful of people confirm it. Beyond the
 * cap the incident ages out and someone has to report it afresh — which is the
 * correct signal, because a fresh report proves it is still there NOW.
 */
export const LIFETIME_CAP_MULTIPLIER = 3;

/**
 * Result of applying one confirmation to an incident's expiry.
 * `extended` is false when the cap (or an already-later expiry) means the
 * confirmation bought no extra time — the confirmation still counts.
 */
export interface ExtendedExpiry {
  expiresAt: Date;
  extended: boolean;
}

/**
 * Expiry after a confirmation at `now`.
 *
 * A confirmation resets the clock to a full fresh TTL from NOW (that is what
 * "I just drove past it" means), but never past the absolute lifetime cap, and
 * never BACKWARDS — an incident whose current expiry is already further out
 * (e.g. a long-TTL type confirmed early) keeps the later value.
 *
 * WHICH RULE WINS WHEN THEY CONFLICT: never-backwards. A stored expiry that is
 * ALREADY past the ceiling is returned unchanged rather than clamped down, so
 * this function's output can exceed the ceiling — but only by passing through a
 * value it did not produce. The invariant the cap actually asserts is intact:
 * no confirmation ever MOVES `expiresAt` outward past the ceiling, and in this
 * case it moves it not at all (`extended: false`, and the callable writes the
 * same instant back).
 *
 * That is deliberate, not an oversight. An over-cap expiry can only come from
 * outside this module (a console edit, a restore from a stale export, an older
 * bug) — no writer produces one, since report.ts stamps `createdAt + 1×TTL` and
 * only this function ever moves it. Clamping DOWN here would not rescue such a
 * document anyway: its lifetime is governed by the TTL sweep reading
 * `expiresAt`, so it survives to its bogus expiry whether or not anyone ever
 * confirms it. Clamping would therefore fix nothing in general, while
 * introducing a "confirming an incident SHORTENS its life" behaviour — a
 * surprising, user-visible regression traded for a partial patch of a defect
 * this function did not create.
 *
 * Pure: takes the instants, returns the new instant. The callable supplies
 * `createdAt` from the stored document so the cap is anchored to the real
 * report time, not to the confirmation time.
 */
export function extendedExpiryFor(params: {
  type: IncidentType;
  createdAt: Date;
  currentExpiresAt: Date;
  now: Date;
}): ExtendedExpiry {
  const ttl = INCIDENT_TTL_MS[params.type];
  const ceiling = params.createdAt.getTime() + LIFETIME_CAP_MULTIPLIER * ttl;
  const proposed = params.now.getTime() + ttl;
  // Never past the cap, never earlier than the expiry the doc already has.
  const capped = Math.min(proposed, ceiling);
  const next = Math.max(capped, params.currentExpiresAt.getTime());
  return {
    expiresAt: new Date(next),
    extended: next > params.currentExpiresAt.getTime(),
  };
}

// ---------------------------------------------------------------------------
// Inputs (Zod)
// ---------------------------------------------------------------------------

const reportInputSchema = z
  .object({
    type: z.enum(INCIDENT_TYPES),
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    note: z.string().trim().max(MAX_NOTE_LENGTH).optional(),
  })
  .strict();

const listNearbyInputSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    radiusMeters: z.number().positive().optional(),
  })
  .strict();

// Firestore-safe document id: reject path separators and the `.`/`..` segments
// so `incidents.doc(incidentId)` can't throw and turn a bad request into an
// internal error (mirrors vehicleIdSchema in garage/garage-core.ts).
const incidentIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(200)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const removeInputSchema = z.object({ incidentId: incidentIdSchema }).strict();

const confirmInputSchema = z.object({ incidentId: incidentIdSchema }).strict();

export type ReportInput = z.infer<typeof reportInputSchema>;
export type ListNearbyInput = z.infer<typeof listNearbyInputSchema>;
export type RemoveInput = z.infer<typeof removeInputSchema>;
export type ConfirmInput = z.infer<typeof confirmInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseReportInput = (d: unknown) =>
  parse(
    reportInputSchema,
    d,
    'Expected { type: accident|roadwork|hazard|police|road_closed, latitude, longitude, note? }.',
  );
export const parseListNearbyInput = (d: unknown) =>
  parse(listNearbyInputSchema, d, 'Expected { latitude, longitude, radiusMeters? }.');
export const parseRemoveInput = (d: unknown) =>
  parse(removeInputSchema, d, 'Expected { incidentId }.');
export const parseConfirmInput = (d: unknown) =>
  parse(confirmInputSchema, d, 'Expected { incidentId }.');

// ---------------------------------------------------------------------------
// Builders
// ---------------------------------------------------------------------------

/**
 * The non-timestamp portion of an `incidents/{id}` document. The callable adds
 * `createdAt` (server timestamp) and `expiresAt` (a Firestore Timestamp) so
 * this stays a pure, testable object.
 */
export interface IncidentFields {
  type: IncidentType;
  latitude: number;
  longitude: number;
  geoCell: string;
  status: typeof INCIDENT_ACTIVE_STATUS;
  source: IncidentSource;
  reporterUid: string | null;
  note: string | null;
}

export function buildIncidentFields(params: {
  type: IncidentType;
  latitude: number;
  longitude: number;
  source: IncidentSource;
  reporterUid: string | null;
  note?: string | null;
}): IncidentFields {
  const note = params.note?.trim();
  return {
    type: params.type,
    latitude: params.latitude,
    longitude: params.longitude,
    geoCell: geoCellKey(params.latitude, params.longitude),
    status: INCIDENT_ACTIVE_STATUS,
    source: params.source,
    reporterUid: params.reporterUid,
    note: note && note.length > 0 ? note : null,
  };
}

/** Shape returned to clients by `incident.report` / `incident.listNearby`. */
export interface IncidentView {
  id: string;
  type: IncidentType;
  latitude: number;
  longitude: number;
  source: IncidentSource;
  reporterUid: string | null;
  note: string | null;
  createdAt: string | null;
  expiresAt: string | null;
  /** How many OTHER members have confirmed it is still there (0 when none). */
  confirmationCount: number;
}

// ---------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------

/** Defensive coordinate re-check (Zod bounds already applied; belt-and-braces). */
export function isReportable(latitude: number, longitude: number): boolean {
  return isValidCoordinate(latitude, longitude);
}
