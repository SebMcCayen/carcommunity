/**
 * Live location domain — constants, pure logic, and builders (Phase 10).
 *
 * The migration's first Realtime Database domain
 * (docs/migration/backend-domain-mapping.md, "Live location latest
 * position → Realtime Database"):
 *
 * - `liveLocation/{uid}/session` — backend-owned session state (id,
 *   status active|stopped|expired, duration 1h|2h|4h, startedAt,
 *   expiresAt, stoppedAt, stopReason). NO client writes.
 * - `liveLocation/{uid}/latest` — the marker node read by entitled
 *   members (activeMember claim, non-suspended). Kept lean; carries the
 *   denormalized displayName and session expiry so clients can render
 *   markers without extra reads. NO client writes — positions flow
 *   through live.updatePosition, which enforces the active session and
 *   the contract's 60-second staleness threshold.
 * - Presence lives at the top-level `/presence/{uid}` (client-managed
 *   onDisconnect), as implemented since Phase 3 and documented in the
 *   data model; the mapping's `liveLocation/{uid}/presence` sketch line
 *   is the same feature, consolidated there.
 * - TTL (mapping): sessions expire at startedAt + duration; a 5-minute
 *   scheduled sweep expires overdue sessions and removes `latest` nodes
 *   whose recordedAt is older than 15 minutes (client gone silent).
 * - "Hide me now" (privacy): stops the session and removes `latest`
 *   immediately; must work for ANY signed-in user, suspended included.
 * - Blocking IS enforced on marker reads: blocking-onBlockWrite mirrors the
 *   userBlocks graph into RTDB (liveLocationBlocks/), and the
 *   liveLocation/{uid}/latest read rule denies either party who has blocked
 *   the other. RTDB rules cannot read Firestore, hence the mirror.
 *
 * ISO-8601 UTC timestamps sort lexicographically, so RTDB string
 * comparisons on expiresAt/recordedAt are chronological.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

export const LIVE_SESSION_DURATIONS = { '1h': 1, '2h': 2, '4h': 4 } as const;
export type LiveSessionDuration = keyof typeof LIVE_SESSION_DURATIONS;

export const LIVE_SESSION_STATUSES = ['active', 'stopped', 'expired'] as const;
export type LiveSessionStatus = (typeof LIVE_SESSION_STATUSES)[number];

export const LIVE_STOP_REASONS = ['user_stop', 'hide_me_now', 'admin_stop'] as const;
export type LiveStopReason = (typeof LIVE_STOP_REASONS)[number];

/** Contract: positions older than this are rejected as stale. */
export const POSITION_STALENESS_SECONDS = 60;
/** Small allowance for client clock skew into the future. */
export const POSITION_FUTURE_SKEW_SECONDS = 30;
/** Mapping TTL: latest nodes go silent-stale after 15 minutes. */
export const LATEST_STALE_MINUTES = 15;

/** Feature flag key (contracts/features/feature-flags.json), default true. */
export const LIVE_LOCATION_FLAG_KEY = 'liveLocation';

// ---------------------------------------------------------------------------
// Inputs (contracts/schemas/live-location.schema.json)
// ---------------------------------------------------------------------------

const coordinateSchema = z
  .object({
    latitude: z.number().min(-90).max(90),
    longitude: z.number().min(-180).max(180),
    accuracyMeters: z.number().min(0).optional(),
    headingDegrees: z.number().min(0).max(360).optional(),
    speedMetersPerSecond: z.number().min(0).optional(),
    recordedAt: z.string().datetime(),
  })
  .strict();

const startSessionInputSchema = z
  .object({ duration: z.enum(['1h', '2h', '4h']) })
  .strict();

const updatePositionInputSchema = z.object({ coordinate: coordinateSchema }).strict();

const stopSessionInputSchema = z
  .object({ reason: z.enum(LIVE_STOP_REASONS).optional() })
  .strict();

export type LiveCoordinate = z.infer<typeof coordinateSchema>;
export type StartSessionInput = z.infer<typeof startSessionInputSchema>;
export type UpdatePositionInput = z.infer<typeof updatePositionInputSchema>;
export type StopSessionInput = z.infer<typeof stopSessionInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseStartSessionInput = (d: unknown) =>
  parse(startSessionInputSchema, d, 'Expected { duration: 1h|2h|4h }.');
export const parseUpdatePositionInput = (d: unknown) =>
  parse(
    updatePositionInputSchema,
    d,
    'Expected { coordinate: { latitude, longitude, recordedAt, accuracyMeters?, headingDegrees?, speedMetersPerSecond? } }.',
  );
export const parseStopSessionInput = (d: unknown) =>
  parse(stopSessionInputSchema, d, 'Expected { reason?: user_stop|hide_me_now|admin_stop }.');

