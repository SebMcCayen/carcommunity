/**
 * incidents.confirm — "is this still there?" confirmation on someone else's
 * crowd-sourced incident (contracts/functions/functions.json: incidents.confirm).
 *
 * Deployed via the `incidents` export group as `incidents-confirm`
 * (europe-west1). Member-gated (requireMemberActor), matching `incidents.report`
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
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import {
  INCIDENT_ACTIVE_STATUS,
  INCIDENT_TYPES,
  extendedExpiryFor,
  parseConfirmInput,
  type IncidentType,
} from './incidents-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
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
  /** Incident expiry after this call (ISO-8601). */
  expiresAt: string;
  /** True when this caller had already confirmed (no double count). */
  alreadyConfirmed: boolean;
}

export const confirm = onCall(CALLABLE_OPTS, async (request): Promise<ConfirmResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseConfirmInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const incidentId = parsed.input.incidentId;

  const ref = db.collection('incidents').doc(incidentId);
  const confirmationRef = ref.collection(CONFIRMATIONS_SUBCOLLECTION).doc(actor.uid);

  return db.runTransaction(async (tx) => {
    // Both reads must precede any write in a Firestore transaction.
    const [snap, existing] = await Promise.all([tx.get(ref), tx.get(confirmationRef)]);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Incident not found.');
    }
    const data = snap.data()!;

    // Imported (Trafikverket) incidents are importer-owned: runTrafikverketSync
    // rewrites each `tv_` doc with a full `batch.set` (no merge) every 30
    // minutes, which would silently wipe a confirmationCount and re-stamp the
    // expiry we just extended. Upstream is also the authority on whether the
    // situation is still live — that is exactly what the 30-minute re-sync
    // means — so a member confirmation would add nothing and fight the
    // importer. Rejected for everyone, mirroring incidents.remove.
    if (data.source !== 'user') {
      throw new HttpsError(
        'failed-precondition',
        'Imported incidents are kept up to date automatically and cannot be confirmed.',
      );
    }

    // A dead incident cannot be confirmed back to life — the sweep may not have
    // reached it yet, but it is already invisible to every reader (the read rule
    // gates on status + expiresAt). Report it fresh instead.
    const currentExpiresAt = data.expiresAt;
    if (
      data.status !== INCIDENT_ACTIVE_STATUS ||
      !(currentExpiresAt instanceof Timestamp) ||
      currentExpiresAt.toMillis() <= Date.now()
    ) {
      throw new HttpsError('failed-precondition', 'This incident is no longer active.');
    }

    if (data.reporterUid === actor.uid) {
      throw new HttpsError('permission-denied', 'You cannot confirm your own report.');
    }

    const storedCount = typeof data.confirmationCount === 'number' ? data.confirmationCount : 0;

    // Already confirmed → idempotent success, nothing written. The expiry is NOT
    // extended again: otherwise one member could hold an incident open forever
    // by re-tapping (the lifetime cap bounds it anyway, but not writing is both
    // cheaper and clearer).
    if (existing.exists) {
      return {
        incidentId,
        confirmationCount: storedCount,
        expiresAt: currentExpiresAt.toDate().toISOString(),
        alreadyConfirmed: true,
      };
    }

    const now = new Date();
    const createdAt = data.createdAt instanceof Timestamp ? data.createdAt.toDate() : now;
    const type = (INCIDENT_TYPES as readonly string[]).includes(data.type as string)
      ? (data.type as IncidentType)
      : 'hazard';
    const { expiresAt } = extendedExpiryFor({
      type,
      createdAt,
      currentExpiresAt: currentExpiresAt.toDate(),
      now,
    });

    // `create` (not `set`): if a concurrent call for the same uid slipped in
    // between the read above and this commit, the transaction aborts and
    // retries rather than double-counting.
    tx.create(confirmationRef, {
      uid: actor.uid,
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.update(ref, {
      confirmationCount: FieldValue.increment(1),
      expiresAt: Timestamp.fromDate(expiresAt),
    });

    return {
      incidentId,
      confirmationCount: storedCount + 1,
      expiresAt: expiresAt.toISOString(),
      alreadyConfirmed: false,
    };
  });
});
