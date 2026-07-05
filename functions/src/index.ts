/**
 * Cloud Functions entry point.
 *
 * Production deployments use GitHub OIDC and Google Workload Identity
 * Federation — no service account JSON file is committed to this repository.
 *
 * Region: europe-west1 (EU, low-latency for Swedish users)
 * Runtime: Node.js 22 (see engines.node in package.json)
 * Generation: 2nd gen (firebase-functions v2 sub-path)
 */

import { onRequest } from 'firebase-functions/v2/https';
import { handleHealth } from './health';
import { completeOnboarding } from './auth/completeOnboarding';
import { onUserCreate } from './auth/onUserCreate';
import { restoreAccess } from './admin/restoreAccess';
import { setFeatureFlag } from './admin/setFeatureFlag';
import { setAdminRole } from './admin/setAdminRole';
import { suspendUser } from './admin/suspendUser';
import { warnUser } from './admin/warnUser';
import { cancel, complete, publish } from './events/eventLifecycle';
import { create, update } from './events/manageEvent';
import { postChatMessage } from './events/postChatMessage';
import { removeChatMessage } from './events/removeChatMessage';
import { reportChatMessage } from './events/reportChatMessage';
import { onRsvpWrite } from './events/onRsvpWrite';
import { deleteDrive } from './drives/deleteDrive';
import { addVehicle, deleteVehicle, updateVehicle } from './garage/manageVehicle';
import { awardHelpfulMember } from './badges/awardHelpfulMember';
import { adminAdjust, adminReverse } from './points/adminPoints';
import { activatePoint, createPoint, pausePoint, updatePoint } from './crownHunt/managePoints';
import { submitClaim } from './crownHunt/submitClaim';
import { reviewApplication, submitApplication } from './partners/applications';
import { createCompany, setCompanyStatus, updateCompany } from './partners/manageCompany';
import { createOffer, setOfferStatus, showOfferCode, updateOffer } from './partners/manageOffer';
import { recordInteraction } from './partnerInsights/recordInteraction';
import { aggregateDaily, cleanupExpired } from './partnerInsights/scheduled';
import {
  activate as activateBillboard,
  create as createBillboard,
  recordInteraction as recordBillboardInteraction,
  setStatus as setBillboardStatus,
  update as updateBillboard,
} from './billboards/manageBillboard';
import { markAllRead, markRead } from './notifications/manageNotifications';
import { registerPushToken, unregisterPushToken } from './notifications/pushTokens';
import { cleanupExpired as cleanupExpiredNotifications } from './notifications/scheduled';
import { hideMeNow, startSession, stopSession, updatePosition } from './live/session';
import { cleanupExpired as cleanupExpiredLive } from './live/scheduled';
import { grantEntitlement, verify as verifySubscription } from './subscription/verify';
import { join as joinGroupDrive, leave as leaveGroupDrive, updateStatus as updateDriveStatus } from './groupDrive/participants';
import { deleteAccount } from './account/deleteAccount';
import { purgeDeleted } from './account/scheduled';
import { submitReport } from './diagnostics/submitReport';
import { cleanupExpired as cleanupExpiredDiagnostics } from './diagnostics/scheduled';
import { saveDrive } from './drives/saveDrive';

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
};

/**
 * Admin domain (grouped export → deployed as `admin-setAdminRole`,
 * `admin-suspendUser`, and `admin-restoreAccess`).
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
};

/**
 * Events domain (grouped export → deployed as `events-create`,
 * `events-update`, `events-publish`, `events-cancel`, `events-complete`,
 * and the `events-onRsvpWrite` Firestore trigger).
 *
 * Admin-only lifecycle callables (contracts/functions/functions.json:
 * events.create/update/publish/cancel/complete) writing the teaser doc
 * `events/{eventId}` + member-gated `events/{eventId}/details/private`
 * split, plus the RSVP counter aggregation trigger. Member RSVPs are direct
 * Security-Rules-gated writes to events/{eventId}/rsvps/{uid} — no callable.
 */
