/**
 * Finance cost model — the ESTIMATOR (pure maths, no I/O).
 *
 * Combines the sourced PRICE TABLE (pricing.ts), the labelled ASSUMPTIONS
 * (assumptions.ts) and the FUNCTION INVENTORY (inventory.ts) into one
 * `FinanceEstimate` the admin board renders. Everything here is a pure function
 * of its inputs (member count + which member-count source), so the arithmetic —
 * free-tier subtraction, the Trafikverket committed cost, FX conversion, the
 * unmapped-function flag — is unit-tested directly (model.test.ts).
 *
 * ⚠️ Every SEK figure this produces is a MODEL ESTIMATE, not an invoice. The
 * board carries that banner; this module only does the arithmetic behind it.
 *
 * THE TWO HONEST HALVES (see brief):
 *  A. COMMITTED — scheduled jobs. Cost = cadence × per-run work, independent of
 *     members. Computed exactly from SCHEDULED_JOBS. Trafikverket dominates.
 *  B. VARIABLE — usage that scales with the live member count (read from the
 *     latest metrics/{date} snapshot by the caller). Per-member usage is a
 *     labelled assumption.
 *
 * FREE TIER is applied explicitly per metric: gross → minus free tier →
 * billable → SEK. Firestore's free tier is DAILY, so Firestore metrics are
 * computed per-day (gross/day − free/day) then scaled to a month; monthly-free
 * metrics (Functions, egress, downloads) subtract a monthly allowance.
 */

import {
  DAYS_PER_MONTH,
  FIRESTORE,
  FUNCTIONS,
  MAPBOX,
  RTDB,
  SCHEDULER,
  SECRET_MANAGER,
  STORAGE,
  USD_TO_SEK,
  USD_TO_SEK_CAPTURED_ON,
  type PricingTier,
} from './pricing';
import {
  ADMIN_WEB_MAP_LOADS_PER_MONTH,
  FALLBACK_MEMBER_COUNT,
  FIXED_SUBSCRIPTIONS,
  NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH,
  NAV_USING_FRACTION,
  PER_MEMBER_PER_DAY,
  RTDB_STORAGE_BYTES,
  SECRET_MANAGER_ACTIVE_VERSIONS,
  STORAGE_BYTES_PER_MEMBER,
  TRAFIKVERKET_SITUATIONS_CAP,
  TRAFIKVERKET_SITUATIONS_PER_RUN,
  type FixedSubscription,
} from './assumptions';
import { CALLABLE_COST_CLASS, SCHEDULED_JOBS, uncostedCallables, type ScheduledJob } from './inventory';

// --- Compute-time assumptions kept local (clearly labelled, not buried) ------

/**
 * Average wall-clock seconds of a member-triggered callable. Most callables
 * return in well under a second; 0.4 s is a round, slightly generous figure.
 * Only used for Cloud Functions compute (GB-seconds / vCPU-seconds), which sits
 * far under the free tier for this app, so precision here barely moves the bill.
 */
const MEMBER_INVOCATION_AVG_SECONDS = 0.4;

/** vCPU per function instance (gen2 default). Used for vCPU-seconds compute. */
const FUNCTION_VCPU = 1;

/** Memory (GiB) of a member-triggered function instance, for GB-seconds compute. */
const PER_MEMBER_MEMORY_GIB = 0.25;

/** Decimal GB (10^9 bytes) — RTDB and Cloud Storage quote decimal GB. */
const BYTES_PER_GB = 1_000_000_000;
/** Binary GiB (2^30) — Firestore stored data quotes GiB. */
const BYTES_PER_GIB = 1024 ** 3;

// --- Result shape ------------------------------------------------------------

/** Where the member count the variable model scaled by came from. */
export type MemberCountSource = 'metrics-snapshot' | 'fallback';

/** One row in the detailed per-service table: gross → free → billable → SEK. */
export interface ServiceLine {
  /** Service, e.g. "Cloud Firestore". */
  service: string;
  /** What drives it, e.g. "Document writes (Trafikverket + members)". */
  driver: string;
  /** Unit of the gross/free/billable figures, e.g. "writes / month". */
  unit: string;
  /** Gross modelled usage per month (all sources summed). */
  gross: number;
  /** Free-tier allowance per month (daily allowances already ×DAYS_PER_MONTH). */
  freeTier: number;
  /** Billable usage after the free tier. */
  billable: number;
  /** Cost in SEK per month. */
  sekPerMonth: number;
  /** True if this is a committed (scheduled) cost, false if variable. */
  committed: boolean;
  /** True if the whole line falls under the free tier (0 SEK). */
  free: boolean;
  /** Optional explanatory note. */
  note?: string;
}

