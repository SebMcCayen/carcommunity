/**
 * Finance cost model — the FUNCTION INVENTORY (what exists, and which cost
 * driver each function maps to).
 *
 * WHY THIS FILE MAKES THE BOARD "DYNAMIC" AND HONEST
 * --------------------------------------------------
 * Two mechanisms guard against a function's cost silently disappearing:
 *
 *  1. SCHEDULED_JOBS enumerates every `onSchedule` function (grepped from
 *     functions/src) with its cadence and per-run work. The committed cost is
 *     computed exactly from these — independent of member count.
 *
 *  2. CALLABLE_COST_CLASS maps EVERY deployed callable (the contract in
 *     contracts/functions/functions.json) to a cost class. A unit test
 *     (inventory.test.ts) cross-checks this map against that contract and FAILS
 *     if a callable is added to the contract without being classified here — so
 *     a new function cannot ship without someone deciding how it costs. A class
 *     of `'uncosted'` is a valid, deliberate choice: it means "this needs a
 *     driver estimate", and the board renders it as such instead of hiding a
 *     zero. That is the brief's rule: adding a function SURFACES its cost.
 *
 * The classes fold callables into the variable model in bulk (the model costs
 * Firestore/Functions usage from per-member assumptions, not per-callable), so
 * classification is about ACKNOWLEDGEMENT, not a per-callable SEK figure.
 */

/** Which Firestore free-tiered metric a scheduled job's per-run work lands on. */
export interface ScheduledJob {
  /** Deployed function id (group-action), for display. */
  id: string;
  /**
   * Source of truth: `<path>:<export>` relative to functions/src, where
   * `<export>` is the `export const <name> = onSchedule(...)` symbol. This is the
   * key the drift guard (inventory.test.ts) matches against a fresh grep of
   * functions/src — a new `onSchedule` export FAILS CI until it appears here.
   * Unlike `id` (a deployed group-action label that can be renamed in index.ts),
   * this pins the entry to a real source export, so it cannot silently rot.
   */
  source: string;
  /** Human label. */
  label: string;
  /** The cron/interval string as written in source. */
  schedule: string;
  /** Executions per day derived from `schedule`. */
  runsPerDay: number;
  /** Firestore document writes per run (0 if none). */
  writesPerRun: number;
  /** Firestore document reads per run (aggregations count too). */
  readsPerRun: number;
  /** Firestore document deletes per run. */
  deletesPerRun: number;
  /** Average wall-clock seconds per run (for Functions compute cost). */
  avgSeconds: number;
  /** Memory configured, GiB (for GB-seconds). */
  memoryGiB: number;
  /** One-line note on what it does / where the number comes from. */
  note: string;
}

/**
 * Every scheduled function in functions/src, with its cadence and per-run work.
 * Cadences are copied from the source `schedule:` strings; keep in sync if a
 * schedule changes. Per-run write/read/delete counts are labelled estimates
 * except Trafikverket, which is near-measured (see assumptions.ts).
 *
 * DRIFT-PROOFED (2026-08-20): every entry carries a `source`
 * (`<path>:<export>`) that inventory.test.ts cross-checks against a fresh grep
 * of every `onSchedule` export in functions/src. Adding a scheduled function
 * without an entry here FAILS CI — closing the vector by which an `onSchedule`
 * function used to silently fall off the Finance board (its invocation +
 * Firestore cost becoming invisible), exactly as CALLABLE_COST_CLASS is guarded
 * for callables. `id` is the deployed group-action label (as re-exported in
 * index.ts) for display; `source` is the stable key.
 *
 * runsPerDay reference by interval: every 5 min → 288, every 10 min → 144,
 * every 15 min → 96, every 20 min → 72, every 30 min → 48, hourly → 24, every 3
 * hours → 8, every 6 hours → 4, daily → 1, weekly → ~0.143, monthly → ~0.033.
 */
