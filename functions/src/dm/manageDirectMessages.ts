/**
 * dm.sendMessage / dm.listConversations / dm.getMessages / dm.markRead —
 * member-gated 1:1 direct-messaging callables (contracts/functions/functions.json).
 *
 * Deployed via the `dm` export group as `dm-sendMessage`,
 * `dm-listConversations`, `dm-getMessages`, `dm-markRead`. Stacked on the
 * friend-graph backend (functions/src/friends): a DM is only allowed between
 * ESTABLISHED friends (users/{uid}/friends/{friendUid}). Blocking is honoured
 * both ways.
 *
 * Invariants:
 *  - Backend is the sole writer of conversations + messages
 *    (firebase/firestore.rules grants member reads, denies all client writes).
 *  - A conversation is a single canonical document per unordered pair
 *    (conversations/{pairId}, pairId = sorted UIDs joined by `__`), so both
 *    friends resolve the same thread.
 *  - Per-member unread counters on the conversation are kept in lock-step with
 *    a per-user aggregate at userPrivate/{uid}.dmUnreadTotal (owner-only read)
 *    so the map-home chat bubble binds ONE document listener for its badge:
 *    sendMessage bumps both by 1 for the recipient; markRead clears the
 *    conversation counter and decrements the aggregate by exactly the cleared
 *    amount (never underflows).
 *  - sendMessage writes a best-effort IN-APP notification for the recipient
 *    ('direct_message' category, honored per-recipient by
 *    writeInAppNotification) on the FIRST message of an unread run only — the
 *    recipient's pre-existing unread count (already read by the send
 *    transaction) says whether they've been notified and haven't looked yet, so
 *    an active back-and-forth doesn't restack the same notice per message.
 *  - FCM push on a new message is intentionally NOT wired here: the migration
 *    schedules FCM delivery (sendPushNotification) for the end-of-MVP Firebase
 *    console setup (see notifications/pushTokens.ts). The DM conversation list
 *    + unread aggregate already drive the in-app badge. See the flag at the
 *    bottom of sendMessage.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import {
  CONVERSATION_NOT_FOUND_MESSAGE,
  DM_CONVERSATIONS_LIMIT,
  DM_MESSAGES_PAGE_SIZE,
  EMPTY_MESSAGE_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  NOT_FRIENDS_MESSAGE,
  SELF_MESSAGE_MESSAGE,
  buildMessageDocument,
  buildNewConversationDocument,
  dmPairId,
  isConversationMember,
  messagePreview,
  parseGetMessagesInput,
  parseListConversationsInput,
  parseMarkReadInput,
  parseSendMessageInput,
  toConversationSummary,
  toMessageSummary,
  toProfileProjection,
  type ConversationSummary,
  type MessageSummary,
  type ProfileProjection,
} from './dm-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

// ---------------------------------------------------------------------------
// Firestore references
// ---------------------------------------------------------------------------

function conversationRef(pairId: string) {
  return db.collection('conversations').doc(pairId);
}

function friendshipRef(ownerUid: string, friendUid: string) {
  return db.collection('users').doc(ownerUid).collection('friends').doc(friendUid);
}

function blockRef(blockerUid: string, blockedUid: string) {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
}

/** Per-user total-unread aggregate — owner-only readable (userPrivate). */
function unreadAggregateRef(uid: string) {
  return db.collection('userPrivate').doc(uid);
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Converts a stored Firestore value to an ISO string, or null. */
function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/** True when either party has blocked the other (block honoured both ways). */
async function isBlockedEitherWay(a: string, b: string): Promise<boolean> {
  const [aBlockedB, bBlockedA] = await Promise.all([
    blockRef(a, b).get(),
    blockRef(b, a).get(),
  ]);
  return aBlockedB.exists || bBlockedA.exists;
}

/**
 * Reads a users/{uid} profile projection. Returns null when the user is
 * missing or soft-deleted (mirrors friends/manageFriends.ts loadProfile).
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || toUserAccessState(snap.data()).deleted) {
    return null;
  }
  return toProfileProjection(snap.data());
}

// ---------------------------------------------------------------------------
// dm.sendMessage
// ---------------------------------------------------------------------------

export interface SendMessageResponse {
  conversationId: string;
  messageId: string;
}

export const sendMessage = onCall(CALLABLE_OPTS, async (request): Promise<SendMessageResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseSendMessageInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { toUid, text } = parsed.input;

  if (!text.trim()) {
    throw new HttpsError('invalid-argument', EMPTY_MESSAGE_MESSAGE);
  }
  if (toUid === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_MESSAGE_MESSAGE);
  }

  // Established friendship is the gate (not request status) — mirrors the
  // friend-graph source of truth.
  const friendshipSnap = await friendshipRef(actor.uid, toUid).get();
  if (!friendshipSnap.exists) {
    throw new HttpsError('failed-precondition', NOT_FRIENDS_MESSAGE);
  }

  // Neutral — never reveals which side blocked (privacy parity with friends).
  if (await isBlockedEitherWay(actor.uid, toUid)) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  // Denormalized profiles for a first message (read before the transaction —
  // profiles change rarely and the conversation refreshes the sender's own
  // entry on every send below).
  const [senderProfile, recipientProfile] = await Promise.all([
    loadProfile(actor.uid),
    loadProfile(toUid),
  ]);
  if (!senderProfile || !recipientProfile) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  const pairId = dmPairId(actor.uid, toUid);
  const convRef = conversationRef(pairId);
  const messageRef = convRef.collection('messages').doc();
  const recipientAggRef = unreadAggregateRef(toUid);

  // Returns the recipient's unread count for this conversation BEFORE this
  // message, read straight off the transaction's existing conversation get (no
  // extra I/O). Drives the notify-once-per-unread-run rule below. Returned from
  // the transaction rather than captured in a closure variable so a Firestore
  // retry can't leave a stale value behind.
  const priorUnread = await db.runTransaction<number>(async (tx) => {
    const convSnap = await tx.get(convRef);
    const ts = FieldValue.serverTimestamp();
    const unreadMap = (convSnap.data()?.unread ?? {}) as Record<string, unknown>;
    const rawUnread = unreadMap[toUid];
    const unreadBefore = typeof rawUnread === 'number' && rawUnread > 0 ? rawUnread : 0;

    if (!convSnap.exists) {
      tx.set(
        convRef,
        buildNewConversationDocument(
          { senderUid: actor.uid, recipientUid: toUid, senderProfile, recipientProfile, text },
          () => ts,
        ),
      );
    } else {
      // Update lastMessage/ordering, bump the recipient's unread, refresh the
      // sender's own denormalized profile (keeps the inbox card current).
      tx.set(
        convRef,
        {
          lastMessage: { text: messagePreview(text), senderUid: actor.uid, createdAt: ts },
          lastMessageAt: ts,
          updatedAt: ts,
          unread: { [toUid]: FieldValue.increment(1) },
          memberProfiles: {
            [actor.uid]: {
              displayName: senderProfile.displayName,
              avatarPath: senderProfile.avatarPath,
            },
          },
        },
        { merge: true },
      );
    }

    tx.set(messageRef, buildMessageDocument({ senderUid: actor.uid, text }, () => ts));

    // Keep the per-user aggregate in lock-step (owner-only readable badge source).
    tx.set(recipientAggRef, { dmUnreadTotal: FieldValue.increment(1) }, { merge: true });

    return unreadBefore;
  });

  // Best-effort in-app notification for the recipient (never fails the send).
  //
  // Notify only on the FIRST message of an unread run: if the recipient already
  // has unread messages in this conversation they were notified when the run
  // started and haven't read it since, so every further message in an active
  // back-and-forth would just restack the same "you have a message from X"
  // notice. dm.markRead zeroes the counter, so the next message after they read
  // notifies again. The signal is the unread count the transaction already read
  // — no extra read, no new state to store.
  //
  // Blocking needs no check here: a blocked pair can't reach this point (the
  // both-ways block gate above rejects the send outright). The 'direct_message'
  // preference is honored per-recipient inside writeInAppNotification.
  if (priorUnread === 0) {
    const senderName = senderProfile.displayName ?? 'En vän';
    await writeInAppNotification(toUid, {
      category: 'direct_message',
      title: `Nytt meddelande från ${senderName}`,
      previewText: messagePreview(text),
      actionType: 'open_notifications',
      // The conversation id — lets the client deep-link straight to the thread.
      relatedEntityId: pairId,
    }).catch(() => undefined);
  }

  // NOTE: push is NOT sent from here. The in-app notification written above is
  // picked up by the notifications-onNotificationCreated trigger
  // (notifications/sendPush.ts), which pushes it to the recipient's devices —
  // so the direct_message opt-out honoured by writeInAppNotification governs
  // push automatically. The unread aggregate + conversation list continue to
  // drive the in-app chat-bubble badge.

  return { conversationId: pairId, messageId: messageRef.id };
});

// ---------------------------------------------------------------------------
// dm.listConversations
// ---------------------------------------------------------------------------

export interface ListConversationsResponse {
  conversations: ConversationSummary[];
  /** Convenience mirror of userPrivate/{uid}.dmUnreadTotal (sum of unreadCount). */
  totalUnread: number;
}

