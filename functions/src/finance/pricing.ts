/**
 * Finance cost model — the PRICE TABLE (single source of every unit price).
 *
 * ⚠️ READ THIS FIRST — EVERYTHING HERE IS A MODEL INPUT, NOT AN INVOICE ⚠️
 * -----------------------------------------------------------------------
 * The admin finance board estimates spend by multiplying MODELLED usage by the
 * unit prices below. It is NOT a read of the real Google Cloud bill. For the
 * invoiced figure see the Google Cloud billing console
 * (https://console.cloud.google.com/billing); Mapbox and Anthropic bill
 * separately on their own dashboards.
 *
 * HONESTY RULES this file exists to satisfy (Seb, 2026-07):
 *  - Every unit price is DATED and SOURCED right beside its value, so a
 *    reviewer can see exactly where each number came from and re-check it.
 *  - Prices are captured from the public pricing pages; GCP bills in USD, so
 *    the USD→SEK rate is a single dated constant (see FX below) that the whole
 *    model runs through. Update the rate here and the entire board re-prices.
 *  - Google Cloud rates match the deployed europe-west1 configuration and its
 *    actual billing SKUs. Free quota scope and reset period are explicit.
 *
 * HOW TO MAINTAIN
 * ---------------
 * Re-capture the prices periodically, bump the `capturedOn` date, and adjust
 * the value. Nothing else in the model hardcodes a price — they all read from
 * here — so this file is the only place a price ever changes.
 */

/** ISO date a figure was captured from its source, for the "as of" stamp. */
export type CaptureDate = string;

/**
 * The single USD→SEK conversion rate. GCP, Mapbox and (optionally) the Claude
 * subscription are quoted in USD; this is the one dated constant the whole
 * board converts through. Labelled "as of" on the page so it is obvious it is
 * a snapshot the reader may need to refresh.
 *
 * Source: Google Cloud Billing Catalog, Standard Storage Belgium SKU
 * A703-5CB6-E0BF, currencyCode=SEK, effective 2026-11-12T08:00:00Z.
 * currencyConversionRate = 9.93094999997794. This is the catalog conversion
 * used for the Google Cloud estimate, not a live bank/card exchange quote.
 */
export const USD_TO_SEK = 9.93095;
export const USD_TO_SEK_CAPTURED_ON: CaptureDate = '2026-11-12';

/**
 * Average days per month (365.25 / 12). Firestore free tiers are DAILY, so the
 * model works in gross-per-day, subtracts the daily free allowance, then scales
 * the billable remainder to a month with this constant. Using the true average
 * (not 30 or 31) keeps a monthly figure honest across month lengths.
 */
export const DAYS_PER_MONTH = 30.4375;

/** One GiB in bytes (binary — Firestore/GCS quote GiB). */
export const BYTES_PER_GIB = 1024 ** 3;

// ---------------------------------------------------------------------------
// Cloud Firestore — Standard edition.
// Standard edition, default database in europe-west1. Free tiers are DAILY
// and shared across all database usage. Automatic TTL deletes have no free tier.
// ---------------------------------------------------------------------------
export const FIRESTORE = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'https://cloud.google.com/firestore/pricing (Standard, europe-west1)',
  /** USD per document read. $0.03 / 100,000. */
  readUsd: 0.03 / 100_000,
  /** USD per document write. $0.09 / 100,000. */
  writeUsd: 0.09 / 100_000,
  /** USD per document delete. $0.01 / 100,000. */
  deleteUsd: 0.01 / 100_000,
  ttlDeleteUsd: 0.01 / 100_000,
  /** USD per GiB stored per month. */
  storageGiBMonthUsd: 0.15,
  /** Free tier — per DAY (shared across all Firestore usage). */
  free: {
    readsPerDay: 50_000,
    writesPerDay: 20_000,
    deletesPerDay: 20_000,
    /** Stored data free allowance, GiB (ongoing, not per-day). */
    storageGiB: 1,
  },
} as const;

// ---------------------------------------------------------------------------
// Cloud Functions gen2 / Cloud Run request-based billing in europe-west1.
// Source: https://cloud.google.com/run/pricing, captured 2026-11-12.
// Free allowances are shared per billing account; do not apply them per job.
// ---------------------------------------------------------------------------
export const FUNCTIONS = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'https://cloud.google.com/run/pricing (request-based, europe-west1)',
  /** USD per invocation. $0.40 / 1,000,000. */
  invocationUsd: 0.4 / 1_000_000,
  /** USD per GiB-second of memory time. */
  gibSecondUsd: 0.0000025,
  /** USD per allocated vCPU-second, not GHz-second. */
  vcpuSecondUsd: 0.000024,
  /** Europe-to-Europe internet egress, USD/GiB. */
  egressGiBUsd: 0.12,
  /** Free tier — per MONTH. */
  free: {
    invocationsPerMonth: 2_000_000,
    gibSecondsPerMonth: 360_000,
    vcpuSecondsPerMonth: 180_000,
    // The North America internet transfer allowance does not cover Belgium.
    egressGiBPerMonth: 0,
  },
} as const;

