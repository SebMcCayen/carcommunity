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
    availableFrom: input.availableFrom ? new Date(input.availableFrom) : null,
    availableUntil: input.availableUntil ? new Date(input.availableUntil) : null,
    approvedAt: null,
    approvedByUserId: null,
    createdByUserId,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}