export const listConversations = onCall(
  CALLABLE_OPTS,
  async (request): Promise<ListConversationsResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseListConversationsInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    const snap = await db
      .collection('conversations')
      .where('members', 'array-contains', actor.uid)
      .orderBy('lastMessageAt', 'desc')
      .limit(DM_CONVERSATIONS_LIMIT)
      .get();

    const conversations = snap.docs.map((doc) =>
      toConversationSummary(doc.id, doc.data(), actor.uid, toIso),
    );
    const totalUnread = conversations.reduce((sum, c) => sum + c.unreadCount, 0);

    return { conversations, totalUnread };
  },
);

// ---------------------------------------------------------------------------
// dm.getMessages
// ---------------------------------------------------------------------------

export interface GetMessagesResponse {
  conversationId: string;
  messages: MessageSummary[];
  /** ISO cursor to pass as `before` for the next (older) page, or null. */
  nextBefore: string | null;
  hasMore: boolean;
}

export const getMessages = onCall(CALLABLE_OPTS, async (request): Promise<GetMessagesResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseGetMessagesInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { conversationId, before } = parsed.input;

  const convRef = conversationRef(conversationId);
  const convSnap = await convRef.get();
  // Not-found (never permission-denied) so a conversation's existence can't be
  // probed by a non-member.
  if (!convSnap.exists || !isConversationMember(convSnap.data(), actor.uid)) {
    throw new HttpsError('not-found', CONVERSATION_NOT_FOUND_MESSAGE);
  }

  let query = convRef
    .collection('messages')
    .orderBy('createdAt', 'desc')
    .limit(DM_MESSAGES_PAGE_SIZE + 1);
  if (before !== undefined) {
    query = query.where('createdAt', '<', Timestamp.fromDate(new Date(before)));
  }

  const messagesSnap = await query.get();
  const docs = messagesSnap.docs;
  const hasMore = docs.length > DM_MESSAGES_PAGE_SIZE;
  const page = hasMore ? docs.slice(0, DM_MESSAGES_PAGE_SIZE) : docs;

  const messages = page.map((doc) => {
    const createdAtIso = toIso(doc.data().createdAt) ?? new Date(0).toISOString();
    return toMessageSummary(doc.id, doc.data(), createdAtIso);
  });

  const nextBefore = hasMore && messages.length > 0 ? messages[messages.length - 1]!.createdAt : null;

  return { conversationId, messages, nextBefore, hasMore };
});