/** One scheduled job's committed contribution (the committed breakdown table). */
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

/**
 * One Mapbox product line — a single SKU (Maps SDK MAU, Nav MAU, Nav trips, or
 * the admin web loads). Each is costed on its own real pricing, then summed
 * into the vendor subtotal. Kept as separate lines so the board can show that
 * the basemap is ~free while Navigation is the line that actually grows.
 */
export interface MapboxLine {
  /** Stable id (React keys). */
  id: string;
  /** Product, e.g. "Maps SDK for Mobile (basemap)". */
  label: string;
  /** Billing driver, e.g. "Monthly Active Users" / "Trips". */
  driver: string;
  /** Human usage summary, e.g. "500 MAU (free ≤ 25,000)". */
  usage: string;
  /** Cost in SEK per month for this line. */
  sekPerMonth: number;
  /** True when this line is entirely inside its free tier (0 SEK). */
  free: boolean;
  /** Short explanatory note (rates / free tier). */
  note: string;
}

/**
 * Mapbox estimate — its own vendor section, never in the Google Cloud total.
 *
 * ⚠️ CORRECTED 2026-08-05 from a WEB per-load model to the real MOBILE billing:
 * Maps SDK (MAU-tiered basemap) + Navigation SDK (MAU + trips, the real driver)
 * + the admin web picker (per-load, negligible). `sekPerMonth` is the vendor
 * subtotal (sum of `lines`) — the field name is unchanged so the projection and
 * the grand-total composition keep working.
 */
export interface MapboxEstimate {
  /** The per-product lines (basemap, nav MAU, nav trips, admin web). */
  lines: MapboxLine[];
  /** Vendor subtotal, SEK/month (sum of `lines`). */
  sekPerMonth: number;
  /** The two labelled navigation assumptions, surfaced for transparency. */
  assumptions: {
    navUsingFraction: number;
    navTripsPerNavigatingMemberPerMonth: number;
  };
  capturedOn: string;
  source: string;
}

/** One fixed-subscription line, resolved to SEK/month (or null if unset). */
export interface SubscriptionLine {
  id: string;
  name: string;
  /** Raw amount as entered, or null if not set. */
  amount: number | null;
  currency: 'SEK' | 'USD';
  period: 'monthly' | 'annual';
  /** Normalised SEK/month, or null if `amount` is unset. */
  sekPerMonth: number | null;
  capturedOn: string;
  note?: string;
}

/**
 * One point on the forward PROJECTION curve — the modelled monthly cost at a
 * hypothetical member count. There is no historical cost data yet, so the board
 * plots this projection (clearly labelled) rather than pretending to show past
 * spend. Only the member count varies; committed cost barely moves, variable +
 * Mapbox grow.
 */
export interface ProjectionPoint {
  members: number;
  googleCloudSekPerMonth: number;
  mapboxSekPerMonth: number;
  subscriptionsSekPerMonth: number;
  grandTotalSekPerMonth: number;
}

/** The full estimate the board renders. */
export interface FinanceEstimate {
  generatedAtMs: number;
  /** USD→SEK rate the whole board converted through. */
  fx: { usdToSek: number; capturedOn: string };
  /** Live member count the variable model used, and where it came from. */
  member: { count: number; source: MemberCountSource; asOf: string | null };

  /** Google Cloud estimate — the detailed table + subtotal (SEK/month). */
  googleCloud: {
    services: ServiceLine[];
    committedJobs: CommittedJobLine[];
    /** Trafikverket's modelled write cost, SEK/month (its share of the billable writes). */
    trafikverketWritesSekPerMonth: number;
    /** Situations written per run (assumption) and the hard cap. */
    trafikverketSituationsPerRun: number;
    trafikverketSituationsCap: number;
    committedSekPerMonth: number;
    variableSekPerMonth: number;
    totalSekPerMonth: number;
  };

  /** Mapbox — separate vendor. */
  mapbox: MapboxEstimate;

  /** Fixed subscriptions / tooling — separate section. */
  fixedSubscriptions: {
    items: SubscriptionLine[];
    totalSekPerMonth: number;
    /** True if any subscription has no amount set (board shows "set your plan cost"). */
    hasUnset: boolean;
  };

