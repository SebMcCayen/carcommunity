/**
 * drives.routeUrl — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-routeUrl`.
 *
 * Issues a SHORT-LIVED (5 min) V4 signed download URL for an owned drive's full
 * GPS route file (rideRoutes/{uid}/{rideId}/route.bin). This is the migration
 * path off the current direct owner Storage read (firebase/storage.rules): once
 * clients fetch the route through this callable, a later PR can drop the
 * direct-read rule. That Storage-rules lockdown is deliberately NOT part of this
 * change — storage.rules and firestore.rules are untouched here.
 *
 * Authorization is owner-only AND tier-gated:
 *  - The ride must exist and belong to the caller, else `not-found` (never
 *    reveal that a rideId belongs to someone else).
 *  - The ride must be inside the caller's subscription-tier history window,
 *    re-derived from server state with the SAME policy drives.listHistory uses
 *    (routeUrl-core.decideRouteVisibility). A downgraded member cannot replay a
 *    drive hidden beyond their window by guessing its rideId.
 *
 * ============================ OPERATOR PREREQUISITE ============================
 * V4 signing with `getSignedUrl` under Application Default Credentials has no
 * private key on hand, so it calls the IAM Credentials `signBlob` API AS the
 * function's runtime service account. That requires the runtime SA to hold
 * `roles/iam.serviceAccountTokenCreator` (permission
 * `iam.serviceAccounts.signBlob`) ON ITSELF. This is an operator IAM grant that
 * is NOT applied by any repo deploy and may be absent. When it is absent (or any
 * other signing error occurs), signing is caught and the callable throws
 * `failed-precondition` with a generic message — it never crashes and never
 * leaks the SA email or IAM detail. Grant (default gen-2 runtime SA shown):
 *   gcloud iam service-accounts add-iam-policy-binding \
 *     <PROJECT_NUMBER>-compute@developer.gserviceaccount.com \
 *     --member="serviceAccount:<PROJECT_NUMBER>-compute@developer.gserviceaccount.com" \
 *     --role="roles/iam.serviceAccountTokenCreator" --project=kungsbacka-car-community
 * =============================================================================
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldPath, Timestamp } from 'firebase-admin/firestore';
import { adminStorage, db } from '../firebase';
import { isRestricted, toUserAccessState } from '../shared/access';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';
import { effectiveSubscriptionTierFromStoredRecord } from '../subscription/subscription-core';
import { COMMUNITY_DRIVE_HISTORY_LIMIT } from './driveHistory-core';
import { rideRoutePath } from './drives-core';
import {
  ROUTE_MISSING_MESSAGE,
  ROUTE_UNAVAILABLE_MESSAGE,
  decideRouteVisibility,
  parseRouteUrlInput,
  signRouteUrl,
  type SignedRouteUrl,
} from './routeUrl-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export type RouteUrlResponse = SignedRouteUrl;

export const routeUrl = onCall(CALLABLE_OPTS, async (request): Promise<RouteUrlResponse> => {
  // Require a stored profile as well as an active account. The shared actor
  // guard tolerates missing profiles for onboarding; route downloads must not
  // do so when a stale token outlives profile deletion and files still remain.
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in to continue.');
  const profile = await db.collection('users').doc(uid).get();
  if (!profile.exists || isRestricted(toUserAccessState(profile.data()))) {
    throw new HttpsError('permission-denied', 'Account access is restricted.');
  }
  const actor = { uid };

  const parsed = parseRouteUrlInput(request.data);
  if (!parsed.ok) throw new HttpsError('invalid-argument', parsed.message);
  const { rideId } = parsed.input;

  const rideSnap = await db.collection('rides').doc(rideId).get();
  // Owner-only. A missing ride and someone else's ride are the SAME response so
  // a caller cannot probe which rideIds exist for other members.
  if (!rideSnap.exists || rideSnap.data()?.userId !== actor.uid) {
    throw new HttpsError('not-found', 'Saved drive not found.');
  }
  const ride = rideSnap.data()!;
  const rideCreatedAtMillis =
    ride.createdAt instanceof Timestamp ? ride.createdAt.toMillis() : null;

  // Tier visibility re-check (independent of drives.listHistory's display gate).
  const subscriptionSnap = await db.collection('subscriptions').doc(actor.uid).get();
  const tier = effectiveSubscriptionTierFromStoredRecord(
    subscriptionSnap.exists ? subscriptionSnap.data() : null,
    actor.uid,
  );
  const serverNowMillis = Date.now();

  // Community's window is "newest five". Resolve membership with the SAME
  // ordered query drives.listHistory uses (createdAt desc, documentId desc),
  // so ties break identically and no separate composite index is needed — the
  // rides (userId, createdAt) index already serves it. Only run it for
  // Community; paid tiers decide from createdAt alone.
  let isAmongNewestForCommunity = false;
  if (tier === 'community') {
    const newestSnap = await db
      .collection('rides')
      .where('userId', '==', actor.uid)
      .orderBy('createdAt', 'desc')
      .orderBy(FieldPath.documentId(), 'desc')
      .limit(COMMUNITY_DRIVE_HISTORY_LIMIT)
      .get();
    isAmongNewestForCommunity = newestSnap.docs.some((doc) => doc.id === rideId);
  }

  const visibility = decideRouteVisibility({
    tier,
    rideCreatedAtMillis,
    serverNowMillis,
    isAmongNewestForCommunity,
  });
  if (!visibility.visible) {
    throw new HttpsError('permission-denied', visibility.message);
  }

  // Use the path stored on the ride when present (the canonical value written by
  // drives.save), falling back to the deterministic path for any older document
  // that predates the stored field.
  const routePath =
    typeof ride.routePath === 'string' && ride.routePath.length > 0
      ? ride.routePath
      : rideRoutePath(actor.uid, rideId);
  const file = adminStorage.bucket().file(routePath);

  // A summary-only or legacy drive may have no uploaded route file. Distinct
  // from the signing fail-safe below: this is a permanent condition for this
  // drive, so it gets its own message.
  const [exists] = await file.exists();
  if (!exists) {
    throw new HttpsError('failed-precondition', ROUTE_MISSING_MESSAGE);
  }

  const signed = await signRouteUrl(
    (expiresAtMillis) =>
      file
        .getSignedUrl({ version: 'v4', action: 'read', expires: expiresAtMillis })
        .then(([url]) => url),
    serverNowMillis,
    (error) =>
      // Logged for operator triage (the missing IAM grant surfaces here), never
      // returned to the client.
      logger.error('drives.routeUrl: signing failed — check Service Account Token Creator', {
        rideId,
        error: String(error),
      }),
  );
  if (!signed.ok) {
    throw new HttpsError('failed-precondition', ROUTE_UNAVAILABLE_MESSAGE);
  }

  return signed.value;
});
