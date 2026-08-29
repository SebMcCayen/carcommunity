/**
 * Cloud Functions entry point.
 *
 * Production deployments use GitHub OIDC and Google Workload Identity
 * Federation — no service account JSON file is committed to this repository.
 *
 * Region: europe-west1 (EU, low-latency for Swedish users)
 * Runtime: Node.js 22 (pinned by firebase.json functions[].runtime;
 *   engines.node in package.json is only the local tooling floor)
 * Generation: 2nd gen (firebase-functions v2 sub-path)
 */

import { onRequest } from 'firebase-functions/v2/https';
import { handleHealth } from './health';
import { eventPage } from './public/eventPage';
import { completeOnboarding } from './auth/completeOnboarding';
import { onUserCreate } from './auth/onUserCreate';
import { recordLogin } from './auth/recordLogin';
import { restoreAccess } from './admin/restoreAccess';
import { setFeatureFlag } from './admin/setFeatureFlag';
import { setAdminRole } from './admin/setAdminRole';
import { suspendUser } from './admin/suspendUser';
import { deleteUser as adminDeleteUser } from './admin/deleteUser';
import { warnUser } from './admin/warnUser';
import { purgeNeverOnboarded } from './admin/purgeNeverOnboarded';
import { cancel, complete, publish } from './events/eventLifecycle';
import { autoClose } from './events/scheduled';
import { remindUpcoming } from './events/eventReminders';
import { create, update } from './events/manageEvent';
import { postChatMessage } from './events/postChatMessage';
import { removeChatMessage } from './events/removeChatMessage';
import { allowChatMessage } from './events/allowChatMessage';
import { reportChatMessage } from './events/reportChatMessage';
import { onMessageReportCreate } from './events/onMessageReportCreate';
import { listChatReports, resolveChatReport } from './events/moderateReports';
import { listAttendees } from './events/listAttendees';
import { onRsvpWrite } from './events/onRsvpWrite';
import { onEventPublished } from './events/onEventPublished';
import { onEventCancelled } from './events/onEventCancelled';
import { checkIn } from './events/checkIn';
import { setPublicSite, onPublicSiteWrite, syncHomepage } from './events/publicSite';
import { deleteDrive } from './drives/deleteDrive';
import { block as blockUser, unblock as unblockUser } from './blocking/manageBlocks';
import { onBlockWrite } from './blocking/onBlockWrite';
import {
  addVehicle,
  addVehiclePhoto,
  deleteVehicle,
  removeVehiclePhoto,
  reorderVehiclePhotos,
  setMainVehicle,
  updateVehicle,
} from './garage/manageVehicle';
import { awardHelpfulMember } from './badges/awardHelpfulMember';
import { grantEarlyTester } from './badges/grantEarlyTester';
import { adminSummary as badgesAdminSummary } from './badges/adminSummary';
import { getMyProgress as badgesGetMyProgress } from './badges/getMyProgress';
import {
  onBadgeProgressWritten,
  onConvoyWritten as onConvoyWrittenForBadges,
  onCrownClaimWritten,
  onSpawnClaimWritten,
  onRideCreated,
  onUserLifecycleWritten,
  onVehicleCreated as onVehicleCreatedForBadges,
} from './badges/progressTriggers';
import { evaluateBacklog as badgesEvaluateBacklog } from './badges/scheduled';
import { adminAdjust, adminReverse } from './points/adminPoints';
import { recordDailyOpen } from './points/dailyOpen';
import {
  onAttendanceVerified,
  onDriveSaved,
  onIncidentConfirmed,
  onLedgerEntryCreated,
  onVehicleCreated,
} from './points/economyTriggers';
import { detectDailyCapReached } from './points/dailyCapDetector';
import {
  activatePoint,
  createPoint,
  deletePoint,
  pausePoint,
  updatePoint,
} from './crownHunt/managePoints';
import { submitClaim } from './crownHunt/submitClaim';
import { claimSpawn } from './crownHunt/claimSpawn';
import { buyPerk } from './crownHunt/buyPerk';
import { deployPerk } from './crownHunt/deployPerk';
import { seedPerkCatalog } from './crownHunt/seedPerkCatalog';
import { setSpawnCellApproval } from './crownHunt/spawnCells';
import {
  createSpawnArea,
  deleteSpawnArea,
  listSpawnAreas,
  updateSpawnArea,
} from './crownHunt/spawnAreas';
import { spawnCrowns, sweepSpawns } from './crownHunt/spawnScheduled';
import { spawnDiagnostics } from './crownHunt/spawnDiagnostics';
import { onSpawnAreaWrittenIngestPois, refreshAreaPois } from './crownHunt/poiIngestion';
import { reingestSpawnAreaPois } from './crownHunt/reingestAreaPois';
import {
  onCrownLedgerEntryForStats,
  onCrownSpawnStatsWritten,
  onPerkDeployForStats,
  onPerkDrainForStats,
} from './crownHunt/statsTriggers';
import { rolloverSeason } from './crownHunt/seasonRollover';
import { generateLeaderboards } from './leaderboard/generator';
import { detectClaimLag } from './crownHunt/claimLagDetector';
import { reviewApplication, submitApplication } from './partners/applications';
import { createCompany, setCompanyStatus, updateCompany } from './partners/manageCompany';
import { createOffer, setOfferStatus, showOfferCode, updateOffer } from './partners/manageOffer';
import { recordInteraction } from './partnerInsights/recordInteraction';
import { aggregateDaily, cleanupExpired } from './partnerInsights/scheduled';
import { adminSummary as partnerInsightsAdminSummary } from './partnerInsights/adminSummary';
import { driveHeat as partnerInsightsDriveHeat } from './partnerInsights/driveHeat';
import { aggregateDriveHeat_scheduled } from './partnerInsights/driveHeatAggregation';
import {
  activate as activateBillboard,
  create as createBillboard,
  recordInteraction as recordBillboardInteraction,
  setStatus as setBillboardStatus,
  update as updateBillboard,
} from './billboards/manageBillboard';
import { sweepVisibility as sweepBillboardVisibility } from './billboards/scheduled';
import {
  deleteAllNotifications,
  deleteNotification,
  markAllRead,
  markRead,
  markSeen as markNotificationsSeen,
} from './notifications/manageNotifications';
import { registerPushToken, unregisterPushToken } from './notifications/pushTokens';
import { onNotificationCreated } from './notifications/sendPush';
import { adminSend as notificationsAdminSend } from './notifications/adminSend';
import { cleanupExpired as cleanupExpiredNotifications } from './notifications/scheduled';
import {
  extendSession,
  hideMeNow,
  startSession,
  stopSession,
  updatePosition,
} from './live/session';
import { listNearby as listNearbyLive } from './live/listNearby';
import { sendWave as sendWaveLive } from './live/sendWave';
import { cleanupExpired as cleanupExpiredLive } from './live/scheduled';
import { grantEntitlement, verify as verifySubscription } from './subscription/verify';
import { expireLapsed as expireLapsedSubscriptions } from './subscription/scheduled';
import {
  join as joinGroupDrive,
  leave as leaveGroupDrive,
  updateStatus as updateDriveStatus,
} from './groupDrive/participants';
import { deleteAccount } from './account/deleteAccount';
import { purgeDeleted } from './account/scheduled';
import { cleanupInactive } from './account/inactivityCleanup';
import { submitReport } from './diagnostics/submitReport';
import { onSignInFailure } from './diagnostics/onSignInFailure';
import { cleanupExpired as cleanupExpiredDiagnostics } from './diagnostics/scheduled';
import { saveDrive } from './drives/saveDrive';
import { reportIssue } from './feedback/reportIssue';
import { interactWithIssue } from './feedback/interactWithIssue';
import { syncOpenTickets } from './feedback/syncOpenTickets';
import { reportClientError } from './errors/reportClientError';
import { onClientErrorReport } from './errors/onClientErrorReport';
import { onServerErrorReport } from './errors/onServerErrorReport';
import { onNewFatalIssue, onNewAnrIssue, onCrashRegression } from './crashReporting/crashReporting';
import { report as reportIncident } from './incidents/report';
import { listNearby as listNearbyIncidents } from './incidents/listNearby';
import { remove as removeIncident } from './incidents/remove';
import { confirm as confirmIncident } from './incidents/confirm';
import { reportCleared as reportIncidentCleared } from './incidents/reportCleared';
import { cleanupExpired as cleanupExpiredIncidents } from './incidents/scheduled';
import { syncTrafikverket } from './incidents/trafikverket';
import { report as reportPolice } from './police/report';
import { listNearby as listNearbyPolice } from './police/listNearby';
import { remove as removePolice } from './police/remove';
import { confirm as confirmPolice, dispute as disputePolice } from './police/verify';
import { onReportDeleted as onPoliceReportDeleted } from './police/onDeleted';
import { captureDaily as metricsCaptureDaily } from './metrics/scheduled';
import { estimate as financeEstimate } from './finance/estimate';
import {
  addRecurringCost as financeAddRecurringCost,
  updateRecurringCost as financeUpdateRecurringCost,
  deleteRecurringCost as financeDeleteRecurringCost,
} from './finance/recurringCosts';
import {
  cancelRequest as cancelFriendRequest,
  list as listFriends,
  remove as removeFriend,
  respondRequest as respondFriendRequest,
  sendRequest as sendFriendRequest,
} from './friends/manageFriends';
import { searchMembers } from './users/searchMembers';
import { onUserProfileWrite } from './users/onUserProfileWrite';
import {
  getMessages as dmGetMessages,
  listConversations as dmListConversations,
  markRead as dmMarkRead,
  sendMessage as dmSendMessage,
} from './dm/manageDirectMessages';
import {
  clearDestination as clearConvoyDestination,
  create as createConvoy,
  end as endConvoy,
  invite as inviteToConvoy,
  leave as leaveConvoy,
  list as listConvoys,
  respond as respondConvoy,
  setDestination as setConvoyDestination,
  start as startConvoy,
} from './convoy/manageConvoy';
import { sendReaction as sendConvoyReaction } from './convoy/reactions';
import { setFollowMe as setConvoyFollowMe } from './convoy/setFollowMe';
import {
  list as communityChatList,
  markRead as communityChatMarkRead,
  post as communityChatPost,
} from './chatchannels/communityChat';
import { digest as communityChatDigest } from './chatchannels/communityDigest';
import {
  list as convoyChatList,
  markRead as convoyChatMarkRead,
  post as convoyChatPost,
} from './chatchannels/convoyChat';
import { reportMessage as chatChannelsReportMessage } from './chatchannels/reportMessage';
import { reportMessage as dmReportMessage } from './dm/reportMessage';
import { reportUser as moderationReportUser } from './moderation/reportUser';

