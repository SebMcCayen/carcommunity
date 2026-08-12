/**
 * Saved drives domain — pure input validation and document builders
 * (Phase 9d).
 *
 * Ports the legacy semantics of services/api/src/lib/saved-drive-service.ts
 * to the Firestore + Cloud Storage model
 * (docs/migration/backend-domain-mapping.md "Saved drives → Firestore +
 * Cloud Storage"):
 *
 * - `rides/{rideId}` holds summary metadata plus a ~64-point route THUMBNAIL
 *   (routeThumbnail, an encoded polyline). The full route GPS track is still a
 *   client-uploaded Cloud Storage file under `rideRoutes/{uid}/{rideId}/` and is
 *   still never stored in Firestore — the thumbnail is a deliberate, bounded
 *   exception so the History list can draw a drive's shape with no extra read
 *   (route-thumbnail.ts explains the trade).
 * - The drives.save callable computes distanceMeters / durationSeconds /
 *   averageSpeedMetersPerSecond / maxSpeedMetersPerSecond server-side from the
 *   submitted points (drive-calculations.ts) — clients never write stats.
 * - Saving requires an active member; owners keep list/read/delete access to
 *   drives saved during a previous membership (route files stay
 *   member-gated in Storage rules).
 * - Repeat saves for the same recording are idempotent via an optional
 *   client-supplied sourceSessionId (legacy sourceLiveLocationSessionId
 *   dedupe parity).
 * - Maximum speed IS now stored and returned, as `maxSpeedMetersPerSecond`,
 *   derived server-side at save time from the submitted points. This module
 *   previously said "No top-speed field is ever stored or returned"; that was
 *   reversed by an explicit product decision (2026-07) and the old wording is
 *   replaced rather than removed silently, so the change is visible in the
 *   history of this file. The no-speed-gamification rule is untouched: the
 *   figure is a neutral stat shown beside distance and duration, never a
 *   record, ranking or achievement (docs/gamification-system.md).
 *   Drives saved BEFORE the decision have no such field and are not
 *   backfilled — clients render their missing-value placeholder.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import {
  averageSpeedMps,
  driveDurationSeconds,
  maxSpeedMps,
  totalDistanceMetres,
} from './drive-calculations';
import { buildRouteThumbnail } from './route-thumbnail';

export const DRIVE_TITLE_MAX_LENGTH = 200;
/** Bound the submitted track: ~5.5 h at 1 Hz. Clients downsample beyond it. */
export const MAX_ROUTE_POINTS = 20_000;
export const SOURCE_SESSION_ID_MAX_LENGTH = 128;

// ---------------------------------------------------------------------------
// Input schemas
// ---------------------------------------------------------------------------

const routePointSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    /** Unix timestamp in milliseconds. */
    timestampMs: z.number().int().positive(),
  })
  .strict();

const saveDriveInputSchema = z
  .object({
    title: z.string().trim().min(1).max(DRIVE_TITLE_MAX_LENGTH).optional(),
    startedAt: z.string().datetime(),
    endedAt: z.string().datetime(),
    routePoints: z.array(routePointSchema).max(MAX_ROUTE_POINTS).optional(),
    /**
     * Client recording identifier for idempotent retries — repeat saves with
     * the same sourceSessionId return the existing drive.
     */
    sourceSessionId: z
      .string()
      .trim()
      .min(1)
      .max(SOURCE_SESSION_ID_MAX_LENGTH)
      .regex(/^[A-Za-z0-9._-]+$/)
      .optional(),
    /**
     * The garage car this drive was driven in (the one chosen in the "Start
     * driving" picker). Both optional and backward-compatible — drives saved by
     * older clients simply omit them. `vehicleId` links back to the car;
     * `carImagePath` is the denormalized cover-photo Storage path the History
     * card renders as a round photo, so the card needs no extra vehicle read.
     */
    vehicleId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      // Firestore-safe id (matches rideIdSchema below and garage-core's vehicle
      // ids): no path separators or reserved '.'/'..', so a bad value is rejected
      // here rather than stored as an unresolvable reference.
      .regex(/^[A-Za-z0-9._-]+$/)
      .refine((id) => id !== '.' && id !== '..')
      .optional(),
    carImagePath: z.string().trim().min(1).max(500).optional(),
  })
  .strict();

// Firestore-safe document ID: no path separators or exotic characters, so a
// bad value fails as invalid-argument instead of throwing inside doc().
// Dots are allowed (sourceSessionId permits them and deterministic ride IDs
// embed it), but the reserved '.'/'..' IDs are rejected.
const rideIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const deleteDriveInputSchema = z.object({ rideId: rideIdSchema }).strict();

export type RoutePointInput = z.infer<typeof routePointSchema>;
export type SaveDriveInput = z.infer<typeof saveDriveInputSchema>;
export type DeleteDriveInput = z.infer<typeof deleteDriveInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parseSaveDriveInput(data: unknown): ParseResult<SaveDriveInput> {
  return parse(
    saveDriveInputSchema,
    data,
    'Expected saveDriveRequest (contracts/schemas/saved-drives.schema.json): { startedAt, endedAt, title?, routePoints?, sourceSessionId? }.',
  );
}

