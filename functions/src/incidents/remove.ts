/**
 * incident.remove — the reporter (or an admin) clears an incident they no
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
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { parseRemoveInput } from './incidents-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
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
    // it reappear on the next deterministic upsert, so reject it for EVERYONE,
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

  return { removed };
});