// ---------------------------------------------------------------------------
// Realtime Database Blaze. Catalog service 1C58-CF90-9614, SKUs
// 04F2-6383-80BD (download) / 6BC9-D757-743E (storage), 2026-11-12.
// Downloads are billed in GiB with a DAILY 360 MiB allowance, not a monthly pool.
// ---------------------------------------------------------------------------
export const RTDB = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'https://firebase.google.com/pricing (Realtime Database Blaze; Cloud Billing catalog)',
  storageGiBMonthUsd: 5,
  downloadGiBUsd: 1,
  free: {
    storageGiB: 1,
    downloadGiBPerDay: 360 / 1024,
  },
} as const;

// ---------------------------------------------------------------------------
// Cloud Storage for Firebase (member images). Default *.firebasestorage.app
// bucket: europe-west1, legacy REGIONAL class. Verified against the actual
// billed SKUs in Cloud Billing Catalog service 95FF-2EF5-5EA1, 2026-11-12.
// A703-5CB6-E0BF: Belgium storage $0.02/GiB-month, NO storage allowance.
// 22EB-AAE8-FBCD: download has a 100 GiB/month zero-price tier.
// 4DBF-185F-A415 / 7870-010B-2763: first 5k class A / 50k class B free.
// These legacy REGIONAL SKU tiers must not be confused with the Standard
// bucket Always Free program, whose eligibility is limited to US regions.
// ---------------------------------------------------------------------------
export const STORAGE = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'https://cloud.google.com/storage/pricing; Cloud Billing catalog 95FF-2EF5-5EA1',
  storageGiBMonthUsd: 0.02,
  downloadTiers: [
    { upTo: 100, usdPerUnit: 0 },
    { upTo: 1024, usdPerUnit: 0.12 },
    { upTo: 10240, usdPerUnit: 0.11 },
    { upTo: Infinity, usdPerUnit: 0.08 },
  ],
  uploadUsd: 0.005 / 1_000,
  downloadOperationUsd: 0.0004 / 1_000,
  free: {
    storageGiB: 0,
    downloadGiBPerMonth: 100,
    uploadsPerMonth: 5_000,
    downloadOpsPerMonth: 50_000,
  },
} as const;

// ---------------------------------------------------------------------------
// Cloud Scheduler: $0.10 per 31 job-days; first 93 job-days/month free per
// billing account (3 jobs x 31 days). Paused jobs are still charged.
// Source: https://cloud.google.com/scheduler/pricing, 2026-11-12.
// ---------------------------------------------------------------------------
export const SCHEDULER = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'https://cloud.google.com/scheduler/pricing',
  usdPerJobDay: 0.1 / 31,
  freeJobDaysPerMonth: 93,
} as const;

// ---------------------------------------------------------------------------
// Secret Manager. $0.06 / active version / month, $0.03 / 10k access ops.
// Free: 6 active versions, 10k access ops / month. Source: cloud.google.com/
// secret-manager/pricing.
// ---------------------------------------------------------------------------
export const SECRET_MANAGER = {
  capturedOn: '2026-11-12' as CaptureDate,
  source: 'cloud.google.com/secret-manager/pricing',
  usdPerActiveVersionPerMonth: 0.06,
  usdPerAccessOp: 0.03 / 10_000,
  free: {
    activeVersions: 6,
    accessOpsPerMonth: 10_000,
  },
} as const;

// ---------------------------------------------------------------------------
// Mapbox — SEPARATE VENDOR (its own invoice, never in the Google Cloud total).
//
// ⚠️ CORRECTED 2026-08-05: the member app is a MOBILE app (Mapbox Maps SDK +
// Navigation SDK for Android). Mobile SDKs are billed by MONTHLY ACTIVE USERS
// (MAU) — NOT by web GL-JS "map loads". The old per-load figure was the WEB
// pricing model and massively overstated cost for a mobile app; it is gone.
//
// The board now models THREE distinct Mapbox products, each on its real SKU:
//   1. Maps SDK for Mobile (the basemap)   — MAU-tiered, 25k free.
//   2. Navigation SDK v3 (turn-by-turn)    — MAU + per-trip, the real driver.
//   3. Admin web map picker (GL JS, #673)  — genuinely per-load, admin-only,
//                                            negligible (~50 loads/month, Seb).
//
// Every tier below is a marginal per-unit rate on the units that fall inside
// that cumulative band (charged only above the free band). Tiers are captured
// from the public pricing page — verify before treating any figure as exact.
// Source: mapbox.com/pricing, captured 2026-08-05.
// ---------------------------------------------------------------------------

/** One marginal pricing tier: charge `usdPerUnit` on units up to `upTo` (cumulative). */
export interface PricingTier {
  /** Upper bound of this cumulative band (inclusive). Use Infinity for the top band. */
  upTo: number;
  /** USD per unit charged on the units that fall inside this band. */
  usdPerUnit: number;
}

