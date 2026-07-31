/**
 * Digital billboards domain — constants, pure logic, and builders
 * (Phase 9k).
 *
 * Ports packages/shared/src/digital-billboards.ts and the pure parts of the
 * legacy billboard-service.ts to the Firestore model:
 *
 * - `billboards/{billboardId}` — sponsored map billboards, public while
 *   ACTIVE (map markers + detail); the shared draft → active ⇄ paused →
 *   ended lifecycle via audited admin callables.
 * - Activation is the strictest SAFETY GATE in the codebase: six explicit
 *   safety confirmations (not a business location, not a road lane, not a
 *   road sign, not obstructing the map, marked as advertising, suitable
 *   for the map) plus an approval reason, and the sponsoring partner must
 *   be ACTIVE.
 * - Billboard taps map onto partner-insights interaction types and flow
 *   through the 9j privacy pipeline (scoped hashes, per-day dedupe, TTL);
 *   analytics failures never block the user's action (legacy parity).
 * - The digitalBillboards feature flag (contract default true) gates the
 *   interaction recording.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';
import type { PartnerInteractionType } from '../partnerInsights/insights-core';

// ---------------------------------------------------------------------------
// Enums and limits (packages/shared/src/digital-billboards.ts)
// ---------------------------------------------------------------------------

export const BILLBOARD_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type BillboardStatus = (typeof BILLBOARD_STATUSES)[number];

export const BILLBOARD_PLACEMENT_TYPES = [
  'map_billboard',
  'event_area',
  'partner_area',
  'other_approved_location',
] as const;
export type BillboardPlacementType = (typeof BILLBOARD_PLACEMENT_TYPES)[number];

export const BILLBOARD_INTERACTION_TYPES = [
  'impression',
  'open',
  'navigate',
  'phone',
  'website',
  'offer_view',
] as const;
export type BillboardInteractionType = (typeof BILLBOARD_INTERACTION_TYPES)[number];

export const BILLBOARD_CTA_TYPES = [
  'navigate',
  'phone',
  'website',
  'offer_view',
  'partner_profile',
] as const;
export type BillboardCtaType = (typeof BILLBOARD_CTA_TYPES)[number];

/**
 * The server-owned "draw this on the member map" flag: `billboards/{id}.mapVisible`.
 *
 * ## Why a denormalised field instead of a rule that reads the window
 *
 * The obvious implementation of "an inactive, expired or unscheduled billboard
 * must not render" is a security rule that compares `availableFrom` /
 * `availableUntil` against `request.time`. That works for a single `get()` and
 * cannot work at all for the LIST the map issues.
 *
 * The reason is how Firestore evaluates a `list`: NOT against each returned
 * document, but SYMBOLICALLY, against the query's own constraints. The rule
 * must be provable from what the query filters on, and any field the rule reads
 * that the query does not constrain is undefined — which raises "Property <x>
 * is undefined on object" and fails the entire query. A rule comparing
 * `availableFrom` against `request.time` is therefore not a rule that hides
 * expired billboards; it is a rule that makes the billboard layer unreadable
 * for everyone, always. (Verified, not assumed: see the map-layer query test in
 * `__tests__/security-rules.emulator.test.ts`, which fails exactly that way if
 * a constraint is dropped.)
 *
 * So the window is resolved to a single boolean on the server, and that boolean
 * is both what the rule checks and what the client query filters on. Nothing
 * but the Admin SDK can write it — all client writes to `billboards` are denied
 * — so a client cannot opt itself into seeing a hidden billboard.
 *
 * **The coupling this creates, stated plainly because it is easy to break:**
 * the member query must constrain EVERY field the read rule reads — today
 * `status` and `mapVisible`, both of them. Adding a condition to the rule
 * without adding the matching `where` to the query does not tighten anything;
 * it takes the layer down.
 *
 * ## The invariant
 *
 * `mapVisible == true` implies `status == 'active'` AND the availability window
 * is open. The lifecycle callables maintain it transactionally in the same
 * write that changes `status` (so a pause takes the marker down immediately,
 * not at the next sweep), and the scheduled sweep owns only the transitions
 * that are driven by the CLOCK rather than by an admin — a window opening or
 * expiring while nobody is touching the record.
 *
 * The rule checks `status == 'active'` as well, and the query filters on it
 * too, so the two fields must agree for a member to read the document. That
 * fails CLOSED and — because the query carries the same pair of constraints —
 * it does so WITHOUT the "one bad document blanks the layer" failure mode:
 * a document that somehow held `mapVisible: true` while paused would simply
 * never enter the result set. Showing a deactivated billboard is the single
 * outcome this feature is not allowed to produce.
 */
export const BILLBOARD_MAP_VISIBLE_FIELD = 'mapVisible';

