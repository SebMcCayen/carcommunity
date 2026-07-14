/**
 * convoyChat.post / convoyChat.list — member-gated per-CONVOY chat callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `convoyChat` export group (functions/src/index.ts) as
 * `convoyChat-post`, `convoyChat-list`. One of the THREE product chats
 * (community / convoy / friends-DMs). Stacked on the convoy backend
 * (functions/src/convoy): a convoy chat is readable + postable ONLY by ACCEPTED
 * members of `convoys/{convoyId}` (memberUids + members[uid].inviteStatus ===
 * 'accepted', owner included).
 *
 * Invariants:
 *  - Backend is the sole writer of convoyChats/{convoyId}/messages
 *    (firebase/firestore.rules grants reads only to accepted convoy members via
 *    a get() of the convoy doc, denies all client writes).
 *  - Membership is re-checked in the callable by loading the convoy doc (a
 *    still-invited/declined member or a non-member is rejected). A non-existent
 *    convoy or a non-member is not-found (never permission-denied) so a convoy's
 *    existence can't be probed — parity with convoy.respond.
 *  - Every message denormalizes the sender's safe profile so the channel renders
 *    with no per-message profile lookup. FCM push deferred (end-of-MVP, as DM).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  CHAT_MESSAGES_PAGE_SIZE,
  CONVOY_CHAT_RETENTION_DAYS,
  CONVOY_NOT_FOUND_MESSAGE,
  EMPTY_MESSAGE_MESSAGE,
  NOT_CONVOY_MEMBER_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  buildChatMessageDocument,
  chatMessageExpiry,
  isAcceptedConvoyMember,
  parseListConvoyInput,
  parsePostConvoyInput,
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

function convoyRef(convoyId: string) {
  return db.collection('convoys').doc(convoyId);
}

function convoyMessagesRef(convoyId: string) {
  return db.collection('convoyChats').doc(convoyId).collection('messages');
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
 * or soft-deleted (mirrors dm loadProfile).
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || toUserAccessState(snap.data()).deleted) {
    return null;
  }
  return toProfileProjection(snap.data());
}

/**
 * Loads the convoy doc and asserts the caller is an ACCEPTED member. Not-found
 * (never permission-denied) for a missing convoy OR a non/pending/declined
 * member so a convoy can't be probed by an outsider.
 */
async function requireAcceptedMember(convoyId: string, uid: string): Promise<void> {
  const snap = await convoyRef(convoyId).get();
  if (!snap.exists) {
    throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
  }
  if (!isAcceptedConvoyMember(snap.data(), uid)) {
    // A member of the convoy who hasn't accepted gets a distinct precondition;
    // a total outsider gets not-found (can't tell a convoy exists).
    const memberUids = Array.isArray(snap.data()?.memberUids)
      ? (snap.data()!.memberUids as unknown[])
      : [];
    if (memberUids.includes(uid)) {
      throw new HttpsError('failed-precondition', NOT_CONVOY_MEMBER_MESSAGE);
    }
    throw new HttpsError('not-found', CONVOY_NOT_FOUND_MESSAGE);
  }
}

// ---------------------------------------------------------------------------
// convoyChat.post
// ---------------------------------------------------------------------------

export interface PostConvoyResponse {
  messageId: string;
}

export const post = onCall(CALLABLE_OPTS, async (request): Promise<PostConvoyResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parsePostConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, text } = parsed.input;
  if (!text.trim()) {
    throw new HttpsError('invalid-argument', EMPTY_MESSAGE_MESSAGE);
  }

  await requireAcceptedMember(convoyId, actor.uid);

  const senderProfile = await loadProfile(actor.uid);
  if (!senderProfile) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  // TTL: convoy messages are retained CONVOY_CHAT_RETENTION_DAYS days. The
  // field-scoped Firestore TTL policy on `expireAt` for the `messages` collection
  // group (see communityChat.ts) auto-deletes them after that.
  const expireAt = Timestamp.fromDate(
    chatMessageExpiry(new Date(), CONVOY_CHAT_RETENTION_DAYS),
  );

  const messageRef = convoyMessagesRef(convoyId).doc();
  await messageRef.set(
    buildChatMessageDocument(
      { senderUid: actor.uid, text, senderProfile, expireAt },
      () => FieldValue.serverTimestamp(),
    ),
  );

  return { messageId: messageRef.id };
});

// ---------------------------------------------------------------------------
// convoyChat.list
// ---------------------------------------------------------------------------

export interface ListConvoyResponse {
  convoyId: string;
  messages: ChatMessageSummary[];
  /** ISO cursor to pass as `before` for the next (older) page, or null. */
  nextBefore: string | null;
  hasMore: boolean;
}

export const list = onCall(CALLABLE_OPTS, async (request): Promise<ListConvoyResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parseListConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, before } = parsed.input;

  await requireAcceptedMember(convoyId, actor.uid);

  let query = convoyMessagesRef(convoyId)
    .orderBy('createdAt', 'desc')
    .limit(CHAT_MESSAGES_PAGE_SIZE + 1);
  if (before !== undefined) {
    query = query.where('createdAt', '<', Timestamp.fromDate(new Date(before)));
  }

  const messagesSnap = await query.get();
  const docs = messagesSnap.docs;
  const hasMore = docs.length > CHAT_MESSAGES_PAGE_SIZE;
  const page = hasMore ? docs.slice(0, CHAT_MESSAGES_PAGE_SIZE) : docs;

  const messages = page.map((doc) => {
    const createdAtIso = toIso(doc.data().createdAt) ?? new Date(0).toISOString();
    return toChatMessageSummary(doc.id, doc.data(), createdAtIso);
  });

  const nextBefore =
    hasMore && messages.length > 0 ? messages[messages.length - 1]!.createdAt : null;

  return { convoyId, messages, nextBefore, hasMore };
});
