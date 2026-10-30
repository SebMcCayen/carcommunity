import type { Transaction } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import { db } from '../firebase';
import { isRestricted, toUserAccessState } from './access';

/** Serialize sends and receipt replay with canonical account revocation. */
export async function requireChatSender(tx: Transaction, uid: string): Promise<void> {
  const user = await tx.get(db.collection('users').doc(uid));
  if (!user.exists || isRestricted(toUserAccessState(user.data())))
    throw new HttpsError('permission-denied', 'Account access is restricted.');
}
