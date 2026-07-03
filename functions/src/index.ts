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
