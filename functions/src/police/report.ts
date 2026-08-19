/**
 * police.report — member-gated create of a short-lived, user-reported POLICE pin
 * (contracts/functions/functions.json: police.report).
 *
 * Deployed via the `police` export group as `police-report` (europe-west1).
 * Requires an active member (requireMemberActor). The pin is written to
 * `policeReports/{id}` with a computed `geoCell` (nearby-query index) and a SHORT
 * `expiresAt` TTL ({@link policeExpiryFor}); `createdAt` is a server timestamp.
 * All writes flow through this callable — clients cannot write the collection
 * directly (firebase/firestore.rules), so a pin cannot be forged or backdated.
 *
 * RATE-LIMITED on report (unlike the shared incidents.report): a per-user
 * fixed-window counter is checked BEFORE the write so one member cannot flood the
 * map with fake police pins and spam every nearby driver with false proximity
 * alerts. The pure admit/reject decision lives in police-core
 * (isUnderPoliceReportRateLimit) and is unit-tested there; the counter write is
 * inlined here (a rejected call costs exactly one get-by-id, no write).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  POLICE_REPORT_RATE_LIMIT_COLLECTION,
  buildPoliceReportFields,
  isReportable,
  isUnderPoliceReportRateLimit,
  parseReportInput,
  policeExpiryFor,
  policeReportRateLimitDocId,
  policeReportRateLimitExpiry,
  type PoliceReportView,
} from './police-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export const report = onCall(CALLABLE_OPTS, async (request): Promise<PoliceReportView> => {
  const actor = await requireMemberActor(request);

  // Validate BEFORE the rate limit so a malformed call never burns the caller's
  // report window on a bad payload.
  const parsed = parseReportInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const input = parsed.input;
  if (!isReportable(input.latitude, input.longitude)) {
    throw new HttpsError('invalid-argument', 'Invalid coordinate.');
  }

  await enforceReportRateLimit(actor.uid);

  const now = new Date();
  const expiresAt = policeExpiryFor(now);
  const fields = buildPoliceReportFields({
    latitude: input.latitude,
    longitude: input.longitude,
    reporterUid: actor.uid,
    source: input.source,
  });

  const ref = db.collection('policeReports').doc();
  await ref.set({
    ...fields,
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: Timestamp.fromDate(expiresAt),
  });

  return {
    id: ref.id,
    latitude: fields.latitude,
    longitude: fields.longitude,
    reporterUid: fields.reporterUid,
    source: fields.source,
    // The stored createdAt is a server timestamp resolved by Firestore, which may
    // not equal `now`; clients read the authoritative value via listNearby.
    createdAt: null,
    expiresAt: expiresAt.toISOString(),
  };
});

/**
 * Fixed-window per-user rate limit for `police.report`.
 *
 * Reads the deterministic counter doc for (uid, current minute) BY ID — no query,
 * no index — and throws `resource-exhausted` once the uid has already made
 * POLICE_REPORT_RATE_LIMIT_MAX reports this window. Otherwise it bumps the counter
 * with FieldValue.increment(1) (a commutative, contention-free server op — no
 * transaction) and stamps `expireAt` so a Firestore TTL policy reaps the spent
 * window (deploy note below). A rejected call performs the single get and NO write.
 */
async function enforceReportRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db
    .collection(POLICE_REPORT_RATE_LIMIT_COLLECTION)
    .doc(policeReportRateLimitDocId(uid, nowMs));

  const snap = await ref.get();
  const currentCount = snap.get('count');
  if (!isUnderPoliceReportRateLimit(typeof currentCount === 'number' ? currentCount : 0)) {
    throw new HttpsError(
      'resource-exhausted',
      'Too many police reports in a short time — please slow down and try again shortly.',
    );
  }

  await ref.set(
    {
      count: FieldValue.increment(1),
      uid,
      expireAt: Timestamp.fromDate(policeReportRateLimitExpiry(nowMs)),
    },
    { merge: true },
  );
}

// One-time deploy step for the counter's TTL (spent windows self-delete so the
// policeReportRateLimits collection never accumulates):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=policeReportRateLimits --enable-ttl
//
// The collection is backend-only: written here via the Admin SDK and denied to
// all clients by firebase/firestore.rules. It needs no composite index (read by
// document id) and no client-readable rule.
//
// The pin documents themselves (policeReports) also carry an `expiresAt` that
// needs its OWN one-time TTL policy — there is NO scheduled sweep, the read rule
// + listNearby hide an expired pin immediately and the TTL policy reclaims it:
//
//   gcloud firestore fields ttls update expiresAt \
//     --collection-group=policeReports --enable-ttl