  /** Grand total = Google Cloud + Mapbox + fixed subscriptions (SEK/month). */
  grandTotalSekPerMonth: number;

  /** Function inventory summary, incl. anything uncosted (needs a driver). */
  functionInventory: {
    totalCallables: number;
    scheduledJobs: number;
    byClass: Record<string, number>;
    /** Callables with no driver estimate — the board flags these, never zeroes them. */
    uncosted: string[];
  };

  /**
   * Forward projection of total monthly cost as the community grows — a
   * PROJECTION from the current model, NOT historical spend (there is none yet).
   */
  projection: ProjectionPoint[];
}

/** Input to the estimator (the caller supplies the live member count). */
export interface EstimateInput {
  memberCount: number;
  memberCountSource: MemberCountSource;
  /** The snapshot date the count came from, or null on fallback. */
  memberCountAsOf: string | null;
  /** Clock (injectable for tests). */
  now?: Date;
}

// --- Small pure helpers ------------------------------------------------------

function usdToSek(usd: number): number {
  return usd * USD_TO_SEK;
}

/** Trafikverket writes per run (the injected assumption); other jobs use their own. */
function writesPerRun(job: ScheduledJob): number {
  return job.id === 'incidents-syncTrafikverket'
    ? TRAFIKVERKET_SITUATIONS_PER_RUN
    : job.writesPerRun;
}

// --- The estimator -----------------------------------------------------------

/** Member-count anchors the forward projection is plotted at (plus the current count). */
const PROJECTION_ANCHORS = [50, 100, 250, 500, 1000, 2500];