// ---------------------------------------------------------------------------
// Guards and builders
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'invalid-argument' | 'failed-precondition'; message: string };

/** The contract's 60-second staleness threshold (plus bounded future skew). */
export function guardPositionFreshness(recordedAt: string, now: Date): GuardResult {
  const recorded = Date.parse(recordedAt);
  const ageSeconds = (now.getTime() - recorded) / 1000;
  if (ageSeconds > POSITION_STALENESS_SECONDS) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: `Position is stale (recordedAt older than ${POSITION_STALENESS_SECONDS}s).`,
    };
  }
  if (ageSeconds < -POSITION_FUTURE_SKEW_SECONDS) {
    return { ok: false, code: 'invalid-argument', message: 'Position recordedAt is in the future.' };
  }
  return { ok: true };
}

/**
 * A safe, PUBLIC-ish projection of the sharer's main car, denormalized onto the
 * live session so viewers of the live share can see which car it is. Only
 * display-safe fields are carried — never registration plates / VIN (which the
 * vehicles schema makes unrepresentable anyway). imagePath points into the
 * owner's public-readable vehicleImages/ prefix.
 */
export interface LiveMainCar {
  make: string;
  model: string;
  modelYear: number;
  imagePath: string | null;
}

/**
 * Builds a {@link LiveMainCar} from a raw `vehicles/{id}` document's data, or
 * null when the required display fields are missing/malformed. Pure so the
 * callable's main-car selection stays testable. Only make/model/modelYear/
 * imagePath are projected — nothing sensitive.
 */
export function toLiveMainCar(data: Record<string, unknown> | undefined | null): LiveMainCar | null {
  if (!data) {
    return null;
  }
  const make = data.make;
  const model = data.model;
  const modelYear = data.modelYear;
  if (typeof make !== 'string' || typeof model !== 'string' || typeof modelYear !== 'number') {
    return null;
  }
  const imagePath = typeof data.imagePath === 'string' ? data.imagePath : null;
  return { make, model, modelYear, imagePath };
}

export interface LiveSession {
  id: string;
  status: LiveSessionStatus;
  duration: LiveSessionDuration;
  startedAt: string;
  expiresAt: string;
  stoppedAt: string | null;
  stopReason?: LiveStopReason;
  /** Denormalized at session start so position updates need no extra read. */
  displayName: string | null;
  /**
   * The sharer's main car at session start (or null when they have none), so
   * viewers see which car the live marker is. Like displayName, it is a
   * start-time snapshot — changing the main car mid-session takes effect on the
   * next session start.
   */
  mainCar: LiveMainCar | null;
}

export function buildSession(
  id: string,
  duration: LiveSessionDuration,
  now: Date,
  displayName: string | null,
  mainCar: LiveMainCar | null = null,
): LiveSession {
  const expires = new Date(
    now.getTime() + LIVE_SESSION_DURATIONS[duration] * 60 * 60 * 1000,
  );
  return {
    id,
    status: 'active',
    duration,
    startedAt: now.toISOString(),
    expiresAt: expires.toISOString(),
    stoppedAt: null,
    displayName,
    mainCar,
  };
}

export function isSessionActive(
  session: Pick<LiveSession, 'status' | 'expiresAt'> | null | undefined,
  now: Date,
): boolean {
  return (
    !!session && session.status === 'active' && Date.parse(session.expiresAt) > now.getTime()
  );
}

/** liveLocation/{uid}/latest node — lean, marker-complete. */
export function buildLatestNode(
  coordinate: LiveCoordinate,
  session: Pick<LiveSession, 'id' | 'expiresAt' | 'displayName'> & {
    mainCar?: LiveMainCar | null;
  },
): Record<string, unknown> {
  return {
    latitude: coordinate.latitude,
    longitude: coordinate.longitude,
    accuracyMeters: coordinate.accuracyMeters ?? null,
    headingDegrees: coordinate.headingDegrees ?? null,
    speedMetersPerSecond: coordinate.speedMetersPerSecond ?? null,
    recordedAt: coordinate.recordedAt,
    sessionId: session.id,
    expiresAt: session.expiresAt,
    displayName: session.displayName,
    // Denormalized main car so viewers of the live share see which car it is.
    mainCar: session.mainCar ?? null,
  };
}

/**
 * Whether a marker's recordedAt is past the 15-minute silent-stale window.
 * Numeric comparison (Date.parse) — ISO strings are not guaranteed to be
 * canonical (offsets, missing milliseconds), so lexicographic ordering is
 * not safe across producers.
 */
export function isLatestStale(recordedAt: string, now: Date): boolean {
  const recorded = Date.parse(recordedAt);
  return (
    !Number.isNaN(recorded) &&
    recorded < now.getTime() - LATEST_STALE_MINUTES * 60 * 1000
  );
}
