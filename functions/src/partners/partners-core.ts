/**
 * Partner domain (companies, offers, applications) — constants, pure logic,
 * and builders (Phase 9i).
 *
 * Ports packages/shared/src/partners.ts + partner-offers.ts and the pure
 * parts of the three legacy partner services to the Firestore model:
 *
 * - `companies/{companyId}` — public while active; admin-managed lifecycle
 *   (draft → active ⇄ paused → ended).
 * - `offers/{offerId}` — THREE-TIER privacy (legacy parity):
 *   teaser (all authenticated users: title/teaserText/type — never the
 *   description, terms, or code) on the top-level document;
 *   member detail in `offers/{offerId}/details/member`;
 *   the discount code in `offers/{offerId}/secret/code`, fully backend-only
 *   and served exclusively by partners.showOfferCode — never logged.
 * - `users/{uid}/savedOffers/{offerId}` — member bookmark, direct writes.
 * - `partnerApplications/{applicationId}` — contact data, never
 *   client-readable; submitted via callable with a duplicate-spam guard;
 *   admin review flow submitted → under_review → approved/rejected, where
 *   approval creates the draft company in the same transaction.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

// ---------------------------------------------------------------------------
// Enums and limits (packages/shared/src/partners.ts + partner-offers.ts)
// ---------------------------------------------------------------------------

export const PARTNER_CATEGORIES = [
  'workshop',
  'car_care',
  'parts',
  'tires',
  'charging',
  'restaurant',
  'retail',
  'other',
] as const;
export type PartnerCategory = (typeof PARTNER_CATEGORIES)[number];

export const PARTNER_COMPANY_STATUSES = ['draft', 'active', 'paused', 'ended'] as const;
export type PartnerCompanyStatus = (typeof PARTNER_COMPANY_STATUSES)[number];

export const PARTNER_APPLICATION_STATUSES = [
  'submitted',
  'under_review',
  'approved',
  'rejected',
  'withdrawn',
] as const;
export type PartnerApplicationStatus = (typeof PARTNER_APPLICATION_STATUSES)[number];

export const PARTNER_OFFER_STATUSES = ['draft', 'active', 'paused', 'ended', 'expired'] as const;
export type PartnerOfferStatus = (typeof PARTNER_OFFER_STATUSES)[number];

export const PARTNER_OFFER_TYPES = [
  'discount_code',
  'percentage_discount',
  'fixed_discount',
  'member_benefit',
  'special_offer',
  'other',
] as const;
export type PartnerOfferType = (typeof PARTNER_OFFER_TYPES)[number];

export const MAX_PARTNER_COMPANY_NAME_LENGTH = 150;
export const MAX_PARTNER_CONTACT_NAME_LENGTH = 120;
export const MAX_PARTNER_EMAIL_LENGTH = 254;
export const MAX_PARTNER_DESCRIPTION_LENGTH = 1_000;
export const MAX_PARTNER_MESSAGE_LENGTH = 2_000;
export const MAX_PARTNER_ADDRESS_LENGTH = 300;
export const MAX_PARTNER_PHONE_LENGTH = 30;
export const MAX_PARTNER_WEBSITE_URL_LENGTH = 500;

export const MAX_OFFER_TITLE_LENGTH = 150;
export const MAX_OFFER_TEASER_TEXT_LENGTH = 250;
export const MAX_OFFER_DESCRIPTION_LENGTH = 2_000;
export const MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH = 1_000;
export const MAX_OFFER_TERMS_LENGTH = 2_000;
export const MAX_OFFER_DISCOUNT_CODE_LENGTH = 100;
export const MAX_OFFER_PERCENTAGE_DISCOUNT = 100;

// ---------------------------------------------------------------------------
// Shared field schemas
// ---------------------------------------------------------------------------

const firestoreIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(300)
  .regex(/^[A-Za-z0-9._-]+$/)
  .refine((id) => id !== '.' && id !== '..');

/**
 * Partner-application website field. Accepts a scheme-less domain like
 * `www.foretag.se` by prepending `https://` before URL validation — zod
 * `.url()` rejects bare domains, which used to make the callable reject a
 * perfectly reasonable value with a misleading `invalid-argument`. A
 * blank/whitespace-only string is treated as missing (normalized to
 * `undefined`) so an accidental "" from any client stays optional rather
 * than triggering `invalid-argument`; max-length is enforced on the
 * normalized value. Wrapped with `.nullable().optional()` at the call site.
 */
const partnerWebsiteUrlSchema = z.preprocess((v) => {
  if (typeof v !== 'string') return v;
  const trimmed = v.trim();
  if (trimmed === '') return undefined;
  return /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
}, z.string().url().max(MAX_PARTNER_WEBSITE_URL_LENGTH).optional());