export const events = {
  create,
  update,
  publish,
  cancel,
  complete,
  onRsvpWrite,
  // Chat (Phase 9c): member post/report callables + admin soft-removal.
  postChatMessage,
  reportChatMessage,
  removeChatMessage,
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
 * Garage domain (grouped export → deployed as `garage-addVehicle`,
 * `garage-updateVehicle`, `garage-deleteVehicle`).
 *
 * Member-only vehicle management (contracts/functions/functions.json:
 * garage.addVehicle/updateVehicle/deleteVehicle). Vehicles are
 * authenticated-readable; all writes go through these callables (per-user
 * cap, strict no-plate/no-VIN schemas, storage cleanup on delete).
 */
export const garage = {
  addVehicle,
  updateVehicle,
  deleteVehicle,
};

/**
 * Badges domain (grouped export → deployed as `badges-awardHelpfulMember`).
 *
 * Awards live at users/{uid}/badges/{badgeKey} (owner-readable, backend-only
 * writes). Automatic badges are evaluated inline by their source domains
 * (garage.addVehicle → garage_created; events.complete → first_event /
 * five_events via badgeProgress counters); helpful_member is the only
 * manually awardable badge (contracts/functions/functions.json:
 * badges.awardHelpfulMember).
 */
export const badges = {
  awardHelpfulMember,
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
 */
export const points = {
  adminAdjust,
  adminReverse,
};

/**
 * Kronjakt domain (grouped export → deployed as `crownHunt-submitClaim`,
 * `crownHunt-createPoint`, `crownHunt-updatePoint`, `crownHunt-activatePoint`,
 * `crownHunt-pausePoint`).
 *
 * Crown Hunt geographic point hunt (contracts/functions/functions.json).
 * submitClaim ports the full legacy anti-fraud validation chain; awards run
 * atomically through the Kronpoäng ledger primitives. Point management is
 * admin-only with a safety-gated activation step. Active points and own
 * claim history are direct member reads.
 */
export const crownHunt = {
  submitClaim,
  createPoint,
  updatePoint,
  activatePoint,
  pausePoint,
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
 * `partnerInsights-recordInteraction` plus the migration's first scheduled
 * functions `partnerInsights-aggregateDaily` and
 * `partnerInsights-cleanupExpired`).
 *
 * Privacy-critical (contracts/functions/functions.json): events carry only
 * partner-scoped user hashes, anonymous_pass_by requires flag + explicit
 * opt-in (silent opt-out), aggregates enforce the minimum-contributor
 * threshold with zeroed below-threshold counts, and raw events expire
 * after 7 days.
 */
export const partnerInsights = {
  recordInteraction,
  aggregateDaily,
  cleanupExpired,
};

/**
 * Digital billboards domain (grouped export → deployed as
 * `billboards-create`, `billboards-update`, `billboards-activate`,
 * `billboards-setStatus`, `billboards-recordInteraction`).
 *
 * Sponsored map billboards (contracts/functions/functions.json). Public
 * reads of active billboards; activation is a six-point safety gate with
 * an audited approval reason and an active sponsoring partner; billboard
 * taps flow into the partner-insights privacy pipeline.
 */
export const billboards = {
  create: createBillboard,
  update: updateBillboard,
  activate: activateBillboard,
  setStatus: setBillboardStatus,
  recordInteraction: recordBillboardInteraction,
};

/**
 * Notifications domain (grouped export → deployed as
 * `notifications-markRead`, `notifications-markAllRead`,
 * `notifications-registerPushToken`, `notifications-unregisterPushToken`,
 * and the scheduled `notifications-cleanupExpired`).
 *
 * Durable in-app inbox (contracts/functions/functions.json): owner-only
 * reads of `notifications/{uid}/items/`, backend-only writes (delivery via
 * writeInAppNotification, read-state via the mark callables), push token
 * registrations stored as SHA-256 hashes only, and the daily retention
 * sweep (unread 30 days, read 7 days). FCM delivery itself ships with the
 * end-of-MVP Firebase console setup.
 */
export const notifications = {
  markRead,
  markAllRead,
  registerPushToken,
  unregisterPushToken,
  cleanupExpired: cleanupExpiredNotifications,
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
 */
export const diagnostics = {
  submitReport,
  cleanupExpired: cleanupExpiredDiagnostics,
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
};

/**
 * Live location domain (grouped export → deployed as `live-startSession`,
 * `live-updatePosition`, `live-stopSession`, `live-hideMeNow`, and the
 * scheduled `live-cleanupExpired`) — Phase 10, the first RTDB domain.
 *
 * All liveLocation/ writes are backend-only (RTDB rules deny every
 * client write); entitled members (activeMember claim, non-suspended)
 * read liveLocation/{uid}/latest markers via RTDB listeners. Sessions
 * expire per their 1h/2h/4h duration; the 5-minute sweep also removes
 * markers whose positions went silent for 15 minutes. hideMeNow works
 * while suspended (privacy action). Also completes the Phase 9h Kronjakt
 * jump-detection seam, which reads liveLocation/{uid}/latest.
 */
export const live = {
  startSession,
  updatePosition,
  stopSession,
  hideMeNow,
  cleanupExpired: cleanupExpiredLive,
};

/**
 * Subscription domain (grouped export → deployed as `subscription-verify`
 * and `subscription-grantEntitlement`) — Phase 11.
 *
 * subscription.verify FAILS CLOSED until store credentials are
 * configured (end-of-MVP console setup; the legacy endpoint was itself a
 * placeholder). admin manual grants (admin.grantEntitlement in the
 * registry) drive the fully-implemented entitlement chain:
 * subscriptions/{uid} record + users/{uid}.activeMember + the
 * activeMember custom claim, with Phase 8 fail-safe privilege ordering.
 * Raw purchase tokens are hashed immediately and never stored.
 */
export const subscription = {
  verify: verifySubscription,
  grantEntitlement,
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
