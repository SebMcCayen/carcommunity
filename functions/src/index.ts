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
import { onRsvpWrite } from './events/onRsvpWrite';

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
};