export function estimateFinance(input: EstimateInput, includeProjection = true): FinanceEstimate {
  const now = input.now ?? new Date();
  const members = Math.max(0, Math.floor(input.memberCount));

  // ---- Firestore: gross PER DAY across committed + variable ----------------
  const committedWritesPerDay = SCHEDULED_JOBS.reduce(
    (sum, j) => sum + writesPerRun(j) * j.runsPerDay,
    0,
  );
  const committedReadsPerDay = SCHEDULED_JOBS.reduce((sum, j) => sum + j.readsPerRun * j.runsPerDay, 0);
  const committedDeletesPerDay = SCHEDULED_JOBS.reduce(
    (sum, j) => sum + j.deletesPerRun * j.runsPerDay,
    0,
  );

  const variableWritesPerDay = members * PER_MEMBER_PER_DAY.firestoreWrites;
  const variableReadsPerDay = members * PER_MEMBER_PER_DAY.firestoreReads;
  const variableDeletesPerDay = members * PER_MEMBER_PER_DAY.firestoreDeletes;

  const grossWritesPerDay = committedWritesPerDay + variableWritesPerDay;
  const grossReadsPerDay = committedReadsPerDay + variableReadsPerDay;
  const grossDeletesPerDay = committedDeletesPerDay + variableDeletesPerDay;

  const writesLine = firestoreDailyLine(
    'Cloud Firestore',
    'Document writes (Trafikverket + members)',
    grossWritesPerDay,
    FIRESTORE.free.writesPerDay,
    FIRESTORE.writeUsd,
    committedWritesPerDay >= variableWritesPerDay,
    `Trafikverket writes ${Math.round(committedWritesPerDay).toLocaleString('en')} /day of the ${Math.round(grossWritesPerDay).toLocaleString('en')} /day gross. Free tier is 20k writes/day, shared.`,
  );
  const readsLine = firestoreDailyLine(
    'Cloud Firestore',
    'Document reads (members + aggregations)',
    grossReadsPerDay,
    FIRESTORE.free.readsPerDay,
    FIRESTORE.readUsd,
    false,
    'Free tier 50k reads/day.',
  );
  const deletesLine = firestoreDailyLine(
    'Cloud Firestore',
    'Document deletes (TTL sweeps + members)',
    grossDeletesPerDay,
    FIRESTORE.free.deletesPerDay,
    FIRESTORE.deleteUsd,
    committedDeletesPerDay >= variableDeletesPerDay,
    'Free tier 20k deletes/day.',
  );

  // Firestore stored data — small; assume incidents + members' docs stay under
  // 1 GiB. Modelled as a flat, generous estimate (not per-member exact).
  const firestoreStoredGiB = estimateFirestoreStorageGiB(members);
  const storedLine = monthlyLine(
    'Cloud Firestore',
    'Stored data',
    'GiB',
    firestoreStoredGiB,
    FIRESTORE.free.storageGiB,
    FIRESTORE.storageGiBMonthUsd,
    false,
    'Aggregate estimate of stored documents; free tier 1 GiB.',
  );

  // ---- Cloud Functions -----------------------------------------------------
  const scheduledInvocationsPerMonth = SCHEDULED_JOBS.reduce(
    (sum, j) => sum + j.runsPerDay * DAYS_PER_MONTH,
    0,
  );
  const memberInvocationsPerMonth = members * PER_MEMBER_PER_DAY.functionInvocations * DAYS_PER_MONTH;
  const invocationsPerMonth = scheduledInvocationsPerMonth + memberInvocationsPerMonth;
  const invocationsLine = monthlyLine(
    'Cloud Functions',
    'Invocations (scheduled + member callables)',
    'invocations / month',
    invocationsPerMonth,
    FUNCTIONS.free.invocationsPerMonth,
    FUNCTIONS.invocationUsd,
    scheduledInvocationsPerMonth >= memberInvocationsPerMonth,
    'Free tier 2M invocations/month.',
  );

  const scheduledGbSeconds = SCHEDULED_JOBS.reduce(
    (sum, j) => sum + j.runsPerDay * DAYS_PER_MONTH * j.avgSeconds * j.memoryGiB,
    0,
  );
  const memberGbSeconds =
    memberInvocationsPerMonth * MEMBER_INVOCATION_AVG_SECONDS * (PER_MEMBER_MEMORY_GIB);
  const gbSecondsPerMonth = scheduledGbSeconds + memberGbSeconds;
  const gbSecondsLine = monthlyLine(
    'Cloud Functions',
    'Compute — memory (GB-seconds)',
    'GB-s / month',
    gbSecondsPerMonth,
    FUNCTIONS.free.gbSecondsPerMonth,
    FUNCTIONS.gbSecondUsd,
    true,
    'Free tier 400k GB-seconds/month.',
  );

  const scheduledCpuSeconds = SCHEDULED_JOBS.reduce(
    (sum, j) => sum + j.runsPerDay * DAYS_PER_MONTH * j.avgSeconds * FUNCTION_VCPU,
    0,
  );
  const memberCpuSeconds = memberInvocationsPerMonth * MEMBER_INVOCATION_AVG_SECONDS * FUNCTION_VCPU;
  const cpuSecondsPerMonth = scheduledCpuSeconds + memberCpuSeconds;
  const cpuSecondsLine = monthlyLine(
    'Cloud Functions',
    'Compute — vCPU (GHz-seconds)',
    'vCPU-s / month',
    cpuSecondsPerMonth,
    FUNCTIONS.free.ghzSecondsPerMonth,
    FUNCTIONS.ghzSecondUsd,
    true,
    'Free tier 200k vCPU-seconds/month.',
  );

  // ---- Realtime Database (live location) -----------------------------------
  const rtdbStorageGb = RTDB_STORAGE_BYTES / BYTES_PER_GB;
  const rtdbStorageLine = monthlyLine(
    'Realtime Database',
    'Stored data (live sessions)',
    'GB',
    rtdbStorageGb,
    RTDB.free.storageGb,
    RTDB.storageGbMonthUsd,
    false,
    'Live nodes are tiny + ephemeral; free tier 1 GB.',
  );
  const rtdbDownloadGbPerMonth =
    (members * PER_MEMBER_PER_DAY.rtdbDownloadBytes * DAYS_PER_MONTH) / BYTES_PER_GB;
  const rtdbDownloadLine = monthlyLine(
    'Realtime Database',
    'Downloaded bytes (watching live markers)',
    'GB / month',
    rtdbDownloadGbPerMonth,
    RTDB.free.downloadGbPerMonth,
    RTDB.downloadGbUsd,
    false,
    'Scales with members watching live location; free tier 10 GB/month.',
  );

  // ---- Cloud Storage (images) ----------------------------------------------
  const storageStoredGb = (members * STORAGE_BYTES_PER_MEMBER) / BYTES_PER_GB;
  const storageStoredLine = monthlyLine(
    'Cloud Storage',
    'Stored images',
    'GB',
    storageStoredGb,
    STORAGE.free.storageGb,
    STORAGE.storageGbMonthUsd,
    false,
    'Members’ uploaded photos; free tier 5 GB.',
  );
  const storageDownloadGbPerMonth =
    (members * PER_MEMBER_PER_DAY.storageDownloadBytes * DAYS_PER_MONTH) / BYTES_PER_GB;
  const storageDownloadLine = monthlyLine(
    'Cloud Storage',
    'Downloaded images (egress)',
    'GB / month',
    storageDownloadGbPerMonth,
    STORAGE.free.downloadGbPerMonth,
    STORAGE.downloadGbUsd,
    false,
    'Free tier 100 GB/month.',
  );

  // ---- Cloud Scheduler (per job) -------------------------------------------
  const schedulerBillableJobs = Math.max(0, SCHEDULED_JOBS.length - SCHEDULER.freeJobs);
  const schedulerLine: ServiceLine = {
    service: 'Cloud Scheduler',
    driver: 'Scheduled jobs',
    unit: 'jobs',
    gross: SCHEDULED_JOBS.length,
    freeTier: SCHEDULER.freeJobs,
    billable: schedulerBillableJobs,
    sekPerMonth: usdToSek(schedulerBillableJobs * SCHEDULER.usdPerJobPerMonth),
    committed: true,
    free: schedulerBillableJobs === 0,
    note: `${SCHEDULED_JOBS.length} scheduled jobs; 3 free per billing account, then $${SCHEDULER.usdPerJobPerMonth}/job/month.`,
  };

  // ---- Secret Manager -------------------------------------------------------
  const secretBillableVersions = Math.max(
    0,
    SECRET_MANAGER_ACTIVE_VERSIONS - SECRET_MANAGER.free.activeVersions,
  );
  const secretLine: ServiceLine = {
    service: 'Secret Manager',
    driver: 'Active secret versions',
    unit: 'versions',
    gross: SECRET_MANAGER_ACTIVE_VERSIONS,
    freeTier: SECRET_MANAGER.free.activeVersions,
    billable: secretBillableVersions,
    sekPerMonth: usdToSek(secretBillableVersions * SECRET_MANAGER.usdPerActiveVersionPerMonth),
    committed: true,
    free: secretBillableVersions === 0,
    note: 'Trafikverket API key; free tier 6 active versions.',
  };

  // ---- No-cost services (listed, never omitted) ----------------------------
  const noCostLines: ServiceLine[] = ['App Check', 'Cloud Messaging (FCM)', 'Trafikverket API'].map(
    (service) => ({
      service,
      driver: 'No metered charge',
      unit: '—',
      gross: 0,
      freeTier: 0,
      billable: 0,
      sekPerMonth: 0,
      committed: true,
      free: true,
      note:
        service === 'Trafikverket API'
          ? 'Open data (CC0); no API fee. Its COST shows up as the Firestore writes above.'
          : 'No usage-based charge on the current plan.',
    }),
  );

  const services: ServiceLine[] = [
    writesLine,
    readsLine,
    deletesLine,
    storedLine,
    invocationsLine,
    gbSecondsLine,
    cpuSecondsLine,
    rtdbStorageLine,
    rtdbDownloadLine,
    storageStoredLine,
    storageDownloadLine,
    schedulerLine,
    secretLine,
    ...noCostLines,
  ];

  const googleCloudTotal = services.reduce((sum, l) => sum + l.sekPerMonth, 0);
  const committedSek = services.filter((l) => l.committed).reduce((s, l) => s + l.sekPerMonth, 0);
  const variableSek = services.filter((l) => !l.committed).reduce((s, l) => s + l.sekPerMonth, 0);

  // Trafikverket's share of the billable writes row (proportional attribution,
  // since the daily free tier is shared across all writes).
  const trafikverketWritesSek =
    grossWritesPerDay > 0
      ? writesLine.sekPerMonth * (committedWritesPerDay / grossWritesPerDay)
      : 0;

  // ---- Committed job breakdown (Trafikverket obvious) ----------------------
  const committedJobs: CommittedJobLine[] = SCHEDULED_JOBS.map((j) => ({
    id: j.id,
    label: j.label,
    schedule: j.schedule,
    runsPerDay: j.runsPerDay,
    writesPerMonth: writesPerRun(j) * j.runsPerDay * DAYS_PER_MONTH,
    readsPerMonth: j.readsPerRun * j.runsPerDay * DAYS_PER_MONTH,
    deletesPerMonth: j.deletesPerRun * j.runsPerDay * DAYS_PER_MONTH,
    note: j.note,
  })).sort((a, b) => b.writesPerMonth - a.writesPerMonth);

  // ---- Mapbox (separate vendor) — MOBILE SDK billing (MAU + trips) ----------
  // The app is a mobile app, so the basemap is billed by Monthly Active Users
  // (≈ the active-member count) and turn-by-turn navigation is billed by Nav
  // MAU + trips. Each product is costed on its own tiered SKU (pricing.ts) and
  // summed into the vendor subtotal. The admin web picker is genuinely per-load
  // but admin-only and negligible.
  const mapbox = estimateMapbox(members);

  // ---- Fixed subscriptions (separate section) ------------------------------
  const subscriptionLines: SubscriptionLine[] = FIXED_SUBSCRIPTIONS.map(resolveSubscription);
  const subsTotal = subscriptionLines.reduce((sum, s) => sum + (s.sekPerMonth ?? 0), 0);
  const subsHasUnset = subscriptionLines.some((s) => s.sekPerMonth === null);

  // ---- Function inventory summary ------------------------------------------
  const byClass: Record<string, number> = {};
  for (const cls of Object.values(CALLABLE_COST_CLASS)) {
    byClass[cls] = (byClass[cls] ?? 0) + 1;
  }

  const grandTotal = googleCloudTotal + mapbox.sekPerMonth + subsTotal;

  // Forward projection — recompute the totals at a spread of member counts.
  // Computed with includeProjection=false so this does not recurse.
  const projection: ProjectionPoint[] = includeProjection
    ? Array.from(new Set([members, ...PROJECTION_ANCHORS]))
        .filter((m) => m >= 0)
        .sort((a, b) => a - b)
        .map((m) => {
          const point = estimateFinance(
            { ...input, memberCount: m, now },
            false,
          );
          return {
            members: m,
            googleCloudSekPerMonth: point.googleCloud.totalSekPerMonth,
            mapboxSekPerMonth: point.mapbox.sekPerMonth,
            subscriptionsSekPerMonth: point.fixedSubscriptions.totalSekPerMonth,
            grandTotalSekPerMonth: point.grandTotalSekPerMonth,
          };
        })
    : [];

  return {
    generatedAtMs: now.getTime(),
    fx: { usdToSek: USD_TO_SEK, capturedOn: USD_TO_SEK_CAPTURED_ON },
    member: {
      count: members,
      source: input.memberCountSource,
      asOf: input.memberCountAsOf,
    },
    googleCloud: {
      services,
      committedJobs,
      trafikverketWritesSekPerMonth: trafikverketWritesSek,
      trafikverketSituationsPerRun: TRAFIKVERKET_SITUATIONS_PER_RUN,
      trafikverketSituationsCap: TRAFIKVERKET_SITUATIONS_CAP,
      committedSekPerMonth: committedSek,
      variableSekPerMonth: variableSek,
      totalSekPerMonth: googleCloudTotal,
    },
    mapbox,
    fixedSubscriptions: {
      items: subscriptionLines,
      totalSekPerMonth: subsTotal,
      hasUnset: subsHasUnset,
    },
    grandTotalSekPerMonth: grandTotal,
    functionInventory: {
      totalCallables: Object.keys(CALLABLE_COST_CLASS).length,
      scheduledJobs: SCHEDULED_JOBS.length,
      byClass,
      uncosted: uncostedCallables(),
    },
    projection,
  };
}

