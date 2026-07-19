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
 *  - The ONLY 'community_chat' notification producer is @MENTIONS: a message
 *    notifies the (at most MAX_MESSAGE_MENTIONS) members it explicitly names,
 *    and nobody else. A message with no mentions still writes no notification at
 *    all. Mentions are resolved by the CLIENT and validated here — see below.
 *
 * Why mentions instead of a per-message producer:
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
 * @mentions invert that: instead of asking "who is in the room" (everyone), they
 * ask "who did this message actually name" (at most MAX_MESSAGE_MENTIONS). The
 * cost of a post stays O(1) in the member count no matter how big the app gets,
 * and every notice written is relevant by construction — someone typed that
 * member's name on purpose.
 *
 * How mentions are resolved — CLIENT-SIDE, and that is the load-bearing choice:
 *
 * post() takes an explicit `mentionedUids` array; it does NOT parse "@Seb" out
 * of the text. `displayName` is not unique in this app (the friends nickname
 * lookup already had to grow an AMBIGUOUS_NICKNAME path for exactly that), so a
 * server-side name→uid guess would sooner or later notify the wrong Seb — a
 * stranger receiving a stranger's conversation. The client's @-picker resolves a
 * tapped profile to a uid, which is unambiguous; the server's job is to VALIDATE
 * that array, never to guess. Validation, in order: bounded count (schema, a
 * hard reject over the cap) → dedup + drop self → drop uids that aren't
 * deliverable active members → drop blocked pairs. Everything after the cap is a
 * DROP rather than a reject: those are races (a mentioned member deletes their
 * account or blocks the sender between picking and posting), and a race must not
 * fail someone's post. The surviving set is stored on the message AND notified,
 * so the highlight the client renders and the notice that was sent never diverge.
 *
 * Still NOT built (unchanged by this): a periodic digest — a scheduled function
 * (as notifications/scheduled.ts already does for cleanup) rolling unread
 * community activity into ONE notice per member per period, for members with
 * activity since their communityChatLastReadAt marker. Cost is O(members) per
 * PERIOD, not per message. Mentions cover "someone is talking TO me"; a digest
 * would cover "the channel has been busy" — different jobs, and the last-read
 * marker + the client's unread dot still serve the second one for free.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import { memberGateAllows } from '../shared/memberGating';