/**
 * GET /health
 *
 * Lightweight liveness check. Returns `{ status: "ok" }`.
 * Does not expose secrets, environment variables, or infrastructure details.
 */
export const health = onRequest(
  {
    region: 'europe-west1',
    minInstances: 0,
    maxInstances: 2,
    memory: '256MiB',
    timeoutSeconds: 10,
    cors: false,
  },
  (req, res) => handleHealth(req, res),
);

/**
 * Public web domain (grouped export → deployed as `publicweb-eventPage`).
 *
 * The server-rendered public event page (issue #768): /e/{eventId} and
 * /e/{eventId}.ics on the DEDICATED public hosting site (target `events`,
 * site `kcc-events` — firebase.json rewrites both paths to this function).
 * Serves ONLY publicly-enabled (events/{eventId}.publicSiteEnabled), still
 * published, not-yet-ended events; sanitized public-safe fields only —
 * attendee COUNT, place name + static-map pin, never the roster, the
 * organizer or the street address (functions/src/public/eventPage.ts).
 * An onRequest endpoint like `health`, deliberately absent from the callable
 * registry (contracts/functions/functions.json).
 */
export const publicweb = {
  eventPage,
};

/**
 * Auth domain (grouped export → deployed as `auth-completeOnboarding` and
 * `auth-onUserCreate`).
 *
 * - `auth-completeOnboarding`: callable `auth.completeOnboarding` from
 *   contracts/functions/functions.json.
 * - `auth-onUserCreate`: 1st-gen Firebase Auth onCreate trigger that
 *   provisions `users/{uid}` and `userPrivate/{uid}` on first sign-in.
 */
export const auth = {
  completeOnboarding,
  onUserCreate,
  // Records userLifecycle/{uid}.lastLoginAt (serverTimestamp) on each sign-in for
  // any authenticated, non-suspended, non-deleted account (requireActiveActor —
  // non-members included) — the queryable, admin-displayable last-activity source
  // used by the scheduled account-cleanupInactive sweep.
  recordLogin,
};

/**
 * Admin domain (grouped export → deployed as `admin-setAdminRole`,
 * `admin-suspendUser`, `admin-restoreAccess`, `admin-warnUser`,
 * `admin-setFeatureFlag`, `admin-purgeNeverOnboarded`, and `admin-deleteUser`).
 *
 * Authorization, moderation status, and custom-claim management
 * (contracts/functions/functions.json: admin.setAdminRole,
 * admin.suspendUser, admin.restoreAccess). Custom claims are set exclusively
 * here via the Admin SDK — clients can never set or modify them.
 */
export const admin = {
  setAdminRole,
  suspendUser,
  restoreAccess,
  // Phase 9o: warning record + audit + essential in-app notice; no access change.
  warnUser,
  // Phase 9m: audited, key-whitelisted writes to config/featureFlags.
  setFeatureFlag,
  // One-off, dry-run-first cleanup of never-onboarded accounts left by the
  // historical display-name leak. Reuses the account-deletion cascade; a real
  // run requires confirmToken "PURGE" and writes an adminAuditEvents record.
  purgeNeverOnboarded,
  // Admin-initiated IRREVERSIBLE erasure of a user and ALL their data. Reuses
  // the canonical purgeUserData routine (account/scheduled.ts) that backs
  // self-service deletion, plus fail-safe lockdown, self/owner/last-admin
  // guards, and an immutable adminAuditEvents (action `user.delete`) record.
  deleteUser: adminDeleteUser,
};

/**
 * Events domain (grouped export → deployed as `events-create`,
 * `events-update`, `events-publish`, `events-cancel`, `events-complete`,
 * the `events-onRsvpWrite` Firestore trigger and the `events-autoClose`
 * scheduled sweep).
 *
 * Lifecycle callables (contracts/functions/functions.json:
 * events.create/update/publish/cancel/complete) writing the teaser doc
 * `events/{eventId}` + member-gated `events/{eventId}/details/private`
 * split, plus the RSVP counter aggregation trigger. Most are admin-only;
 * `create` is member-or-admin and `complete` is creator-or-admin. Member
 * RSVPs are direct Security-Rules-gated writes to events/{eventId}/rsvps/{uid}
 * — no callable, but events-listAttendees is a member-readable roster of who
 * RSVP'd (identity joined + blocking applied server-side). events-autoClose
 * completes finished events unattended (events/scheduled.ts), and
 * events-remindUpcoming nudges going-RSVP attendees a couple of hours before an
 * event starts (events/eventReminders.ts).
 *
 * PUBLIC SITE (events-setPublicSite + events-onPublicSiteWrite +
 * events-syncHomepage; events/publicSite.ts): the creator's opt-in switch
 * putting an upcoming published event on the public community homepage and
 * behind its /e/{eventId} page, plus the trigger + daily sweep that keep the
 * homepage repo's generated data/app-events.json in sync (GitHub Contents
 * API, HOMEPAGE_REPO_TOKEN secret, no commit when unchanged).
 *
 * CHAT AUTO-MODERATION (events-onMessageReportCreate + events-allowChatMessage):
 * a message has a moderation state machine — visible → auto_hidden (trigger,
 * when >= CHAT_AUTO_HIDE_REPORTER_THRESHOLD DISTINCT reporters have reported it)
 * and the two TERMINAL admin overrides removed (events-removeChatMessage) and
 * allowed (events-allowChatMessage). The onMessageReportCreate Firestore trigger
 * recomputes the distinct-reporter count on every report and only performs the
 * visible → auto_hidden transition transactionally; it treats allowed/removed as
 * terminal, so an admin Allow is sticky (never re-hidden) and a Remove is
 * permanent. Clients render auto_hidden as a collapsed "Show reported message"
 * placeholder (reveal is per-user ephemeral, never persisted); removed as a
 * permanent moderator-removed placeholder with no reveal.
 */
export const events = {
  create,
  update,
  publish,
  cancel,
  complete,
  onRsvpWrite,
  // Firestore trigger: on the published transition of an event (member create
  // OR admin publish), fans ONE `event_created` in-app notice (push follows) out
  // to every active member bar the creator, deep-linking to the event on tap.
  onEventPublished,
  // Firestore trigger: on the CANCELLED transition of an event, fans ONE
  // `event_cancelled` in-app notice (push follows) out to every going-RSVP
  // attendee bar the creator, deep-linking to the event on tap. Edits are silent.
  onEventCancelled,
  // Scheduled lifecycle: hourly sweep completing events past their end.
  autoClose,
  // Scheduled RSVP reminder: every 15 min, one `event_reminder` in-app
  // notification (push follows) to each going attendee ~2h before start.
  remindUpcoming,
  // Chat (Phase 9c): member post/report callables + admin soft-removal.
  postChatMessage,
  reportChatMessage,
  removeChatMessage,
  // Chat auto-moderation: onCreate trigger auto-hides a message once enough
  // DISTINCT users report it, and the admin Allow override un-hides it (sticky).
  onMessageReportCreate,
  allowChatMessage,
  // Chat report moderation queue (Phase 18d): admin list + resolve.
  listChatReports,
  resolveChatReport,
  // Attendee roster: member-readable list of who RSVP'd (going/maybe/not_going),
  // identity joined + blocking applied server-side (events/listAttendees.ts).
  listAttendees,
  // Attendance proof (Phase 20 points economy): a geofence + dwell position
  // sample. Two taps ten minutes apart inside a 150 m fence verify attendance
  // and earn `event_attend_verified` (events/checkIn.ts).
  checkIn,
  // Public-site publishing (events/publicSite.ts): the creator-or-admin
  // toggle for events/{eventId}.publicSiteEnabled — the ONE switch that puts
  // an upcoming published event on the community homepage feed
  // (data/app-events.json in the homepage repo, committed via the GitHub
  // Contents API with the HOMEPAGE_REPO_TOKEN secret) and behind its public
  // /e/{eventId} page. An admin unsetting it is the moderation safety valve.
  setPublicSite,
  // Firestore trigger regenerating the homepage feed when a write can change
  // it (flag flip, cancel/complete — autoClose included — reschedule, edit);
  // any other write is a no-op. Writes only to GitHub, so no trigger loop.
  onPublicSiteWrite,
  // Daily scheduled regen: past events fall out of the generated file even
  // with zero Firestore activity, and any failed trigger sync self-heals.
  // Commits nothing when the feed is unchanged.
  syncHomepage,
};

