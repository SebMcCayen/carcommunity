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
 *  - NO per-message notification producer, deliberately. The 'community_chat'
 *    category exists and users can opt out of it, but post() writes no
 *    notification — see below.
 *
 * Why no community_chat producer (deliberate omission, not a gap):
 *
 * The other three social surfaces have a natural, bounded audience — a DM has
 * one recipient, a friend request has one invitee, a convoy chat has at most
 * ~50 accepted members. The community channel is the app-wide town square: its
 * audience is EVERY active member. A per-message producer would therefore mean
 * one inbox write per member per message — an O(members × messages) fan-out
 * whose cost and noise both grow with the app's success, and whose only outcome
 * is that every member's inbox is buried by whatever was last said in a global
 * room they can already see. The first busy day would train users to disable
 * notifications wholesale, which also costs us the notices that DO matter
 * (account warnings can't be disabled, but everything else can).
 *
 * The category is kept because the sane designs still need it, and each is its
 * own scoped piece of work rather than a line in post():
 *  - @mentions only — notify the handful of members a message explicitly names.
 *    Bounded by the mention count, and the notification is by definition
 *    relevant. Needs mention parsing + a handle→uid resolution the chat domain
 *    doesn't have yet, and must respect blocking so a mention can't be used to
 *    reach someone who blocked you.
 *  - A periodic digest — a scheduled function (as notifications/scheduled.ts
 *    already does for cleanup) that rolls unread community activity into ONE
 *    notice per member per period, sent only to members with activity since
 *    their communityChatLastReadAt marker. Cost is O(members) per PERIOD, not
 *    per message, and it collapses a busy day into a single item.
 * Until one of those ships, the existing per-user last-read marker + the
 * client's unread dot are the community channel's notification surface, and
 * they cost nothing per message.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  CHAT_MESSAGES_PAGE_SIZE,
  COMMUNITY_CHANNEL_ID,
  COMMUNITY_CHAT_RETENTION_DAYS,
  EMPTY_MESSAGE_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  buildChatMessageDocument,
  chatMessageExpiry,
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

  // TTL: community messages are retained COMMUNITY_CHAT_RETENTION_DAYS days. A
  // Firestore TTL policy on `expireAt` auto-deletes them after that (one-time
  // setup: `gcloud firestore fields ttls update expireAt \
  //   --collection-group=messages`; the policy is field-scoped, so it also
  // covers convoy messages, which share the `messages` collection group).
  const expireAt = Timestamp.fromDate(
    chatMessageExpiry(new Date(), COMMUNITY_CHAT_RETENTION_DAYS),
  );

  const messageRef = communityMessagesRef().doc();
  await messageRef.set(
    buildChatMessageDocument(
      { senderUid: actor.uid, text, senderProfile, expireAt },
      () => FieldValue.serverTimestamp(),
    ),
  );

  // NOTE: there is deliberately NO 'community_chat' notification producer here,
  // even though the category exists and the other three social surfaces (DM,
  // friend request, convoy chat) now have one. Fanning out to every active
  // member on every message is a notification-spam and cost disaster; the
  // module header records the reasoning and the two designs (@mentions, or a
  // periodic digest) that would be acceptable instead.
  //
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
