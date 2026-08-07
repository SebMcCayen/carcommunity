/**
 * Kronjakt (Crown Hunt) domain — constants, pure logic, and builders
 * (Phase 9h).
 *
 * Ports packages/shared/src/crown-hunt.ts constants and the pure parts of
 * the legacy crown-hunt-service.ts to the Firestore model
 * (docs/migration/backend-domain-mapping.md "Kronjakt claims → callable
 * functions + Firestore transactions"):
 *
 * - `crownHuntPoints/{pointId}` — admin-managed reward points; members read
 *   active points directly.
 * - `crownHuntClaims/{claimId}` — every claim ATTEMPT is recorded; document
 *   ID = the SHA-256-scoped idempotency key, so a duplicate submission is a
 *   replay. Owner-readable history WITHOUT risk data.
 * - `crownHuntClaimRisk/{claimId}` — risk score/reasons live in a separate
 *   backend-only collection: rules cannot redact fields per-read, and risk
 *   thresholds/reasons must never reach mobile clients (legacy rule).
 * - Claims are never automatic; awards happen only when ALL validation
 *   steps pass AND risk is acceptable, atomically with the claim record via
 *   the 9g points-ledger transaction primitives.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';
import { isValidCoordinate } from './crown-hunt-geo';

// ---------------------------------------------------------------------------
// Constants (packages/shared/src/crown-hunt.ts + legacy service limits)
// ---------------------------------------------------------------------------

export const CROWN_HUNT_REPEAT_RULES = ['once', 'daily', 'weekly'] as const;
export type CrownHuntRepeatRule = (typeof CROWN_HUNT_REPEAT_RULES)[number];

export const CROWN_HUNT_POINT_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type CrownHuntPointStatus = (typeof CROWN_HUNT_POINT_STATUSES)[number];

export const CROWN_HUNT_CLAIM_RESULTS = [
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

export const MIN_GEOFENCE_RADIUS_METERS = 20;
export const MAX_GEOFENCE_RADIUS_METERS = 150;
export const MIN_REWARD_POINTS = 1;
export const MAX_REWARD_POINTS = 1_000;
export const MAX_TITLE_LENGTH = 100;
export const MAX_DESCRIPTION_LENGTH = 500;
/** Walking pace (~5 km/h): claiming is only allowed when safely stopped. */
export const MAX_CLAIM_SPEED_MPS = 1.4;
export const MAX_POSITION_AGE_SECONDS = 60;
/**
 * Largest horizontal GPS accuracy a claim may even REPORT (meters). Real
 * device fixes — including cell-tower-only ones — stay far below this; a
 * larger (or non-finite) value is malformed or hostile input and is rejected
 * as invalid-argument rather than silently buffering the geofence. Values
 * below this but still poor are clamped by MAX_GEOFENCE_ACCURACY_METERS in
 * crown-hunt-geo.ts and flagged by the risk scorer.
 */
export const MAX_REPORTED_ACCURACY_METERS = 10_000;

/** Legacy service limits. */
export const MAX_DAILY_SUCCESSFUL_CLAIMS = 10;

/** Feature flag key + contract default (contracts/features/feature-flags.json). */
export const CROWN_HUNT_FLAG_KEY = 'crownHunt';
export const CROWN_HUNT_FLAG_DEFAULT = true;

// ---------------------------------------------------------------------------
// Swedish claim result messages (legacy getClaimMessage, verbatim)
// ---------------------------------------------------------------------------