/**
 * Drives domain (grouped export → deployed as `drives-save` and
 * `drives-delete`).
 *
 * Saved drives (contracts/functions/functions.json: drives.save,
 * drives.delete). Stats are computed server-side; route GPS data lives in
 * Cloud Storage under rideRoutes/{uid}/{rideId}/ (member-gated), never in
 * Firestore. Listing/detail are direct owner reads of rides/{rideId}.
 */
export const drives = {
  save: saveDrive,
  delete: deleteDrive,
};

/**
 * Blocking domain (grouped export → deployed as `blocking-block`,
 * `blocking-unblock`, and the `blocking-onBlockWrite` Firestore trigger).
 *
 * Directional, idempotent user blocking backed by
 * userBlocks/{uid}/blocked/{targetUid} (owner-readable; backend-only writes).
 * Ports services/api blocking-service.ts. The blocked list is a direct owner
 * read of the subcollection. onBlockWrite mirrors the block graph into RTDB
 * (liveLocationBlocks/) so live-location marker reads honour blocks — RTDB
 * security rules cannot read Firestore directly.
 */
export const blocking = {
  block: blockUser,
  unblock: unblockUser,
  onBlockWrite,
};

/**
 * Garage domain (grouped export → deployed as `garage-addVehicle`,
 * `garage-updateVehicle`, `garage-setMainVehicle`, `garage-deleteVehicle`,
 * `garage-addVehiclePhoto`, `garage-removeVehiclePhoto`,
 * `garage-reorderVehiclePhotos`).
 *
 * Vehicle management + multi-photo gallery (contracts/functions/functions.json:
 * garage.addVehicle/updateVehicle/setMainVehicle/deleteVehicle/addVehiclePhoto/
 * removeVehiclePhoto/reorderVehiclePhotos). Vehicles are authenticated-readable;
 * all writes go through these callables (per-user cap, strict no-VIN schemas,
 * server-side normalisation of the deliberately-public `registrationPlate`,
 * per-vehicle photo cap + own-prefix validation, storage cleanup).
 */
export const garage = {
  addVehicle,
  updateVehicle,
  setMainVehicle,
  deleteVehicle,
  addVehiclePhoto,
  removeVehiclePhoto,
  reorderVehiclePhotos,
};

/**
 * Badges domain (grouped export → deployed as `badges-awardHelpfulMember`,
 * `badges-adminSummary`, `badges-getMyProgress`, the seven Firestore triggers
 * below and the scheduled `badges-evaluateBacklog`).
 *
 * `badges-getMyProgress` is the one OWNER-ONLY callable in the group: it hands
 * the signed-in member a read-only projection of their OWN seven ladder
 * counters from the backend-only `badgeProgress/{uid}` document, so the profile
 * wall can draw a progress bar on every ladder (issue #799) without the client
 * ever reading the doc directly and without exposing another member's numbers.
 * It reads, never writes — a tier still cannot be forged. See getMyProgress.ts.
 *
 * Awards live at users/{uid}/badges/{badgeKey} (owner-readable, backend-only
 * writes). Flat badges are evaluated inline by their source domains
 * (garage.addVehicle → garage_created; events.complete → first_event /
 * five_events via badgeProgress counters). Two badges are MANUALLY granted by
 * an admin callable rather than earned: helpful_member (criteria-based, one
 * target + reason — badges.awardHelpfulMember) and early_tester ("Grundare",
 * the exclusive early-tester reward with NO criteria, granted to a hand-picked
 * UID list on demand — badges.grantEarlyTester). Neither is ever awarded
 * automatically.
 *
 * The six TIERED LADDERS (Kronjägare / Vägfarare / Träffräv / Trogen /
 * Konvojledare / Samlare — badges/badge-core.ts) are awarded from TRIGGERS, not
 * callables, so a tier cannot be forged: six source triggers bump
 * server-verified counters on badgeProgress/{uid} (Kronjägare has two —
 * hand-placed crownHuntClaims AND auto-spawn crownSpawnClaims — both gated so a
 * `risk_review` Kronjakt claim never counts), and the single
 * badges-onBadgeProgressWritten trigger evaluates every ladder for that one
 * member. badges-evaluateBacklog is the bounded, cursor-paged self-healing
 * sweep. See badges/progressTriggers.ts.
 */
export const badges = {
  awardHelpfulMember,
  grantEarlyTester,
  adminSummary: badgesAdminSummary,
  getMyProgress: badgesGetMyProgress,
  onBadgeProgressWritten,
  onCrownClaimWritten,
  onSpawnClaimWritten,
  onRideCreated,
  onConvoyWritten: onConvoyWrittenForBadges,
  onVehicleCreated: onVehicleCreatedForBadges,
  onUserLifecycleWritten,
  evaluateBacklog: badgesEvaluateBacklog,
};

/**
 * Points domain (grouped export → deployed as `points-adminAdjust` and
 * `points-adminReverse`).
 *
 * Kronpoäng ledger (contracts/functions/functions.json). All balance
 * mutations run through the internal creditPoints/debitPoints transaction
 * primitives (functions/src/points/ledger.ts) — never exposed as generic
 * endpoints. Wallet/ledger reads are direct owner reads of
 * pointsLedger/{uid} and its entries subcollection.
 *
 * Phase 20 adds the EARNING RULES on top of that ledger
 * (points/points-economy-core.ts is the canonical rule table, cap arithmetic
 * and streak maths; points/economy-award.ts is the single award door). All of
 * it is server-authoritative: only `recordDailyOpen` is client-triggered —
 * because "opened the app" leaves no other server footprint — and it takes no
 * arguments, is rate-limited and is idempotent per Europe/Stockholm day.
 * Everything else hangs off documents the backend already writes:
 *   - points-onDriveSaved         rides/{rideId}                    -> drive_5km
 *   - points-onIncidentConfirmed  incidents/{id}/confirmations/{uid} -> incident_report_confirmed
 *   - points-onVehicleCreated     vehicles/{vehicleId}               -> garage_first_car
 *   - points-onAttendanceVerified eventAttendance/{id}               -> event_attend_verified + event_host_success
 *   - points-onLedgerEntryCreated pointsLedger/{uid}/entries/{id}    -> folds Kronjakt crowns into the daily cap
 * `live_session_1km` has no such document (live positions are RTDB-only with
 * no history by design), so points/liveDistance.ts is called inline by
 * live.updatePosition from the session node it has already read.
 *
 * The scheduled `points-detectDailyCapReached` (points/dailyCapDetector.ts)
 * reads the same `pointsDailyTotals` counter hourly and auto-files ONE
 * deduplicated GitHub issue per Europe/Stockholm day when members reach
 * `DAILY_POINTS_CAP`, so a cap that is starting to bite honest grinders is
 * noticed and can be retuned — out of band, so the award path is never slowed.
 */
export const points = {
  adminAdjust,
  adminReverse,
  recordDailyOpen,
  onDriveSaved,
  onIncidentConfirmed,
  onVehicleCreated,
  onAttendanceVerified,
  onLedgerEntryCreated,
  detectDailyCapReached,
};

/**
 * Kronjakt domain (grouped export → deployed as `crownHunt-submitClaim`,
 * `crownHunt-createPoint`, `crownHunt-updatePoint`, `crownHunt-activatePoint`,
 * `crownHunt-pausePoint`, `crownHunt-deletePoint`, `crownHunt-claimSpawn`,
 * `crownHunt-setSpawnCellApproval`, the marked-area CRUD
 * `crownHunt-createSpawnArea`, `crownHunt-updateSpawnArea`,
 * `crownHunt-deleteSpawnArea`, `crownHunt-listSpawnAreas`, and the scheduled
 * `crownHunt-spawnCrowns` and `crownHunt-sweepSpawns`, and the OSM safe-stop POI
 * ingestion `crownHunt-onSpawnAreaWrittenIngestPois` and
 * `crownHunt-refreshAreaPois`).
 *
 * Crown Hunt geographic point hunt (contracts/functions/functions.json). It has
 * TWO sources of crowns:
 *
 * HAND-PLACED points (`crownHuntPoints`) — curated, permanent, with repeat
 * rules. `submitClaim` ports the full legacy anti-fraud validation chain;
 * awards run atomically through the Kronpoäng ledger primitives. Point
 * management is admin-only with a safety-gated activation step in which a named
 * admin confirms that exact spot is safe to stop at. Active points and own
 * claim history are direct member reads.
 *
 * AUTO-SPAWNED crowns (`crownSpawns`) — ephemeral, machine-placed near where
 * members actually are, first-come-first-served, collected via `claimSpawn`.
 * `crownHunt-spawnCrowns` tops approved cells up toward an activity-derived
 * target every 10 min and `crownHunt-sweepSpawns` reaps expired ones every
 * 15 min. Because no human sees an auto-spawned coordinate, the safety approval
 * moves up to the AREA: `setSpawnCellApproval` is the admin allow-list of grid
 * cells the spawner may place in, and the whole automatic half is behind the
 * `crownHuntSpawn` feature flag, contract default OFF. Collection requires the
 * member to be STOPPED and dwelling — two server-verified fixes, no reward for
 * arriving fast, ever.
 */
