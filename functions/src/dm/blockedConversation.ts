/**
 * DM conversation redaction for a blocked pair — the part of block invisibility
 * that Firestore rules CANNOT do.
 *
 * ## Why this exists
 *
 * The Android inbox reads `conversations` with a LIST query
 * (`members array-contains uid`, ordered by lastMessageAt). Firestore security
 * rules are not filters: a per-document condition on a list query makes the
 * WHOLE query fail, not the offending row disappear. So a "hide blocked
 * conversations" rule on `conversations/{pairId}` would break every inbox that
 * contains one blocked thread — it cannot be used.
 *
 * The thread's MESSAGES are a different story (all messages in one conversation
 * share the same pair, so the condition is constant across the query) and ARE
 * rules-enforced — see firestore.rules `conversations/{pairId}/messages`.
 *
 * That leaves exactly one thing still delivered to a blocked party's device:
 * the conversation document itself, whose `lastMessage` carries a preview of
 * the counterparty's last message. This module removes that content at the
 * source when the pair becomes blocked, so nothing readable remains, and puts
 * it back when the last block edge between them goes away. The inbox ROW is
 * additionally dropped client-side (the doc is still delivered — it must be,
 * or the query breaks), but by then it carries no message content.
 *
 * ## Behaviour chosen for a blocked DM: the thread is HIDDEN and INERT
 *
 * Not "their messages are removed from the thread". A 1:1 thread stripped of
 * one side reads as a monologue and still shows that a conversation happened;
 * and sending was already refused for a blocked pair (dm.sendMessage), so a
 * half-thread would be a dead end by construction. Blocking therefore takes the
 * whole conversation out of view for BOTH parties, and restores it whole on
 * unblock. Unread is cleared as part of that (a hidden thread must not keep the
 * chat badge lit), and is deliberately NOT restored on unblock — the messages
 * are old news by then.
 */

import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { dmPairId, messagePreview } from './dm-core';

/** Marker field: the preview was redacted because the pair is blocked. */
export const BLOCKED_PAIR_FIELD = 'blockedPair';

function conversationRef(pairId: string) {
  return db.collection('conversations').doc(pairId);
}

/**
 * Redacts (hidden=true) or restores (hidden=false) the pair's conversation
 * document. A pair with no conversation is a no-op.
 *
 * Idempotent: the `blockedPair` marker short-circuits a repeat in either
 * direction, so a trigger retry neither double-decrements the unread aggregate
 * nor re-derives a preview that is already current.
 */
export async function setConversationBlocked(
  uidA: string,
  uidB: string,
  hidden: boolean,
): Promise<void> {
  const convRef = conversationRef(dmPairId(uidA, uidB));

  if (hidden) {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(convRef);
      if (!snap.exists) return;
      const data = snap.data() ?? {};
      if (data[BLOCKED_PAIR_FIELD] === true) return; // already redacted

      // Clear both members' unread and unwind the owner-only aggregate by
      // EXACTLY what was cleared, so userPrivate/{uid}.dmUnreadTotal stays in
      // lock-step with the per-conversation counters (same contract as
      // dm.markRead) and never underflows.
      const unreadMap = (data.unread ?? {}) as Record<string, unknown>;
      const cleared: Array<[string, number]> = [];
      for (const uid of [uidA, uidB]) {
        const raw = unreadMap[uid];
        if (typeof raw === 'number' && raw > 0) cleared.push([uid, raw]);
      }

      tx.set(
        convRef,
        {
          [BLOCKED_PAIR_FIELD]: true,
          // The only counterparty CONTENT on this document. Null, not deleted,
          // so the field's presence stays stable for readers.
          lastMessage: null,
          unread: { [uidA]: 0, [uidB]: 0 },
        },
        { merge: true },
      );

      for (const [uid, amount] of cleared) {
        tx.set(
          db.collection('userPrivate').doc(uid),
          { dmUnreadTotal: FieldValue.increment(-amount) },
          { merge: true },
        );
      }
    });
    return;
  }

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(convRef);
    if (!snap.exists) return;
    if ((snap.data() ?? {})[BLOCKED_PAIR_FIELD] !== true) return; // nothing to restore

    // Re-derive the preview from the newest surviving message rather than
    // stashing a copy: the messages are the source of truth, and a stash could
    // outlive the message it previewed (chat messages have no TTL here, but a
    // moderation deletion would leave the stash stale).
    const newest = await tx.get(
      convRef.collection('messages').orderBy('createdAt', 'desc').limit(1),
    );
    const doc = newest.docs[0];
    const data = doc?.data();
    const senderUid = typeof data?.senderUid === 'string' ? data.senderUid : null;
    const createdAt = data?.createdAt instanceof Timestamp ? data.createdAt : null;
    const lastMessage =
      senderUid !== null
        ? {
            text: messagePreview(typeof data?.text === 'string' ? data.text : ''),
            senderUid,
            createdAt,
          }
        : null;

    // `lastMessageAt` is documented as a MIRROR of lastMessage.createdAt, and is
    // the inbox's ordering key (see the data model in dm-core.ts). Restoring the
    // preview without it would let the two drift whenever the message we
    // re-derive from is not the one that set lastMessageAt originally — e.g. the
    // newest message was erased by an account deletion while the block stood.
    //
    // Written ONLY when a real Timestamp came back. Leaving it untouched
    // otherwise is deliberate and load-bearing: the inbox query orders by
    // `lastMessageAt`, and a document MISSING that field drops out of the query
    // altogether — so clearing it would HIDE a restored conversation rather than
    // merely misorder it.
    const restored: Record<string, unknown> = { [BLOCKED_PAIR_FIELD]: false, lastMessage };
    if (createdAt !== null) {
      restored.lastMessageAt = createdAt;
    }

    tx.set(convRef, restored, { merge: true });
  });
}