export function getClaimMessage(result: CrownHuntClaimResult): string {
  switch (result) {
    case 'awarded':
      return 'Belöningen har lagts till i ditt Kronpoäng-saldo.';
    case 'already_claimed':
      return 'Du har redan samlat in den här belöningen.';
    case 'outside_geofence':
      return 'Du är för långt från platsen.';
    case 'moving_too_fast':
      return 'Du rör dig för snabbt för att samla in. Stanna säkert innan du samlar in belöningen.';
    case 'position_too_old':
      return 'Din position är för gammal. Vänta en stund och försök igen.';
    case 'point_inactive':
      return 'Den här belöningspunkten är inte längre tillgänglig.';
    case 'cooldown_active':
      return 'Du behöver vänta lite innan du kan samla in igen.';
    case 'daily_limit_reached':
      return 'Du har nått dagens gräns för Kronjakt. Försök igen imorgon.';
    case 'risk_review':
      return 'Claimen behöver granskas och inga poäng har delats ut ännu.';
    case 'feature_disabled':
      return 'Kronjakt är för tillfället inte tillgängligt.';
    case 'not_eligible':
      return 'Du behöver ett aktivt Kronjakt-medlemskap för att delta.';
  }
}

// ---------------------------------------------------------------------------
// Idempotency scoping (legacy scopeClaimIdempotencyKey, verbatim)
// ---------------------------------------------------------------------------

/**
 * Scopes a client idempotency key to the user via SHA-256 — the hex digest
 * is the crownHuntClaims document ID (Firestore-safe by construction).
 */
export function scopeClaimIdempotencyKey(userId: string, idempotencyKey: string): string {
  return createHash('sha256').update(userId).update(':').update(idempotencyKey).digest('hex');
}

/** Ledger idempotency key for the claim's Kronpoäng award (Firestore-safe). */
export function claimLedgerIdempotencyKey(scopedKey: string): string {
  return `crown-hunt-claim_${scopedKey}`;
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const submitClaimInputSchema = z
  .object({
    pointId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .regex(/^[A-Za-z0-9._-]+$/)
      .refine((id) => id !== '.' && id !== '..'),
    latitude: z.number(),
    longitude: z.number(),
    // `.finite()` + `.max()`: accuracy is client-controlled and feeds the
    // geofence buffer, so Infinity/NaN and absurd magnitudes never reach the
    // geofence check (which additionally clamps what it accepts).
    accuracyMeters: z
      .number()
      .finite()
      .nonnegative()
      .max(MAX_REPORTED_ACCURACY_METERS)
      .nullable()
      .optional(),
    speedMetersPerSecond: z.number().finite().nonnegative().nullable().optional(),
    recordedAt: z.string().datetime(),
    idempotencyKey: z.string().trim().min(1).max(128),
    /** Platform integrity placeholder — null until native integration. */
    platformIntegrityPassed: z.boolean().nullable().optional(),
  })
  .strict();

export type SubmitClaimInput = z.infer<typeof submitClaimInputSchema>;

const pointFieldsSchema = z.object({
  // A Crown is a map COLLECTABLE (Pokémon GO–style), not a titled document, so
  // create/update accept no title or description. The stored crownHuntPoints
  // doc still carries title ('') and description (null) for reader back-compat
  // (see createPoint); MAX_TITLE_LENGTH / MAX_DESCRIPTION_LENGTH document those
  // stored-field caps.
  latitude: z.number(),
  longitude: z.number(),
  geofenceRadiusMeters: z
    .number()
    .min(MIN_GEOFENCE_RADIUS_METERS)
    .max(MAX_GEOFENCE_RADIUS_METERS),
  rewardPoints: z.number().int().min(MIN_REWARD_POINTS).max(MAX_REWARD_POINTS),
  repeatRule: z.enum(CROWN_HUNT_REPEAT_RULES),
  // Distinct-collector cap. null = unlimited (default, best for events); a
  // positive integer caps the headcount so the first N distinct collectors
  // succeed, then the point deactivates. Stored on the crownHuntPoints doc and
  // enforced INSIDE the award transaction (submitClaim). Absent on legacy
  // points → unlimited, for back-compat.
  maxCollectors: z.number().int().min(1).nullable().optional(),
  availableFrom: z.string().datetime().nullable().optional(),
  availableUntil: z.string().datetime().nullable().optional(),
});

const pointIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const createPointInputSchema = pointFieldsSchema.strict();

const updatePointInputSchema = pointFieldsSchema
  .partial()
  .extend({ pointId: pointIdSchema })
  .strict();

const activatePointInputSchema = z
  .object({
    pointId: pointIdSchema,
    /** Legacy safety gate: activation requires an explicit confirmation. */
    safeLocationConfirmed: z.literal(true),
    approvalNote: z.string().trim().min(3).max(2000),
  })
  .strict();

const pausePointInputSchema = z
  .object({
    pointId: pointIdSchema,
    reason: z.string().trim().max(2000).optional(),
  })
  .strict();

// Hard-delete a hand-placed point. Unlike pause/end this REMOVES the
// crownHuntPoints/{id} doc and its distinct-collector markers; it is allowed
// from ANY status (a live active crown is removed from the map the instant its
// doc is gone, since members read only status=='active'). Same optional-reason
// shape as pause, recorded in the audit entry.
const deletePointInputSchema = z
  .object({
    pointId: pointIdSchema,
    reason: z.string().trim().max(2000).optional(),
  })
  .strict();

export type CreatePointInput = z.infer<typeof createPointInputSchema>;
export type UpdatePointInput = z.infer<typeof updatePointInputSchema>;
export type ActivatePointInput = z.infer<typeof activatePointInputSchema>;
export type PausePointInput = z.infer<typeof pausePointInputSchema>;
export type DeletePointInput = z.infer<typeof deletePointInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export function parseSubmitClaimInput(data: unknown): ParseResult<SubmitClaimInput> {
  return parse(
    submitClaimInputSchema,
    data,
    'Expected submitClaimRequest (contracts/schemas/crown-hunt.schema.json): { pointId, latitude, longitude, recordedAt, idempotencyKey, accuracyMeters?, speedMetersPerSecond?, platformIntegrityPassed? }.',
  );
}

export function parseCreatePointInput(data: unknown): ParseResult<CreatePointInput> {
  return parse(
    createPointInputSchema,
    data,
    'Expected createPointRequest: { latitude, longitude, geofenceRadiusMeters (20-150), rewardPoints (1-1000), repeatRule, maxCollectors? (>=1 or null=unlimited), availableFrom?, availableUntil? }.',
  );
}

export function parseUpdatePointInput(data: unknown): ParseResult<UpdatePointInput> {
  return parse(updatePointInputSchema, data, 'Expected { pointId } plus updatePointRequest fields.');
}

export function parseActivatePointInput(data: unknown): ParseResult<ActivatePointInput> {
  return parse(
    activatePointInputSchema,
    data,
    'Expected { pointId, safeLocationConfirmed: true, approvalNote (>=3 chars) }.',
  );
}

export function parsePausePointInput(data: unknown): ParseResult<PausePointInput> {
  return parse(pausePointInputSchema, data, 'Expected { pointId, reason? }.');
}

export function parseDeletePointInput(data: unknown): ParseResult<DeletePointInput> {
  return parse(deletePointInputSchema, data, 'Expected { pointId, reason? }.');
}

// ---------------------------------------------------------------------------
// Guards (legacy validatePointFields extras + availability windows)
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'invalid-argument'; message: string };