export const crownHunt = {
  submitClaim,
  createPoint,
  updatePoint,
  activatePoint,
  pausePoint,
  deletePoint,
  claimSpawn,
  setSpawnCellApproval,
  createSpawnArea,
  updateSpawnArea,
  deleteSpawnArea,
  listSpawnAreas,
  // Read-only admin troubleshooting view over the marked-area auto-spawn engine
  // (next-run countdown, candidate cells, blockers). Deployed as
  // crownHunt-spawnDiagnostics (functions/src/crownHunt/spawnDiagnostics.ts).
  spawnDiagnostics,
  spawnCrowns,
  sweepSpawns,
  // OpenStreetMap safe-stop POI ingestion for AREA spawning. The area spawn pass
  // above anchors crowns to cached parking/fuel/charging POIs inside a marked
  // area (crownSpawnAreaPois), never random points. Deployed as
  // crownHunt-onSpawnAreaWrittenIngestPois (Firestore trigger: ingest POIs when
  // an area is created active / activated / re-drawn) and crownHunt-refreshAreaPois
  // (weekly scheduled refresh). Overpass API, no key/secret required.
  onSpawnAreaWrittenIngestPois,
  refreshAreaPois,
  // Admin ON-DEMAND re-run of one area's POI ingestion — the "Retry POIs" button
  // in the diagnostics panel. Recovers from a transient Overpass 504/timeout
  // without waiting for the weekly refresh or a deactivate+reactivate. Reuses
  // runAreaPoiIngestion; an Overpass failure returns a structured result, not a
  // 500. Deployed as crownHunt-reingestSpawnAreaPois.
  reingestSpawnAreaPois,
  // Stats + leaderboard (this slice). Firestore triggers that maintain the
  // leaderboard/stat aggregates on collection, plus the daily season-rollover
  // aggregator. Deployed as crownHunt-onCrownLedgerEntryForStats,
  // crownHunt-onCrownSpawnStatsWritten and crownHunt-rolloverSeason.
  onCrownLedgerEntryForStats,
  onCrownSpawnStatsWritten,
  // Perk-usage aggregate (admin-stats PR-A). Two triggers maintain the admin-only
  // crownHuntPerkStats/{scope} counts: onPerkDeployForStats (usedByPerk, per
  // deploy) and onPerkDrainForStats (trapTriggers, per trap drain). Purchases
  // (purchasedByPerk) fold in via the perk_shop branch of
  // onCrownLedgerEntryForStats above — no extra trigger on the ledger path.
  // Deployed as crownHunt-onPerkDeployForStats, crownHunt-onPerkDrainForStats.
  onPerkDeployForStats,
  onPerkDrainForStats,
  rolloverSeason,
  // Kronjakt SHOP (Crown Hunt Shop PR1, backend core). buyPerk is the first
  // member-facing Kronpoäng SINK — a member spends KP to buy a perk, granted
  // atomically to the backend-only perkInventory; it is gated on the
  // contract-default-OFF crownHuntPerks flag (rejects while off). seedPerkCatalog
  // (admin-gated, NOT flag-gated) writes the member-readable display mirror
  // config/perkCatalog from the constants — an operator runs it to seed the doc
  // while the flag is still OFF. Perks are not deployed/used until later PRs.
  // Deployed as crownHunt-buyPerk and crownHunt-seedPerkCatalog.
  buyPerk,
  seedPerkCatalog,
  // Kronjakt PvP (Crown Hunt Shop PR3). deployPerk CONSUMES an owned perk from
  // perkInventory and applies its effect: a trap drops an invisible armed
  // activePerks doc at the caller's GPS (drained inline by live.updatePosition
  // → pvp-drain.processTrapDrains when a rival enters its 100 m radius), a
  // shield raises perkShield/{uid} + the public perkShieldPublic/{uid} status,
  // a boost arms perkBoost/{uid} so the crown-award path pays 2x. All anti-abuse
  // (1 active trap, 3 deploys/day, 300 m self-spacing, <=10 victims/trap,
  // once-per-trap-per-victim, 150 KP/day earn + 45 KP/day loss caps, 2h victim
  // cooldown, 7-day new-account immunity, shield skip) is server-enforced.
  // Entirely behind the contract-default-OFF crownHuntPerks flag — deployPerk,
  // the drain and the boost all no-op while it is OFF. Deployed as
  // crownHunt-deployPerk (functions/src/crownHunt/deployPerk.ts).
  deployPerk,
  // Scheduled COLLECT-LAG detector (every 20 min, Europe/Stockholm). Reads the
  // per-attempt claim docs the two collect paths already write (crownSpawnClaims
  // / crownHuntClaims — no hot-path writes added) and auto-files ONE deduplicated
  // GitHub issue per retry-lag SHAPE (dominant rejection + distance/accuracy
  // bucket) when members are tapping a collect 3+ times in 2 min. Uses the shared
  // autoIssueFiling pipeline + GITHUB_ISSUE_TOKEN. Deployed as
  // crownHunt-detectClaimLag (functions/src/crownHunt/claimLagDetector.ts).
  detectClaimLag,
};

/**
 * Social leaderboard domain (grouped export → deployed as the scheduled
 * `leaderboard-generateLeaderboards`).
 *
 * The precompute behind the social screen's competitive board. A scheduled
 * Admin-SDK generator (functions/src/leaderboard/generator.ts) assembles ONE
 * client-readable document `leaderboards/{scope}` per scope — this PR ships the
 * ALL-TIME scope (`leaderboards/alltime`) — holding each category's top-10 as an
 * ordered `[{rank, uid, displayName, avatarPath, value}]` array, so a member
 * renders the whole board from a single cheap read.
 *
 * Categories (owner-approved all-time set): crownPoints (from the maintained
 * crownHuntLeaderboardEntries all-time counters), and the four badgeProgress
 * counters distance (lifetimeDistanceMeters), events (completedEventsAttended —
 * the historic field name), convoys (convoysLed) and streak (bestDayStreak).
 * Opt-out (userPrivate/{uid}.leaderboardOptOut) and deleted members (no
 * users/{uid} doc) are filtered SERVER-SIDE before the board is written, so an
 * opted-out member is never published. The pure ranking/assembly lives in
 * leaderboard-core.ts; there is NO callable here (nothing is client-invoked), so
 * this scheduled function is deliberately absent from the callable registry
 * (contracts/functions/functions.json). The monthly board + the public web JSON
 * + the Android UI are follow-up PRs that reuse this same core and doc shape.
 */
export const leaderboard = {
  generateLeaderboards,
};

/**
 * Partners domain (grouped export → deployed as `partners-…`).
 *
 * Partner companies, offers, and applications
 * (contracts/functions/functions.json). Offers use a three-tier privacy
 * split: teaser (authenticated read), member detail subdocument, and the
 * backend-only discount code served exclusively by partners.showOfferCode.
 * Applications are never client-readable. Saved offers are direct member
 * bookmark writes under users/{uid}/savedOffers.
 */
export const partners = {
  submitApplication,
  reviewApplication,
  createCompany,
  updateCompany,
  setCompanyStatus,
  createOffer,
  updateOffer,
  setOfferStatus,
  showOfferCode,
};

/**
 * Partner insights domain (grouped export → deployed as
 * `partnerInsights-recordInteraction`, `partnerInsights-adminSummary`,
 * `partnerInsights-driveHeat`, plus the scheduled functions
 * `partnerInsights-aggregateDaily`, `partnerInsights-cleanupExpired` and
 * `partnerInsights-aggregateDriveHeat`).
 *
 * Privacy-critical (contracts/functions/functions.json): events carry only
 * partner-scoped user hashes, anonymous_pass_by requires the flag and
 * default-on consent (only an explicit opt-out is silently excluded),
 * aggregates enforce the minimum-contributor
 * threshold with zeroed below-threshold counts, and raw events expire
 * after 7 days. The drive heatmap adds an anonymised H3 heat aggregate over
 * consented users' completed drives, floored at ≥10 unique contributors per
 * hex with endpoints trimmed (driveHeatAggregation.ts / drive-heat-core.ts).
 */
export const partnerInsights = {
  recordInteraction,
  adminSummary: partnerInsightsAdminSummary,
  driveHeat: partnerInsightsDriveHeat,
  aggregateDaily,
  cleanupExpired,
  aggregateDriveHeat: aggregateDriveHeat_scheduled,
};

/**
 * Digital billboards domain (grouped export → deployed as
 * `billboards-create`, `billboards-update`, `billboards-activate`,
 * `billboards-setStatus`, `billboards-recordInteraction`,
 * `billboards-sweepVisibility`).
 *
 * Sponsored map billboards (contracts/functions/functions.json). Public
 * reads of active billboards; activation is a six-point safety gate with
 * an audited approval reason and an active sponsoring partner; billboard
 * taps flow into the partner-insights privacy pipeline.
 *
 * `sweepVisibility` is the scheduled half of the map-visibility invariant the
 * lifecycle callables maintain: it owns only the transitions the CLOCK causes —
 * an availability window opening or expiring with nobody touching the record.
 * See billboards/scheduled.ts.
 */
export const billboards = {
  create: createBillboard,
  update: updateBillboard,
  activate: activateBillboard,
  setStatus: setBillboardStatus,
  recordInteraction: recordBillboardInteraction,
  sweepVisibility: sweepBillboardVisibility,
};

