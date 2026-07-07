/**
 * Partner Insights admin feature module (Phase 13j — Firebase migration).
 *
 * Migrated from the legacy `apiRequest` REST client to the Firebase callable
 * client (`callAdmin`), backed by:
 *  - partnerInsights-adminSummary → threshold-enforced aggregate counts per
 *    interaction type for one company/period (the only admin read path;
 *    partnerInsights + partnerInsightsEvents are backend-only, even for admins).
 *
 * Design decisions (documented):
 *  - Period model: the admin UI offers rolling-window options (last_7_days,
 *    last_30_days, current_month, previous_month), but the backend aggregates
 *    are CALENDAR buckets (day/week/month). Each UI option is mapped to the
 *    nearest calendar bucket (see periodToBucket). last_30_days and current_month
 *    both resolve to the current calendar month.
 *  - The legacy time-series browser (adminGetPartnerInsights → buckets) is NOT
 *    carried over: the migrated backend exposes only a single-period summary,
 *    no time-series callable. Building one is out of scope for this migration.
 *
 * Never exposes user-level data, raw coordinates, device identifiers, or raw
 * event timestamps — only threshold-safe aggregate counts.
 */

import {
  ADMIN_INSIGHTS_PERIODS,
  type AdminInsightsPeriod,
  type InsightResultStatus,
  type PartnerInsightsMetric,
  type PartnerInsightsSummary,
  type PartnerInteractionType,
} from '@carcommunity/shared/partner-insights';

import { ApiError } from '../../lib/api';
import { callAdmin } from '../../lib/callables';

export type * from '@carcommunity/shared/partner-insights';
export { ADMIN_INSIGHTS_PERIODS, ApiError };

type BackendPeriodType = 'day' | 'week' | 'month';

interface BackendMetric {
  interactionType: PartnerInteractionType;
  totalCount: number;
  uniqueContributorCount: number | null;
  status: InsightResultStatus;
}

interface BackendSummary {
  companyId: string;
  periodType: string;
  periodStart: string;
  metrics: BackendMetric[];
}

/**
 * Maps a rolling-window admin period option onto the backend's calendar
 * aggregate bucket (+ an optional reference date). The aggregates are calendar
 * buckets, so this selects the nearest one; the previous_month option shifts the
 * reference date back into the prior month.
 */
export function periodToBucket(period: AdminInsightsPeriod): {
  periodType: BackendPeriodType;
  date?: string;
} {
  switch (period) {
    case 'last_7_days':
      return { periodType: 'week' };
    case 'last_30_days':
    case 'current_month':
      return { periodType: 'month' };
    case 'previous_month': {
      const now = new Date();
      // Mid-previous-month so the reference lands squarely inside that bucket.
      const prev = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 15));
      return { periodType: 'month', date: prev.toISOString() };
    }
  }
}

/**
 * Loads the threshold-safe aggregate summary for a company/period via the
 * `partnerInsights-adminSummary` callable, adapting the backend payload into the
 * legacy `PartnerInsightsSummary` shape the page already renders.
 */
export async function adminGetPartnerInsightsSummary(
  partnerId: string,
  period: AdminInsightsPeriod = 'last_30_days',
  _token?: string,
): Promise<PartnerInsightsSummary> {
  const { periodType, date } = periodToBucket(period);
  const payload: Record<string, unknown> = { companyId: partnerId, periodType };
  if (date) payload.date = date;

  const result = await callAdmin<BackendSummary>('partnerInsights-adminSummary', payload);

  const metrics: PartnerInsightsMetric[] = result.metrics.map((m) => ({
    interactionType: m.interactionType,
    totalCount: m.totalCount,
    // Only present for anonymous_pass_by when above the privacy threshold.
    ...(m.uniqueContributorCount != null
      ? { uniqueContributorCount: m.uniqueContributorCount }
      : {}),
    periodStart: result.periodStart,
    // The single-period summary callable returns no periodEnd; the UI renders
    // only the counts, so periodStart is used for both.
    periodEnd: result.periodStart,
    status: m.status,
  }));

  return { partnerId, period, metrics, generatedAt: new Date().toISOString() };
}
