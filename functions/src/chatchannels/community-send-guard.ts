import type { Firestore, Transaction } from 'firebase-admin/firestore';
import { stageChatSendGuard } from '../shared/chat-send-guard';
import { COMMUNITY_SEND_GUARD_COLLECTION, evaluateCommunitySend } from './community-send-policy';

/** All authorization, receipts and reply reads must precede this helper.
 * Both budgets and the message commit together. Active shared pauses write
 * neither guard. A Community overrun uses the existing account-wide escalation.
 */
export async function stageCommunitySendGuard(
  db: Firestore,
  tx: Transaction,
  uid: string,
  text: string,
  clock = Date.now,
) {
  const ref = db.collection(COMMUNITY_SEND_GUARD_COLLECTION).doc(uid);
  const snapshot = await tx.get(ref);
  const candidate = evaluateCommunitySend(snapshot.data(), text, clock());
  const guard = await stageChatSendGuard(db, tx, uid, clock, !candidate.allowed);
  if (guard.allowed || (!candidate.allowed && guard.next !== null)) tx.set(ref, candidate.next);
  return guard;
}