export const MAPBOX = {
  capturedOn: '2026-08-05' as CaptureDate,
  source: 'mapbox.com/pricing',

  /**
   * Maps SDK for Mobile — the Android basemap. Billed per MONTHLY ACTIVE USER
   * (a member counts as 1 MAU the first time they view a map that month).
   *   Free: up to 25,000 MAU/month
   *   25,001–125,000: $4.00 / 1,000 MAU
   *   125,001–250,000: $3.20 / 1,000
   *   250,001+:        $2.40 / 1,000
   * The app is map-first, so Maps SDK MAU ≈ the active-member count. At the
   * app's current scale (≤2,500 members) this is entirely inside the 25k free
   * band = 0 kr — the projection should reflect that honestly.
   */
  mapsSdkMobile: {
    freeMau: 25_000,
    /** Marginal MAU tiers (per-unit = per-1,000 rate ÷ 1,000). */
    tiers: [
      { upTo: 25_000, usdPerUnit: 0 },
      { upTo: 125_000, usdPerUnit: 4.0 / 1_000 },
      { upTo: 250_000, usdPerUnit: 3.2 / 1_000 },
      { upTo: Infinity, usdPerUnit: 2.4 / 1_000 },
    ] as PricingTier[],
  },

  /**
   * Navigation SDK v3 (turn-by-turn) — the REAL cost driver, and it bites far
   * sooner than the basemap. Metered Trips model: billed on BOTH active users
   * and the number of trips.
   *   Free: 100 MAU AND 1,000 trips/month
   *   MAU:   $0.30 per user (101+)
   *   Trips: $0.08/trip (1,001–50,000), $0.064 (50,001–100,000),
   *          $0.048 (100,001+)
   * A member who uses navigation counts as a Nav MAU + generates their trips.
   * The 50,001–100,000 trip tier ($0.064) sits between the two rates the
   * pricing page lists as endpoints; it never binds at this app's scale but is
   * included so the high end is not understated.
   */
  navigationSdk: {
    freeMau: 100,
    freeTrips: 1_000,
    /** Marginal MAU tiers. */
    mauTiers: [
      { upTo: 100, usdPerUnit: 0 },
      { upTo: Infinity, usdPerUnit: 0.3 },
    ] as PricingTier[],
    /** Marginal trip tiers. */
    tripTiers: [
      { upTo: 1_000, usdPerUnit: 0 },
      { upTo: 50_000, usdPerUnit: 0.08 },
      { upTo: 100_000, usdPerUnit: 0.064 },
      { upTo: Infinity, usdPerUnit: 0.048 },
    ] as PricingTier[],
  },

  /**
   * Admin web map picker (Mapbox GL JS, from #673) — this ONE genuinely is web
   * per-load pricing ($5 / 1,000 loads, first 50,000/month free), but it is
   * admin-only (Seb placing event/area pins) so it is a rounding error. Kept as
   * its own labelled line so it is visible, never folded into the member-app
   * MAU maths and never allowed to dominate.
   */
  webGlJs: {
    freeLoadsPerMonth: 50_000,
    tiers: [
      { upTo: 50_000, usdPerUnit: 0 },
      { upTo: Infinity, usdPerUnit: 5 / 1_000 },
    ] as PricingTier[],
  },
} as const;

// ---------------------------------------------------------------------------
// External free APIs — no metered CHARGE, but a real QUOTA the board must be
// honest about. Like App Check / FCM / Trafikverket, these cost 0 SEK; unlike
// them, they are rate-limited, so the exposure is a quota, not money. Modelled
// as $0 lines (see model.ts) with the quota called out in the note, so a
// reviewer sees the dependency even though it never shows up on an invoice.
// Captured 2026-08-20.
// ---------------------------------------------------------------------------

/** One external free-but-quota-bound API surfaced as a $0 line on the board. */
export interface ExternalFreeApi {
  /** Service name shown in the table. */
  service: string;
  /** Billing driver label. */
  driver: string;
  /** Note carrying the rate-quota annotation (shown on hover, like other lines). */
  note: string;
}

export const EXTERNAL_FREE_APIS: readonly ExternalFreeApi[] = [
  {
    service: 'GitHub REST API',
    driver: 'No metered charge (rate-quota bound)',
    // ~15 functions call it: in-app feedback tickets, server-error + crash
    // auto-filing, the daily-cap and claim-lag detectors, leaderboard public-JSON
    // commits, and the homepage events sync.
    note: 'Free — no per-request fee. QUOTA: 5,000 requests/hour per token (issueBudget-core.ts rate-limits issue creation against it). Used by ~15 functions (feedback tickets, error/crash auto-filing, daily-cap + claim-lag detectors, leaderboard commits, homepage events sync). Cost is 0 kr; the exposure is the hourly quota.',
  },
  {
    service: 'Overpass / OpenStreetMap API',
    driver: 'No metered charge (fair-use bound)',
    note: 'Free + keyless — no per-request fee. QUOTA: public Overpass endpoints are fair-use rate-limited and throttle heavy callers. Used by crownHunt-refreshAreaPois (weekly safe-stop POI ingestion). Cost is 0 kr; the exposure is fair-use throttling.',
  },
] as const;