/** Coordinates + availability-window ordering (legacy validatePointFields). */
export function guardPointFields(fields: {
  latitude: number;
  longitude: number;
  availableFrom?: string | null;
  availableUntil?: string | null;
}): GuardResult {
  if (!isValidCoordinate(fields.latitude, fields.longitude)) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'Latitude must be between -90 and 90 and longitude between -180 and 180.',
    };
  }
  if (fields.availableFrom && fields.availableUntil) {
    if (new Date(fields.availableUntil) <= new Date(fields.availableFrom)) {
      return {
        ok: false,
        code: 'invalid-argument',
        message: 'availableUntil must be later than availableFrom.',
      };
    }
  }
  return { ok: true };
}

export function isPointCurrentlyAvailable(
  point: { availableFrom?: Date | null; availableUntil?: Date | null },
  now: Date,
): boolean {
  if (point.availableFrom && now < point.availableFrom) return false;
  if (point.availableUntil && now > point.availableUntil) return false;
  return true;
}

/** Start of the current UTC calendar day (legacy daily windows). */
export function startOfUtcDay(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

/** Start of the current UTC ISO week, Monday (legacy weekly windows). */
export function startOfUtcWeek(date: Date): Date {
  const d = new Date(date);
  const day = d.getUTCDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setUTCDate(d.getUTCDate() + diff);
  d.setUTCHours(0, 0, 0, 0);
  return d;
}

/** Window start for a repeat rule, or null when 'once' (all history counts). */
export function repeatRuleWindowStart(rule: CrownHuntRepeatRule, now: Date): Date | null {
  if (rule === 'daily') return startOfUtcDay(now);
  if (rule === 'weekly') return startOfUtcWeek(now);
  return null;
}

// ---------------------------------------------------------------------------
// Deterministic award-guard identifiers (Phase 9h race hardening)
//
// The step-11 repeat-rule query and the step-12 daily-cap query are read
// BEFORE the award transaction, so N concurrent claims with distinct
// client idempotency keys each pass those pre-checks and each award. The
// guard documents below make the repeat rule and daily cap enforceable
// INSIDE the award transaction with IDs that do NOT derive from the
// client-supplied idempotency key, so concurrent duplicates serialize on a
// shared document and only one commits. Both collections are backend-only
// (firestore.rules deny all client access).
// ---------------------------------------------------------------------------

/** UTC calendar-day key (YYYY-MM-DD) — the daily window and counter bucket. */
export function utcDayKey(date: Date): string {
  return startOfUtcDay(date).toISOString().slice(0, 10);
}

/**
 * The window key an award falls in for a repeat rule: 'once' (all history),
 * or the ISO date (YYYY-MM-DD) of the daily/weekly window start. Using the
 * window-start date keeps the key unambiguous without ISO-week arithmetic.
 */
export function awardGuardWindowKey(rule: CrownHuntRepeatRule, now: Date): string {
  const start = repeatRuleWindowStart(rule, now);
  return start ? start.toISOString().slice(0, 10) : 'once';
}

/**
 * Length-prefixed SHA-256 over a tuple → a collision-resistant, Firestore-safe
 * (hex) document ID. Length-prefixing makes the encoding injective: no input
 * value can forge a field boundary, so distinct tuples never map to the same
 * digest regardless of which characters (including the historical `__`
 * separator) the parts contain. Used for guard/counter IDs, which must be
 * derived purely from server-trusted values — never the client idempotency key.
 */
function hashDocId(parts: readonly string[]): string {
  const hash = createHash('sha256');
  for (const part of parts) {
    hash.update(`${part.length}:${part}`);
  }
  return hash.digest('hex');
}

/**
 * Deterministic crownHuntAwardGuards document ID for a (user, point, window).
 * A `tx.create` of this ID inside the award transaction rejects a second
 * concurrent award for the same window (already-claimed). Hashed so it cannot
 * collide even if a uid or pointId contains separator substrings.
 */
export function awardGuardDocId(
  uid: string,
  pointId: string,
  rule: CrownHuntRepeatRule,
  now: Date,
): string {
  return hashDocId([uid, pointId, rule, awardGuardWindowKey(rule, now)]);
}

/**
 * Deterministic crownHuntDailyClaims counter document ID for a (user, UTC
 * day). Read-and-incremented inside the award transaction so the daily cap
 * cannot be beaten by concurrent claims. Hashed for collision resistance (the
 * queryable userId/day are stored as fields on the document).
 */
export function dailyClaimCounterDocId(uid: string, now: Date): string {
  return hashDocId([uid, utcDayKey(now)]);
}

/**
 * Deterministic crownHuntPointCollectors document ID for a (point, user).
 * Created the FIRST time a user is awarded on a given point, so a limited
 * crown counts DISTINCT collectors regardless of the repeat rule: a user who
 * re-collects under a daily/weekly rule already holds their marker and does
 * NOT consume a second slot. `tx.create` of this ID inside the award
 * transaction also serialises concurrent first-collects for the same user.
 * Hashed for collision resistance (queryable pointId/userId stored as fields).
 */
export function pointCollectorDocId(pointId: string, uid: string): string {
  return hashDocId([pointId, uid]);
}
