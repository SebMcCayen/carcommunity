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

export const PARTNER_INSIGHTS_ROUTE_PATHS = {
  recordInteraction: '/v1/partners/:partnerId/interactions',
  adminInsights: '/v1/admin/partners/:partnerId/insights',
  adminInsightsSummary: '/v1/admin/partners/:partnerId/insights/summary',
} as const;

export function buildRecordInteractionPath(partnerId: string): string {
  return `/v1/partners/${partnerId}/interactions`;
}

export function buildAdminInsightsPath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/insights`;
}

export function buildAdminInsightsSummaryPath(partnerId: string): string {
  return `/v1/admin/partners/${partnerId}/insights/summary`;
}

export const ADMIN_INSIGHTS_PERIODS = [
  'last_7_days',
  'last_30_days',
  'current_month',
  'previous_month',
] as const;
export type AdminInsightsPeriod = (typeof ADMIN_INSIGHTS_PERIODS)[number];

export interface RecordPartnerInteractionRequest {
  interactionType: PartnerInteractionType;
  relatedOfferId?: string;
  idempotencyKey?: string;
}

export interface RecordPartnerInteractionResponse {
  ok: true;
  data: {
    recorded: boolean;
  };
}

export interface PartnerInsightsMetric {
  interactionType: PartnerInteractionType;
  totalCount: number;
  uniqueContributorCount?: number;
  periodStart: string;
  periodEnd: string;
  status: InsightResultStatus;
}

export interface PartnerInsightsSummary {
  partnerId: string;
  period: AdminInsightsPeriod;
  metrics: PartnerInsightsMetric[];
  generatedAt: string;
}

export interface PartnerInsightsTimeSeriesBucket {
  periodStart: string;
  periodEnd: string;
  periodType: AggregationPeriod;
  metrics: PartnerInsightsMetric[];
}

export interface AdminPartnerInsightsResponse {
  ok: true;
  data: {
    partnerId: string;
    period: AdminInsightsPeriod;
    buckets: PartnerInsightsTimeSeriesBucket[];
    generatedAt: string;
  };
}

export interface AdminPartnerInsightsSummaryResponse {
  ok: true;
  data: PartnerInsightsSummary;
}

export interface PrivacyThresholdResult {
  meetsThreshold: boolean;
  suppressedCount: boolean;
}

export interface AnonymousPassByAggregationResult {
  counted: boolean;
  reason:
    | 'opted_out'
    | 'feature_disabled'
    | 'threshold_pending'
    | 'already_counted'
    | 'no_active_location'
    | 'partner_inactive'
    | 'success';
}

export const MIN_ANONYMOUS_CONTRIBUTOR_THRESHOLD = 10;
export const PASS_BY_RADIUS_METERS = 100;
export const INTERACTION_EVENT_TTL_DAYS = 7;
export const PASS_BY_CONTRIBUTION_TTL_DAYS = 2;

// ---------------------------------------------------------------------------
// Drive heatmap (anonymised H3 heat over consented users' completed drives)
// ---------------------------------------------------------------------------

/**
 * H3 resolution the drive-heat cells are binned at. Res 10 ≈ 76 m hexagon edge
 * (~150 m across) — fine enough that the heat visibly hugs roads, coarse enough
 * that drivers concentrate into cells that can clear the ≥10 contributor floor.
 * Kept in sync with the backend DRIVE_HEAT_H3_RESOLUTION.
 */
export const DRIVE_HEAT_H3_RESOLUTION = 10;

/** Metres trimmed from each end of a drive before binning (home/work reveal). */
export const DRIVE_HEAT_ENDPOINT_TRIM_METERS = 200;

/**
 * One anonymised drive-heat cell. The ONLY per-cell shape that ever leaves the
 * backend: no user ids, no routes, no endpoints, no timestamps.
 */
export interface DriveHeatCell {
  /** H3 cell index at {@link DRIVE_HEAT_H3_RESOLUTION}. */
  h3Index: string;
  /** Distinct consented users who drove this cell — always ≥ the privacy floor. */
  contributorCount: number;
  /** Total drive-traversals of this cell (density signal, carries no identity). */
  weight: number;
}

/**
 * Raw payload of the partnerInsights.driveHeat admin callable (no { ok, data }
 * envelope — callAdmin returns this directly).
 */
export interface DriveHeatResult {
  cells: DriveHeatCell[];
  /** H3 resolution of the cells, so the client renders boundaries to match. */
  resolution: number;
  /** Rolling window (days) of completed drives folded into the aggregate. */
  windowDays: number;
  /** ISO 8601 build time of the aggregate, or null if it has never run. */
  generatedAt: string | null;
}