/**
 * Firestore stored-data estimate (GiB). Incidents (~3k small docs, overwritten
 * each run) plus members' documents. Deliberately a flat, generous whole-app
 * estimate that grows slowly with members rather than a false-precision sum.
 */
function estimateFirestoreStorageGiB(members: number): number {
  const incidentsBytes = TRAFIKVERKET_SITUATIONS_CAP * 2_000; // ~2 KB/incident doc
  const perMemberBytes = 200_000; // ~200 KB of docs per member (profile, garage, drives…)
  return (incidentsBytes + members * perMemberBytes) / BYTES_PER_GIB;
}

/**
 * Marginal tiered cost in USD. `tiers` are cumulative bands sorted ascending by
 * `upTo`; each band's `usdPerUnit` is charged only on the units that fall inside
 * it. A leading $0 band expresses the free tier. Example (Maps SDK MAU): the
 * first 25,000 units cost $0, the next 100,000 cost $0.004 each, and so on — so
 * a total of 130,000 MAU costs 100,000×$0.004 + 5,000×$0.0032, never a flat
 * rate on the whole amount.
 */
function tieredUsd(units: number, tiers: readonly PricingTier[]): number {
  let cost = 0;
  let lower = 0;
  for (const tier of tiers) {
    if (units <= lower) break;
    const upper = Math.min(units, tier.upTo);
    cost += (upper - lower) * tier.usdPerUnit;
    lower = upper;
  }
  return cost;
}

