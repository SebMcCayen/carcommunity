/**
 * Saved drives domain — pure input validation and document builders
 * (Phase 9d).
 *
 * Ports the legacy semantics of services/api/src/lib/saved-drive-service.ts
 * to the Firestore + Cloud Storage model
 * (docs/migration/backend-domain-mapping.md "Saved drives → Firestore +
 * Cloud Storage"):
 *
 * - `rides/{rideId}` holds summary metadata only; route GPS data is a
 *   client-uploaded Cloud Storage file under `rideRoutes/{uid}/{rideId}/`
 *   and is never stored in Firestore.
 * - The drives.save callable computes distanceMeters / durationSeconds /
 *   averageSpeedMetersPerSecond server-side from the submitted points
 *   (drive-calculations.ts) — clients never write stats.
 * - Saving requires an active member; owners keep list/read/delete access to
 *   drives saved during a previous membership (route files stay
 *   member-gated in Storage rules).
 * - Repeat saves for the same recording are idempotent via an optional
 *   client-supplied sourceSessionId (legacy sourceLiveLocationSessionId
 *   dedupe parity).
 * - No top-speed field is ever stored or returned.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import { averageSpeedMps, driveDurationSeconds, totalDistanceMetres } from './drive-calculations';

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
  })
  .strict();

const deleteDriveInputSchema = z.object({ rideId: z.string().trim().min(1) }).strict();

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

/** Route points must be ordered by timestamp and lie within the drive window. */
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
}

/**
 * Server-side stats from the submitted recording. Distance and average
 * speed are null for summary-only saves (no points) — legacy parity.
 */
export function computeDriveStats(input: SaveDriveInput): DriveStats {
  const durationSeconds = driveDurationSeconds(new Date(input.startedAt), new Date(input.endedAt));
  if (!input.routePoints || input.routePoints.length < 2) {
    return { durationSeconds, distanceMeters: null, averageSpeedMetersPerSecond: null };
  }
  const distanceMeters = totalDistanceMetres(input.routePoints);
  return {
    durationSeconds,
    distanceMeters,
    averageSpeedMetersPerSecond: averageSpeedMps(distanceMeters, durationSeconds),
  };
}

/** rides/{rideId} document (docs/firebase-data-model.md). */
export function buildRideDocument(
  input: SaveDriveInput,
  context: { userId: string; rideId: string; stats: DriveStats },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    userId: context.userId,
    title: input.title ?? null,
    distanceMeters: context.stats.distanceMeters,
    durationSeconds: context.stats.durationSeconds,
    averageSpeedMetersPerSecond: context.stats.averageSpeedMetersPerSecond,
    startedAt: new Date(input.startedAt),
    endedAt: new Date(input.endedAt),
    routePath: rideRoutePath(context.userId, context.rideId),
    previewImagePath: ridePreviewPath(context.userId, context.rideId),
    sourceSessionId: input.sourceSessionId ?? null,
    createdAt: serverTimestamp(),
  };
}
