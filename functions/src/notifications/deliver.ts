/**
 * writeInAppNotification — the single backend writer for the in-app inbox
 * (Phase 9l). Not a callable: clients can never author notifications.
 *
 * Domain producers (event lifecycle changes, moderation actions,
 * subscription updates, ...) call this helper the same way the billboards
 * bridge calls writeInteractionEvent — wiring those producers up is each
 * domain's follow-up, this module owns eligibility and shape:
 *
 * - Deleted recipients receive nothing; suspended recipients receive only
 *   the essential account notices (legacy invariants).
 * - Per-category opt-outs in `userPrivate/{uid}.notificationPreferences`
 *   are honored, but the essential categories cannot be disabled —
 *   enforced here because the backend is the sole inbox writer.
 * - Content is truncated to the legacy plain-text limits.
 */

import { FieldValue, type Transaction, type Firestore } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { toUserAccessState } from '../shared/access';
import {
  buildNotificationDocument,
  decideInAppDelivery,
  type InAppNotificationInput,
} from './notifications-core';

export interface WriteNotificationResult {
  delivered: boolean;
  notificationId: string | null;
  /** Set when delivered=false: deleted | suspended | opted_out | duplicate. */
  skippedReason?: string;
}

/**
 * Writes one in-app notification for `recipientUid` if the recipient is
 * eligible. Pass `notificationId` for producers that need idempotent
 * delivery (deterministic IDs; an existing document is left untouched).
 * An optional transaction context serializes producer guards, preferences and
 * the inbox write with any producer receipt. It requires a deterministic ID.
 */
export async function writeInAppNotification(
  recipientUid: string,
  input: InAppNotificationInput,
  notificationId?: string,
  context?: { transaction: Transaction; firestore: Firestore },
): Promise<WriteNotificationResult> {
  // Producers with canonical state guards can share ONE serializable commit.
  // Read eligibility inside that same transaction; never trust a prefetched decision.
  const transaction = context?.transaction;
  const store = context?.firestore ?? db;
  const userRef = store.collection('users').doc(recipientUid);
  const privateRef = store.collection('userPrivate').doc(recipientUid);
  const [userSnap, privateSnap] = transaction
    ? await transaction.getAll(userRef, privateRef)
    : await Promise.all([userRef.get(), privateRef.get()]);
  if (!userSnap?.exists) {
    return { delivered: false, notificationId: null, skippedReason: 'deleted' };
  }

  const decision = decideInAppDelivery(
    input.category,
    toUserAccessState(userSnap.data()),
    privateSnap?.data()?.notificationPreferences,
  );
  if (!decision.deliver) {
    return { delivered: false, notificationId: null, skippedReason: decision.reason };
  }

  const items = store.collection('notifications').doc(recipientUid).collection('items');
  const document = buildNotificationDocument(input, () => FieldValue.serverTimestamp());

  if (notificationId !== undefined) {
    // Idempotent create-if-absent (same pattern as the Kronjakt claim
    // records): a replayed producer never duplicates or overwrites.
    const ref = items.doc(notificationId);
    const create = async (tx: Transaction) => {
      const existing = await tx.get(ref);
      if (existing.exists) {
        return false;
      }
      tx.set(ref, document);
      return true;
    };
    const created = transaction ? await create(transaction) : await store.runTransaction(create);
    return {
      delivered: created,
      notificationId: ref.id,
      ...(created ? {} : { skippedReason: 'duplicate' }),
    };
  }

  if (transaction) throw new Error('Transactional notifications require a deterministic ID.');
  const ref = await items.add(document);
  return { delivered: true, notificationId: ref.id };
}
