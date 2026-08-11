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
 * The accepted duration keys, derived from {@link LIVE_SESSION_DURATIONS} so the
 * start-session schema and the map cannot drift: add or remove a key in ONE place
 * and both the numeric window and the accepted input follow. Typed as a non-empty
 * tuple because that is the shape `z.enum` requires.
 */
export const LIVE_SESSION_DURATION_KEYS = Object.keys(LIVE_SESSION_DURATIONS) as [
  LiveSessionDuration,
  ...LiveSessionDuration[],
];

/**
 * Cost/data control constant for live-location sessions — the SERVER copy of the
 * 6h cap. The Android client keeps its own copy (`LiveLocation.LIVE_SESSION_MAX_MS`
 * plus the `location/` service constants); the two cannot literally share a
 * constant across the TS/Kotlin line, so each side documents the other and the
 * agreement is asserted by tests (functions/src/live/live-core.test.ts here,
 * `LiveLocationTest` on the client). Seb-approved value, kept as a named constant
 * so it stays retunable in one place.
 *
 * LIVE_SESSION_MAX_MS is the ABSOLUTE ceiling on any one sharing window (single
 * AND convoy — a convoy member shares through the very same session node), and is
 * also the window every current client starts with: a session simply runs to 6h
 * and auto-stops, with no prompt to prolong it. No `expiresAt` this backend ever
 * writes — at start OR on the retained backward-compatible extend — may be more
 * than this far past `now`, so a forgotten phone always stops within one window.
 */
export const LIVE_SESSION_MAX_MS = 6 * 60 * 60 * 1000; // 6 hours

/**
 * Legacy: how long before `expiresAt` an OLDER client used to show the "still
 * sharing? continue?" extend prompt. The current client no longer prompts to
 * prolong — a session just runs to the 6h cap and auto-stops — so this is retained
 * only to document the window the still-deployed backward-compatible
 * `extendSession` callable serves. Not read by the current app.
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
  .object({
    duration: z.enum(LIVE_SESSION_DURATION_KEYS),
    // Which garage car the sharer is driving this session. Optional: the start
    // paths fall back to the main car (then the first car, then no car) when it
    // is absent or no longer owned — see pickSessionVehicleData. Bounded like a
    // Firestore doc id; a stray value simply fails the ownership match and falls
    // back rather than erroring the start.
    vehicleId: z.string().trim().min(1).max(300).optional(),
  })
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
  parse(
    startSessionInputSchema,
    d,
    `Expected { duration: ${LIVE_SESSION_DURATION_KEYS.join('|')} }.`,
  );
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

/**
 * Picks WHICH garage car a live session denormalizes, from the caller's owned
 * vehicles. The order matches the product rule the "Start driving" picker
 * follows on the client so the two never disagree:
 *
 *   1. the explicitly chosen `vehicleId`, when it names a car the caller still
 *      owns (a stale/foreign id is ignored, not honoured, and never errors);
 *   2. otherwise the car flagged `isMainCar`;
 *   3. otherwise the first owned car;
 *   4. no cars → null (the session carries no car, the generic marker).
 *
 * Pure over the decoded docs so the selection is unit-testable without Firestore.
 * Returns the whole `{ id, data }` ENTRY (not just `data`) so the caller reads
 * BOTH the id it denormalizes (the drive record links to it) AND the data it runs
 * through {@link toLiveMainCar} from ONE selection — the id and the projection can
 * never come from different cars, and neither depends on object identity.
 */
