/**
 * Partner-insights admin read core (pure logic): input parsing and the
 * defensive read-time privacy-threshold re-application for
 * partnerInsights.adminSummary.
 *
 * partnerInsights aggregates are threshold-enforced at WRITE time (below the
 * minimum-unique-contributor floor, anonymous_pass_by counts are ZEROED and
 * the status is insufficient_data). This module re-applies that guard at READ
 * time as defense-in-depth: if the configured floor was raised after an
 * aggregate was written, an otherwise-available anonymous_pass_by row that now
 * falls below the floor is re-zeroed before it is ever returned. Firebase-free
 * so it is unit-testable.
 */

import { z } from 'zod';
import {
  AGGREGATION_PERIODS,
  INSIGHT_RESULT_STATUSES,
  type InsightResultStatus,
  type PartnerInteractionType,
} from './insights-core';

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

const adminSummarySchema = z
  .object({
    // companyId is composed into the partnerInsights document id (aggregateId),
    // so it is constrained to the safe doc-id charset — a '/' would break out
    // into a nested collection path (path traversal / wrong document).
    companyId: z
      .string()
      .trim()
      .min(1)
      .max(128)
      .regex(/^[A-Za-z0-9_-]+$/, 'companyId must contain only letters, digits, "_" or "-".'),
    periodType: z.enum(AGGREGATION_PERIODS).optional(),
    /** ISO 8601 instant anchoring the period; defaults to the last aggregated day. */
    date: z.string().datetime().optional(),
  })
  .strict();

export type AdminInsightsSummaryInput = z.infer<typeof adminSummarySchema>;

export function parseAdminInsightsSummaryInput(
  data: unknown,
): ParseResult<AdminInsightsSummaryInput> {
  const result = adminSummarySchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: `Expected { companyId, periodType?: ${AGGREGATION_PERIODS.join('|')}, date? (ISO 8601) }.`,
    };
  }
  return { ok: true, input: result.data };
}

/**
 * Coerces an untrusted stored resultStatus into a known InsightResultStatus.
 * A missing/malformed value fails closed to no_data (never a permissive
 * 'available'), so a corrupt aggregate can't leak counts past the threshold guard.
 */
export function coerceResultStatus(raw: unknown): InsightResultStatus {
  return (INSIGHT_RESULT_STATUSES as readonly string[]).includes(raw as string)
    ? (raw as InsightResultStatus)
    : 'no_data';
}

/** The privacy-relevant fields of a stored partnerInsights/{aggregateId} document. */
export interface StoredAggregateMetric {
  totalCount: number;
  uniqueContributorCount: number | null;
  resultStatus: InsightResultStatus;
}

/** One returned metric (PartnerInsightsMetric contract shape). */
export interface PartnerInsightsMetricOut {
  interactionType: PartnerInteractionType;
  totalCount: number;
  uniqueContributorCount: number | null;
  status: InsightResultStatus;
}

/**
 * Maps a stored aggregate (or its absence) into a safe returned metric.
 * A missing aggregate is no_data. An available anonymous_pass_by aggregate is
 * re-zeroed to insufficient_data if its unique-contributor count is now below
 * the effective threshold; every other stored status is already safe.
 */
export function applyReadThreshold(
  interactionType: PartnerInteractionType,
  stored: StoredAggregateMetric | null,
  threshold: number,
): PartnerInsightsMetricOut {
  if (stored === null) {
    return { interactionType, totalCount: 0, uniqueContributorCount: null, status: 'no_data' };
  }
  if (
    stored.resultStatus === 'available' &&
    interactionType === 'anonymous_pass_by' &&
    (stored.uniqueContributorCount ?? 0) < threshold
  ) {
    return {
      interactionType,
      totalCount: 0,
      uniqueContributorCount: null,
      status: 'insufficient_data',
    };
  }
  return {
    interactionType,
    totalCount: stored.totalCount,
    uniqueContributorCount: stored.uniqueContributorCount,
    status: stored.resultStatus,
  };
}