const companyFieldsSchema = z.object({
  name: z.string().trim().min(1).max(MAX_PARTNER_COMPANY_NAME_LENGTH),
  category: z.enum(PARTNER_CATEGORIES),
  description: z.string().max(MAX_PARTNER_DESCRIPTION_LENGTH).nullable().optional(),
  website: z.string().trim().url().max(MAX_PARTNER_WEBSITE_URL_LENGTH).nullable().optional(),
  phone: z.string().trim().max(MAX_PARTNER_PHONE_LENGTH).nullable().optional(),
  address: z.string().max(MAX_PARTNER_ADDRESS_LENGTH).nullable().optional(),
  latitude: z.number().min(-90).max(90).nullable().optional(),
  longitude: z.number().min(-180).max(180).nullable().optional(),
  logoPath: z
    .string()
    .regex(/^companyImages\/[^/]+\/[^/]+$/)
    .max(500)
    .nullable()
    .optional(),
});

const offerFieldsSchema = z.object({
  companyId: firestoreIdSchema,
  title: z.string().trim().min(1).max(MAX_OFFER_TITLE_LENGTH),
  teaserText: z.string().trim().min(1).max(MAX_OFFER_TEASER_TEXT_LENGTH),
  offerType: z.enum(PARTNER_OFFER_TYPES),
  description: z.string().trim().min(1).max(MAX_OFFER_DESCRIPTION_LENGTH),
  redemptionInstructions: z
    .string()
    .max(MAX_OFFER_REDEMPTION_INSTRUCTIONS_LENGTH)
    .nullable()
    .optional(),
  terms: z.string().max(MAX_OFFER_TERMS_LENGTH).nullable().optional(),
  discountCode: z.string().trim().min(1).max(MAX_OFFER_DISCOUNT_CODE_LENGTH).nullable().optional(),
  percentageDiscount: z.number().min(0).max(MAX_OFFER_PERCENTAGE_DISCOUNT).nullable().optional(),
  fixedDiscountMinorUnits: z.number().int().positive().nullable().optional(),
  currencyCode: z.string().regex(/^[A-Z]{3}$/).nullable().optional(),
  availableFrom: z.string().datetime().nullable().optional(),
  availableUntil: z.string().datetime().nullable().optional(),
});

// ---------------------------------------------------------------------------
// Callable inputs
// ---------------------------------------------------------------------------

const submitApplicationInputSchema = z
  .object({
    companyName: z.string().trim().min(1).max(MAX_PARTNER_COMPANY_NAME_LENGTH),
    organizationNumber: z.string().trim().max(20).nullable().optional(),
    category: z.enum(PARTNER_CATEGORIES),
    contactName: z.string().trim().min(1).max(MAX_PARTNER_CONTACT_NAME_LENGTH),
    contactEmail: z.string().trim().email().max(MAX_PARTNER_EMAIL_LENGTH),
    contactPhone: z.string().trim().max(MAX_PARTNER_PHONE_LENGTH).nullable().optional(),
    websiteUrl: partnerWebsiteUrlSchema.nullable().optional(),
    proposedDescription: z.string().max(MAX_PARTNER_DESCRIPTION_LENGTH).nullable().optional(),
    proposedAddress: z.string().max(MAX_PARTNER_ADDRESS_LENGTH).nullable().optional(),
    message: z.string().max(MAX_PARTNER_MESSAGE_LENGTH).nullable().optional(),
  })
  .strict();

const reviewApplicationInputSchema = z
  .object({
    applicationId: firestoreIdSchema,
    action: z.enum(['start_review', 'approve', 'reject']),
    note: z.string().trim().max(2000).optional(),
  })
  .strict();

const createCompanyInputSchema = companyFieldsSchema.strict();
const updateCompanyInputSchema = companyFieldsSchema
  .partial()
  .extend({ companyId: firestoreIdSchema })
  .strict();

const setCompanyStatusInputSchema = z
  .object({
    companyId: firestoreIdSchema,
    action: z.enum(['activate', 'pause', 'end']),
    reason: z.string().trim().max(2000).optional(),
  })
  .strict();

const createOfferInputSchema = offerFieldsSchema.strict();
const updateOfferInputSchema = offerFieldsSchema
  .partial()
  .omit({ companyId: true })
  .extend({ offerId: firestoreIdSchema })
  .strict();

const setOfferStatusInputSchema = z
  .object({
    offerId: firestoreIdSchema,
    action: z.enum(['activate', 'pause', 'end']),
    reason: z.string().trim().max(2000).optional(),
  })
  .strict();

const showOfferCodeInputSchema = z.object({ offerId: firestoreIdSchema }).strict();