import { writeInAppNotification } from '../notifications/deliver';
import {
  CHAT_MESSAGES_PAGE_SIZE,
  COMMUNITY_CHANNEL_ID,
  COMMUNITY_CHAT_RETENTION_DAYS,
  EMPTY_MESSAGE_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  buildChatMessageDocument,
  chatMessageExpiry,
  communityMentionNotificationId,
  messagePreview,
  normalizeMentionCandidates,
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

/** A directed block edge (mirrors dm/manageDirectMessages blockRef). */
function blockRef(blockerUid: string, blockedUid: string) {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
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

/**
 * Narrows client-supplied mention candidates to the ones actually worth a
 * notice: a real, non-suspended, non-deleted account that passes the member
 * gate, whom the sender has not blocked and who has not blocked the sender.
 *
 * Member gating is currently DISABLED (shared/memberGating.ts), so non-member
 * accounts are mentionable today; re-enabling it drops them again.
 *
 * Everything here is a DROP, never a throw. A uid that fails is either a race
 * (the member deleted their account, lost their subscription, or blocked the
 * sender after the picker resolved them) or a client that sent something it
 * shouldn't have — and neither is a reason to reject a message a human wrote.
 * The message posts; the bad mention just isn't one.
 *
 * Cost is bounded and flat: `candidates` is capped at MAX_MESSAGE_MENTIONS by
 * the schema, and both lookups are single batched getAll calls (<=10 profile
 * reads + <=20 block reads per post) rather than a per-uid round trip.
 *
 * The block check is BOTH ways, matching the DM domain. The community channel
 * deliberately doesn't block-filter its READ surface (chat-core.ts), and this
 * doesn't change that: a blocked member still sees the message in the channel
 * like everyone else. What they don't get is a mention notice pushed into their
 * inbox — because that is directed reach, which is precisely what a block is
 * meant to stop.
 */
async function resolveMentions(candidates: string[], senderUid: string): Promise<string[]> {
  if (candidates.length === 0) {
    return [];
  }

  const [profileSnaps, blockSnaps] = await Promise.all([
    db.getAll(...candidates.map((uid) => db.collection('users').doc(uid))),
    db.getAll(
      ...candidates.flatMap((uid) => [blockRef(senderUid, uid), blockRef(uid, senderUid)]),
    ),
  ]);

  return candidates.filter((_uid, index) => {
    const profile = profileSnaps[index]!;
    if (!profile.exists || !memberGateAllows(toUserAccessState(profile.data()))) {
      return false;
    }
    // Two block docs per candidate, in the order they were requested above.
    const senderBlocked = blockSnaps[index * 2]!.exists;
    const blockedSender = blockSnaps[index * 2 + 1]!.exists;
    return !senderBlocked && !blockedSender;
  });
}

// ---------------------------------------------------------------------------
// communityChat.post
// ---------------------------------------------------------------------------

export interface PostCommunityResponse {
  messageId: string;
  /**
   * The mentions that SURVIVED validation — the set stored on the message and
   * notified. Returned so the client's composer can reconcile its optimistic
   * render with what the server actually accepted (a mention silently dropped as
   * blocked or no-longer-a-member shouldn't keep rendering as a live highlight).
   */
  mentionedUids: string[];
}

export const post = onCall(CALLABLE_OPTS, async (request): Promise<PostCommunityResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parsePostCommunityInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { text, mentionedUids } = parsed.input;
  if (!text.trim()) {
    throw new HttpsError('invalid-argument', EMPTY_MESSAGE_MESSAGE);
  }

  const senderProfile = await loadProfile(actor.uid);
  if (!senderProfile) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  // Dedup + drop self first (free), so only the remainder costs lookups.
  const mentions = await resolveMentions(
    normalizeMentionCandidates(mentionedUids, actor.uid),
    actor.uid,
  );

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
      { senderUid: actor.uid, text, senderProfile, expireAt, mentionedUids: mentions },
      () => FieldValue.serverTimestamp(),
    ),
  );

  // The ONLY 'community_chat' producer: the members this message named, and no
  // one else. A message with no mentions writes nothing — fanning out to every
  // active member per message is the spam/cost non-starter the header describes.
  //
  // Best-effort (never fails a post that already landed), bounded by
  // MAX_MESSAGE_MENTIONS by construction, and per-recipient eligibility (opt-out,
  // suspended, deleted) is left to writeInAppNotification rather than re-checked
  // here — it is the single inbox writer and already owns that decision. The
  // per-(sender, window) notification id caps a repeat-mentioner at one notice
  // per window via the same idempotent create-if-absent the convoy chat uses.
  //
  // NOTE: FCM push is intentionally deferred (end-of-MVP Firebase console setup),
  // consistent with the DM + notifications domains.
  if (mentions.length > 0) {
    const senderName = senderProfile.displayName ?? 'En medlem';
    const notificationId = communityMentionNotificationId(actor.uid, new Date());
    await Promise.all(
      mentions.map((uid) =>
        writeInAppNotification(
          uid,
          {
            category: 'community_chat',
            title: 'Du nämndes i community-chatten',
            previewText: `${senderName}: ${messagePreview(text)}`,
            actionType: 'open_notifications',
            // The channel is a singleton ('global'), so the message id is the
            // only part worth carrying — it's what a deep-link needs to scroll to.
            relatedEntityId: messageRef.id,
          },
          notificationId,
        ).catch(() => undefined),
      ),
    );
  }

  return { messageId: messageRef.id, mentionedUids: mentions };
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
