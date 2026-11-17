import { HttpsError } from 'firebase-functions/v2/https';
import { onCall } from '../access/callable';
import { FieldValue, type Firestore } from 'firebase-admin/firestore';
import { z } from 'zod';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { writeInAppNotification } from '../notifications/deliver';
import type { InAppNotificationInput } from '../notifications/notifications-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

/** Serializes with the persistent writer, including when the overlay arrives first. */
export async function acknowledgeDisplayedWave(store: Firestore, uid: string, waveId: string) {
  const waveRef = store.doc(`liveWaves/${uid}/waves/${waveId}`);
  await store.runTransaction(async (tx) => {
    const wave = await tx.get(waveRef);
    if (!wave.exists) return;
    const id = wave.get('notificationId');
    const itemRef =
      typeof id === 'string' && id && !id.includes('/')
        ? store.doc(`notifications/${uid}/items/${id}`)
        : null;
    const item = itemRef ? await tx.get(itemRef) : null;
    tx.update(waveRef, { seen: true });
    if (item?.exists && item.get('category') === 'wave' && item.get('read') !== true) {
      tx.update(item.ref, { read: true, readAt: FieldValue.serverTimestamp() });
    }
  });
}

export const acknowledgeWave = onCall(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_MEMBER,
    memory: '256MiB',
    timeoutSeconds: 30,
    enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
  },
  async (request) => {
    const actor = await requireActiveActor(request);
    const parsed = z
      .object({
        waveId: z
          .string()
          .min(1)
          .max(160)
          .regex(/^[a-zA-Z0-9_-]+$/),
      })
      .strict()
      .safeParse(request.data);
    if (!parsed.success) throw new HttpsError('invalid-argument', 'Expected { waveId }.');
    await acknowledgeDisplayedWave(db, actor.uid, parsed.data.waveId);
    return { acknowledged: true };
  },
);

/** The inbox write and the overlay receipt share the wave document as a fence. */
export async function writeWaveNotice(
  store: Firestore,
  recipientUid: string,
  waveId: string,
  input: InAppNotificationInput,
  notificationId: string,
) {
  return store.runTransaction(async (tx) => {
    const wave = await tx.get(store.doc(`liveWaves/${recipientUid}/waves/${waveId}`));
    return writeInAppNotification(
      recipientUid,
      { ...input, read: wave.get('seen') === true },
      notificationId,
      { transaction: tx, firestore: store },
    );
  });
}
