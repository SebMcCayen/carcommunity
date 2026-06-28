/**
 * Partner Insights admin feature module.
 *
 * Provides aggregate-only partner insights API clients for the admin portal.
 * Never exposes user-level data, raw coordinates, device identifiers, or raw event timestamps.
 */

import {
  ADMIN_INSIGHTS_PERIODS,
  PARTNER_INSIGHTS_ROUTE_PATHS,
  buildAdminInsightsPath,
  buildAdminInsightsSummaryPath,
  type AdminInsightsPeriod,
  type AdminPartnerInsightsResponse,
  type AdminPartnerInsightsSummaryResponse,
  type PartnerInsightsSummary,
} from '@carcommunity/shared/partner-insights';

import { apiRequest } from '../../lib/api';

export type * from '@carcommunity/shared/partner-insights';
export {
  ADMIN_INSIGHTS_PERIODS,
  PARTNER_INSIGHTS_ROUTE_PATHS,
  buildAdminInsightsPath,
  buildAdminInsightsSummaryPath,
};

export async function adminGetPartnerInsights(
  partnerId: string,
  period: AdminInsightsPeriod = 'last_30_days',
  token?: string,
): Promise<AdminPartnerInsightsResponse> {
  return apiRequest<AdminPartnerInsightsResponse>(
    `${buildAdminInsightsPath(partnerId)}?period=${encodeURIComponent(period)}`,
    { token },
  );
}

export async function adminGetPartnerInsightsSummary(
  partnerId: string,
  period: AdminInsightsPeriod = 'last_30_days',
  token?: string,
): Promise<PartnerInsightsSummary> {
  const response = await apiRequest<AdminPartnerInsightsSummaryResponse>(
    `${buildAdminInsightsSummaryPath(partnerId)}?period=${encodeURIComponent(period)}`,
    { token },
  );
  return response.data;
}