/**
 * Notifications domain (grouped export → deployed as
 * `notifications-markRead`, `notifications-markAllRead`,
 * `notifications-delete`, `notifications-deleteAll`,
 * `notifications-registerPushToken`, `notifications-unregisterPushToken`,
 * the scheduled `notifications-cleanupExpired`, and the Firestore trigger
 * `notifications-onNotificationCreated`).
 *
 * Durable in-app inbox (contracts/functions/functions.json): owner-only
 * reads of `notifications/{uid}/items/`, backend-only writes (delivery via
 * writeInAppNotification, read-state via the mark callables, member-initiated
 * removal via the delete callables), push token registrations, and the daily
 * retention sweep (unread 30 days, read 7 days).
 *
 * The delete callables are what let a member clear their own inbox (the
 * Android swipe-to-delete and "delete all"); retention still expires whatever
 * they leave behind. Their ownership is structural — every reference is built
 * from the authenticated uid — so no caller can name another member's inbox.
 *
 * `notifications-onNotificationCreated` is the FCM delivery path: a create
 * trigger on `notifications/{uid}/items/{id}` that pushes the item to the
 * member's registered devices. It hangs off the OUTPUT of
 * writeInAppNotification precisely so push inherits the in-app opt-out
 * decision structurally — no inbox document (opted out / suspended / deleted)
 * means no push, and the decision is re-derived through decidePushDelivery,
 * which calls decideInAppDelivery internally. Sends are data-only multicasts
 * batched at 500 tokens; tokens FCM reports as dead are pruned from the
 * registry (functions/src/notifications/sendPush.ts).
 */
export const notifications = {
  markRead,
  markAllRead,
  markSeen: markNotificationsSeen,
  // Aliased because `delete` is a reserved word and cannot be an import
  // binding; the deployed name is still `notifications-delete`.
  delete: deleteNotification,
  deleteAll: deleteAllNotifications,
  registerPushToken,
  unregisterPushToken,
  adminSend: notificationsAdminSend,
  cleanupExpired: cleanupExpiredNotifications,
  onNotificationCreated,
};

/**
 * Diagnostics domain (grouped export → deployed as
 * `diagnostics-submitReport` and the scheduled
 * `diagnostics-cleanupExpired`).
 *
 * Crash/error telemetry (contracts/functions/functions.json): the only
 * PUBLIC callable (anonymous reports allowed — sign-in failures must be
 * reportable; App Check still applies in production), with all privacy
 * sanitization server-side (tokens/credentials/coordinates/stack traces
 * stripped, bounded scalars only, dedup fingerprints). Admin-only
 * Firestore reads; 90-day retention swept monthly. Replaces the Phase 8
 * errorReports scaffold.
 *
 * `diagnostics-onSignInFailure` is a Firestore create trigger on
 * diagnosticsReports/{reportId}: for `sign_in`-area reports ONLY it files ONE
 * deduplicated PUBLIC GitHub issue per unique fingerprint (labelled
 * sign-in-failure + auto-generated), tracking occurrences in the server-only
 * signInIssueLinks/{fingerprint} collection. The body carries only the
 * sanitized error type/reason + client context + fingerprint — no uid (reports
 * are unauthenticated) or PII. GitHub failures never crash-loop the trigger.
 * Requires the GITHUB_ISSUE_TOKEN secret
 * (functions/src/diagnostics/onSignInFailure.ts).
 */
export const diagnostics = {
  submitReport,
  onSignInFailure,
  cleanupExpired: cleanupExpiredDiagnostics,
};

/**
 * Feedback domain (grouped export → deployed as `feedback-reportIssue`).
 *
 * The Android "Report a problem" flow (contracts/functions/functions.json:
 * feedback.reportIssue). AUTHENTICATED + App-Check enforced, rate-limited to
 * 5 reports/hour/user. Persists the private record of record to
 * feedbackReports/{reportId} (admin-only read; carries the uid) FIRST, then
 * files a PUBLIC GitHub issue (labelled android-issue) whose body carries only
 * the typed description + client context + report id + timestamp — no uid or
 * PII. GitHub failures never fail the callable (the report is already
 * captured) and never surface a raw GitHub error to the app. Requires the
 * GITHUB_ISSUE_TOKEN secret (functions/src/feedback/reportIssue.ts).
 *
 * `feedback-interactWithIssue` (callable) + `feedback-syncOpenTickets`
 * (scheduled) back the in-app "open tickets" browser (report-tickets PR1).
 * syncOpenTickets mirrors OPEN public issues labelled `android-issue` into the
 * member-readable `openTickets/{issueNumber}` collection every few minutes;
 * interactWithIssue lets an active member, once each per issue, +1 (a fixed
 * "another user is affected" comment) or comment (neutralized + bounded, posted
 * to the public issue AND mirrored to the moderationReports admin queue) —
 * deduped by a backend-only `issueInteractions/{n}__{uid}__{type}` doc and
 * gated on the contract-default-OFF `reportTicketsBrowser` flag. Both bind the
 * GITHUB_ISSUE_TOKEN secret (functions/src/feedback/*).
 */
export const feedback = {
  reportIssue,
  interactWithIssue,
  syncOpenTickets,
};

/**
 * Errors domain (grouped export → deployed as `errors-reportClientError` and
 * the Firestore trigger `errors-onClientErrorReport`).
 *
 * The single client-side error-reporting pipeline (contracts/functions/
 * functions.json: errors.reportClientError). An authenticated app reports a
 * genuine runtime error (e.g. the Messages inbox listener failing); the
 * callable persists the private clientErrorReports/{reportId} record of record
 * (admin-only read; carries the uid) AND writes an adminAuditEvents entry
 * (action `client.error`) so the failure shows in the KCC admin Audit Log. The
 * errors-onClientErrorReport Firestore trigger then files ONE deduplicated
 * PUBLIC GitHub issue per unique fingerprint (labelled `auto-error` +
 * `auto-generated`), tracking occurrences in the server-only
 * clientErrorIssueLinks/{fingerprint} collection — reusing the shared GitHub
 * helper + GITHUB_ISSUE_TOKEN secret. No uid or secret ever reaches the public
 * issue (functions/src/errors/*).
 *
 * SERVER-side errors use the mirror-image pipeline, added because backend
 * failures previously reached NOBODY — a scheduled sweep throwing at 03:30 only
 * ever landed in Cloud Logging. `withServerErrorReporting(source, handler)`
 * (functions/src/errors/serverErrors.ts) now wraps all 15 onSchedule handlers: an
 * unexpected throw is written to the private serverErrorReports/{reportId} record
 * (admin-only read; FULL message/stack/context) and then RETHROWN, so Cloud
 * Scheduler retry and alerting semantics are unchanged. HttpsError is skipped —
 * those are deliberate client-facing outcomes. The errors-onServerErrorReport
 * trigger then files ONE deduplicated PUBLIC issue per fingerprint (labelled
 * `server-error` + `auto-generated`) into serverErrorIssueLinks/{fingerprint}.
 *
 * PUBLIC-REPO SAFETY (server side is stricter than the client side): server error
 * text routinely embeds Firestore document paths — which contain uids — plus
 * user-supplied values and coordinates, so the public issue is a strict ALLOWLIST
 * (source, errorName, errorCode, `file:line` frames, fingerprint, first-seen,
 * count) and the message/stack are NEVER published. The fingerprint is the opaque
 * correlation id an admin matches back to the private record. Both auto-filing
 * paths additionally share ONE global hourly issue budget
 * (shared/issueBudget-core.ts) so a bad release cannot dump hundreds of
 * permanently public issues.
 */
export const errors = {
  reportClientError,
  onClientErrorReport,
  onServerErrorReport,
};

/**
 * Crash-reporting domain — the Crashlytics → GitHub-issue bridge (grouped export
 * → deployed as `crashReporting-onNewFatalIssue`, `crashReporting-onNewAnrIssue`
 * and `crashReporting-onCrashRegression`).
 *
 * Native crashes and ANRs already reach Firebase Crashlytics (Android SDK +
 * gradle plugin, collection-on for release — docs/crashlytics.md), but a NEW
 * Crashlytics issue never became a GitHub issue, so crashes were invisible to
 * the issue tracker. These three Firebase Alerts triggers close that gap: each
 * turns a new fatal crash / ANR / regressed-crash alert into ONE deduplicated
 * PUBLIC GitHub issue, reusing the SAME shared auto-filing flow as the error
 * pipelines (shared/autoIssueFiling.ts: claim → global hourly budget → create →
 * reconcile) and the SAME GITHUB_ISSUE_TOKEN secret.
 *
 * The dedup fingerprint is the Crashlytics ISSUE ID — one crash issue files one
 * GitHub issue (labelled `android-crash`/`anr`/`regression` + `auto-generated`),
 * tallied in the server-only crashlyticsIssueLinks/{issueId} collection, and the
 * create is charged against the shared 20-issues/hour global budget so a crash
 * storm cannot spam the repo. The alert payload carries no uid/PII; the full
 * multi-frame stack trace is NOT in the payload (it lives at the Crashlytics
 * deep link the issue body links to — the title/subtitle give exception + top
 * frame only), and the issue body says so honestly (functions/src/crashReporting/*).
 */
export const crashReporting = {
  onNewFatalIssue,
  onNewAnrIssue,
  onCrashRegression,
};

