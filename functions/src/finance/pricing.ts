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
 *  - Where a regional SKU (europe-west1) may differ from the figure captured,
 *    that is flagged in the comment. When in doubt the model rounds toward the
 *    published us-central1 / standard number and says so — never invents a
 *    lower one to make the board look cheaper.
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
 * Source: US Federal Reserve H.10 / xe.com spot, USD→SEK.
 * Captured 2026-07-30: 1 USD ≈ 9.71 SEK (July 2026 ranged ~9.65–9.76).
 */
export const USD_TO_SEK = 9.71;
export const USD_TO_SEK_CAPTURED_ON: CaptureDate = '2026-07-30';

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
// Prices below are the published Standard-edition figures (us-central1 baseline
// as shown on cloud.google.com/firestore/pricing, captured 2026-07-31).
// europe-west1 regional SKUs may differ slightly; verify before treating any
// figure as exact. Free tiers are DAILY and shared across ALL Firestore usage.
// ---------------------------------------------------------------------------
export const FIRESTORE = {
  capturedOn: '2026-07-31' as CaptureDate,
  source: 'cloud.google.com/firestore/pricing (Standard edition, us-central1)',
  /** USD per document read. $0.03 / 100,000. */
  readUsd: 0.03 / 100_000,
  /** USD per document write. $0.09 / 100,000. */
  writeUsd: 0.09 / 100_000,
  /** USD per document delete. $0.01 / 100,000. */
  deleteUsd: 0.01 / 100_000,
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
// Cloud Functions (2nd gen). Free tier is MONTHLY.
// Source: firebase.google.com/pricing (Blaze), captured 2026-07-31.
// ---------------------------------------------------------------------------
export const FUNCTIONS = {
  capturedOn: '2026-07-31' as CaptureDate,
  source: 'firebase.google.com/pricing (Cloud Functions, Blaze)',
  /** USD per invocation. $0.40 / 1,000,000. */
  invocationUsd: 0.4 / 1_000_000,
  /** USD per GB-second of memory time. */
  gbSecondUsd: 0.0000025,
  /** USD per GHz-second (vCPU) of compute time. */
  ghzSecondUsd: 0.00001,
  /** USD per GB outbound networking (egress). */
  egressGbUsd: 0.12,
  /** Free tier — per MONTH. */
  free: {
    invocationsPerMonth: 2_000_000,
    gbSecondsPerMonth: 400_000,
    ghzSecondsPerMonth: 200_000,
    egressGbPerMonth: 5,
  },
} as const;

// ---------------------------------------------------------------------------
// Realtime Database (live location). Free tier: 1 GB stored, 10 GB/mo download.
// Source: firebase.google.com/pricing (Realtime Database, Blaze).
// ---------------------------------------------------------------------------
export const RTDB = {
  capturedOn: '2026-07-31' as CaptureDate,
  source: 'firebase.google.com/pricing (Realtime Database, Blaze)',
  /** USD per GB stored per month. */
  storageGbMonthUsd: 5,
  /** USD per GB downloaded. */
  downloadGbUsd: 1,
  free: {
    storageGb: 1,
    /** ~360 MB/day ≈ 10 GB/month. */
    downloadGbPerMonth: 10,
  },
} as const;

// ---------------------------------------------------------------------------
// Cloud Storage for Firebase (member images). Default *.firebasestorage.app
// bucket bills on Cloud Storage pricing (Standard class, US).
// Source: firebase.google.com/pricing + cloud.google.com/storage/pricing.
// ---------------------------------------------------------------------------
export const STORAGE = {
  capturedOn: '2026-07-31' as CaptureDate,
  source: 'firebase.google.com/pricing (Cloud Storage for Firebase, Blaze)',
  /** USD per GB stored per month (Standard class). */
  storageGbMonthUsd: 0.026,
  /** USD per GB downloaded (egress). */
  downloadGbUsd: 0.12,
  free: {
    storageGb: 5,
    downloadGbPerMonth: 100,
    uploadsPerMonth: 5_000,
    downloadOpsPerMonth: 50_000,
  },
} as const;

// ---------------------------------------------------------------------------
// Cloud Scheduler. Billed per JOB, not per execution: $0.10 / job / month.
// 3 jobs free PER BILLING ACCOUNT. Source: cloud.google.com/scheduler/pricing.
// ---------------------------------------------------------------------------
export const SCHEDULER = {
  capturedOn: '2026-07-31' as CaptureDate,
  source: 'cloud.google.com/scheduler/pricing',
  usdPerJobPerMonth: 0.1,
  freeJobs: 3,
} as const;

// ---------------------------------------------------------------------------
// Secret Manager. $0.06 / active version / month, $0.03 / 10k access ops.
// Free: 6 active versions, 10k access ops / month. Source: cloud.google.com/
// secret-manager/pricing.
// ---------------------------------------------------------------------------
export const SECRET_MANAGER = {
  capturedOn: '2026-07-31' as CaptureDate,
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
