/**
 * police.confirm / police.dispute — the "verify" actions a non-reporter takes on a
 * tapped police pin (contracts/functions/functions.json: police.confirm,
 * police.dispute).
 *
 * Deployed via the `police` export group as `police-confirm` and `police-dispute`
 * (europe-west1). Both are member-gated (requireMemberActor), matching
 * police.report/remove: a verify is a WRITE that changes a count every nearby
 * member sees on the pin's tap sheet, so it demands the same trust as reporting.
 *
 * Modelled on incidents.confirm / incidents.reportCleared, deliberately leaner
 * (see police-core's verify-ledger note for the full rationale):
 *  - CONFIRM corroborates the pin. Unlike incidents.confirm it does NOT extend the
 *    pin's life — a still-relevant patrol is re-reported, not confirmed-to-extend.
 *  - DISPUTE ("Borta/Not here") records disbelief. Unlike incidents.reportCleared
 *    it does NOT remove the pin at a threshold: taking a pin off everyone's map on
 *    a vote is only trustworthy with a geofenced presence proof, which a ~40 min
 *    self-expiring pin does not justify. A dispute informs (a count) only.
 *
 * Shared semantics (this file):
 *  - ONE vote per (uid, pin): a single ledger doc `policeReports/{id}/votes/{uid}`
 *    holds the caller's current `kind`. Switching sides moves the caller from one
 *    count to the other in the SAME transaction, so a member is never counted on
 *    both sides. A repeat on the same side is idempotent success (alreadyVoted),
 *    not an error, so the client's button settles either way.
 *  - The reporter can neither confirm nor dispute their own pin (they have Remove).
 *  - A dead pin (inactive / expired) cannot be verified — it is already hidden from
 *    every reader; report a fresh one instead.
 *  - Rate-limited on a per-uid fixed-window budget SHARED by confirm + dispute.
 *  - No PII in logs (only the pin id + coarse types on a corrupt-doc guard).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  POLICE_VOTE_RATE_LIMIT_COLLECTION,
  POLICE_VOTES_SUBCOLLECTION,
  isPoliceReportLive,
  isUnderPoliceVoteRateLimit,
  isValidVoteCount,
  parseVoteInput,
  policeVoteRateLimitDocId,
  policeVoteRateLimitExpiry,
  readVoteCount,
  type PoliceVoteKind,
} from './police-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface VerifyResponse {
  policeReportId: string;
  /** Total confirmations after this call. */
  confirmationCount: number;
  /** Total disputes after this call. */
  disputeCount: number;
  /** True when this caller had already voted THIS side (no double count). */
  alreadyVoted: boolean;
  /** True when this vote replaced the caller's opposite earlier vote. */
  switched: boolean;
}

/**
 * The shared confirm/dispute body. `kind` selects which count this vote lands on;
 * a switch decrements the opposite count in the same transaction.
 */
