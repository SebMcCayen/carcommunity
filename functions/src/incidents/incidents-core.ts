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
 *                    a scheduled sweep deletes docs past it.
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
export const INCIDENT_TYPES = [
  'accident',
  'roadwork',
  'hazard',
  'police',
  'road_closed',
] as const;
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

/** Expiry instant for a freshly-reported incident of `type`. */
export function expiryFor(type: IncidentType, now: Date): Date {
  return new Date(now.getTime() + INCIDENT_TTL_MS[type]);
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

export type ReportInput = z.infer<typeof reportInputSchema>;
export type ListNearbyInput = z.infer<typeof listNearbyInputSchema>;
export type RemoveInput = z.infer<typeof removeInputSchema>;

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
}

// ---------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------

/** Defensive coordinate re-check (Zod bounds already applied; belt-and-braces). */
export function isReportable(latitude: number, longitude: number): boolean {
  return isValidCoordinate(latitude, longitude);
}