export const SCHEDULED_JOBS: ScheduledJob[] = [
  {
    id: 'incidents-syncTrafikverket',
    source: 'incidents/trafikverket.ts:syncTrafikverket',
    label: 'Trafikverket import',
    schedule: '*/30 * * * *',
    runsPerDay: 48,
    // Reads the existing imported set, writes only new/changed fingerprints +
    // one freshness metadata doc, and deletes vanished imports after a guarded
    // complete response. The model injects all three measured/tunable values.
    writesPerRun: 0,
    readsPerRun: 0,
    deletesPerRun: 0,
    avgSeconds: 30,
    memoryGiB: 0.25,
    note: 'Fingerprint sync: scans ~4.6k imported deviations, skips unchanged incident writes, writes one freshness doc, and safely reconciles vanished imports. Tune from created/changed/unchangedSkipped/missingDeleted telemetry.',
  },
  {
    id: 'metrics-captureDaily',
    source: 'metrics/scheduled.ts:captureDaily',
    label: 'Growth metrics snapshot (#652)',
    schedule: '30 2 * * *',
    runsPerDay: 1,
    writesPerRun: 1,
    readsPerRun: 40,
    deletesPerRun: 0,
    avgSeconds: 10,
    memoryGiB: 0.25,
    note: '~40 count()/sum() aggregations + 1 snapshot write per day.',
  },
  {
    id: 'incidents-cleanupExpired',
    source: 'incidents/scheduled.ts:cleanupExpired',
    label: 'Incident TTL cleanup',
    schedule: '*/15 * * * *',
    runsPerDay: 96,
    writesPerRun: 0,
    readsPerRun: 10,
    deletesPerRun: 20,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Sweeps expired crowd-sourced incidents; small delete volume.',
  },
  {
    id: 'events-remindUpcoming',
    source: 'events/eventReminders.ts:remindUpcoming',
    label: 'Event reminders',
    schedule: '*/15 * * * *',
    runsPerDay: 96,
    writesPerRun: 2,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Queues reminder notifications for upcoming events.',
  },
  {
    id: 'events-autoClose',
    source: 'events/scheduled.ts:autoClose',
    label: 'Event lifecycle sweep',
    schedule: '0 * * * *',
    runsPerDay: 24,
    writesPerRun: 3,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Hourly transition of events between statuses.',
  },
  {
    id: 'events-syncHomepage',
    source: 'events/publicSite.ts:syncHomepage',
    label: 'Homepage events sync',
    schedule: '40 4 * * *',
    runsPerDay: 1,
    writesPerRun: 0,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 5,
    memoryGiB: 0.25,
    note: 'Daily regeneration of the public homepage event feed (GitHub commit only when changed; no Firestore writes).',
  },
  {
    id: 'billboards-sweepVisibility',
    source: 'billboards/scheduled.ts:sweepVisibility',
    label: 'Billboard visibility sweep',
    schedule: '*/10 * * * *',
    runsPerDay: 144,
    writesPerRun: 2,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 6,
    memoryGiB: 0.25,
    note: 'Activates/deactivates billboards on their schedule.',
  },
  {
    id: 'live-cleanupExpired',
    source: 'live/scheduled.ts:cleanupExpired',
    label: 'Live-session sweep',
    schedule: '*/5 * * * *',
    runsPerDay: 288,
    writesPerRun: 0,
    readsPerRun: 5,
    deletesPerRun: 5,
    avgSeconds: 5,
    memoryGiB: 0.25,
    note: 'Clears expired live-location discovery docs every 5 min.',
  },
  {
    id: 'crownHunt-spawnCrowns',
    source: 'crownHunt/spawnScheduled.ts:spawnCrowns',
    label: 'Kronjakt crown spawn',
    schedule: '*/10 * * * *',
    runsPerDay: 144,
    writesPerRun: 3,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 6,
    memoryGiB: 0.25,
    note: 'Spawns crowns into approved cells.',
  },
  {
    id: 'crownHunt-sweepSpawns',
    source: 'crownHunt/spawnScheduled.ts:sweepSpawns',
    label: 'Kronjakt crown sweep',
    schedule: '*/15 * * * *',
    runsPerDay: 96,
    writesPerRun: 0,
    readsPerRun: 10,
    deletesPerRun: 3,
    avgSeconds: 6,
    memoryGiB: 0.25,
    note: 'Expires uncollected crowns.',
  },
  {
    id: 'subscription-expireLapsed',
    source: 'subscription/scheduled.ts:expireLapsed',
    label: 'Subscription expiry sweep (#633)',
    schedule: 'every 3 hours',
    runsPerDay: 8,
    writesPerRun: 2,
    readsPerRun: 10,
    deletesPerRun: 0,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Downgrades lapsed entitlements.',
  },
  {
    id: 'subscription-reconcileEntitlements',
    source: 'subscription/reconcile.ts:reconcileEntitlements',
    label: 'Subscription reconciliation backstop',
    schedule: 'every 6 hours',
    runsPerDay: 4,
    // Cursor-rotated page of MAX_RECONCILE_PER_RUN (200) subscription docs +
    // one Auth getUser and one users/{uid} read per candidate; writes only on
    // the rare drift it downgrades (usually 0) plus one cursor doc. No Play
    // API calls. No-op entirely while the Google provider is disabled.
    writesPerRun: 1,
    readsPerRun: 400,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.25,
    note: 'Downgrade-only integrity backstop: re-converges entitlement claim/flag to the authoritative subscriptions record. Provider-gated (inert while off).',
  },
  {
    id: 'badges-evaluateBacklog',
    source: 'badges/scheduled.ts:evaluateBacklog',
    label: 'Badge progress sweep',
    schedule: 'every 6 hours',
    runsPerDay: 4,
    writesPerRun: 5,
    readsPerRun: 20,
    deletesPerRun: 0,
    avgSeconds: 10,
    memoryGiB: 0.25,
    note: 'Recomputes time-based badge progress.',
  },
  {
    id: 'notifications-cleanupExpired',
    source: 'notifications/scheduled.ts:cleanupExpired',
    label: 'Notification retention sweep',
    schedule: '0 5 * * *',
    runsPerDay: 1,
    // Deletes read items past 7 days + unread past 30 days (collectionGroup
    // 'items'); no writes. Delete volume is small at this scale.
    writesPerRun: 0,
    readsPerRun: 20,
    deletesPerRun: 20,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Daily retention sweep — deletes read items >7d and unread >30d.',
  },
  {
    // RELABELLED 2026-08-20: was `account-lastLoginSweep`, but its 30 3 * * *
    // cadence is the daily deletion-request purge (account/scheduled.ts), NOT a
    // login sweep. Corrected to the real deployed id + source.
    id: 'account-purgeDeleted',
    source: 'account/scheduled.ts:purgeDeleted',
    label: 'Account deletion purge',
    schedule: '30 3 * * *',
    runsPerDay: 1,
    // Reads due deletion requests (usually 0–few); for each, purgeUserData
    // hard-deletes all of that member's docs (delete-dominated) + marks the
    // request processed. Rare, but the deletes spike on a due day.
    writesPerRun: 2,
    readsPerRun: 20,
    deletesPerRun: 20,
    avgSeconds: 20,
    memoryGiB: 0.5,
    note: 'GDPR erasure — purges soft-deleted accounts once their grace period is due.',
  },
  {
    id: 'account-cleanupInactive',
    source: 'account/inactivityCleanup.ts:cleanupInactive',
    label: 'Inactive-account cleanup',
    schedule: '15 4 * * *',
    runsPerDay: 1,
    writesPerRun: 2,
    readsPerRun: 20,
    deletesPerRun: 2,
    avgSeconds: 20,
    memoryGiB: 0.5,
    note: 'Warns/removes long-inactive accounts.',
  },
  {
    id: 'partnerInsights-aggregateDaily',
    source: 'partnerInsights/scheduled.ts:aggregateDaily',
    label: 'Partner insights aggregate',
    schedule: '0 3 * * *',
    runsPerDay: 1,
    writesPerRun: 10,
    readsPerRun: 50,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.25,
    note: 'Builds daily partner interaction aggregates.',
  },
  {
    // RELABELLED 2026-08-20: was `partnerInsights-rollup`, but its 0 4 * * *
    // cadence is the retention cleanup (partnerInsights/scheduled.ts:cleanupExpired),
    // NOT a period rollup. Corrected to the real deployed id + source.
    id: 'partnerInsights-cleanupExpired',
    source: 'partnerInsights/scheduled.ts:cleanupExpired',
    label: 'Partner insights cleanup',
    schedule: '0 4 * * *',
    runsPerDay: 1,
    // Deletes partner-interaction aggregates past their retention window; a
    // bounded read to find them, delete-dominated, no writes.
    writesPerRun: 0,
    readsPerRun: 30,
    deletesPerRun: 20,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Deletes expired partner-interaction aggregates past retention.',
  },
  {
    id: 'partnerInsights-aggregateDriveHeat',
    source: 'partnerInsights/driveHeatAggregation.ts:aggregateDriveHeat_scheduled',
    label: 'Partner drive heatmap',
    schedule: '30 4 * * *',
    runsPerDay: 1,
    // Reads: one page of rides + a consent doc per distinct user + a route.bin
    // Storage download per consented ride; a single aggregate write.
    writesPerRun: 1,
    readsPerRun: 400,
    deletesPerRun: 0,
    // Route decode + H3 binning over a rolling 90-day window; the heaviest job.
    avgSeconds: 120,
    memoryGiB: 1,
    note: 'Anonymised H3 drive-density aggregate over consented drives.',
  },
  {
    id: 'communityChat-digest',
    source: 'chatchannels/communityDigest.ts:digest',
    label: 'Community digest',
    schedule: '0 18 * * *',
    runsPerDay: 1,
    writesPerRun: 3,
    readsPerRun: 20,
    deletesPerRun: 0,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Evening community chat digest.',
  },
  {
    id: 'diagnostics-cleanupExpired',
    source: 'diagnostics/scheduled.ts:cleanupExpired',
    label: 'Diagnostics retention sweep',
    schedule: '0 6 1 * *',
    runsPerDay: 1 / 30.4375,
    writesPerRun: 1,
    readsPerRun: 30,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.25,
    note: 'Monthly diagnostics retention sweep — negligible.',
  },

  // ---------------------------------------------------------------------------
  // ADDED 2026-08-20 — the six scheduled functions the cost-coverage audit
  // found deployed in source but missing from the board (invisible cost). Each
  // is verified `onSchedule` on current main; cadence copied from source, per-run
  // work estimated from reading the handler. Several call the GitHub REST API or
  // Overpass/OSM — those dependencies are surfaced as $0/quota lines in model.ts.
  // ---------------------------------------------------------------------------
  {
    id: 'feedback-syncOpenTickets',
    source: 'feedback/syncOpenTickets.ts:syncOpenTickets',
    label: 'Open-ticket mirror sync',
    schedule: 'every 5 minutes',
    runsPerDay: 288,
    // Flag-gated OFF by default (reportTicketsBrowser) — a complete no-op until
    // enabled. When ON: lists OPEN `android-issue` GitHub issues (external — see
    // the GitHub REST API $0/quota line), upserts one mirror doc per open ticket
    // (small set), reads the mirror to reconcile, deletes any now-closed ticket.
    writesPerRun: 3,
    readsPerRun: 10,
    deletesPerRun: 1,
    avgSeconds: 5,
    memoryGiB: 0.25,
    note: 'Mirrors OPEN android-issue GitHub tickets into Firestore for the in-app browser (flag-gated). GitHub list call each run.',
  },
  {
    id: 'leaderboard-generateLeaderboards',
    source: 'leaderboard/generator.ts:generateLeaderboards',
    label: 'Social leaderboard precompute',
    schedule: '0 * * * *',
    runsPerDay: 24,
    // Bounded paged scan (LEADERBOARD_SCAN_PAGE_SIZE = 500) of member point
    // buckets for BOTH the all-time and current-month boards, plus opt-out reads.
    // This is the heaviest committed READ line and it grows ~linearly with the
    // member count (paged 500 at a time) — the committed-read line to watch as
    // the community scales. Writes: the all-time + current-month board docs; the
    // public web JSON is committed to GitHub (external quota line), not Firestore.
    writesPerRun: 3,
    readsPerRun: 500,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.5,
    note: 'Hourly precompute of the social leaderboards; bounded per-member scan (grows with membership). Publishes public JSON via GitHub.',
  },
  {
    id: 'crownHunt-detectClaimLag',
    source: 'crownHunt/claimLagDetector.ts:detectClaimLag',
    label: 'Crown claim-lag detector',
    schedule: '*/20 * * * *',
    runsPerDay: 72,
    // Scans a bounded ~40-min claim window (cap MAX_CLAIMS_SCANNED = 5000;
    // typically far fewer), dedups by fingerprint, files up to MAX_ISSUES_PER_RUN
    // (10) GitHub issues + writes an issue-link doc per filed issue. GitHub calls
    // only when lag is detected (rate-limited via issueBudget-core.ts).
    writesPerRun: 2,
    readsPerRun: 100,
    deletesPerRun: 0,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Detects lagging crown claims in a bounded window; files GitHub issues (budgeted) when found.',
  },
  {
    id: 'points-detectDailyCapReached',
    source: 'points/dailyCapDetector.ts:detectDailyCapReached',
    label: 'Daily points-cap detector',
    schedule: '0 * * * *',
    runsPerDay: 24,
    // Scans the current day's dailyTotals (cap MAX_DAILY_TOTALS_SCANNED = 20000;
    // typically far fewer), files a GitHub issue for a member hitting the cap.
    writesPerRun: 1,
    readsPerRun: 80,
    deletesPerRun: 0,
    avgSeconds: 8,
    memoryGiB: 0.25,
    note: 'Hourly detector for members hitting the daily points cap; files GitHub issues (budgeted).',
  },
  {
    id: 'crownHunt-rolloverSeason',
    source: 'crownHunt/seasonRollover.ts:rolloverSeason',
    label: 'Crown season rollover',
    schedule: '15 0 * * *',
    runsPerDay: 1,
    // Ensures the current season is active, reads active seasons, and finalizes
    // any past season — reading up to SEASON_STANDINGS_LIMIT (100) standings and
    // writing the frozen results. Real work only on the first run of a new month.
    writesPerRun: 5,
    readsPerRun: 120,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.5,
    note: 'Daily just after midnight; finalizes last season + opens the new one on a month boundary.',
  },
  {
    id: 'crownHunt-refreshAreaPois',
    source: 'crownHunt/poiIngestion.ts:refreshAreaPois',
    label: 'Crown safe-stop POI refresh',
    schedule: '0 3 * * 1',
    runsPerDay: 1 / 7,
    // Weekly (Mondays 03:00). Reads confirmed safe spawn areas (~tens), queries
    // the Overpass/OSM API per area (external — see the Overpass $0/quota line),
    // and rewrites POI docs per area (bounded MAX_POIS_PER_AREA = 5000; realistic
    // volume tens–hundreds), replacing the previous set (deletes + writes).
    writesPerRun: 200,
    readsPerRun: 20,
    deletesPerRun: 50,
    avgSeconds: 60,
    memoryGiB: 0.25,
    note: 'Weekly OSM safe-stop POI ingestion per spawn area (Overpass API); rewrites the POI set.',
  },
];

