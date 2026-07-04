/**
 * notifications.markRead / notifications.markAllRead — authenticated
 * callables (contracts/functions/functions.json).
 *
 * The inbox is backend-write-only, so read-state changes go through these
 * callables instead of direct document writes. Ownership is structural:
 * the callables only ever touch `notifications/{caller}/items/...`, so a
 * notification ID belonging to another user is simply not-found (legacy
 * parity). Both are idempotent.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireActiveActor } from '../shared/memberActor';
import { parseMarkNotificationReadInput } from './notifications-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

const MARK_ALL_BATCH_SIZE = 500;

export interface MarkReadResponse {
  notificationId: string;
  /** False when the notification was already read (idempotent replay). */
  marked: boolean;
}

export const markRead = onCall(CALLABLE_OPTS, async (request): Promise<MarkReadResponse> => {
  const actor = await requireActiveActor(request);

  const parsed = parseMarkNotificationReadInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const ref = db
    .collection('notifications')
    .doc(actor.uid)
    .collection('items')
    .doc(parsed.input.notificationId);

  const marked = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new HttpsError('not-found', 'Notification not found.');
    }
    if (snap.data()!.read === true) {
      return false;
    }
    tx.update(ref, { read: true, readAt: FieldValue.serverTimestamp() });
    return true;
  });

  return { notificationId: parsed.input.notificationId, marked };
});

export interface MarkAllReadResponse {
  markedCount: number;
}

export const markAllRead = onCall(
  CALLABLE_OPTS,
  async (request): Promise<MarkAllReadResponse> => {
    const actor = await requireActiveActor(request);

    const items = db.collection('notifications').doc(actor.uid).collection('items');
    let markedCount = 0;
    for (;;) {
      const unread = await items
        .where('read', '==', false)
        .limit(MARK_ALL_BATCH_SIZE)
        .get();
      if (unread.empty) {
        break;
      }
      const batch = db.batch();
      for (const doc of unread.docs) {
        batch.update(doc.ref, { read: true, readAt: FieldValue.serverTimestamp() });
      }
      await batch.commit();
      markedCount += unread.size;
      if (unread.size < MARK_ALL_BATCH_SIZE) {
        break;
      }
    }

    return { markedCount };
  },
);
