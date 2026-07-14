/**
 * communityChat.post / communityChat.list / communityChat.markRead —
 * member-gated COMMUNITY (app-wide) chat callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `communityChat` export group (functions/src/index.ts) as
 * `communityChat-post`, `communityChat-list`, `communityChat-markRead`. One of
 * the THREE product chats (community / convoy / friends-DMs); the friends chat
 * is the existing dm.* domain and is NOT rebuilt here.
 *
 * Invariants:
 *  - Backend is the sole writer of communityChat/global/messages
 *    (firebase/firestore.rules grants any active-member read, denies client
 *    writes).
 *  - Every message denormalizes the sender's safe profile
 *    (senderDisplayName/senderAvatarPath) so the channel renders with no
 *    per-message profile lookup.
 *  - No fan-out unread aggregate: the community channel uses a lightweight
 *    per-user last-read marker at userPrivate/{uid}.communityChatLastReadAt
 *    (owner-only readable, alongside dmUnreadTotal). markRead stamps it; list
 *    returns it; the client's newest-message live listener derives the unread
 *    dot (createdAt > lastReadAt). See chat-core.ts.
 *  - Blocking is NOT filtered server-side (global town square) — documented in
 *    chat-core.ts. FCM push is intentionally not wired (end-of-MVP, as DM).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  CHAT_MESSAGES_PAGE_SIZE,
  COMMUNITY_CHANNEL_ID,
  EMPTY_MESSAGE_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  buildChatMessageDocument,
  parseListCommunityInput,
  parseMarkReadCommunityInput,
  parsePostCommunityInput,
  toChatMessageSummary,
  toProfileProjection,
  type ChatMessageSummary,
  type ProfileProjection,
} from './chat-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

// ---------------------------------------------------------------------------
// Firestore references
// ---------------------------------------------------------------------------

/** The single global community channel's messages subcollection. */
function communityMessagesRef() {
  return db.collection('communityChat').doc(COMMUNITY_CHANNEL_ID).collection('messages');
}

/** Per-user last-read marker — owner-only readable (userPrivate). */
function userPrivateRef(uid: string) {
  return db.collection('userPrivate').doc(uid);
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Converts a stored Firestore value to an ISO string, or null. */
function toIso(value: unknown): string | null {
  return value instanceof Timestamp ? value.toDate().toISOString() : null;
}

/**
 * Reads a users/{uid} profile projection. Returns null when the user is missing
 * or soft-deleted (mirrors dm/manageDirectMessages loadProfile).
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || toUserAccessState(snap.data()).deleted) {
    return null;
  }
  return toProfileProjection(snap.data());
}

// ---------------------------------------------------------------------------
// communityChat.post
// ---------------------------------------------------------------------------

export interface PostCommunityResponse {
  messageId: string;
}

export const post = onCall(CALLABLE_OPTS, async (request): Promise<PostCommunityResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parsePostCommunityInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { text } = parsed.input;
  if (!text.trim()) {
    throw new HttpsError('invalid-argument', EMPTY_MESSAGE_MESSAGE);
  }

  const senderProfile = await loadProfile(actor.uid);
  if (!senderProfile) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  const messageRef = communityMessagesRef().doc();
  await messageRef.set(
    buildChatMessageDocument(
      { senderUid: actor.uid, text, senderProfile },
      () => FieldValue.serverTimestamp(),
    ),
  );

  // NOTE: FCM push is intentionally deferred (end-of-MVP Firebase console setup),
  // consistent with the DM + notifications domains.

  return { messageId: messageRef.id };
});

// ---------------------------------------------------------------------------
// communityChat.list
// ---------------------------------------------------------------------------

export interface ListCommunityResponse {
  messages: ChatMessageSummary[];
  /** ISO cursor to pass as `before` for the next (older) page, or null. */
  nextBefore: string | null;
  hasMore: boolean;
  /** The caller's own last-read marker (userPrivate) — drives the unread dot. */
  lastReadAt: string | null;
}

export const list = onCall(CALLABLE_OPTS, async (request): Promise<ListCommunityResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseListCommunityInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { before } = parsed.input;

  let query = communityMessagesRef()
    .orderBy('createdAt', 'desc')
    .limit(CHAT_MESSAGES_PAGE_SIZE + 1);
  if (before !== undefined) {
    query = query.where('createdAt', '<', Timestamp.fromDate(new Date(before)));
  }

  const [messagesSnap, privateSnap] = await Promise.all([
    query.get(),
    userPrivateRef(actor.uid).get(),
  ]);

  const docs = messagesSnap.docs;
  const hasMore = docs.length > CHAT_MESSAGES_PAGE_SIZE;
  const page = hasMore ? docs.slice(0, CHAT_MESSAGES_PAGE_SIZE) : docs;

  const messages = page.map((doc) => {
    const createdAtIso = toIso(doc.data().createdAt) ?? new Date(0).toISOString();
    return toChatMessageSummary(doc.id, doc.data(), createdAtIso);
  });

  const nextBefore =
    hasMore && messages.length > 0 ? messages[messages.length - 1]!.createdAt : null;
  const lastReadAt = toIso(privateSnap.data()?.communityChatLastReadAt);

  return { messages, nextBefore, hasMore, lastReadAt };
});

// ---------------------------------------------------------------------------
// communityChat.markRead
// ---------------------------------------------------------------------------

export interface MarkReadCommunityResponse {
  lastReadAt: string;
}

export const markRead = onCall(
  CALLABLE_OPTS,
  async (request): Promise<MarkReadCommunityResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseMarkReadCommunityInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }

    await userPrivateRef(actor.uid).set(
      { communityChatLastReadAt: FieldValue.serverTimestamp() },
      { merge: true },
    );

    // Read back the stamped value so the client can update its unread baseline
    // without a second round-trip.
    const fresh = await userPrivateRef(actor.uid).get();
    return { lastReadAt: toIso(fresh.data()?.communityChatLastReadAt) ?? new Date().toISOString() };
  },
);