export const MAX_BILLBOARD_HEADLINE_LENGTH = 100;
export const MAX_BILLBOARD_MESSAGE_LENGTH = 300;
export const MAX_BILLBOARD_SAFETY_NOTE_LENGTH = 500;
export const MAX_BILLBOARD_CTA_VALUE_LENGTH = 500;

/** Feature flag key (contracts/features/feature-flags.json), default true. */
export const BILLBOARDS_FLAG_KEY = 'digitalBillboards';
export const BILLBOARDS_FLAG_DEFAULT = true;

/**
 * Billboard tap → partner-insights interaction type (legacy typeMap):
 * impressions count as map views, opens as profile views.
 */
export const BILLBOARD_TO_INSIGHTS_TYPE: Readonly<
  Record<BillboardInteractionType, PartnerInteractionType>
> = {
  impression: 'map_view',
  open: 'profile_view',
  navigate: 'navigate',
  phone: 'phone',
  website: 'website',
  offer_view: 'offer_view',
};

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const firestoreIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

const billboardFieldsSchema = z.object({
  partnerCompanyId: firestoreIdSchema,
  headline: z.string().trim().min(1).max(MAX_BILLBOARD_HEADLINE_LENGTH),
  message: z.string().trim().min(1).max(MAX_BILLBOARD_MESSAGE_LENGTH),
  placementType: z.enum(BILLBOARD_PLACEMENT_TYPES),
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
  callToActionType: z.enum(BILLBOARD_CTA_TYPES).nullable().optional(),
  callToActionValue: z
    .string()
    .trim()
    .min(1)
    .max(MAX_BILLBOARD_CTA_VALUE_LENGTH)
    .nullable()
    .optional(),
  safetyNote: z.string().max(MAX_BILLBOARD_SAFETY_NOTE_LENGTH).nullable().optional(),
  imagePath: z
    .string()
    .regex(/^billboardImages\/[^/]+\/[^/]+$/)
    .max(500)
    .nullable()
    .optional(),
  availableFrom: z.string().datetime().nullable().optional(),
  availableUntil: z.string().datetime().nullable().optional(),
});

const createBillboardInputSchema = billboardFieldsSchema.strict();

const updateBillboardInputSchema = billboardFieldsSchema
  .partial()
  .omit({ partnerCompanyId: true })
  .extend({ billboardId: firestoreIdSchema })
  .strict();

/** The six legacy safety confirmations — every one must be literally true. */
const activateBillboardInputSchema = z
  .object({
    billboardId: firestoreIdSchema,
    notBusinessLocationConfirmed: z.literal(true),
    notRoadLaneConfirmed: z.literal(true),
    notRoadSignConfirmed: z.literal(true),
    notObstructingMapConfirmed: z.literal(true),
    markedAsAdvertisingConfirmed: z.literal(true),
    suitableForMapConfirmed: z.literal(true),
    approvalReason: z.string().trim().min(1).max(2000),
  })
  .strict();

const setBillboardStatusInputSchema = z
  .object({
    billboardId: firestoreIdSchema,
    action: z.enum(['pause', 'end']),
    reason: z.string().trim().max(2000).optional(),
  })
  .strict();

const recordBillboardInteractionInputSchema = z
  .object({
    billboardId: firestoreIdSchema,
    interactionType: z.enum(BILLBOARD_INTERACTION_TYPES),
  })
  .strict();

export type CreateBillboardInput = z.infer<typeof createBillboardInputSchema>;
export type UpdateBillboardInput = z.infer<typeof updateBillboardInputSchema>;
export type ActivateBillboardInput = z.infer<typeof activateBillboardInputSchema>;
export type SetBillboardStatusInput = z.infer<typeof setBillboardStatusInputSchema>;
export type RecordBillboardInteractionInput = z.infer<
  typeof recordBillboardInteractionInputSchema
>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseCreateBillboardInput = (d: unknown) =>
  parse(
    createBillboardInputSchema,
    d,
    'Expected createBillboardRequest: { partnerCompanyId, headline, message, placementType, latitude, longitude, ... }.',
  );
export const parseUpdateBillboardInput = (d: unknown) =>
  parse(updateBillboardInputSchema, d, 'Expected { billboardId } plus billboard fields.');
export const parseActivateBillboardInput = (d: unknown) =>
  parse(
    activateBillboardInputSchema,
    d,
    'Expected { billboardId, approvalReason } with ALL six safety confirmations set to true.',
  );
export const parseSetBillboardStatusInput = (d: unknown) =>
  parse(setBillboardStatusInputSchema, d, 'Expected { billboardId, action: pause|end, reason? }.');
export const parseRecordBillboardInteractionInput = (d: unknown) =>
  parse(
    recordBillboardInteractionInputSchema,
    d,
    `Expected { billboardId, interactionType: ${BILLBOARD_INTERACTION_TYPES.join('|')} }.`,
  );

// ---------------------------------------------------------------------------
// Guards (shared lifecycle semantics with companies/offers/crownHunt points)
// ---------------------------------------------------------------------------

