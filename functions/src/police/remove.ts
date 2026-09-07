/**
 * police.remove — the REPORTER takes down their own police pin
 * (contracts/functions/functions.json: police.remove).
 *
 * Deployed via the `police` export group as `police-remove` (europe-west1).
 * Active-account-gated (requireActiveActor), matching police.report — reporting a pin and
 * un-reporting it demand the same trust level. A caller may remove ONLY a pin they
 * reported: the stored (never-returned) `reporterUid` is compared to the caller and
 * anyone else is rejected with permission-denied. This is deliberately stricter
 * than incidents.remove, which also lets an admin moderate — a police pin is
 * transient and self-expiring, so the only removal that needs a callable is the
 * reporter correcting their own report; moderation can wait for the ~40 min TTL.
 *
 * Idempotent: removing a missing pin is a no-op success ({ removed: false }), so a
 * retry after the pin already aged out (or a double-tap) is not an error. Removal
 * DELETES the document so the marker disappears for everyone on the next nearby
 * fetch; the `votes/{uid}` verify ledger is swept immediately after (best-effort).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { parseVoteInput } from './police-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

export interface RemoveResponse {
  removed: boolean;
}

export const remove = onCall(CALLABLE_OPTS, async (request): Promise<RemoveResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseVoteInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const ref = db.collection('policeReports').doc(parsed.input.policeReportId);
  const removed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      return false; // idempotent no-op — the pin already aged out or never existed
    }
    // Only the reporter may remove their own pin. The reporterUid is stored on the
    // doc and NEVER returned to clients (privacy); it is compared here to the
    // authenticated caller. A missing/non-string reporterUid never equals the
    // caller, so a corrupt doc is treated as "not yours" (permission-denied)
    // rather than deletable by anyone — fail closed on the authorization guard.
    if (snap.get('reporterUid') !== actor.uid) {
      throw new HttpsError('permission-denied', 'You can only remove your own police report.');
    }
    tx.delete(ref);
    return true;
  });

  if (removed) {
    // A document delete does not remove its sub-collections, so the `votes/{uid}`
    // verify ledger is orphaned. recursiveDelete cannot run inside a transaction,
    // so sweep it immediately after the commit (the pin is already gone for
    // readers at this point). BEST-EFFORT: the user-visible outcome — the marker
    // gone for everyone — is already complete, and letting a transient cleanup
    // failure propagate would report failure for an operation that succeeded (and
    // a retry would take the idempotent no-op branch and wrongly say nothing was
    // removed). An orphaned ledger is callable-only dead storage; no query reads
    // it and ids are never reused. The pin doc itself is reclaimed by its TTL, but
    // the sub-collection is not, so this logs the id if it is ever left behind.
    try {
      await db.recursiveDelete(ref);
    } catch (error) {
      logger.error('police.remove: verify ledger cleanup failed; orphan left behind', {
        policeReportId: parsed.input.policeReportId,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }

  return { removed };
});