export type SubmitApplicationInput = z.infer<typeof submitApplicationInputSchema>;
export type ReviewApplicationInput = z.infer<typeof reviewApplicationInputSchema>;
export type CreateCompanyInput = z.infer<typeof createCompanyInputSchema>;
export type UpdateCompanyInput = z.infer<typeof updateCompanyInputSchema>;
export type SetCompanyStatusInput = z.infer<typeof setCompanyStatusInputSchema>;
export type CreateOfferInput = z.infer<typeof createOfferInputSchema>;
export type UpdateOfferInput = z.infer<typeof updateOfferInputSchema>;
export type SetOfferStatusInput = z.infer<typeof setOfferStatusInputSchema>;
export type ShowOfferCodeInput = z.infer<typeof showOfferCodeInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function parse<T>(schema: z.ZodType<T>, data: unknown, expected: string): ParseResult<T> {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: expected };
  }
  return { ok: true, input: result.data };
}

export const parseSubmitApplicationInput = (d: unknown) =>
  parse(
    submitApplicationInputSchema,
    d,
    'Expected submitApplicationRequest (contracts/schemas/partners.schema.json).',
  );
export const parseReviewApplicationInput = (d: unknown) =>
  parse(
    reviewApplicationInputSchema,
    d,
    'Expected { applicationId, action: start_review|approve|reject, note? }.',
  );
export const parseCreateCompanyInput = (d: unknown) =>
  parse(createCompanyInputSchema, d, 'Expected createCompanyRequest: { name, category, ... }.');
export const parseUpdateCompanyInput = (d: unknown) =>
  parse(updateCompanyInputSchema, d, 'Expected { companyId } plus company fields.');
export const parseSetCompanyStatusInput = (d: unknown) =>
  parse(setCompanyStatusInputSchema, d, 'Expected { companyId, action: activate|pause|end, reason? }.');
export const parseCreateOfferInput = (d: unknown) =>
  parse(
    createOfferInputSchema,
    d,
    'Expected createOfferRequest: { companyId, title, teaserText, offerType, description, ... }.',
  );
export const parseUpdateOfferInput = (d: unknown) =>
  parse(updateOfferInputSchema, d, 'Expected { offerId } plus offer fields.');
export const parseSetOfferStatusInput = (d: unknown) =>
  parse(setOfferStatusInputSchema, d, 'Expected { offerId, action: activate|pause|end, reason? }.');
export const parseShowOfferCodeInput = (d: unknown) =>
  parse(showOfferCodeInputSchema, d, 'Expected { offerId }.');

// ---------------------------------------------------------------------------
// Lifecycle guards (companies and offers share the same transition rules)
// ---------------------------------------------------------------------------

export type StatusAction = 'activate' | 'pause' | 'end';
export type GuardResult =
  | { ok: true; nextStatus: PartnerCompanyStatus }
  | { ok: false; code: 'failed-precondition'; message: string };

/** Audit-friendly past tense for a status action ("end" → "ended"). */
export function statusActionPastTense(action: StatusAction): string {
  return action === 'end' ? 'ended' : `${action}d`;
}

const TRANSITIONABLE_STATUSES: ReadonlySet<string> = new Set(['draft', 'active', 'paused']);

/**
 * draft → active ⇄ paused → ended; ended/expired are terminal (legacy
 * parity). Unknown/corrupted statuses are rejected too — a transition must
 * never silently "heal" bad data into a live record.
 */
export function guardStatusTransition(
  current: string,
  action: StatusAction,
): GuardResult {
  if (!TRANSITIONABLE_STATUSES.has(current)) {
    return {
      ok: false,
      code: 'failed-precondition',
      message:
        current === 'ended' || current === 'expired'
          ? 'Ended or expired records cannot change status.'
          : `Cannot change status of a record in unrecognized status "${current}".`,
    };
  }
  if (action === 'activate') return { ok: true, nextStatus: 'active' };
  if (action === 'pause') return { ok: true, nextStatus: 'paused' };
  return { ok: true, nextStatus: 'ended' };
}

/** Only draft or paused records may be edited (legacy parity). */
export function guardEditableStatus(current: string): GuardResult {
  if (current !== 'draft' && current !== 'paused') {
    return {
      ok: false,
      code: 'failed-precondition',
      message: 'Only draft or paused records may be edited.',
    };
  }
  return { ok: true, nextStatus: current as PartnerCompanyStatus };
}

export function guardAvailabilityWindow(
  availableFrom: string | null | undefined,
  availableUntil: string | null | undefined,
):
  | { ok: true }
  | { ok: false; code: 'invalid-argument'; message: string } {
  if (availableFrom && availableUntil && new Date(availableUntil) <= new Date(availableFrom)) {
    return {
      ok: false,
      code: 'invalid-argument',
      message: 'availableUntil must be later than availableFrom.',
    };
  }
  return { ok: true };
}

// ---------------------------------------------------------------------------
// Document builders — the offer three-tier split
// ---------------------------------------------------------------------------