/**
 * Crowd-sourced incidents / roadwork domain (grouped export → deployed as
 * `incidents-report`, `incidents-listNearby`, `incidents-remove`,
 * `incidents-confirm`, `incidents-reportCleared`, the
 * scheduled `incidents-cleanupExpired`, and the scheduled
 * `incidents-syncTrafikverket`) — the navigation feature's Waze-style map
 * layer. NEW additive domain.
 *
 * Members report short-lived incidents (accident / roadwork / hazard / police /
 * road_closed) via `incidents.report`; the write carries a computed `geoCell`
 * (nearby-query index) and a per-type `expiresAt` TTL. Every signed-in user
 * reads ACTIVE, unexpired incidents — directly via security rules and, for the
 * map's bounded batch, via `incidents.listNearby` (chunked `geoCell in`
 * queries + server-side Haversine radius filter, never a full scan). Reporters
 * (or admins) clear their own via `incidents.remove`. Any OTHER member confirms a
 * report is still there via `incidents.confirm`, which counts one confirmation
 * per user (sub-collection doc keyed by uid) and pushes `expiresAt` out up to a
 * hard lifetime cap — confirmed incidents persist, stale ones fade. The
 * opposite signal is `incidents.reportCleared`: a member who is physically NEAR
 * an incident votes that it is GONE. That vote never deletes anything on its
 * own — one tap erasing a real accident for everyone is the failure mode that
 * matters — it counts into `clearedCount`, and while clears LEAD the incident
 * stays on the map flagged `reportedCleared` so clients draw it faded with both
 * counts visible. Only 2 NET clear votes (or the reporter/an admin clearing it)
 * expires it. Imported (Trafikverket) incidents reject clear votes: the importer
 * full-overwrites them every 30 minutes, so a vote would simply be erased.
 * `incidents-cleanupExpired`
 * sweeps expired docs every 15 min. `incidents-syncTrafikverket` imports Swedish
 * roadwork/traffic situations from the Trafikverket open API — GUARDED on the
 * `TRAFIKVERKET_API_KEY` secret, so it no-ops safely until the free key is set.
 */
export const incidents = {
  report: reportIncident,
  listNearby: listNearbyIncidents,
  remove: removeIncident,
  confirm: confirmIncident,
  reportCleared: reportIncidentCleared,
  cleanupExpired: cleanupExpiredIncidents,
  syncTrafikverket,
};

/**
 * User-reported POLICE pins domain (grouped export → deployed as `police-report`
 * and `police-listNearby`) — the police-proximity alert feature.
 *
 * A member drops a SHORT-LIVED police pin via `police.report` (rate-limited so
 * the map can't be flooded with fakes); the write carries a computed `geoCell`
 * and a ~40 min `expiresAt` TTL. An active MEMBER reads ACTIVE,
 * unexpired pins near a point via `police.listNearby` (member-gated —
 * requireMemberActor + the isActiveMember read rule; chunked `geoCell in`
 * queries + Haversine radius filter), which the Android map polls on the incident
 * camera-idle cadence to draw distinct police markers AND to fire the mid-screen
 * ReactionOverlay once when the driver comes within the proximity radius of a pin.
 *
 * A tapped pin is interactive, modelled on the incidents sheet:
 *  - the REPORTER removes their own pin via `police.remove` (owner-only);
 *  - a NON-reporter verifies it via `police.confirm` (still there) or
 *    `police.dispute` (gone) — one vote per (uid, pin) in a `votes/{uid}` ledger,
 *    switchable, surfaced as confirmation/dispute counts on the sheet. A dispute
 *    informs only; it does NOT auto-remove the pin (see police/verify.ts).
 *
 * NO scheduled sweep for the PIN document: both the security read rule
 * (`status=='active' && expiresAt > request.time`) and listNearby hide an expired
 * pin immediately, and a field-scoped Firestore TTL policy on
 * `policeReports.expiresAt` reclaims the document (one-time deploy note in
 * police/report.ts, alongside the rate-limit counter TTL policies — the verify
 * counter TTL note is in police/verify.ts).
 *
 * A pin's `votes/{uid}` verify ledger is NOT reclaimed by that TTL (Firestore TTL
 * deletes the document but does NOT cascade into sub-collections). Sub-collection
 * cleanup is trigger-based: `police.onReportDeleted` (onDocumentDeleted) fires on
 * EVERY pin delete — TTL expiry (the common path), `police.remove`, or an admin
 * delete — and recursiveDeletes the votes. `police.remove` also recursiveDeletes
 * inline; the two compose (whichever runs second finds it already empty).
 */
export const police = {
  report: reportPolice,
  listNearby: listNearbyPolice,
  remove: removePolice,
  confirm: confirmPolice,
  dispute: disputePolice,
  onReportDeleted: onPoliceReportDeleted,
};

/**
 * Account deletion domain (grouped export → deployed as
 * `account-deleteAccount` and the scheduled `account-purgeDeleted`).
 *
 * Two-stage deletion (contracts/functions/functions.json): immediate
 * soft delete (Auth user disabled, tokens revoked, users/{uid}.deleted,
 * accountDeletionRequests record) and the daily hard purge after the
 * 30-day retention window (Firestore trees, owned documents, storage
 * prefixes, the Auth user; the request record is retained as the
 * proof-of-deletion). Retained data is documented in
 * functions/src/account/deletion-core.ts.
 */
export const account = {
  deleteAccount,
  purgeDeleted,
  // Daily inactive-account sweep (account lifecycle): warns accounts inactive
  // >= 11 months (email + in-app; email degrades to a no-op until wired) and,
  // ONLY when the hard-delete gate is open (config flag AND email available),
  // hard-deletes them 30 days later by reusing the account-deletion routine.
  // Deletes NOTHING today — the gate is closed until email works.
  cleanupInactive,
};

/**
 * Live location domain (grouped export → deployed as `live-startSession`,
 * `live-updatePosition`, `live-stopSession`, `live-extendSession`,
 * `live-hideMeNow`, `live-listNearby`, `live-sendWave`, and the scheduled
 * `live-cleanupExpired`) — Phase 10, the first RTDB domain.
 *
 * All liveLocation/ writes are backend-only (RTDB rules deny every client
 * write); a non-suspended, non-blocked signed-in VIEWER reads
 * liveLocation/{uid}/latest markers via RTDB listeners (the read rule does NOT
 * currently encode an activeMember gate — member gating is disabled repo-wide,
 * shared/memberGating.ts; a paid-viewing gate is added to that rule when it is
 * re-locked). Sessions expire per their 1h/2h/4h duration, never past the 6h
 * hard cap (LIVE_SESSION_MAX_MS); extendSession grants a fresh capped window on
 * the user's pre-expiry "keep sharing" confirmation. The 5-minute sweep expires
 * sessions past expiresAt server-side (so an offline device cannot linger past
 * the cap), removes markers whose positions went silent for 15 minutes, and
 * deletes expired nearby-discovery docs. hideMeNow works while suspended
 * (privacy action). Also completes the Phase 9h Kronjakt jump-detection seam,
 * which reads liveLocation/{uid}/latest.
 *
 * live.listNearby is the DISCOVERY path (mirrors incidents.listNearby): it
 * returns active, unexpired sharers near the caller from the queryable Firestore
 * index (liveSessions/{uid}, written by updatePosition), so a STANDALONE sharer
 * — with no convoy/group roster carrying their uid — is visible to users
 * nearby. Self / blocked (either direction) / expired are excluded; suspended
 * users cannot refresh a discovery doc and age out. See functions/src/live/
 * nearby-core.ts for why the position stream stays in RTDB and only the geo
 * index is in Firestore.
 *
 * live.sendWave is the SOCIAL broadcast on the same discovery substrate: a live
 * sharer fans a transient "👋 <name> waved" out to every OTHER live sharer within
 * WAVE_RADIUS_METERS (found by the same listNearby geo-query + block matrix, from
 * the sender's OWN authoritative discovery-doc position), delivered to each
 * recipient's per-user `liveWaves/{uid}/waves` inbox (owner-read, backend-write,
 * TTL-swept — the notifications-inbox shape). Anti-spam is server-enforced: a
 * per-user 45s cooldown (liveWaveCooldowns/{uid}) is checked+stamped in a
 * transaction BEFORE the geo-query. See functions/src/live/sendWave.ts +
 * wave-core.ts.
 */
export const live = {
  startSession,
  updatePosition,
  stopSession,
  extendSession,
  hideMeNow,
  listNearby: listNearbyLive,
  sendWave: sendWaveLive,
  cleanupExpired: cleanupExpiredLive,
};

/**
 * Subscription domain (grouped export → deployed as `subscription-verify`,
 * `subscription-grantEntitlement` and the scheduled
 * `subscription-expireLapsed`) — Phase 11.
 *
 * subscription.verify FAILS CLOSED until store credentials are
 * configured (end-of-MVP console setup; the legacy endpoint was itself a
 * placeholder). admin manual grants (subscription.grantEntitlement in the
 * registry) drive the fully-implemented entitlement chain:
 * subscriptions/{uid} record + users/{uid}.activeMember + the
 * activeMember custom claim, with Phase 8 fail-safe privilege ordering.
 * Raw purchase tokens are hashed immediately and never stored.
 */
