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
import { setAdminRole } from './admin/setAdminRole';
import { suspendUser } from './admin/suspendUser';
import { cancel, complete, publish } from './events/eventLifecycle';
import { create, update } from './events/manageEvent';
import { postChatMessage } from './events/postChatMessage';
import { removeChatMessage } from './events/removeChatMessage';
import { reportChatMessage } from './events/reportChatMessage';
import { onRsvpWrite } from './events/onRsvpWrite';
import { deleteDrive } from './drives/deleteDrive';
import { addVehicle, deleteVehicle, updateVehicle } from './garage/manageVehicle';
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