export interface OfferDocuments {
  /** offers/{offerId} — teaser-safe + lifecycle fields. */
  offerDoc: Record<string, unknown>;
  /** offers/{offerId}/details/member — member-gated detail. */
  memberDoc: Record<string, unknown>;
  /** offers/{offerId}/secret/code — backend-only discount code. */
  secretDoc: Record<string, unknown>;
}

export function buildOfferDocuments(
  input: CreateOfferInput,
  companyName: string,
  serverTimestamp: () => unknown,
): OfferDocuments {
  return {
    offerDoc: {
      companyId: input.companyId,
      partnerCompanyName: companyName,
      title: input.title,
      teaserText: input.teaserText,
      offerType: input.offerType,
      status: 'draft',
      availableFrom: input.availableFrom ? new Date(input.availableFrom) : null,
      availableUntil: input.availableUntil ? new Date(input.availableUntil) : null,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    },
    memberDoc: {
      description: input.description,
      redemptionInstructions: input.redemptionInstructions ?? null,
      terms: input.terms ?? null,
      percentageDiscount: input.percentageDiscount ?? null,
      fixedDiscountMinorUnits: input.fixedDiscountMinorUnits ?? null,
      currencyCode: input.currencyCode ?? null,
      updatedAt: serverTimestamp(),
    },
    secretDoc: {
      discountCode: input.discountCode ?? null,
      updatedAt: serverTimestamp(),
    },
  };
}

/** Splits a partial offer update across the three documents. */
export function buildOfferUpdates(
  input: UpdateOfferInput,
  serverTimestamp: () => unknown,
): OfferDocuments & { changedFields: string[] } {
  const offerDoc: Record<string, unknown> = {};
  const memberDoc: Record<string, unknown> = {};
  const secretDoc: Record<string, unknown> = {};
  const changedFields: string[] = [];
  const set = (target: Record<string, unknown>, key: string, value: unknown) => {
    target[key] = value;
    changedFields.push(key);
  };

  if (input.title !== undefined) set(offerDoc, 'title', input.title);
  if (input.teaserText !== undefined) set(offerDoc, 'teaserText', input.teaserText);
  if (input.offerType !== undefined) set(offerDoc, 'offerType', input.offerType);
  if (input.availableFrom !== undefined)
    set(offerDoc, 'availableFrom', input.availableFrom ? new Date(input.availableFrom) : null);
  if (input.availableUntil !== undefined)
    set(offerDoc, 'availableUntil', input.availableUntil ? new Date(input.availableUntil) : null);

  if (input.description !== undefined) set(memberDoc, 'description', input.description);
  if (input.redemptionInstructions !== undefined)
    set(memberDoc, 'redemptionInstructions', input.redemptionInstructions);
  if (input.terms !== undefined) set(memberDoc, 'terms', input.terms);
  if (input.percentageDiscount !== undefined)
    set(memberDoc, 'percentageDiscount', input.percentageDiscount);
  if (input.fixedDiscountMinorUnits !== undefined)
    set(memberDoc, 'fixedDiscountMinorUnits', input.fixedDiscountMinorUnits);
  if (input.currencyCode !== undefined) set(memberDoc, 'currencyCode', input.currencyCode);

  if (input.discountCode !== undefined) set(secretDoc, 'discountCode', input.discountCode);

  for (const target of [offerDoc, memberDoc, secretDoc]) {
    if (Object.keys(target).length > 0) {
      target.updatedAt = serverTimestamp();
    }
  }
  return { offerDoc, memberDoc, secretDoc, changedFields };
}

/** companies/{companyId} document. */
export function buildCompanyDocument(
  input: CreateCompanyInput,
  context: { createdByUserId: string; sourceApplicationId: string | null },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    name: input.name,
    category: input.category,
    description: input.description ?? null,
    website: input.website ?? null,
    phone: input.phone ?? null,
    address: input.address ?? null,
    latitude: input.latitude ?? null,
    longitude: input.longitude ?? null,
    logoPath: input.logoPath ?? null,
    status: 'draft',
    sourceApplicationId: context.sourceApplicationId,
    createdByUserId: context.createdByUserId,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

/** partnerApplications/{applicationId} document — never client-readable. */
export function buildApplicationDocument(
  input: SubmitApplicationInput,
  submittedByUserId: string,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    companyName: input.companyName,
    organizationNumber: input.organizationNumber ?? null,
    category: input.category,
    contactName: input.contactName,
    contactEmail: input.contactEmail.toLowerCase(),
    contactPhone: input.contactPhone ?? null,
    websiteUrl: input.websiteUrl ?? null,
    proposedDescription: input.proposedDescription ?? null,
    proposedAddress: input.proposedAddress ?? null,
    message: input.message ?? null,
    status: 'submitted',
    submittedByUserId,
    reviewedByUserId: null,
    reviewNote: null,
    partnerCompanyId: null,
    submittedAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}