export const subscription = {
  verify: verifySubscription,
  grantEntitlement,
  // The EXIT from the paid tier: `subscriptions/{uid}.expiresAt` was
  // written from day one and read by nothing, so an entitlement never
  // lapsed. This 3-hourly sweep revokes entitlements whose subscription
  // expired more than the 72h grace window ago, through the same
  // applyEntitlement the grant path uses (so all three representations —
  // record, users.activeMember, activeMember claim — are cleared together
  // with fail-safe ordering). Records the revocation on
  // userLifecycle/{uid}.subscriptionExpiry, notifies the member in-app
  // under subscription_status, and never revokes on the ABSENCE of a
  // record — a perpetual manual grant is untouched.
  expireLapsed: expireLapsedSubscriptions,
};

/**
 * Group driving domain (grouped export → deployed as `groupDrive-join`,
 * `groupDrive-updateStatus`, `groupDrive-leave`) — Phase 11.
 *
 * Roster at events/{eventId}/groupDriveParticipants/{uid}
 * (member-readable for published events; callable-only writes). Join
 * requires published event + RSVP going|maybe + not ended (legacy
 * canJoinEventGroupDrive); rejoin resets; leave is idempotent and never
 * stops the live location session. Markers remain the live-location
 * domain.
 */
export const groupDrive = {
  join: joinGroupDrive,
  updateStatus: updateDriveStatus,
  leave: leaveGroupDrive,
};

/**
 * Friends domain (grouped export → deployed as `friend-sendRequest`,
 * `friend-respondRequest`, `friend-cancelRequest`, `friend-remove`,
 * `friend-list`).
 *
 * The friend-GRAPH foundation (contracts/functions/functions.json:
 * friend.sendRequest/respondRequest/cancelRequest/remove/list) — messaging/DMs are a
 * separate follow-up and are NOT part of this domain. Model:
 * friendRequests/{requestId} — one directional request per ordered pair, keyed
 * by a deterministic hash of the (fromUid, toUid) pair (friendRequestId in
 * friends-core.ts), NOT the literal `fromUid__toUid` string — and the per-side
 * users/{uid}/friends/{friendUid} subcollection written for
 * BOTH parties on accept. Owner-readable, callable-only writes
 * (firebase/firestore.rules). sendRequest resolves a nickname (displayName,
 * NOT unique): 0 matches → not-found, 1 → proceed, >1 → failed-precondition
 * (AMBIGUOUS_NICKNAME) carrying a candidate list in the error details for
 * client disambiguation via { toUid }. Blocking is honoured both ways
 * (neutral NOT_ADDABLE); an incoming pending request is auto-accepted when
 * the caller sends the reverse. Established friendship — not request status —
 * is the source of truth for "already friends". A pending request is
 * withdrawable by its SENDER via cancelRequest, which addresses it by RECIPIENT
 * and derives the doc id server-side (so no other member's request can be named
 * or probed) and deletes it, leaving the pair free to send again later.
 *
 * The names/avatars denormalized onto those documents are a write-time snapshot
 * nothing rewrites, so `list` re-reads live `users/{uid}` in one batched `getAll`
 * and serves that (the stored copy remains the fallback). Without it a member
 * who uploaded or changed their avatar AFTER becoming friends showed no picture
 * in the friends list while their profile screen showed the real one.
 */
export const friend = {
  sendRequest: sendFriendRequest,
  respondRequest: respondFriendRequest,
  cancelRequest: cancelFriendRequest,
  remove: removeFriend,
  list: listFriends,
};

/**
 * User-search domain (grouped export → deployed as `userSearch-members` and the
 * `userSearch-onUserProfileWrite` Firestore trigger).
 *
 * Powers the "find a person" typeahead on the Friends surface
 * (contracts/functions/functions.json: userSearch.members). Case-insensitive
 * PREFIX matching over the denormalized `users/{uid}.displayNameLower` key —
 * typing 'gt' finds 'gt_86'; a mid-word substring like '86' does NOT match,
 * because Firestore offers no substring operator and an n-gram token index is
 * deliberately not built (functions/src/users/user-search-core.ts).
 *
 * Server-side rather than a client query even though `users/{uid}` is
 * authenticated-readable: only a callable can enforce a minimum query length, a
 * hard result cap with no cursor, a per-user rate limit, an allowlisted
 * three-field projection (uid/displayName/avatarPath — never email or any
 * backend-managed flag), and either-way block exclusion. A client-side range
 * query would be a paginated member-directory dump.
 *
 * onUserProfileWrite re-derives `displayNameLower` from `displayName` on every
 * users/{uid} write, whoever wrote it — the OWNER may update `displayName`
 * directly under firestore.rules but cannot write the key, so without this a
 * renamed member stays findable only under their OLD nickname.
 */
export const userSearch = {
  members: searchMembers,
  onUserProfileWrite,
};

/**
 * Direct messaging domain (grouped export → deployed as `dm-sendMessage`,
 * `dm-listConversations`, `dm-getMessages`, `dm-markRead`).
 *
 * 1:1 DMs between ESTABLISHED friends (contracts/functions/functions.json:
 * dm.sendMessage/listConversations/getMessages/markRead), stacked on the
 * friend-graph backend. Model: a single canonical conversations/{pairId}
 * (pairId = the two UIDs sorted + joined by `__`) with members[], denormalized
 * memberProfiles, a lastMessage preview + lastMessageAt ordering key, and
 * per-member unread + lastReadAt maps; messages live at
 * conversations/{pairId}/messages/{id} {senderUid,text,createdAt}. Member reads,
 * callable-only writes (firebase/firestore.rules). sendMessage requires an
 * established friendship (users/{uid}/friends/{friendUid}) and honours blocking
 * both ways (neutral failed-precondition). The per-member unread counters are
 * kept in lock-step with a per-user aggregate at
 * userPrivate/{uid}.dmUnreadTotal (owner-only read) so the map-home chat bubble
 * binds ONE document listener for its badge. FCM push on a new message is
 * deferred to the end-of-MVP Firebase console setup (as with notifications).
 */
export const dm = {
  sendMessage: dmSendMessage,
  listConversations: dmListConversations,
  getMessages: dmGetMessages,
  markRead: dmMarkRead,
  reportMessage: dmReportMessage,
};

/**
 * Convoy domain (grouped export → deployed as `convoy-create`,
 * `convoy-respond`, `convoy-start`, `convoy-end`, `convoy-list`,
 * `convoy-leave`, `convoy-invite`, `convoy-setDestination`,
 * `convoy-clearDestination`, `convoy-sendReaction`, `convoy-setFollowMe`).
 *
 * The convoy FOUNDATION (contracts/functions/functions.json:
 * convoy.create/respond/start/end/list/leave/invite/setDestination/
 * clearDestination) — chat channels are a SEPARATE domain (convoyChat below).
 * Model: `convoys/{convoyId}` with ownerUid, status (forming|active|ended), a
 * `memberUids` array (owner + invitees; drives the array-contains list read and
 * the rules membership gate), a `members` map keyed by uid
 * ({role, inviteStatus:'invited'|'accepted'|'declined', invitedAt, joinedAt}),
 * denormalized memberProfiles, and a `summary` computed + stored on end
 * (duration + accepted participants; distance null — no shared-route
 * aggregation in this foundation). Member-readable, callable-only writes
 * (firebase/firestore.rules). Only FRIENDS of the INVITER may be invited
 * (users/{inviter}/friends), blocking honoured both ways (non-friend/blocked
 * invitees silently skipped). On invite a best-effort in-app notification is
 * written (writeInAppNotification, 'convoy_invite' category).
 *
 * MEMBERSHIP CHANGES: any ACCEPTED member may `invite` into an existing convoy
 * (friend-gated to them, block-checked against every other accepted member,
 * capped at 25 total members); a non-owner accepted member may `leave`
 * (removed from memberUids/members/memberProfiles, so their convoy read, convoy
 * chat and live-position slot all drop with it). The OWNER may NOT leave —
 * they use `end`, since an owner leaving would orphan every owner-gated
 * transition. The last non-owner leaving does NOT auto-end the convoy.
 *
 * SHARED DESTINATION: `setDestination` / `clearDestination` maintain an
 * optional `destination` field on the convoy doc ({latitude, longitude, label,
 * setByUid, setByDisplayName, setAt}) that is serialized into every
 * ConvoySummary, so members receive it through the convoy read path they
 * already subscribe to rather than a second listener. Any accepted member may
 * set (last write wins); the SETTER or the OWNER may clear. It SURVIVES `end`
 * untouched as a record of where the convoy was headed, and arrival is
 * deliberately not tracked and never auto-ends the convoy. LIVE POSITIONS
 * reuse the live-location domain: the response's livePositionUids (accepted
 * members) are the uids the convoy map subscribes to at RTDB
 * liveLocation/{uid}/latest — the convoy never duplicates GPS storage.
 *
 * REACTIONS (`convoy-sendReaction`): an accepted member broadcasts a transient
 * reaction (police alert / hello-goodbye / follow-me) to the rest of the convoy.
 * It writes ONE short-lived doc to convoyChats/{convoyId}/reactions — the SAME
 * real-time channel convoy chat uses — so every member's existing per-convoy
 * listener delivers it and each client pops a mid-screen animation for a couple
 * of seconds. The police alert is rate-limited SERVER-SIDE (60s/member/convoy)
 * via a per-(convoy, member) cooldown doc read-and-written in the same
 * transaction as the reaction; hello/follow-me get shorter windows. No
 * notification fan-out and no long-term storage (a short expireAt TTL sweeps
 * both docs). See functions/src/convoy/reactions.ts + reaction-core.ts.
 *
 * FOLLOW-ME LEADER TRAIL (`convoy-setFollowMe`): a persistent, SHARED, toggleable
 * line of where the current leader has recently driven, drawn on every member's
 * map so a separated member can rejoin. Distinct from the transient follow-me
 * REACTION above: pressing Follow-me fires the ~30s animation AND toggles this
 * durable trail. State lives on the per-convoy subdoc
 * `convoys/{convoyId}/followMe/current` {leaderUid, polyline (~15 km rolling,
 * base64 CCRB), updatedAt}. The callable owns ONLY the leaderUid pointer under a
 * transaction: active=true SETS leaderUid=caller and resets the polyline (a
 * TAKEOVER — only ONE leader trail per convoy, newest presser wins), active=false
 * CLEARS it but only when the caller is the current leader (else no-op, so no
 * member can wipe another's trail). The trail POINTS are written DIRECTLY by the
 * leader's client on a ~3-5s throttle (Firestore-rules-gated to the current
 * leader), so the high-frequency updates never invoke a function. Cleanup: leave
 * (when the leaver led it) and end (always) delete the subdoc; members also stop
 * drawing a trail whose leader went stale or left memberUids. See
 * functions/src/convoy/setFollowMe.ts + followMe-core.ts.
 */