/** Format an integer count for the usage summaries (thousands-grouped). */
function fmtInt(n: number): string {
  return Math.round(n).toLocaleString('en');
}

/**
 * Mapbox estimate — the corrected MOBILE model. Three products, four lines:
 *   1. Maps SDK for Mobile (basemap)   — MAU ≈ active-member count, 25k free.
 *   2. Navigation SDK — active users   — a NAV_USING_FRACTION subset, 100 free.
 *   3. Navigation SDK — trips          — nav members × trips/member, 1k free.
 *   4. Admin web map picker            — per-load GL JS, admin-only, ~0.
 * Navigation (2+3) is the real, growing cost driver; the basemap is free at
 * this app's scale and the admin picker is a rounding error. The vendor
 * subtotal is the sum, kept separate from the Google Cloud total.
 */
function estimateMapbox(members: number): MapboxEstimate {
  // 1. Maps SDK for Mobile — one MAU per active member (map-first app).
  const mapsMau = members;
  const mapsUsd = tieredUsd(mapsMau, MAPBOX.mapsSdkMobile.tiers);
  const mapsLine: MapboxLine = {
    id: 'maps-sdk-mau',
    label: 'Maps SDK for Mobile (basemap)',
    driver: 'Monthly Active Users',
    usage: `${fmtInt(mapsMau)} MAU (free ≤ ${fmtInt(MAPBOX.mapsSdkMobile.freeMau)})`,
    sekPerMonth: usdToSek(mapsUsd),
    free: mapsUsd === 0,
    note: `1 MAU per active member. Free up to ${fmtInt(MAPBOX.mapsSdkMobile.freeMau)} MAU/month, then $4.00 / 1,000 (tiered). Free at current scale.`,
  };

  // 2 + 3. Navigation SDK — a subset of members navigate; they are Nav MAU and
  // generate trips. This is the line that actually grows with the community.
  const navMau = Math.round(members * NAV_USING_FRACTION);
  const navTrips = navMau * NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH;
  const navMauUsd = tieredUsd(navMau, MAPBOX.navigationSdk.mauTiers);
  const navTripsUsd = tieredUsd(navTrips, MAPBOX.navigationSdk.tripTiers);

  const navMauLine: MapboxLine = {
    id: 'nav-sdk-mau',
    label: 'Navigation SDK — active users',
    driver: 'Navigating MAU',
    usage: `${fmtInt(navMau)} nav MAU (${Math.round(NAV_USING_FRACTION * 100)}% of members, free ≤ ${fmtInt(MAPBOX.navigationSdk.freeMau)})`,
    sekPerMonth: usdToSek(navMauUsd),
    free: navMauUsd === 0,
    note: `Turn-by-turn users — ${Math.round(NAV_USING_FRACTION * 100)}% of members (NAV_USING_FRACTION). Free ${fmtInt(MAPBOX.navigationSdk.freeMau)} MAU, then $0.30/user. The real growing Mapbox cost.`,
  };
  const navTripsLine: MapboxLine = {
    id: 'nav-sdk-trips',
    label: 'Navigation SDK — trips',
    driver: 'Trips',
    usage: `${fmtInt(navTrips)} trips (${NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH}/nav member, free ≤ ${fmtInt(MAPBOX.navigationSdk.freeTrips)})`,
    sekPerMonth: usdToSek(navTripsUsd),
    free: navTripsUsd === 0,
    note: `${NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH} trips per navigating member (NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH). Free ${fmtInt(MAPBOX.navigationSdk.freeTrips)} trips, then $0.08/trip (tiered down at scale).`,
  };

  // 4. Admin web map picker (GL JS) — genuinely per-load, admin-only, ~0.
  const adminLoads = ADMIN_WEB_MAP_LOADS_PER_MONTH;
  const adminUsd = tieredUsd(adminLoads, MAPBOX.webGlJs.tiers);
  const adminLine: MapboxLine = {
    id: 'admin-web-loads',
    label: 'Admin web map picker (GL JS)',
    driver: 'Web map loads',
    usage: `${fmtInt(adminLoads)} loads (free ≤ ${fmtInt(MAPBOX.webGlJs.freeLoadsPerMonth)})`,
    sekPerMonth: usdToSek(adminUsd),
    free: adminUsd === 0,
    note: `Admin-only picker (Seb placing pins). $5 / 1,000 web loads, first ${fmtInt(MAPBOX.webGlJs.freeLoadsPerMonth)} free — negligible.`,
  };

  const lines = [mapsLine, navMauLine, navTripsLine, adminLine];
  const sekPerMonth = lines.reduce((sum, l) => sum + l.sekPerMonth, 0);

  return {
    lines,
    sekPerMonth,
    assumptions: {
      navUsingFraction: NAV_USING_FRACTION,
      navTripsPerNavigatingMemberPerMonth: NAV_TRIPS_PER_NAVIGATING_MEMBER_PER_MONTH,
    },
    capturedOn: MAPBOX.capturedOn,
    source: MAPBOX.source,
  };
}

