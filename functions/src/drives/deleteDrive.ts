/**
 * drives.delete — callable (contracts/functions/functions.json).
 *
 * Deployed via the `drives` export group as `drives-delete`.
 *
 * Deletes an owned drive: removes the Cloud Storage route/preview files
 * under `rideRoutes/{uid}/{rideId}/` first, then the `rides/{rideId}`
 * document, so no orphaned route data can outlive the metadata. Available to
 * any active (non-suspended, non-deleted) owner — membership is NOT required
 * (legacy parity: free users can delete drives saved during a previous
 * membership). Idempotent: deleting an already-deleted drive returns
 * not-found, matching the legacy API.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { adminStorage, db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { parseDeleteDriveInput, rideStoragePrefix } from './drives-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

export interface DeleteDriveResponse {
  rideId: string;
  deleted: true;
}

export const deleteDrive = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 60,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request): Promise<DeleteDriveResponse> => {
    const actor = await requireActiveActor(request);

    const parsed = parseDeleteDriveInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { rideId } = parsed.input;

    const rideRef = db.collection('rides').doc(rideId);
    const snap = await rideRef.get();
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Saved drive not found.');
    }
    if (snap.data()?.userId !== actor.uid) {
      throw new HttpsError('permission-denied', 'You can only delete your own saved drives.');
    }

    // Storage first: if this partially fails the document survives and the
    // user can retry; the reverse order would orphan the route files.
    await adminStorage
      .bucket()
      .deleteFiles({ prefix: rideStoragePrefix(actor.uid, rideId) });
    await rideRef.delete();

    return { rideId, deleted: true };
  },
);