export type GuardResult =
  | { ok: true }
  | { ok: false; code: 'invalid-argument' | 'failed-precondition'; message: string };

/** Only draft or paused billboards may be edited or activated (legacy). */
export function guardEditableBillboard(status: string): GuardResult {
  if (status !== 'draft' && status !== 'paused') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Only draft or paused billboards can be changed.',
    };
  }
  return { ok: true };
}

export function guardAvailabilityWindow(
  availableFrom: string | null | undefined,
  availableUntil: string | null | undefined,
): GuardResult {
  if (availableFrom && availableUntil && new Date(availableUntil) <= new Date(availableFrom)) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'availableUntil must be later than availableFrom.',
    };
  }
  return { ok: true };
}

/** A CTA value requires a CTA type and vice versa. */
export function guardCallToActionPair(
  ctaType: string | null | undefined,
  ctaValue: string | null | undefined,
): GuardResult {
  if ((ctaType != null) !== (ctaValue != null)) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'callToActionType and callToActionValue must both be provided or both omitted.',
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Map visibility (see BILLBOARD_MAP_VISIBLE_FIELD)
// ---------------------------------------------------------------------------

/**
 * Whether a billboard should be drawn on the member map right now.
 *
 * True only when it is ACTIVE — an admin has taken it through the six-point
 * safety gate — and `now` falls inside its availability window. A null bound is
 * "unbounded on that side", which is how an admin says "from the moment it is
 * activated" or "until I stop it"; it is NOT a licence to ignore the other
 * bound.
 *
 * The window is compared half-open, `[from, until)`: a billboard whose
 * `availableUntil` is exactly now has, by the admin's own definition, finished.
 *
 * Pure and clock-injected so the boundary behaviour is unit-tested rather than
 * inferred from a sweep that happened to run at the right second.
 */
export function isBillboardMapVisible(
  status: string,
  availableFrom: Date | null | undefined,
  availableUntil: Date | null | undefined,
  now: Date,
): boolean {
  if (status !== 'active') return false;
  const t = now.getTime();
  // An unparseable stored date (NaN) must not read as "unbounded" — every
  // comparison against NaN is false, which would silently let a billboard with
  // a corrupt window render forever. Treat it as closed.
  if (availableFrom != null) {
    const from = availableFrom.getTime();
    if (!Number.isFinite(from) || t < from) return false;
  }
  if (availableUntil != null) {
    const until = availableUntil.getTime();
    if (!Number.isFinite(until) || t >= until) return false;
  }
  return true;
}

/** One document the visibility sweep decided to rewrite. */
export interface VisibilityChange {
  id: string;
  mapVisible: boolean;
}

/**
 * The scheduled sweep's per-document decision: given what is stored, what
 * should `mapVisible` be, and does that differ from what is already there?
 *
 * Returns null when the document is already correct — the normal case, and the
 * one that must not cost a write. Not merely a cost point: a no-op write would
 * still push a snapshot delta to every device listening to the billboards
 * layer.
 *
 * Lives here rather than in scheduled.ts so it is unit-testable without the
 * Admin SDK — this module is deliberately Firebase-free.
 */
export function decideVisibility(
  id: string,
  status: unknown,
  availableFrom: Date | null,
  availableUntil: Date | null,
  storedMapVisible: unknown,
  now: Date,
): VisibilityChange | null {
  const desired = isBillboardMapVisible(String(status ?? ''), availableFrom, availableUntil, now);
  // `storedMapVisible !== true` rather than `=== false`: a document written
  // before the field existed holds `undefined`, and that is a mismatch worth
  // repairing exactly when the billboard should now be visible. This is what
  // makes the sweep double as a backfill, so no manual migration is needed.
  const stored = storedMapVisible === true;
  if (stored === desired) return null;
  return { id, mapVisible: desired };
}

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

/** billboards/{billboardId} document. */
export function buildBillboardDocument(
  input: CreateBillboardInput,
  createdByUserId: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    partnerCompanyId: input.partnerCompanyId,
    headline: input.headline,
    message: input.message,
    placementType: input.placementType,
    latitude: input.latitude,
    longitude: input.longitude,
    callToActionType: input.callToActionType ?? null,
    callToActionValue: input.callToActionValue ?? null,
    safetyNote: input.safetyNote ?? null,
    imagePath: input.imagePath ?? null,
    status: 'draft',
    // Every billboard starts invisible. Drafts have not been through the safety
    // gate, so there is no window in which a freshly created one may be drawn —
    // the field is written explicitly rather than left absent so the member
    // query (`where mapVisible == true`) and the read rule both have something
    // to evaluate from the very first write.
    mapVisible: false,
    availableFrom: input.availableFrom ? new Date(input.availableFrom) : null,
    availableUntil: input.availableUntil ? new Date(input.availableUntil) : null,
    approvedAt: null,
    approvedByUserId: null,
    createdByUserId,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}
