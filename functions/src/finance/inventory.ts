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
 * runsPerDay reference by interval: every 5 min → 288, every 10 min → 144,
 * every 15 min → 96, every 30 min → 48, hourly → 24, every 3 hours → 8, every
 * 6 hours → 4, daily → 1, monthly → ~0.033.
 */
export const SCHEDULED_JOBS: ScheduledJob[] = [
  {
    id: 'incidents-syncTrafikverket',
    label: 'Trafikverket import',
    schedule: '*/30 * * * *',
    runsPerDay: 48,
    // Upserts one doc per active Situation. writesPerRun is filled from the
    // assumption (TRAFIKVERKET_SITUATIONS_PER_RUN) by the model, not hardcoded
    // here — see buildScheduledWrites(). Kept 0 here so the number lives in one
    // place; the model injects it.
    writesPerRun: 0,
    readsPerRun: 0,
    deletesPerRun: 0,
    avgSeconds: 30,
    memoryGiB: 0.25,
    note: 'THE dominant committed line — one Firestore write per active national road Situation, every 30 min. Write count injected from TRAFIKVERKET_SITUATIONS_PER_RUN.',
  },
  {
    id: 'metrics-captureDaily',
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
    id: 'incidents-expireStale',
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
    id: 'events-eventReminders',
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
    id: 'events-lifecycleSweep',
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
    id: 'billboards-visibilitySweep',
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
    id: 'live-sweepExpired',
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
    id: 'crownHunt-spawn',
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
    id: 'crownHunt-sweep',
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
    id: 'subscription-expirySweep',
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
    id: 'badges-progressSweep',
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
    id: 'notifications-batchSend',
    label: 'Notification batch',
    schedule: '0 5 * * *',
    runsPerDay: 1,
    writesPerRun: 5,
    readsPerRun: 20,
    deletesPerRun: 0,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Daily digest/batch notification pass.',
  },
  {
    id: 'account-lastLoginSweep',
    label: 'Account activity sweep',
    schedule: '30 3 * * *',
    runsPerDay: 1,
    writesPerRun: 2,
    readsPerRun: 20,
    deletesPerRun: 0,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Flags inactivity from lastLoginAt.',
  },
  {
    id: 'account-inactivityCleanup',
    label: 'Inactive-account cleanup',
    schedule: '15 4 * * *',
    runsPerDay: 1,
    writesPerRun: 2,
    readsPerRun: 20,
    deletesPerRun: 2,
    avgSeconds: 20,
    memoryGiB: 0.25,
    note: 'Warns/removes long-inactive accounts.',
  },
  {
    id: 'partnerInsights-aggregate',
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
    id: 'partnerInsights-rollup',
    label: 'Partner insights rollup',
    schedule: '0 4 * * *',
    runsPerDay: 1,
    writesPerRun: 5,
    readsPerRun: 30,
    deletesPerRun: 0,
    avgSeconds: 15,
    memoryGiB: 0.25,
    note: 'Rolls daily aggregates into periods.',
  },
  {
    id: 'chatchannels-communityDigest',
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
    id: 'diagnostics-monthlyReport',
    label: 'Diagnostics monthly report',
    schedule: '0 6 1 * *',
    runsPerDay: 1 / 30.4375,
    writesPerRun: 1,
    readsPerRun: 30,
    deletesPerRun: 0,
    avgSeconds: 20,
    memoryGiB: 0.25,
    note: 'Once a month — negligible.',
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
  'drives.save': 'variable-member',
  'drives.delete': 'variable-member',
  'blocking.block': 'variable-member',
  'blocking.unblock': 'variable-member',
  'crownHunt.submitClaim': 'variable-member',
  'crownHunt.createPoint': 'admin-rare',
  'crownHunt.updatePoint': 'admin-rare',
  'crownHunt.activatePoint': 'admin-rare',
  'crownHunt.pausePoint': 'admin-rare',
  'crownHunt.claimSpawn': 'variable-member',
  'crownHunt.setSpawnCellApproval': 'admin-rare',
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
  'garage.addVehicle': 'variable-member',
  'garage.updateVehicle': 'variable-member',
  'garage.setMainVehicle': 'variable-member',
  'garage.deleteVehicle': 'variable-member',
  'garage.addVehiclePhoto': 'variable-member',
  'garage.removeVehiclePhoto': 'variable-member',
  'garage.reorderVehiclePhotos': 'variable-member',
  'badges.awardHelpfulMember': 'admin-rare',
  'badges.adminSummary': 'admin-rare',
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
  'billboards.create': 'admin-rare',
  'billboards.update': 'admin-rare',
  'billboards.activate': 'admin-rare',
  'billboards.setStatus': 'admin-rare',
  'billboards.recordInteraction': 'variable-member',
  'notifications.markRead': 'variable-member',
  'notifications.markAllRead': 'variable-member',
  'notifications.delete': 'variable-member',
  'notifications.deleteAll': 'variable-member',
  'notifications.registerPushToken': 'variable-member',
  'notifications.adminSend': 'admin-rare',
  'notifications.unregisterPushToken': 'variable-member',
  'diagnostics.submitReport': 'variable-member',
  'feedback.reportIssue': 'variable-member',
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
  'communityChat.post': 'variable-member',
  'communityChat.list': 'variable-member',
  'communityChat.markRead': 'variable-member',
  'convoyChat.post': 'variable-member',
  'convoyChat.list': 'variable-member',
  'convoyChat.markRead': 'variable-member',
  'chatchannels.reportMessage': 'variable-member',
  'moderation.reportUser': 'variable-member',
  'incidents.report': 'variable-member',
  'incidents.listNearby': 'variable-member',
  'incidents.remove': 'variable-member',
  'incidents.confirm': 'variable-member',
  'incidents.reportCleared': 'variable-member',
  // The finance board's own callable — an admin opens it a few times a day.
  'finance.estimate': 'admin-rare',
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
