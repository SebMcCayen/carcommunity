/**
 * Global purchase-token ownership.
 *
 * A Play purchase token may be verified repeatedly by its original Firebase
 * UID (restore/reinstall/retry), but never claimed by another UID. The document
 * id is only the SHA-256 token hash; raw tokens never reach Firestore.
 */

import { FieldValue } from 'firebase-admin/firestore';
import { db } from '../firebase';
import {
  type PurchaseTokenOwnershipRecord,
  validateTokenOwnership,
} from './purchase-token-ownership-core';

const COLLECTION = 'subscriptionPurchaseTokens';

export async function claimPurchaseTokenHash(
  purchaseTokenHash: string,
  expected: PurchaseTokenOwnershipRecord,
): Promise<void> {
  const ref = db.collection(COLLECTION).doc(purchaseTokenHash);
  await db.runTransaction(async (transaction) => {
    const snap = await transaction.get(ref);
    const decision = validateTokenOwnership(snap.exists ? snap.data() : null, expected);
    if (decision === 'create') {
      transaction.create(ref, {
        tokenHash: purchaseTokenHash,
        uid: expected.uid,
        productId: expected.productId,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
    } else {
      transaction.update(ref, { updatedAt: FieldValue.serverTimestamp() });
    }
  });
}
