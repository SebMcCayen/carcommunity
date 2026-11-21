/** Pure validation and projections for partner self-service. Never project customer identity. */
import { createHash } from 'node:crypto';
import { z } from 'zod';

const id = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9_-]+$/);
const text = (max: number) => z.string().trim().max(max);
const date = z.iso.datetime({ offset: true }).nullable().optional();
export const applicationSchema = z
  .object({
    companyName: text(160).min(2),
    category: z.enum([
      'workshop',
      'car_care',
      'parts',
      'tires',
      'charging',
      'restaurant',
      'retail',
      'other',
    ]),
    organizationNumber: text(32).optional(),
    contactName: text(120).min(2),
    contactEmail: z.email().max(254),
    contactPhone: text(40).optional(),
    website: text(2048).optional(),
    description: text(4000).optional(),
    address: text(300).optional(),
    publicPhone: text(40).optional(),
    selfServiceRequested: z.boolean(),
    billboardRequested: z.boolean(),
    billboardPlacementRequest: text(1000).optional(),
    message: text(2000).optional(),
    logoBase64: z.string().max(7_000_000).optional(),
    logoRightsConfirmed: z.boolean().optional(),
  })
  .strict();
export const managedOfferSchema = z
  .object({
    companyId: id,
    offerId: id.optional(),
    title: text(120).min(2),
    teaserText: text(300).min(2),
    description: text(6000).min(2),
    terms: text(4000).min(2),
    percentageDiscount: z.number().int().min(1).max(100).nullable().optional(),
    availableFrom: date,
    availableUntil: date,
    capacity: z.number().int().min(1).max(100000).nullable().optional(),
    reservationDays: z.number().int().min(1).max(30).default(7),
  })
  .strict();
export const portalSchemas = {
  submitApplication: applicationSchema,
  workspace: z.object({}).strict(),
  uploadLogo: z
    .object({
      companyId: id,
      logoBase64: z.string().max(7_000_000),
      logoRightsConfirmed: z.literal(true),
    })
    .strict(),
  updateProfile: z
    .object({
      companyId: id,
      description: text(4000).min(2),
      phone: text(40).nullable().optional(),
      website: text(2048).nullable().optional(),
    })
    .strict(),
  listOffers: z.object({ companyId: id }).strict(),
  submitOffer: managedOfferSchema,
  pauseReservations: z.object({ companyId: id, offerId: id, paused: z.boolean() }).strict(),
  offer: z.object({ offerId: id }).strict(),
  reserve: z
    .object({ offerId: id, requestId: id.min(8), expectedVersion: z.number().int().min(1) })
    .strict(),
  reservations: z.object({}).strict(),
  cancel: z.object({ reservationId: id }).strict(),
  token: z.object({ reservationId: id }).strict(),
  inspect: z.object({ token: z.string().min(20).max(512) }).strict(),
  redeem: z.object({ token: z.string().min(20).max(512), requestId: id.min(8) }).strict(),
  driveHeat: z.object({ companyId: id }).strict(),
  statistics: z
    .object({
      companyId: id,
      period: z
        .string()
        .regex(/^(all|\d{4}-(0[1-9]|1[0-2]))$/)
        .default('all'),
    })
    .strict(),
  adminStatistics: z
    .object({
      companyId: id.optional(),
      period: z
        .string()
        .regex(/^(all|\d{4}-(0[1-9]|1[0-2]))$/)
        .default('all'),
    })
    .strict(),
  reviewApplication: z
    .object({
      applicationId: id,
      decision: z.enum(['publish', 'request_changes']),
      expectedRevision: z.number().int().min(0).optional(),
      reason: text(1000).optional(),
      grantSelfService: z.boolean().optional(),
      latitude: z.number().min(-90).max(90).optional(),
      longitude: z.number().min(-180).max(180).optional(),
    })
    .strict(),
  membership: z
    .object({ companyId: id, uid: id, role: z.enum(['manager', 'redeemer']), enabled: z.boolean() })
    .strict(),
  members: z.object({ companyId: id }).strict(),
  billboardLogo: z.object({ billboardId: id }).strict(),
} as const;
export type PortalAction = keyof typeof portalSchemas;
export function hash(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

export function millis(value: unknown): number | null {
  if (value == null) return null;
  if (typeof value === 'object' && 'toMillis' in value && typeof value.toMillis === 'function')
    return value.toMillis();
  if (typeof value === 'string') {
    const n = Date.parse(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
export function iso(value: unknown): string | null {
  const n = millis(value);
  return n == null ? null : new Date(n).toISOString();
}
export function inWindow(offer: Record<string, unknown>, now: number): boolean {
  const from = millis(offer.availableFrom),
    until = millis(offer.availableUntil);
  return (
    (offer.availableFrom == null || (from != null && from <= now)) &&
    (offer.availableUntil == null || (until != null && now < until))
  );
}
export function reservationProjection(id: string, data: Record<string, unknown>, now = Date.now()) {
  return {
    reservationId: id,
    offerId: data.offerId,
    companyId: data.companyId,
    companyName: data.companyName,
    title: data.title,
    terms: data.terms,
    status:
      data.status === 'reserved' && (millis(data.expiresAt) ?? 0) <= now ? 'expired' : data.status,
    expiresAt: iso(data.expiresAt),
    redeemedAt: iso(data.redeemedAt),
  };
}
export function offerProjection(
  id: string,
  data: Record<string, unknown>,
  details: Record<string, unknown> = {},
  managed = false,
) {
  const capacity = typeof data.capacity === 'number' ? data.capacity : null;
  const remainingCount = capacity == null ? null : Number(data.remainingCount ?? capacity);
  return {
    offerId: id,
    companyId: data.companyId,
    title: data.title,
    teaserText: data.teaserText,
    offerType: data.offerType,
    description: details.description ?? '',
    terms: details.terms ?? '',
    percentageDiscount: details.percentageDiscount ?? null,
    availableFrom: iso(data.availableFrom),
    availableUntil: iso(data.availableUntil),
    capacity,
    reservationDays: data.reservationDays ?? 7,
    remainingCount,
    reservationsPaused: data.reservationsPaused === true,
    redemptionMode: data.redemptionMode ?? null,
    status: data.status,
    version: data.version ?? 1,
    ...(managed
      ? { reservedCount: data.reservedCount ?? 0, redeemedCount: data.redeemedCount ?? 0 }
      : {}),
  };
}
