/**
 * incidents.confirm — "is this still there?" confirmation on someone else's
 * crowd-sourced incident (contracts/functions/functions.json: incidents.confirm).
 *
 * Deployed via the `incidents` export group as `incidents-confirm`
 * (europe-west1). Active-account-gated (requireActiveActor), matching `incidents.report`
 * rather than the read-only `incidents.listNearby`: a confirmation is a WRITE
 * that changes what every user on the map sees and keeps a marker alive, so it
 * demands the same trust level as creating one in the first place.
 *
 * Semantics:
 *  - One confirmation per user per incident, enforced by the document id of
 *    `incidents/{id}/confirmations/{uid}` — claimed with `tx.create` inside the
 *    same transaction that bumps the counter and the expiry, so a double-tap
 *    (or two devices) cannot double-count. A repeat confirmation is NOT an
 *    error: it returns the current state with `alreadyConfirmed: true`, so the
 *    client's button settles into a stable "confirmed" state either way.
 *  - The reporter cannot confirm their own report (self-corroboration is not
 *    evidence).
 *  - A member who had voted the incident CLEAR (`incidents.reportCleared`) may
 *    switch back to confirming: their clear vote is deleted and `clearedCount`
 *    decremented in the SAME transaction that claims the confirmation, so a
 *    switcher is never counted on both sides. This is the mirror of the
 *    confirm→clear switch handled in reportCleared.ts, and it exists for the
 *    same reason: a member whose information changed must be able to correct
 *    their own vote, or the map stops tracking reality.
 *  - Every confirmation RE-DERIVES `reportedCleared` from the resulting counts.
 *    That flag is what makes clients draw the marker faded, so a confirmation
 *    that brings confirms level with (or ahead of) clears must un-fade it —
 *    leaving a stale `true` behind would keep a re-corroborated hazard looking
 *    like it had been reported gone.
 *  - Confirming extends `expiresAt` to a fresh TTL from now, bounded by the
 *    hard lifetime cap in extendedExpiryFor — a popular incident persists but
 *    never becomes immortal.
 *  - Imported (Trafikverket) incidents are NOT confirmable; see below.
 *
 * No notification is sent to the reporter. A confirmation is ambient corroboration,
 * not a social interaction: on a busy road one report could collect dozens, and
 * a push per confirmation would be pure noise for zero action the reporter can
 * take. The count is visible on the marker, which is where it is useful.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import {
  CLEAR_VOTES_SUBCOLLECTION,
  INCIDENT_TYPES,
  evaluateClearVote,
  extendedExpiryFor,
  isIncidentLive,
  isValidClearedCount,
  isValidConfirmationCount,
  parseConfirmInput,
  readClearedCount,
  readConfirmationCount,
  type IncidentType,
} from './incidents-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Sub-collection holding the per-uid confirmation ledger. */
export const CONFIRMATIONS_SUBCOLLECTION = 'confirmations';

export interface ConfirmResponse {
  incidentId: string;
  /** Total confirmations after this call. */
  confirmationCount: number;
  /** Total "it's gone" votes after this call (a switch decrements it). */
  clearedCount: number;
  /** True when clear votes still lead — the marker stays faded. */
  reportedCleared: boolean;
  /** Incident expiry after this call (ISO-8601). */
  expiresAt: string;
  /** True when this caller had already confirmed (no double count). */
  alreadyConfirmed: boolean;
  /** True when this confirmation replaced the caller's earlier clear vote. */
  switchedFromClearVote: boolean;
}