// ---------------------------------------------------------------------------
// dm.markRead
// ---------------------------------------------------------------------------

export interface MarkReadResponse {
  conversationId: string;
  /** How many unread messages were cleared (0 when already read). */
  cleared: number;
}

export const markRead = onCall(CALLABLE_OPTS, async (request): Promise<MarkReadResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseMarkReadInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { conversationId } = parsed.input;

  const convRef = conversationRef(conversationId);
  const aggRef = unreadAggregateRef(actor.uid);

  const cleared = await db.runTransaction<number>(async (tx) => {
    const convSnap = await tx.get(convRef);
    if (!convSnap.exists || !isConversationMember(convSnap.data(), actor.uid)) {
      throw new HttpsError('not-found', CONVERSATION_NOT_FOUND_MESSAGE);
    }

    const unreadMap = (convSnap.data()?.unread ?? {}) as Record<string, unknown>;
    const raw = unreadMap[actor.uid];
    const current = typeof raw === 'number' && raw > 0 ? raw : 0;

    const ts = FieldValue.serverTimestamp();
    tx.set(
      convRef,
      { unread: { [actor.uid]: 0 }, lastReadAt: { [actor.uid]: ts }, updatedAt: ts },
      { merge: true },
    );

    if (current > 0) {
      // Decrement the aggregate by EXACTLY what we cleared — stays consistent
      // with the per-conversation counters and never underflows.
      tx.set(aggRef, { dmUnreadTotal: FieldValue.increment(-current) }, { merge: true });
    }

    return current;
  });

  return { conversationId, cleared };
});
