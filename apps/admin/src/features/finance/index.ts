/**
 * Finance cost board (admin portal).
 *
 * Invokes the admin-only `finance-estimate` callable and shapes its result for
 * the /finance page. The callable computes an IN-APP COST MODEL in SEK — a
 * sourced+dated price table × modelled usage — NOT the real Google Cloud bill.
 * The page renders that estimate with a permanent "estimate, not your invoice"
 * banner and a link to the billing console.
 *
 * The FinanceEstimate shape mirrors functions/src/finance/model.ts by hand: the
 * Cloud Functions codebase is standalone (it does not depend on
 * @carcommunity/shared), so — like MetricsSnapshot and the partner-insights
 * types — the writer and reader each declare the shape. Keep the two in sync.
 */

import { callAdmin } from '@/lib/callables';

// Recurring-costs CRUD (operator-entered actuals) — its own module, re-exported
// so the finance page imports everything from one feature entry point.
export * from './recurringCosts';

export type MemberCountSource = 'metrics-snapshot' | 'fallback';

/** One row in the detailed per-service table. */
export interface ServiceLine {
  service: string;
  driver: string;
  unit: string;
  gross: number;
  freeTier: number;
  billable: number;
  sekPerMonth: number;
  committed: boolean;
  free: boolean;
  note?: string;
}

export interface CommittedJobLine {
  id: string;
  label: string;
  schedule: string;
  runsPerDay: number;
  writesPerMonth: number;
  readsPerMonth: number;
  deletesPerMonth: number;
  note: string;
}

/** One Mapbox product line (Maps SDK MAU, Nav MAU, Nav trips, or admin web). */
export interface MapboxLine {
  id: string;
  label: string;
  driver: string;
  usage: string;
  sekPerMonth: number;
  free: boolean;
  note: string;
}

export interface MapboxEstimate {
  lines: MapboxLine[];
  sekPerMonth: number;
  assumptions: {
    navUsingFraction: number;
    navTripsPerNavigatingMemberPerMonth: number;
  };
  capturedOn: string;
  source: string;
}

/**
 * One operator-entered recurring cost, resolved to SEK/month by the backend.
 * Mirrors functions/src/finance/model.ts RecurringCostLine (the functions
 * codebase is standalone, so the shape is declared on both sides — keep in
 * sync). `sekPerMonth` is the normalised monthly figure (a yearly cost is /12);
 * `annualSek` is the yearly figure for the line detail; USD amounts are already
 * FX-converted.
 */
export interface RecurringCostLine {
  id: string;
  label: string;
  description: string;
  amount: number;
  currency: 'SEK' | 'USD';
  period: 'monthly' | 'yearly';
  sekPerMonth: number;
  annualSek: number;
}

export interface ProjectionPoint {
  members: number;
  googleCloudSekPerMonth: number;
  mapboxSekPerMonth: number;
  recurringCostsSekPerMonth: number;
  grandTotalSekPerMonth: number;
}

export interface FinanceEstimate {
  generatedAtMs: number;
  fx: { usdToSek: number; capturedOn: string };
  member: { count: number; source: MemberCountSource; asOf: string | null };
  googleCloud: {
    services: ServiceLine[];
    committedJobs: CommittedJobLine[];
    trafikverketWritesSekPerMonth: number;
    trafikverketSituationsPerRun: number;
    trafikverketSituationsCap: number;
    committedSekPerMonth: number;
    variableSekPerMonth: number;
    totalSekPerMonth: number;
  };
  mapbox: MapboxEstimate;
  recurringCosts: {
    items: RecurringCostLine[];
    totalSekPerMonth: number;
    count: number;
  };
  grandTotalSekPerMonth: number;
  functionInventory: {
    totalCallables: number;
    scheduledJobs: number;
    byClass: Record<string, number>;
    uncosted: string[];
  };
  projection: ProjectionPoint[];
}

/** The Google Cloud billing console — the source of the REAL invoice. */
export const GCP_BILLING_URL = 'https://console.cloud.google.com/billing';

/** Loads the on-demand cost estimate from the backend cost model. */
export function loadFinanceEstimate(): Promise<FinanceEstimate> {
  return callAdmin<FinanceEstimate>('finance-estimate', {});
}

/** Formats a SEK amount for display (Swedish grouping, 2 decimals under 100). */
export function formatSek(value: number): string {
  const decimals = value !== 0 && Math.abs(value) < 100 ? 2 : 0;
  return `${value.toLocaleString('sv-SE', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  })} kr`;
}

/** Formats a large count with Swedish grouping. */
export function formatCount(value: number): string {
  return Math.round(value).toLocaleString('sv-SE');
}