async function castVote(data: unknown, uid: string, kind: PoliceVoteKind): Promise<VerifyResponse> {
  const parsed = parseVoteInput(data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const policeReportId = parsed.input.policeReportId;

  // A verify cannot forge a pin (one vote per uid, deduped below), so a slip in
  // this non-transactional counter is harmless — it only caps FREQUENCY. Checked
  // before the main transaction so a throttled call costs ~one counter get.
  await enforceVoteRateLimit(uid);

  const ref = db.collection('policeReports').doc(policeReportId);
  const voteRef = ref.collection(POLICE_VOTES_SUBCOLLECTION).doc(uid);

  return db.runTransaction(async (tx) => {
    // All reads precede any write in a Firestore transaction. Reading voteRef is
    // also what gives concurrency safety: a same-uid double-tap that slipped
    // between this read and the commit conflicts on voteRef and retries, so the
    // absolute counts written below can never double-count.
    const [snap, existingVote] = await Promise.all([tx.get(ref), tx.get(voteRef)]);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Police report not found.');
    }
    const doc = snap.data()!;
    const now = new Date();

    // A dead pin cannot be verified back to life — it is already invisible to every
    // reader (the read rule + listNearby gate on status + expiresAt). The
    // `instanceof` also narrows the value for isPoliceReportLive.
    const expiresAt = doc.expiresAt;
    if (
      !(expiresAt instanceof Timestamp) ||
      !isPoliceReportLive(doc.status, expiresAt.toMillis(), now.getTime())
    ) {
      throw new HttpsError('failed-precondition', 'This police report is no longer active.');
    }

    // The reporterUid must be a real uid before the self-verify check can mean
    // anything. report.ts always writes it, so a missing/non-string value is
    // corruption from outside — and the failure is SILENT if ignored (a null
    // reporterUid never equals the caller, so the guard never fires and the
    // reporter could verify their own pin). Refuse loudly instead. No uid logged.
    if (typeof doc.reporterUid !== 'string' || doc.reporterUid.length === 0) {
      logger.error('police.verify: pin has a missing/invalid reporterUid', {
        policeReportId,
        reporterUidType: typeof doc.reporterUid,
      });
      throw new HttpsError('internal', 'This police report cannot be verified right now.');
    }
    if (doc.reporterUid === uid) {
      throw new HttpsError('permission-denied', 'You cannot verify your own police report.');
    }

    // PRESENT-but-corrupt counts must stop the call, not be defaulted: this is a
    // write path and the reply + next vote build on the number. (listNearby takes
    // the opposite branch — it degrades a bulk read to 0 rather than failing.)
    if (doc.confirmationCount !== undefined && !isValidVoteCount(doc.confirmationCount)) {
      logger.error('police.verify: pin has a corrupt confirmationCount', { policeReportId });
      throw new HttpsError('internal', 'This police report cannot be verified right now.');
    }
    if (doc.disputeCount !== undefined && !isValidVoteCount(doc.disputeCount)) {
      logger.error('police.verify: pin has a corrupt disputeCount', { policeReportId });
      throw new HttpsError('internal', 'This police report cannot be verified right now.');
    }
    const storedConfirm = readVoteCount(doc.confirmationCount);
    const storedDispute = readVoteCount(doc.disputeCount);

    const rawExistingKind = existingVote.exists ? (existingVote.get('kind') as unknown) : null;
    // Normalise the ledger's stored kind to a KNOWN side, treating anything that is
    // not exactly 'confirm'/'dispute' (absent, or present-but-corrupt from a
    // hand-edit — this callable only ever writes valid kinds) as "no recorded
    // side". A corrupt kind is thus HEALED as a fresh vote: this write overwrites
    // the doc with a valid kind and bumps the chosen side once. It deliberately
    // does NOT throw (a garbled ledger doc must not wedge verifying a transient
    // pin) and does NOT decrement a side it cannot trust the value of — so it can
    // never double-count off a corrupt value.
    const existingKind: PoliceVoteKind | null =
      rawExistingKind === 'confirm' || rawExistingKind === 'dispute' ? rawExistingKind : null;

    // Already on this side → idempotent success, nothing written.
    if (existingKind === kind) {
      return {
        policeReportId,
        confirmationCount: storedConfirm,
        disputeCount: storedDispute,
        alreadyVoted: true,
        switched: false,
      };
    }

    // A switch is a VALID existing vote on the opposite side (the only remaining
    // case now that same-side returned above and corrupt/absent normalised to null).
    const switched = existingKind !== null;
    let nextConfirm = storedConfirm;
    let nextDispute = storedDispute;
    if (kind === 'confirm') {
      nextConfirm = storedConfirm + 1;
      if (existingKind === 'dispute') nextDispute = Math.max(0, storedDispute - 1);
    } else {
      nextDispute = storedDispute + 1;
      if (existingKind === 'confirm') nextConfirm = Math.max(0, storedConfirm - 1);
    }

    // Overwrites on a switch, creates on a first vote — either way voteRef was read
    // above, so a concurrent write to it aborts+retries this transaction.
    tx.set(voteRef, { uid, kind, createdAt: FieldValue.serverTimestamp() });
    // ABSOLUTE counts (not FieldValue.increment): the transaction read the doc, so
    // a concurrent write aborts it and it retries with fresh values — and absolute
    // writes also cap a corrupt prior value rather than compounding it.
    tx.update(ref, { confirmationCount: nextConfirm, disputeCount: nextDispute });

    return {
      policeReportId,
      confirmationCount: nextConfirm,
      disputeCount: nextDispute,
      alreadyVoted: false,
      switched,
    };
  });
}

export const confirm = onCall(CALLABLE_OPTS, async (request): Promise<VerifyResponse> => {
  const actor = await requireMemberActor(request);
  return castVote(request.data, actor.uid, 'confirm');
});

export const dispute = onCall(CALLABLE_OPTS, async (request): Promise<VerifyResponse> => {
  const actor = await requireMemberActor(request);
  return castVote(request.data, actor.uid, 'dispute');
});

/**
 * Fixed-window per-user rate limit SHARED by confirm + dispute — a deterministic
 * counter doc read by id and TTL-reaped via `expireAt`, in its own collection so a
 * burst of verifies can never starve reporting or map refreshes.
 *
 * TRANSACTIONAL with an absolute write (read + 1), NOT a plain get→check→increment:
 * this is a HARD 10/60s cap, so concurrent confirm/dispute calls from the same uid
 * must not all observe a below-cap count and each increment past the budget. Same
 * exactness (and rationale) as {@link enforceReportRateLimit} in report.ts.
 */
async function enforceVoteRateLimit(uid: string): Promise<void> {
  const nowMs = Date.now();
  const ref = db.collection(POLICE_VOTE_RATE_LIMIT_COLLECTION).doc(policeVoteRateLimitDocId(uid, nowMs));

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const stored = snap.get('count');
    const currentCount = typeof stored === 'number' ? stored : 0;
    if (!isUnderPoliceVoteRateLimit(currentCount)) {
      // Thrown inside the transaction: it aborts (no write) and propagates. Not a
      // Firestore contention error, so it is NOT retried — a clean reject.
      throw new HttpsError(
        'resource-exhausted',
        'Too many police verifications in a short time — please slow down and try again shortly.',
      );
    }
    // Absolute write of the consistent read + 1 (not FieldValue.increment) so
    // simultaneous verifies can't each see below-cap and overshoot; it also caps a
    // corrupt prior value rather than compounding it.
    tx.set(
      ref,
      {
        count: currentCount + 1,
        uid,
        expireAt: Timestamp.fromDate(policeVoteRateLimitExpiry(nowMs)),
      },
      { merge: true },
    );
  });
}

// One-time deploy step for the verify counter's TTL (spent windows self-delete):
//
//   gcloud firestore fields ttls update expireAt \
//     --collection-group=policeVoteRateLimits --enable-ttl