/**
 * Cost class of a deployed callable. Callables are folded into the variable
 * model in bulk, so this is an ACKNOWLEDGEMENT that a function's cost is
 * accounted for, not a per-callable SEK figure.
 */
export type CallableCostClass =
  /** Member-driven; its usage is covered by the per-member variable model. */
  | 'variable-member'
  /** Admin/operator-driven; a handful of calls, negligible, but acknowledged. */
  | 'admin-rare'
  /** No metered cost of its own (pure validation / returns quickly, trivial). */
  | 'free'
  /** Not yet costed — HAS NO DRIVER ESTIMATE. The board flags these. */
  | 'uncosted';

/**
 * Every callable in contracts/functions/functions.json, mapped to a cost class.
 * This is a hand-maintained MIRROR of that contract, like MetricsSnapshot
 * mirrors its writer. inventory.test.ts asserts the two are in sync and fails
 * CI if a callable is added upstream without being classified here — which is
 * exactly how a newly-added function surfaces instead of silently costing zero.
 */
export const CALLABLE_COST_CLASS: Record<string, CallableCostClass> = {
  'auth.completeOnboarding': 'variable-member',
  'auth.recordLogin': 'variable-member',
  'subscription.verify': 'variable-member',
  'live.startSession': 'variable-member',
  'live.updatePosition': 'variable-member',
  'live.stopSession': 'variable-member',
  'live.hideMeNow': 'variable-member',
  'live.extendSession': 'variable-member',
  'live.listNearby': 'variable-member',
  'live.sendWave': 'variable-member',
  'drives.save': 'variable-member',
  // Subscription read + bounded ride page; first page also performs one
  // (Community) or two (Plus) count aggregations for the hidden-history UX.
  'drives.listHistory': 'variable-member',
  // Subscription read + a single projected scan of the tier-visible ride set
  // (no count()/sum() aggregation); all totals/maxima/month tallies are computed
  // in memory from that one snapshot.
  'drives.stats': 'variable-member',
  // Owner-only paginated deletion inventory (one look-ahead read per page).
  'drives.listDeletable': 'variable-member',
  'drives.delete': 'variable-member',
  // Owner reads one ride + one subscription doc (+ a bounded newest-5 query for
  // Community) then generates a short-lived signed Storage URL; member-driven.
  'drives.routeUrl': 'variable-member',
  'blocking.block': 'variable-member',
  'blocking.unblock': 'variable-member',
  'crownHunt.submitClaim': 'variable-member',
  'crownHunt.createPoint': 'admin-rare',
  'crownHunt.updatePoint': 'admin-rare',
  'crownHunt.activatePoint': 'admin-rare',
  'crownHunt.pausePoint': 'admin-rare',
  'crownHunt.claimSpawn': 'variable-member',
  'crownHunt.setSpawnCellApproval': 'admin-rare',
  'crownHunt.createSpawnArea': 'admin-rare',
  'crownHunt.updateSpawnArea': 'admin-rare',
  'crownHunt.deleteSpawnArea': 'admin-rare',
  'crownHunt.listSpawnAreas': 'admin-rare',
  'crownHunt.spawnDiagnostics': 'admin-rare',
  'crownHunt.deletePoint': 'admin-rare',
  'crownHunt.buyPerk': 'variable-member',
  'crownHunt.deployPerk': 'variable-member',
  'crownHunt.seedPerkCatalog': 'admin-rare',
  'events.create': 'admin-rare',
  'events.update': 'admin-rare',
  'events.publish': 'admin-rare',
  'events.cancel': 'admin-rare',
  'events.complete': 'admin-rare',
  'events.postChatMessage': 'variable-member',
  'events.reportChatMessage': 'variable-member',
  'events.removeChatMessage': 'admin-rare',
  'events.allowChatMessage': 'admin-rare',
  'events.listChatReports': 'admin-rare',
  'events.resolveChatReport': 'admin-rare',
  'events.listAttendees': 'variable-member',
  'events.checkIn': 'variable-member',
  // A creator flips their event's public-page flag a handful of times over
  // the event's whole life — admin-rare traffic shape even though members
  // may call it.
  'events.setPublicSite': 'admin-rare',
  'garage.addVehicle': 'variable-member',
  'garage.updateVehicle': 'variable-member',
  'garage.setMainVehicle': 'variable-member',
  'garage.deleteVehicle': 'variable-member',
  'garage.addVehiclePhoto': 'variable-member',
  'garage.removeVehiclePhoto': 'variable-member',
  'garage.reorderVehiclePhotos': 'variable-member',
  'badges.awardHelpfulMember': 'admin-rare',
  'badges.grantEarlyTester': 'admin-rare',
  'badges.adminSummary': 'admin-rare',
  'badges.getMyProgress': 'variable-member',
  'points.adminAdjust': 'admin-rare',
  'points.adminReverse': 'admin-rare',
  'points.recordDailyOpen': 'variable-member',
  'partners.submitApplication': 'variable-member',
  'partners.reviewApplication': 'admin-rare',
  'partners.createCompany': 'admin-rare',
  'partners.updateCompany': 'admin-rare',
  'partners.setCompanyStatus': 'admin-rare',
  'partners.createOffer': 'admin-rare',
  'partners.updateOffer': 'admin-rare',
  'partners.setOfferStatus': 'admin-rare',
  'partners.showOfferCode': 'variable-member',
  'partnerInsights.recordInteraction': 'variable-member',
  'partnerInsights.adminSummary': 'admin-rare',
  'partnerInsights.driveHeat': 'admin-rare',
  'billboards.create': 'admin-rare',
  'billboards.update': 'admin-rare',
  'billboards.activate': 'admin-rare',
  'billboards.setStatus': 'admin-rare',
  'billboards.recordInteraction': 'variable-member',
  'notifications.markRead': 'variable-member',
  'notifications.markAllRead': 'variable-member',
  'notifications.markSeen': 'variable-member',
  'notifications.delete': 'variable-member',
  'notifications.deleteAll': 'variable-member',
  'notifications.registerPushToken': 'variable-member',
  'notifications.adminSend': 'admin-rare',
  'notifications.unregisterPushToken': 'variable-member',
  'diagnostics.submitReport': 'variable-member',
  'feedback.reportIssue': 'variable-member',
  'feedback.interactWithIssue': 'variable-member',
  'errors.reportClientError': 'variable-member',
  'account.deleteAccount': 'variable-member',
  'subscription.grantEntitlement': 'admin-rare',
  'groupDrive.join': 'variable-member',
  'groupDrive.updateStatus': 'variable-member',
  'groupDrive.leave': 'variable-member',
  'admin.suspendUser': 'admin-rare',
  'admin.warnUser': 'admin-rare',
  'admin.restoreAccess': 'admin-rare',
  'admin.setFeatureFlag': 'admin-rare',
  'admin.setAdminRole': 'admin-rare',
  'admin.purgeNeverOnboarded': 'admin-rare',
  'admin.deleteUser': 'admin-rare',
  'friend.sendRequest': 'variable-member',
  'friend.respondRequest': 'variable-member',
  'friend.cancelRequest': 'variable-member',
  'friend.remove': 'variable-member',
  'friend.list': 'variable-member',
  'userSearch.members': 'variable-member',
  'dm.sendMessage': 'variable-member',
  'dm.listConversations': 'variable-member',
  'dm.getMessages': 'variable-member',
  'dm.markRead': 'variable-member',
  'dm.reportMessage': 'variable-member',
  'convoy.create': 'variable-member',
  'convoy.respond': 'variable-member',
  'convoy.start': 'variable-member',
  'convoy.end': 'variable-member',
  'convoy.list': 'variable-member',
  'convoy.leave': 'variable-member',
  'convoy.invite': 'variable-member',
  'convoy.setDestination': 'variable-member',
  'convoy.clearDestination': 'variable-member',
  'convoy.sendReaction': 'variable-member',
  'convoy.setFollowMe': 'variable-member',
  'communityChat.post': 'variable-member',
  'communityChat.list': 'variable-member',
  'communityChat.markRead': 'variable-member',
  'convoyChat.post': 'variable-member',
  'convoyChat.list': 'variable-member',
  'convoyChat.markRead': 'variable-member',
  'chatchannels.reportMessage': 'variable-member',
  'chatchannels.adminDeleteMessage': 'admin-rare',
  'moderation.reportUser': 'variable-member',
  'incidents.report': 'variable-member',
  'incidents.listNearby': 'variable-member',
  'incidents.remove': 'variable-member',
  'incidents.confirm': 'variable-member',
  'incidents.reportCleared': 'variable-member',
  'police.report': 'variable-member',
  'police.listNearby': 'variable-member',
  'police.remove': 'variable-member',
  'police.confirm': 'variable-member',
  'police.dispute': 'variable-member',
  // The finance board's own callables — an admin opens the board a few times a
  // day and edits the recurring-costs list rarely.
  'finance.estimate': 'admin-rare',
  'finance.addRecurringCost': 'admin-rare',
  'finance.updateRecurringCost': 'admin-rare',
  'finance.deleteRecurringCost': 'admin-rare',
};

/** Callable names the model has NOT costed (class 'uncosted'). Board flags these. */
export function uncostedCallables(): string[] {
  return Object.entries(CALLABLE_COST_CLASS)
    .filter(([, cls]) => cls === 'uncosted')
    .map(([name]) => name)
    .sort();
}

/**
 * Given the CURRENT deployed callable inventory (names from
 * contracts/functions/functions.json), returns any name the finance model does
 * not know about. A non-empty result means a function was added without being
 * classified — the board shows these as "uncosted — needs a driver estimate".
 * This is the runtime half of the surfacing mechanism (the test is the CI half).
 */
export function unknownCallables(inventoryNames: readonly string[]): string[] {
  return inventoryNames.filter((name) => !(name in CALLABLE_COST_CLASS)).sort();
}
