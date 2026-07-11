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

  await db.runTransaction(async (tx) => {
    const convSnap = await tx.get(convRef);
    const ts = FieldValue.serverTimestamp();

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
  });

  // NOTE: FCM push to the recipient is intentionally deferred — actual FCM
  // delivery (sendPushNotification) ships with the end-of-MVP Firebase console
  // setup (notifications/pushTokens.ts). The unread aggregate + conversation
  // list already drive the in-app chat-bubble badge.

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
