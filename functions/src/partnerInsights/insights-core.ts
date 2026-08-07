/**
 * Partner insights — the privacy-critical aggregation domain (Phase 9j).
 * Pure logic ported from packages/shared/src/partner-insights.ts and the
 * legacy partner-insights-service.ts.
 *
 * Privacy invariants (legacy, preserved exactly):
 * - Raw user IDs are NEVER stored on insight events: a user is represented
 *   by a partner-scoped SHA-256 hash, so events for the same user at two
 *   different partners cannot be correlated.
 * - anonymous_pass_by contributions require the pass-by feature flag
 *   (default OFF) AND default-on / opt-out consent: a user contributes
 *   unless anonymousPartnerStatsOptIn is explicitly `false` (missing/true
 *   both contribute). An opted-out contribution returns { recorded: false }
 *   silently — opting out must be unobservable.
 * - Aggregates for anonymous_pass_by below the minimum-unique-contributor
 *   threshold are ZEROED (status insufficient_data, counts 0/null), not
 *   merely hidden — a small partner can never infer individuals.
 * - The configured threshold can only RAISE the floor, never lower it:
 *   effective = max(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD, configured).
 * - Raw events expire after 7 days (scheduled cleanup); only aggregates
 *   persist.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { createHash } from 'node:crypto';
import { z } from 'zod';

// ---------------------------------------------------------------------------
// Enums and constants (packages/shared/src/partner-insights.ts)
// ---------------------------------------------------------------------------

export const PARTNER_INTERACTION_TYPES = [
  'map_view',
  'profile_view',
  'navigate',
  'phone',
  'website',
  'offer_view',
  'show_code',
  'save_offer',
  'anonymous_pass_by',
] as const;
export type PartnerInteractionType = (typeof PARTNER_INTERACTION_TYPES)[number];

export const AGGREGATION_PERIODS = ['day', 'week', 'month'] as const;
export type AggregationPeriod = (typeof AGGREGATION_PERIODS)[number];

export const INSIGHT_RESULT_STATUSES = ['available', 'insufficient_data', 'no_data'] as const;
export type InsightResultStatus = (typeof INSIGHT_RESULT_STATUSES)[number];

/** Absolute floor for the unique-contributor threshold — never lowered. */
export const MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD = 10;
export const INTERACTION_EVENT_TTL_DAYS = 7;

/** Feature flag key for pass-by collection; contract default OFF. */
export const PASS_BY_FLAG_KEY = 'partnerInsightsPassBy';
export const PASS_BY_FLAG_DEFAULT = false;

const PARTNER_INSIGHTS_HASH_PREFIX = 'kcc-pi';

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const recordInteractionInputSchema = z
  .object({
    companyId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .regex(/^[A-Za-z0-9._-]+$/)
      .refine((id) => id !== '.' && id !== '..'),
    interactionType: z.enum(PARTNER_INTERACTION_TYPES),
    relatedOfferId: z
      .string()
      .trim()
      .min(1)
      .max(300)
      .regex(/^[A-Za-z0-9._-]+$/)
      .optional(),
  })
  .strict();

export type RecordInteractionInput = z.infer<typeof recordInteractionInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseRecordInteractionInput(
  data: unknown,
): ParseResult<RecordInteractionInput> {
  const result = recordInteractionInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: `Expected { companyId, interactionType: ${PARTNER_INTERACTION_TYPES.join('|')}, relatedOfferId? }.`,
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Privacy primitives
// ---------------------------------------------------------------------------

/**
 * Partner-scoped user reference (legacy buildScopedHash, verbatim): the raw
 * UID never appears on an insight event, and hashes are scoped per partner
 * so cross-partner correlation is impossible.
 */
export function buildScopedHash(companyId: string, userId: string): string {
  return createHash('sha256')
    .update(`${PARTNER_INSIGHTS_HASH_PREFIX}:${companyId}:${userId}`)
    .digest('hex')
    .slice(0, 64);
}

/** Effective threshold: configuration can only RAISE the floor (legacy). */
export function effectiveThreshold(configured: unknown): number {
  const value =
    typeof configured === 'number' && Number.isSafeInteger(configured) ? configured : 0;
  return Math.max(MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD, value);
}

// ---------------------------------------------------------------------------
// UTC period math (legacy resolvePeriodBounds)
// ---------------------------------------------------------------------------

export interface PeriodBounds {
  start: Date;
  /** Exclusive. */
  end: Date;
}

export function startOfUtcDay(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

function addUtcDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setUTCDate(result.getUTCDate() + days);
  return result;
}

export function resolvePeriodBounds(date: Date, periodType: AggregationPeriod): PeriodBounds {
  if (periodType === 'week') {
    const day = date.getUTCDay() || 7;
    const start = addUtcDays(startOfUtcDay(date), 1 - day);
    return { start, end: addUtcDays(start, 7) };
  }
  if (periodType === 'month') {
    const start = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1));
    return { start, end: new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1)) };
  }
  const start = startOfUtcDay(date);
  return { start, end: addUtcDays(start, 1) };
}

export function eventExpiry(now: Date): Date {
  return addUtcDays(now, INTERACTION_EVENT_TTL_DAYS);
}

/**
 * The previous UTC calendar day (start-of-day). Proper date arithmetic —
 * subtracting 24h in milliseconds can land on the wrong calendar day
 * around clock shifts.
 */
export function previousUtcDay(now: Date): Date {
  return addUtcDays(startOfUtcDay(now), -1);
}

// ---------------------------------------------------------------------------
// Deterministic IDs (create-if-absent dedupe, established pattern)
// ---------------------------------------------------------------------------

/**
 * One event per (company, type, UTC day, scoped user) — the legacy per-day
 * dedupe becomes the document ID.
 */
export function interactionEventId(
  companyId: string,
  interactionType: PartnerInteractionType,
  aggregationDate: Date,
  userReferenceHash: string,
): string {
  return `${companyId}_${interactionType}_${toIsoDate(aggregationDate)}_${userReferenceHash}`;
}

/** One aggregate per (company, type, periodType, periodStart). */
export function aggregateId(
  companyId: string,
  interactionType: PartnerInteractionType,
  periodType: AggregationPeriod,
  periodStart: Date,
): string {
  return `${companyId}_${interactionType}_${periodType}_${toIsoDate(periodStart)}`;
}

export function toIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------------------
// Aggregation math with threshold enforcement (legacy aggregatePeriod)
// ---------------------------------------------------------------------------

export interface AggregateMetric {
  totalCount: number;
  uniqueContributorCount: number | null;
  resultStatus: InsightResultStatus;
}

/**
 * Computes the persisted metric for one (type, period). For
 * anonymous_pass_by below the threshold, counts are ZEROED and the status
 * is insufficient_data — the true counts never persist anywhere readable.
 */
export function computeAggregateMetric(
  interactionType: PartnerInteractionType,
  totalCount: number,
  uniqueContributorCount: number,
  threshold: number,
): AggregateMetric {
  if (totalCount === 0) {
    return { totalCount: 0, uniqueContributorCount: null, resultStatus: 'no_data' };
  }
  if (interactionType === 'anonymous_pass_by' && uniqueContributorCount < threshold) {
    return { totalCount: 0, uniqueContributorCount: null, resultStatus: 'insufficient_data' };
  }
  return { totalCount, uniqueContributorCount, resultStatus: 'available' };
}
