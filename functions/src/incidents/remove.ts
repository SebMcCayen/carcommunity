/**
 * incidents.remove — the reporter (or an admin) clears an incident they no
 * longer stand behind (contracts/functions/functions.json: incidents.remove).
 *
 * Deployed via the `incidents` export group as `incidents-remove`
 * (europe-west1). A signed-in caller may remove their OWN report; an admin may
 * remove any (moderation). Idempotent: removing a missing incident is a no-op
 * success. User-sourced incidents only — imported (Trafikverket) roadwork is
 * managed by the sync/sweep, not hand-removed. Removal deletes the document so
 * the marker disappears for everyone on the next nearby fetch.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { parseRemoveInput } from './incidents-core';
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

  const parsed = parseRemoveInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  // Server-managed admin claim (set only by admin.setAdminRole); a suspended
  // admin is already rejected by requireActiveActor above.
  const isAdmin = request.auth?.token.admin === true;

  const ref = db.collection('incidents').doc(parsed.input.incidentId);
  const removed = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      return false; // idempotent no-op
    }
    const data = snap.data()!;
    // Only user-sourced reports are hand-removable — imported (Trafikverket)
    // incidents are managed by the sync/sweep. Deleting one here would just make
    // it reappear from the authoritative feed, so reject it for EVERYONE,
    // admins included.
    if (data.source !== 'user') {
      throw new HttpsError(
        'failed-precondition',
        'Imported incidents are managed automatically and cannot be removed.',
      );
    }
    const ownsIt = data.reporterUid === actor.uid;
    if (!ownsIt && !isAdmin) {
      throw new HttpsError('permission-denied', 'You can only remove your own report.');
    }
    tx.delete(ref);
    return true;
  });

  if (removed) {
    // A document delete does not remove its sub-collections, so the transaction
    // above leaves the `confirmations/{uid}` and `clearVotes/{uid}` ledgers
    // orphaned. recursiveDelete
    // cannot run inside a transaction, so sweep it immediately after the commit
    // (the incident is already gone for readers at this point).
    //
    // BEST-EFFORT, deliberately. The user-visible operation — the marker
    // disappearing for everyone — is complete the moment the transaction
    // commits. Letting a transient failure here propagate would report failure
    // for an operation that succeeded, and the natural retry is worse than
    // useless: the incident is already gone, so the second call takes the
    // idempotent no-op branch and returns `{ removed: false }`, telling the
    // user nothing was removed when in fact it was.
    //
    // What an orphan costs: nothing user-visible. The ledgers are callable-only
    // (the rules' deny-all catch-all covers the sub-collections), no query reads
    // them — there is no collection-group query over `confirmations` or
    // `clearVotes` anywhere in the codebase — and document ids are never reused,
    // so a future report cannot inherit one. It is dead storage and nothing else.
    //
    // NOTE it is NOT reclaimed later: the TTL sweep queries the `incidents`
    // collection, and this incident's document no longer exists, so the sweep
    // will never match it and never recurse into it. The orphan is permanent.
    // That is an acceptable price for not lying about the outcome, but it is
    // the reason this logs at error severity with the incident id rather than
    // swallowing quietly — a recurring pattern here is a real signal.
    try {
      await db.recursiveDelete(ref);
    } catch (error) {
      logger.error('incidents.remove: confirmation ledger cleanup failed; orphan left behind', {
        incidentId: parsed.input.incidentId,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  }

  return { removed };
});