export const convoy = {
  create: createConvoy,
  respond: respondConvoy,
  start: startConvoy,
  end: endConvoy,
  list: listConvoys,
  leave: leaveConvoy,
  invite: inviteToConvoy,
  setDestination: setConvoyDestination,
  clearDestination: clearConvoyDestination,
  sendReaction: sendConvoyReaction,
  setFollowMe: setConvoyFollowMe,
};

/**
 * Community chat domain (grouped export → deployed as `communityChat-post`,
 * `communityChat-list`, `communityChat-markRead`, and the scheduled
 * `communityChat-digest`).
 *
 * The single APP-WIDE chat — one of the THREE product chats (community / convoy
 * / friends-DMs; the friends chat is the existing `dm` domain). Model: a fixed
 * channel doc `communityChat/global` with a `messages` subcollection
 * (communityChat/global/messages/{id} {senderUid, text, createdAt,
 * senderDisplayName, senderAvatarPath}). Any ACTIVE MEMBER reads
 * (firebase/firestore.rules); writes are callable-only. No fan-out unread
 * aggregate — a lightweight per-user last-read marker lives at
 * userPrivate/{uid}.communityChatLastReadAt (owner-only readable) so the client
 * derives the unread dot from its newest-message live listener (O(1) per user).
 * Blocking IS filtered, in BOTH directions, off the symmetric
 * blockVisibility/{uid}.hiddenUids mirror — one document read per page in
 * communityChat.list, and the same mirror drives the client's live-window filter
 * (a Firestore rule cannot filter a list query per document). See
 * functions/src/chatchannels/chat-core.ts and blocking/block-visibility.ts.
 *
 * There is deliberately NO per-message notification producer (that would be an
 * O(members × messages) fan-out on the town square). Instead the channel notifies
 * via (1) @MENTIONS (communityChat.post) and (2) the scheduled `communityChat-digest`
 * (18:00 Europe/Stockholm, daily): a low-frequency roll-up that writes ONE
 * `community_chat` notice to each member with >= COMMUNITY_DIGEST_MIN_UNREAD messages
 * since their communityChatLastReadAt. Cost is O(behind-members) per DAY (a
 * last-read range query + one count() per behind member — never per message);
 * re-notify is prevented by the userPrivate/{uid}.communityChatDigestedUpTo marker,
 * and the per-category opt-out is inherited via writeInAppNotification. See
 * functions/src/chatchannels/communityDigest.ts + communityDigest-core.ts.
 */
export const communityChat = {
  post: communityChatPost,
  list: communityChatList,
  markRead: communityChatMarkRead,
  digest: communityChatDigest,
};

/**
 * Convoy chat domain (grouped export → deployed as `convoyChat-post`,
 * `convoyChat-list`, `convoyChat-markRead`).
 *
 * The per-CONVOY chat — one of the THREE product chats. Stacked on the convoy
 * backend: readable + postable ONLY by ACCEPTED members of `convoys/{convoyId}`
 * (memberUids + members[uid].inviteStatus === 'accepted', owner included).
 * Model: convoyChats/{convoyId}/messages/{id} with the same denormalized message
 * shape as community. Rules gate reads behind a get() of the convoy doc
 * (firebase/firestore.rules); writes are callable-only, and the callables
 * re-check accepted membership (missing convoy / outsider → not-found so a
 * convoy can't be probed). See functions/src/chatchannels/chat-core.ts.
 *
 * UNREAD follows community's shape, not a fan-out counter: `convoyChat-markRead`
 * stamps a per-user, per-convoy last-read marker in the map at
 * userPrivate/{uid}.convoyChatLastReadAt (owner-only readable, capped at
 * chat-core CONVOY_LAST_READ_MAX_ENTRIES), and the client derives the unread
 * COUNT from the bounded newest-message live listener it already holds — O(1)
 * per user, no write per member per post. That count is what the map shell's
 * convoy bar badges.
 */
export const convoyChat = {
  post: convoyChatPost,
  list: convoyChatList,
  markRead: convoyChatMarkRead,
};

/**
 * Chat-channel moderation (grouped export → deployed as
 * `chatchannels-reportMessage`).
 *
 * The report path for the two CHANNEL chats. It is one callable across both
 * channels (`{ channel: 'community' | 'convoy', convoyId? }`) rather than one
 * per channel because the only thing that differs is the eligibility check;
 * the validation, dedup, rate limit, snapshot and admin queue are identical,
 * and splitting them would be two chances to drift. Eligibility mirrors each
 * channel's read rule (any active member for community; an ACCEPTED convoy
 * member for convoy, via the same gate convoyChat.post/list use). Writes
 * moderationReports/{reportId} — admin-read-only, client-write-denied
 * (firebase/firestore.rules) — snapshotting the reported message because
 * channel messages are TTL-deleted. Blocking does NOT gate reporting, in
 * either direction. See functions/src/moderation/moderation-core.ts.
 */
export const chatchannels = {
  reportMessage: chatChannelsReportMessage,
};

/**
 * Moderation domain (grouped export → deployed as `moderation-reportUser`).
 *
 * Reporting a PERSON rather than a message — the escalation for behaviour that
 * doesn't reduce to one line of chat. Gated on requireActiveActor (matching
 * blocking.*, the sibling safety tool: a lapsed member must still be able to
 * report harassment), rejects self-reports, and deduplicates per
 * (reporter, reportedUser) WITHOUT the reason, so one reporter cannot fill the
 * queue by cycling the reason enum — a repeat tallies `occurrences` on the one
 * document instead. Captures only the reported user's public profile
 * projection plus that tally; never their history. A per-target aggregate at
 * moderationUserSummaries/{uid} carries the distinct-reporter count for O(1)
 * admin triage. See functions/src/moderation/moderation-core.ts.
 */
export const moderation = {
  reportUser: moderationReportUser,
};

/**
 * Community growth metrics domain (grouped export → deployed as the scheduled
 * `metrics-captureDaily`).
 *
 * A daily job writes ONE bounded document to `metrics/{YYYY-MM-DD}` recording
 * cumulative community totals (users, convoys, km driven, events, vehicle-brand
 * distribution, and a few "fun" counters) purely from Firestore count()/sum()
 * aggregations — it never reads the documents themselves, and the brand
 * distribution is O(catalogue), not O(vehicles). The admin web app charts the
 * series (a screenshot-friendly growth page). Every field is a PII-free
 * aggregate; the series starts empty and fills in going forward (no historical
 * backfill). See functions/src/metrics/scheduled.ts for the cost/storage math.
 */
export const metrics = {
  captureDaily: metricsCaptureDaily,
};

/**
 * Finance cost model domain (grouped export → deployed as `finance-estimate`).
 *
 * An admin-only on-demand callable that estimates monthly spend in SEK from an
 * in-app COST MODEL: a sourced+dated price table (functions/src/finance/
 * pricing.ts), labelled usage assumptions, and the function inventory. It reads
 * the latest metrics/{date} snapshot for the live member count so the variable
 * half tracks community growth, applies each service's free tier, and returns a
 * Google Cloud subtotal, a separate Mapbox estimate, and a separate
 * RECURRING-COSTS section. Every modelled figure is a MODEL ESTIMATE, not the
 * real bill — the admin page carries that banner and links to the billing
 * console. No scheduled write (and so no added cost) — see finance/estimate.ts.
 *
 * The recurring-costs section is DATA-BACKED: admins add/update/delete
 * operator-entered actuals (Claude, tooling, domains …) via the three audited
 * CRUD callables below, stored in `financeRecurringCosts` and folded into the
 * grand total. Deployed as finance-addRecurringCost / finance-updateRecurringCost
 * / finance-deleteRecurringCost (functions/src/finance/recurringCosts.ts).
 */
export const finance = {
  estimate: financeEstimate,
  addRecurringCost: financeAddRecurringCost,
  updateRecurringCost: financeUpdateRecurringCost,
  deleteRecurringCost: financeDeleteRecurringCost,
};