export const confirm = onCall(CALLABLE_OPTS, async (request): Promise<ConfirmResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseConfirmInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const incidentId = parsed.input.incidentId;

  const ref = db.collection('incidents').doc(incidentId);
  const confirmationRef = ref.collection(CONFIRMATIONS_SUBCOLLECTION).doc(actor.uid);
  const clearVoteRef = ref.collection(CLEAR_VOTES_SUBCOLLECTION).doc(actor.uid);

  return db.runTransaction(async (tx) => {
    // All reads must precede any write in a Firestore transaction.
    const [snap, existing, existingClearVote] = await Promise.all([
      tx.get(ref),
      tx.get(confirmationRef),
      tx.get(clearVoteRef),
    ]);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Incident not found.');
    }
    const data = snap.data()!;

    // Imported (Trafikverket) incidents are importer-owned: runTrafikverketSync
    // fully overwrites a `tv_` doc when it is created, migrated, or changed;
    // fingerprint-matched unchanged syncs skip the write. A later importer write
    // would still silently wipe a confirmationCount or expiry we extended here.
    // Upstream is also the authority on whether the situation is still live, so
    // a member confirmation would add nothing and fight the importer. Rejected
    // for everyone, mirroring incidents.remove.
    if (data.source !== 'user') {
      throw new HttpsError(
        'failed-precondition',
        'Imported incidents are kept up to date automatically and cannot be confirmed.',
      );
    }

    // ONE clock reading for the whole decision: the liveness check below and the
    // extended expiry computed further down must agree on what "now" is.
    // Re-reading the clock would not make anything safer — a deadline compared
    // against a wall clock is racy by construction, and any number of re-checks
    // only moves the window rather than closing it — but a single reading makes
    // the handler internally consistent and removes the question entirely.
    const now = new Date();

    // A dead incident cannot be confirmed back to life — the sweep may not have
    // reached it yet, but it is already invisible to every reader (the read rule
    // gates on status + expiresAt). Report it fresh instead.
    // The `instanceof` stays in the condition so it also NARROWS
    // `currentExpiresAt` for the `.toDate()` reads further down; `isIncidentLive`
    // owns the status + deadline rule itself.
    const currentExpiresAt = data.expiresAt;
    if (
      !(currentExpiresAt instanceof Timestamp) ||
      !isIncidentLive(data.status, currentExpiresAt.toMillis(), now.getTime())
    ) {
      throw new HttpsError('failed-precondition', 'This incident is no longer active.');
    }

    // `reporterUid` must be a real uid before the self-confirmation check below
    // can mean anything. A user-sourced incident always has one (report.ts
    // writes `reporterUid: actor.uid` unconditionally), so a missing or
    // non-string value is corruption from outside — and the failure mode is
    // worse than the malformed createdAt/type below, because it is silent: a
    // `null` reporterUid never equals any caller's uid, so the authorization
    // check does not reject, it simply never fires, and the reporter can
    // confirm and extend their own report. An authorization guard that
    // quietly stops guarding is exactly the class of thing that must be loud.
    if (typeof data.reporterUid !== 'string' || data.reporterUid.length === 0) {
      logger.error('incidents.confirm: user incident has a missing/invalid reporterUid', {
        incidentId,
        reporterUidType: typeof data.reporterUid,
      });
      throw new HttpsError('internal', 'This incident cannot be confirmed right now.');
    }

    if (data.reporterUid === actor.uid) {
      throw new HttpsError('permission-denied', 'You cannot confirm your own report.');
    }

    // Absent is the normal pre-first-confirmation state → 0. PRESENT but not a
    // non-negative integer is corruption, and this is a write path: the reply
    // and the next confirmation both build on this number, and NaN survives
    // `FieldValue.increment` (NaN + 1 is NaN) so a single corrupt value would
    // be permanent. It is not even reportable — the callable framework
    // serialises NaN/Infinity to JSON `null`, so a client typed against
    // `confirmationCount: number` would silently receive `null`. Refuse, as
    // with the other malformed fields. (listNearby takes the opposite branch on
    // purpose: it is a bulk read of a shared map layer, so it degrades the one
    // marker to 0 rather than failing everyone's batch — see
    // readConfirmationCount.)
    if (data.confirmationCount !== undefined && !isValidConfirmationCount(data.confirmationCount)) {
      logger.error('incidents.confirm: incident has a corrupt confirmationCount', {
        incidentId,
        confirmationCount: String(data.confirmationCount),
      });
      throw new HttpsError('internal', 'This incident cannot be confirmed right now.');
    }
    const storedCount = readConfirmationCount(data.confirmationCount);

    // Same reasoning for the clear-vote counter: this path WRITES a value
    // derived from it (the fade flag and, on a switch, the count itself), so a
    // corrupt number must stop the call rather than be built upon.
    if (data.clearedCount !== undefined && !isValidClearedCount(data.clearedCount)) {
      logger.error('incidents.confirm: incident has a corrupt clearedCount', {
        incidentId,
        clearedCount: String(data.clearedCount),
      });
      throw new HttpsError('internal', 'This incident cannot be confirmed right now.');
    }
    const storedClearedCount = readClearedCount(data.clearedCount);

    // Already confirmed → idempotent success, nothing written. The expiry is NOT
    // extended again: otherwise one member could hold an incident open forever
    // by re-tapping (the lifetime cap bounds it anyway, but not writing is both
    // cheaper and clearer).
    if (existing.exists) {
      const tally = evaluateClearVote({
        clearedCount: storedClearedCount,
        confirmationCount: storedCount,
      });
      return {
        incidentId,
        confirmationCount: storedCount,
        clearedCount: storedClearedCount,
        reportedCleared: tally.reportedCleared,
        expiresAt: currentExpiresAt.toDate().toISOString(),
        alreadyConfirmed: true,
        switchedFromClearVote: false,
      };
    }

    // FAIL FAST on a malformed document rather than substituting defaults.
    //
    // Both inputs below decide how long this incident lives, so a wrong value
    // is not cosmetic: `createdAt` anchors the lifetime cap (defaulting it to
    // `now` would silently RE-ANCHOR the cap on every confirmation, which is
    // exactly the immortality the cap exists to prevent), and `type` selects
    // the TTL (defaulting a corrupt type to 'hazard' would write an expiry
    // computed under the wrong rules). Extending the wrong incident's life is
    // strictly worse than refusing, so we refuse.
    //
    // REACHABILITY: this is defensive-only under the current schema. The only
    // writers of `incidents/{id}` are incidents/report.ts (validated enum type,
    // server-timestamp createdAt) and the Trafikverket importer (rejected above
    // by the `source !== 'user'` guard); firebase/firestore.rules denies ALL
    // client writes to the collection. So no code path produces a malformed
    // user incident today — this guards a hand-edit in the console, a restore
    // from a stale export, or a future schema change, and turns any of them
    // into a loud, findable error instead of quiet mis-expiry.
    //
    // `internal`, not `failed-precondition`: failed-precondition is this
    // callable's vocabulary for NORMAL states the user can understand (expired,
    // imported). A corrupt document is a server-side defect the user can do
    // nothing about, and the message stays opaque rather than leaking schema
    // detail. The logger.error below carries the incidentId so the offending
    // document can actually be found.
    if (!(data.createdAt instanceof Timestamp)) {
      logger.error('incidents.confirm: incident has a missing/invalid createdAt', {
        incidentId,
        createdAtType: typeof data.createdAt,
      });
      throw new HttpsError('internal', 'This incident cannot be confirmed right now.');
    }
    if (!(INCIDENT_TYPES as readonly string[]).includes(data.type as string)) {
      logger.error('incidents.confirm: incident has an unrecognised type', {
        incidentId,
        type: String(data.type),
      });
      throw new HttpsError('internal', 'This incident cannot be confirmed right now.');
    }
    const createdAt = data.createdAt.toDate();
    const type = data.type as IncidentType;

    const { expiresAt } = extendedExpiryFor({
      type,
      createdAt,
      currentExpiresAt: currentExpiresAt.toDate(),
      now,
    });

    // A member who had voted this incident GONE is switching back. Their clear
    // vote is dropped and the counter moved in this SAME transaction, so they
    // are never counted on both sides. (The mirror-image switch lives in
    // reportCleared.ts; see its header for why switching is allowed at all.)
    const switchedFromClearVote = existingClearVote.exists;
    const nextClearedCount = switchedFromClearVote
      ? Math.max(0, storedClearedCount - 1)
      : storedClearedCount;
    const nextConfirmationCount = storedCount + 1;
    const tally = evaluateClearVote({
      clearedCount: nextClearedCount,
      confirmationCount: nextConfirmationCount,
    });

    // `create` (not `set`): if a concurrent call for the same uid slipped in
    // between the read above and this commit, the transaction aborts and
    // retries rather than double-counting.
    tx.create(confirmationRef, {
      uid: actor.uid,
      createdAt: FieldValue.serverTimestamp(),
    });
    if (switchedFromClearVote) {
      tx.delete(clearVoteRef);
    }
    // ABSOLUTE counts rather than FieldValue.increment, now that a value DERIVED
    // from them (`reportedCleared`) is written in the same update: an increment
    // could commit a count that disagrees with the flag written beside it. Safe
    // because this transaction READ the document — a concurrent write aborts it
    // and it retries with fresh values.
    tx.update(ref, {
      confirmationCount: nextConfirmationCount,
      clearedCount: nextClearedCount,
      // Re-derived every time, so a confirmation that brings confirms level with
      // the clears un-fades the marker instead of leaving a stale `true`.
      reportedCleared: tally.reportedCleared,
      expiresAt: Timestamp.fromDate(expiresAt),
    });

    return {
      incidentId,
      confirmationCount: nextConfirmationCount,
      clearedCount: nextClearedCount,
      reportedCleared: tally.reportedCleared,
      expiresAt: expiresAt.toISOString(),
      alreadyConfirmed: false,
      switchedFromClearVote,
    };
  });
});