/**
 * Builds a Firestore service line from a PER-DAY gross and a PER-DAY free tier,
 * scaling the billable remainder to a month. Firestore free tiers are daily, so
 * this is where "only usage above the daily free tier costs money" is enforced.
 */
function firestoreDailyLine(
  service: string,
  driver: string,
  grossPerDay: number,
  freePerDay: number,
  usdPerOp: number,
  committed: boolean,
  note: string,
): ServiceLine {
  const billablePerDay = Math.max(0, grossPerDay - freePerDay);
  const grossPerMonth = grossPerDay * DAYS_PER_MONTH;
  const freePerMonth = freePerDay * DAYS_PER_MONTH;
  const billablePerMonth = billablePerDay * DAYS_PER_MONTH;
  const sek = usdToSek(billablePerMonth * usdPerOp);
  return {
    service,
    driver,
    unit: 'ops / month',
    gross: grossPerMonth,
    freeTier: freePerMonth,
    billable: billablePerMonth,
    sekPerMonth: sek,
    committed,
    free: billablePerMonth === 0,
    note,
  };
}

/**
 * Builds a service line from MONTHLY gross and a MONTHLY free allowance.
 */
function monthlyLine(
  service: string,
  driver: string,
  unit: string,
  grossPerMonth: number,
  freePerMonth: number,
  usdPerUnit: number,
  committed: boolean,
  note: string,
): ServiceLine {
  const billable = Math.max(0, grossPerMonth - freePerMonth);
  return {
    service,
    driver,
    unit,
    gross: grossPerMonth,
    freeTier: freePerMonth,
    billable,
    sekPerMonth: usdToSek(billable * usdPerUnit),
    committed,
    free: billable === 0,
    note,
  };
}