export function pickSessionVehicle(
  vehicles: ReadonlyArray<{ id: string; data: Record<string, unknown> }>,
  vehicleId?: string | null,
): { id: string; data: Record<string, unknown> } | null {
  const first = vehicles[0];
  if (!first) {
    return null;
  }
  if (vehicleId) {
    const chosen = vehicles.find((v) => v.id === vehicleId);
    if (chosen) {
      return chosen;
    }
  }
  const main = vehicles.find((v) => v.data.isMainCar === true);
  if (main) {
    return main;
  }
  return first;
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
   * The garage-vehicle id the sharer is driving this session (the car chosen in
   * the "Start driving" picker, or the resolved main/first-car fallback), or null
   * when they have no car. A start-time snapshot like {@link mainCar}. NOT read by
   * viewers or written to the marker — it exists so the client can stamp WHICH car
   * a drive was driven in onto the saved ride (for the History card) without
   * exposing it on the public marker.
   */
  vehicleId?: string | null;
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
   * Set once the scheduled sweep has FINALIZED this convoy-auto session into a
   * saved ride (or confirmed the client already saved one) — see
   * decideConvoyRideFinalize and live.cleanupExpired. It is the idempotency
   * marker that stops the every-5-minute sweep from re-processing the same
   * stopped session forever. Absent/false means "not yet considered".
   */
  convoyRideFinalized?: boolean;
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
  vehicleId: string | null = null,
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
    vehicleId,
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

/**
 * How long the scheduled sweep WAITS after a convoy-auto session ends before it
 * writes the server-side baseline ride. This grace window is what makes the
 * finalize race-free: a member whose app is alive at session end saves the RICH
 * route drive within seconds (keyed on the same session id), and only if that
 * has NOT happened by the time this window elapses does the server write the
 * duration-only fallback — so the common foreground case is never downgraded.
 * Comfortably larger than a client save's round-trip, and well inside the
 * 5-minute sweep cadence.
 */
export const CONVOY_RIDE_FINALIZE_GRACE_MS = 3 * 60 * 1000;

/** Why a session was (not) chosen for a server-side convoy-ride finalize. */
export type ConvoyRideFinalizeSkip =
  | 'not-convoy-auto'
  | 'already-finalized'
  | 'not-ended'
  | 'missing-times'
  | 'nonpositive-duration'
  | 'within-grace';

export interface ConvoyRideFinalizeDecision {
  finalize: boolean;
  /** Present only when finalize is false. */
  skip?: ConvoyRideFinalizeSkip;
  /** The ride's startedAt/endedAt (ISO), present only when finalize is true. */
  startedAt?: string;
  endedAt?: string;
}

/**
 * Decides whether the scheduled sweep should FINALIZE a live session into a
 * server-side "convoy run" ride — the reliability backstop for issue "members'
 * runs are not saved when the host stops the convoy".
 *
 * The member's own client only saves the drive while its Compose UI is alive to
 * observe the session stop; a member whose app is backgrounded-and-destroyed or
 * killed while driving therefore loses the run entirely. This decision lets the
 * server persist a duration-only baseline for exactly those sessions, WITHOUT
 * duplicating or downgrading the client's richer save:
 *  - only convoy-AUTO sessions (a manual solo session is ended by the user IN
 *    the app, so their client is alive to save it);
 *  - only ENDED ones (stopped by convoy.end / convoy.leave, or expired by the
 *    TTL sweep) with a real positive duration;
 *  - only after CONVOY_RIDE_FINALIZE_GRACE_MS, so a live client's save (keyed on
 *    the SAME session id → same ride doc) lands first and the server write
 *    becomes an idempotent no-op;
 *  - never twice (the convoyRideFinalized marker).
 *
 * Pure so the policy is unit-tested off the scheduler. The caller still checks
 * Firestore for an existing ride before writing — this only gates WHICH sessions
 * are even considered.
 */
export function decideConvoyRideFinalize(
  session: LiveSession | null | undefined,
  now: Date,
  graceMs: number = CONVOY_RIDE_FINALIZE_GRACE_MS,
): ConvoyRideFinalizeDecision {
  if (!session || session.convoyAutoStarted !== true) {
    return { finalize: false, skip: 'not-convoy-auto' };
  }
  if (session.convoyRideFinalized === true) {
    return { finalize: false, skip: 'already-finalized' };
  }
  // 'stopped' (convoy.end / convoy.leave) or 'expired' (6h TTL sweep) — an
  // 'active' session is still being driven and has nothing to finalize yet.
  if (session.status !== 'stopped' && session.status !== 'expired') {
    return { finalize: false, skip: 'not-ended' };
  }
  const startedMs = Date.parse(session.startedAt);
  const endedMs = session.stoppedAt ? Date.parse(session.stoppedAt) : NaN;
  if (!Number.isFinite(startedMs) || !Number.isFinite(endedMs)) {
    return { finalize: false, skip: 'missing-times' };
  }
  if (endedMs <= startedMs) {
    return { finalize: false, skip: 'nonpositive-duration' };
  }
  if (now.getTime() - endedMs < graceMs) {
    return { finalize: false, skip: 'within-grace' };
  }
  return { finalize: true, startedAt: session.startedAt, endedAt: session.stoppedAt! };
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
