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
 * Fixed-window per-user rate limit for `police.report` — enforced ATOMICALLY.
 *
 * The read (count), the cap check, and the increment run inside ONE Firestore
 * transaction on the deterministic counter doc for (uid, current minute), so N
 * concurrent reports from the same uid in the same window SERIALIZE on that doc
 * and only the first POLICE_REPORT_RATE_LIMIT_MAX succeed — the rest get
 * `resource-exhausted` and write no pin. This is deliberately STRICTER than the
 * read-path `police.listNearby` guard (which uses a non-transactional
 * FieldValue.increment and tolerates a few boundary slips, because its only job
 * is to stop a runaway, not to be exact): here a slip lets a burst of parallel
 * calls FLOOD every nearby driver's map with fake pins, so the cap must hold
 * under concurrency. This matches the codebase's other WRITE-path limiters
 * (feedback.reportIssue / errors.reportClientError / moderation reports), which
 * are likewise transactional. Cross-uid there is NO contention (different uids →
 * different docs); the only serialization is a single user's own burst, which is
 * exactly what we want to bound.
 *
 * FAILS OPEN on a corrupt (non-finite) counter — a garbled rate-limit doc must
 * never stop a member warning others about a patrol (isUnderPoliceReportRateLimit
 * treats a non-finite count as admitted). The count is written as an ABSOLUTE
 * value (read + 1) rather than FieldValue.increment precisely because the
 * transaction already gives us the consistent pre-value, and an absolute write is
 * what lets the cap be enforced against a corrupt prior value without compounding
 * it. `expireAt` is stamped so a Firestore TTL policy reaps the spent window.
 */
async function enforceReportRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db
    .collection(POLICE_REPORT_RATE_LIMIT_COLLECTION)
    .doc(policeReportRateLimitDocId(uid, nowMs));

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const stored = snap.get('count');
    const currentCount = typeof stored === 'number' ? stored : 0;
    if (!isUnderPoliceReportRateLimit(currentCount)) {
      // Thrown inside the transaction: it aborts (no write) and propagates. Not a
      // Firestore contention error, so it is NOT retried — a clean reject.
      throw new HttpsError(
        'resource-exhausted',
        'Too many police reports in a short time — please slow down and try again shortly.',
      );
    }
    // Absolute write of the consistent read + 1 (see KDoc: exactness under
    // concurrency is the whole point, and it also caps a corrupt prior value
    // rather than compounding it). A non-finite currentCount only reaches here
    // when the guard failed open; `+ 1` on it stays non-finite, so the next call
    // also fails open rather than locking the user out.
    tx.set(
      ref,
      {
        count: currentCount + 1,
        uid,
        expireAt: Timestamp.fromDate(policeReportRateLimitExpiry(nowMs)),
      },
      { merge: true },
    );
  });
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