export function parseDeleteDriveInput(data: unknown): ParseResult<DeleteDriveInput> {
  return parse(deleteDriveInputSchema, data, 'Expected { rideId }.');
}

// ---------------------------------------------------------------------------
// Guards
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'invalid-argument'; message: string };

export function guardDriveTimes(startedAt: string, endedAt: string): GuardResult {
  if (new Date(endedAt).getTime() <= new Date(startedAt).getTime()) {
    return { ok: false, code: 'invalid-argument', message: 'endedAt must be after startedAt.' };
  }
  return { ok: true };
}

/**
 * Route points must be ordered by timestampMs. Points are NOT required to
 * fall inside the startedAt..endedAt window — the distance calculation
 * already discards implausible segments, and GPS clocks can straddle the
 * user-visible start/stop moments slightly.
 */
export function guardRoutePoints(
  points: readonly RoutePointInput[] | undefined,
): GuardResult {
  if (!points || points.length === 0) {
    return { ok: true };
  }
  for (let i = 1; i < points.length; i += 1) {
    if ((points[i] as RoutePointInput).timestampMs < (points[i - 1] as RoutePointInput).timestampMs) {
      return {
        ok: false,
        code: 'invalid-argument',
        message: 'routePoints must be ordered by timestampMs.',
      };
    }
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Canonical Cloud Storage paths (mapping + firebase/storage.rules)
// ---------------------------------------------------------------------------

export function rideRoutePath(uid: string, rideId: string): string {
  return `rideRoutes/${uid}/${rideId}/route.bin`;
}

export function ridePreviewPath(uid: string, rideId: string): string {
  return `rideRoutes/${uid}/${rideId}/preview.png`;
}

/** Storage prefix removed when a drive is deleted. */
export function rideStoragePrefix(uid: string, rideId: string): string {
  return `rideRoutes/${uid}/${rideId}/`;
}

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

export interface DriveStats {
  durationSeconds: number;
  distanceMeters: number | null;
  averageSpeedMetersPerSecond: number | null;
  /**
   * Highest plausible instantaneous speed over the submitted points, or null
   * for a summary-only save. Neutral factual stat — see the module header on
   * why this exists and what it must never become.
   */
  maxSpeedMetersPerSecond: number | null;
}

/**
 * Server-side stats from the submitted recording. Distance, average speed and
 * maximum speed are null for summary-only saves (no points) — legacy parity,
 * and null so clients show a missing-value dash rather than a false 0.
 */
export function computeDriveStats(input: SaveDriveInput): DriveStats {
  const durationSeconds = driveDurationSeconds(new Date(input.startedAt), new Date(input.endedAt));
  if (!input.routePoints || input.routePoints.length < 2) {
    return {
      durationSeconds,
      distanceMeters: null,
      averageSpeedMetersPerSecond: null,
      maxSpeedMetersPerSecond: null,
    };
  }
  const distanceMeters = totalDistanceMetres(input.routePoints);
  return {
    durationSeconds,
    distanceMeters,
    averageSpeedMetersPerSecond: averageSpeedMps(distanceMeters, durationSeconds),
    maxSpeedMetersPerSecond: maxSpeedMps(input.routePoints),
  };
}

/**
 * The stored `routeThumbnail` for a save, or null when the recording has no
 * drawable shape. Re-exported through this module so callables build a ride
 * document from one place (see route-thumbnail.ts).
 */
export function computeRouteThumbnail(input: SaveDriveInput): string | null {
  return buildRouteThumbnail(input.routePoints);
}

/** rides/{rideId} document (docs/firebase-data-model.md). */
export function buildRideDocument(
  input: SaveDriveInput,
  context: {
    userId: string;
    rideId: string;
    stats: DriveStats;
    /** Encoded ~64-point route overview, or null when there is none to draw. */
    routeThumbnail: string | null;
  },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    userId: context.userId,
    title: input.title ?? null,
    distanceMeters: context.stats.distanceMeters,
    durationSeconds: context.stats.durationSeconds,
    averageSpeedMetersPerSecond: context.stats.averageSpeedMetersPerSecond,
    maxSpeedMetersPerSecond: context.stats.maxSpeedMetersPerSecond,
    routeThumbnail: context.routeThumbnail,
    startedAt: new Date(input.startedAt),
    endedAt: new Date(input.endedAt),
    routePath: rideRoutePath(context.userId, context.rideId),
    previewImagePath: ridePreviewPath(context.userId, context.rideId),
    sourceSessionId: input.sourceSessionId ?? null,
    // Which car this drive was driven in — null on saves from older clients or
    // when no car was chosen. carImagePath is what the History card renders.
    vehicleId: input.vehicleId ?? null,
    carImagePath: input.carImagePath ?? null,
    createdAt: serverTimestamp(),
  };
}
