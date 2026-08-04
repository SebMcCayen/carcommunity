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
 * - `liveLocation/{uid}/latest` — the marker node read by any signed-in,
 *   non-suspended VIEWER who is not blocked by (or blocking) the sharer.
 *   The read rule does NOT currently require an activeMember claim: member
 *   gating is disabled repo-wide (shared/memberGating.ts) and the RTDB rule
 *   (database.rules.json) deliberately encodes only the suspended + blocking
 *   checks — a paid-viewing gate is added to that rule when viewing is
 *   re-locked. Kept lean; carries the denormalized displayName and session
 *   expiry so clients can render markers without extra reads. NO client
 *   writes — positions flow through live.updatePosition, which enforces the
 *   active session and the contract's 60-second staleness threshold, and
 *   also refreshes the queryable nearby-discovery index (see nearby-core.ts).
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

import { MIN_MODEL_YEAR, maxModelYear } from '../garage/garage-core';

// '6h' is the live-session window every CURRENT client starts with (single AND
// convoy) — it equals LIVE_SESSION_MAX_MS, so a session simply runs to the 6h
// hard cap and auto-stops, with no "prolong/extend" prompt in between. The
// shorter 1h/2h/4h keys are retained for BACKWARD COMPATIBILITY: sessions
// already stored in RTDB by older app builds carry them, and an older client
// still in the wild may pass one to live.startSession. Every key is bounded by
// the same 6h cap (clampExpiryToCap in buildSession), so accepting them is safe.
export const LIVE_SESSION_DURATIONS = { '1h': 1, '2h': 2, '4h': 4, '6h': 6 } as const;
export type LiveSessionDuration = keyof typeof LIVE_SESSION_DURATIONS;

/**
 * Cost/data control constants for live-location sessions — the SERVER copies of
 * the shared timeframes. The Android client keeps its own copies
 * (`LiveLocation.LIVE_SESSION_MAX_MS` / `LIVE_SESSION_EXTEND_PROMPT_MS` and the
 * `location/` service constants); the two boundaries cannot literally share a
 * constant across the TS/Kotlin line, so each side documents the other and the
 * agreement is asserted by tests (functions/src/live/live-core.test.ts here,
 * `LiveLocationTest` on the client). Seb-approved values, kept as named
 * constants so they stay retunable in one place.
 *
 * LIVE_SESSION_MAX_MS is the ABSOLUTE ceiling on any one sharing window (single
 * AND convoy — a convoy member shares through the very same session node). No
 * `expiresAt` this backend ever writes — at start OR on extend — may be more
 * than this far past `now`. A forgotten phone therefore always stops within one
 * window; continuing past it requires a deliberate human "yes" (see
 * `extendSession`), which grants a fresh capped window.
 */
export const LIVE_SESSION_MAX_MS = 6 * 60 * 60 * 1000; // 6 hours

/**
 * How long before `expiresAt` the client shows the "still sharing? continue?"
 * extend prompt. Server-side only in that the server documents it and the client
 * mirrors it; the prompt itself is a client concern. 15 min before a 6h window
 * is the "5h45" checkpoint Seb specified.
 */
export const LIVE_SESSION_EXTEND_PROMPT_MS = 15 * 60 * 1000; // 15 minutes

/**
 * Clamps a proposed expiry (epoch millis) to the 6h hard cap measured from
 * `nowMs`. The one place the ceiling is enforced numerically, so start AND
 * extend agree and a client can never request a longer window than the cap.
 */
export function clampExpiryToCap(nowMs: number, requestedExpiryMs: number): number {
  return Math.min(requestedExpiryMs, nowMs + LIVE_SESSION_MAX_MS);
}

/**
 * The expiry an extend grants: a FRESH full capped window from `now` (exactly
 * `now + LIVE_SESSION_MAX_MS`), as an ISO string. Extending resets the clock to
 * a new 6h window rather than nudging the old expiry, so (a) each extended
 * window re-prompts 15 min before its own end — the "5h45 → prompt, 6h → stop"
 * cadence — and (b) "no unbounded session" holds because every 6h a human must
 * reconfirm. Passed through {@link clampExpiryToCap} defensively; it is already
 * the cap.
 */
export function extendedExpiryIso(now: Date): string {
  return new Date(clampExpiryToCap(now.getTime(), now.getTime() + LIVE_SESSION_MAX_MS)).toISOString();
}

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
  .object({ duration: z.enum(['1h', '2h', '4h', '6h']) })
  .strict();

const updatePositionInputSchema = z.object({ coordinate: coordinateSchema }).strict();

const stopSessionInputSchema = z
  .object({ reason: z.enum(LIVE_STOP_REASONS).optional() })
  .strict();

// Extending takes no client-controlled fields: the server computes the new
// expiry as a fresh capped window (see extendedExpiryIso), so the client cannot
// request a longer one. Strict rejects any stray payload.
const extendSessionInputSchema = z.object({}).strict();