/** Normalises a fixed subscription to SEK/month (null stays null → "set your plan cost"). */
function resolveSubscription(sub: FixedSubscription): SubscriptionLine {
  let sekPerMonth: number | null = null;
  if (sub.amount !== null) {
    const monthlyAmount = sub.period === 'annual' ? sub.amount / 12 : sub.amount;
    sekPerMonth = sub.currency === 'USD' ? usdToSek(monthlyAmount) : monthlyAmount;
  }
  return {
    id: sub.id,
    name: sub.name,
    amount: sub.amount,
    currency: sub.currency,
    period: sub.period,
    sekPerMonth,
    capturedOn: sub.capturedOn,
    note: sub.note,
  };
}

/**
 * Resolves the member count for the variable model. When no metrics snapshot
 * exists yet, falls back to a labelled default and reports the fallback so the
 * board can say it is not a live figure.
 */
export function resolveMemberCount(
  latestTotalUsers: number | null,
  latestSnapshotDate: string | null,
): { count: number; source: MemberCountSource; asOf: string | null } {
  if (latestTotalUsers !== null && Number.isFinite(latestTotalUsers) && latestTotalUsers >= 0) {
    return { count: Math.floor(latestTotalUsers), source: 'metrics-snapshot', asOf: latestSnapshotDate };
  }
  return { count: FALLBACK_MEMBER_COUNT, source: 'fallback', asOf: null };
}
