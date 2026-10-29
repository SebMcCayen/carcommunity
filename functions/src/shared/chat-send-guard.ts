import type { Firestore, Transaction } from 'firebase-admin/firestore';
import { HttpsError } from 'firebase-functions/v2/https';
import {
  CHAT_GUARD_COLLECTION,
  evaluateChatSend,
  type ChatGuardDecision,
} from './chat-abuse-policy.js';

/** Call after authorization, receipt and all other reads, before message writes.
 * The clock is sampled AFTER the guard read on each transaction attempt: an
 * earlier request may read state committed by a later request while awaiting I/O.
 * forceViolation is server-derived from a stricter legacy send quota.
 * Return denials from the transaction; throwing here would roll back penalties.
 */
export async function stageChatSendGuard(
  db: Firestore,
  tx: Transaction,
  uid: string,
  clock: () => number = Date.now,
  forceViolation: boolean = false,
): Promise<ChatGuardDecision> {
  const ref = db.collection(CHAT_GUARD_COLLECTION).doc(uid);
  const snapshot = await tx.get(ref);
  const now = clock();
  const decision = evaluateChatSend(snapshot.data(), now, forceViolation);
  if (decision.next) tx.set(ref, decision.next);
  return decision;
}

/** Invoke ONLY after runTransaction resolves (including its commit). */
export function throwChatRateLimited(
  decision: Extract<ChatGuardDecision, { allowed: false }>,
): never {
  throw new HttpsError(
    'resource-exhausted',
    'Too many messages — wait before sending again.',
    decision.details,
  );
}