export type LiveCoordinate = z.infer<typeof coordinateSchema>;
export type StartSessionInput = z.infer<typeof startSessionInputSchema>;
export type UpdatePositionInput = z.infer<typeof updatePositionInputSchema>;
export type StopSessionInput = z.infer<typeof stopSessionInputSchema>;
export type ExtendSessionInput = z.infer<typeof extendSessionInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseStartSessionInput = (d: unknown) =>
  parse(startSessionInputSchema, d, 'Expected { duration: 1h|2h|4h|6h }.');
export const parseUpdatePositionInput = (d: unknown) =>
  parse(
    updatePositionInputSchema,
    d,
    'Expected { coordinate: { latitude, longitude, recordedAt, accuracyMeters?, headingDegrees?, speedMetersPerSecond? } }.',
  );
export const parseStopSessionInput = (d: unknown) =>
  parse(stopSessionInputSchema, d, 'Expected { reason?: user_stop|hide_me_now|admin_stop }.');
export const parseExtendSessionInput = (d: unknown) =>
  parse(extendSessionInputSchema, d, 'Expected {} (extend takes no arguments).');

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
 * display-safe fields are carried. VIN stays unrepresentable on the vehicles
 * schema, but `registrationPlate` is now a real (deliberately public) field on
 * `vehicles/{id}` — so keeping it OFF the live marker is an ENFORCED projection
 * choice here, not a schema impossibility. A live marker follows the sharer
 * around a map in real time; pairing a plate with a live position is a much
 * stronger disclosure than showing the plate on a static car profile, so
 * toLiveMainCar deliberately does not carry it (guard test in
 * functions/src/__tests__/live-core.test.ts). imagePath points into the owner's
 * public-readable vehicleImages/ prefix.
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
 * imagePath are projected — every other field on the source doc, including the
 * public `registrationPlate`, is dropped on purpose.
 *
 * `modelYear` must be a FINITE INTEGER within the same bounds the garage
 * validation enforces on write (MIN_MODEL_YEAR..maxModelYear). NaN/Infinity or
 * a non-integer can't be represented reliably in RTDB (Android reads it as a
 * Long) and would make startSession/marker writes fail or viewers drop the
 * mainCar, so a doc with such a year is treated as malformed and rejected.
 */
export function toLiveMainCar(
  data: Record<string, unknown> | undefined | null,
  now: Date = new Date(),
): LiveMainCar | null {
  if (!data) {
    return null;
  }
  const make = data.make;
  const model = data.model;
  const modelYear = data.modelYear;
  if (typeof make !== 'string' || typeof model !== 'string' || typeof modelYear !== 'number') {
    return null;
  }
  if (
    !Number.isInteger(modelYear) ||
    modelYear < MIN_MODEL_YEAR ||
    modelYear > maxModelYear(now)
  ) {
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
  /**
   * True when this session was auto-started BY a convoy (convoy.start /
   * convoy.respond-accept) rather than by the user tapping "share live". Absent
   * on manually-started sessions. Used purely for TEARDOWN: convoy.leave /
   * convoy.end stop only the session they auto-started (this flag + a matching
   * convoyId), so a live session the user started manually for their own reasons
   * is never killed when a convoy tears down. NOT read by viewers — the marker
   * (liveLocation/{uid}/latest) is unchanged, so it does not touch the read path.
   */
  convoyAutoStarted?: boolean;
  /**
   * The convoy that auto-started this session, when convoyAutoStarted is true.
   * Scopes teardown to the RIGHT convoy and lets a future convoy-scoped audience
   * filter markers by convoy WITHOUT changing anything today.
   */
  convoyId?: string;
  /**
   * Throttle state for the Firestore nearby-discovery doc (see
   * shouldRefreshDiscovery in nearby-core.ts). Written by updatePosition when it
   * refreshes the discovery doc; absent until the first refresh. Not a session
   * lifecycle field — purely bookkeeping so the (frequent) position-update path
   * can skip a Firestore write without an extra read.
   */
  discoveryRefreshedAt?: string | null;
  discoveryGeoCell?: string | null;
  /**
   * Throttle state for the Kronjakt auto-spawn ACTIVITY AGGREGATE (see
   * shouldRecordCrownActivity in crownHunt/crown-spawn-core.ts). Same shape and
   * same reasoning as the discovery pair above — bookkeeping that lets the
   * position-update path skip a write without an extra read — but on its own
   * (much slower) interval and its own, much finer spawn grid, so the two
   * throttles cannot drag each other. Absent until the first activity write.
   */
  crownActivityAt?: string | null;
  crownActivityCell?: string | null;
}

export function buildSession(
  id: string,
  duration: LiveSessionDuration,
  now: Date,
  displayName: string | null,
  mainCar: LiveMainCar | null = null,
): LiveSession {
  // The chosen window, clamped to the 6h hard cap. The current client always
  // passes '6h', which equals the cap, so clampExpiryToCap is exactly the
  // identity there; the retained shorter keys sit under it. Either way this keeps
  // the invariant "no expiresAt is ever more than LIVE_SESSION_MAX_MS past now"
  // true at the one place expiries are minted — matching extendSession.
  const expires = new Date(
    clampExpiryToCap(now.getTime(), now.getTime() + LIVE_SESSION_DURATIONS[duration] * 60 * 60 * 1000),
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
