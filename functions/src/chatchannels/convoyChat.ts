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
 *  - On post, a best-effort IN-APP notification fans out to the other ACCEPTED
 *    members under the 'convoy_chat' category, so a member can silence convoy
 *    chatter without silencing DMs or invites. writeInAppNotification checks the
 *    preference; a deterministic per-window notification id
 *    (convoyChatNotificationId) collapses a busy chat into at most one notice
 *    per member per CONVOY_CHAT_NOTIFY_WINDOW_MS. This is IN-APP only — no push
 *    path exists yet.
 *  - NO @mentions, deliberately — unlike communityChat.post this callable takes
 *    no `mentionedUids`, and every convoy message stores `mentionedUids: []`
 *    (the shared builder always writes the field, so the stored message shape
 *    stays uniform across both channels and readers never branch on its
 *    absence). Mentions exist on the community channel to pick a handful of
 *    recipients out of an audience of EVERY active member. A convoy chat has no
 *    such problem: it already notifies all of its <=50 accepted members on every
 *    message, so a mention notice would just be a duplicate of one the member is
 *    getting anyway — and it would land under a different category, letting a
 *    mention slip past a member who silenced 'convoy_chat'. The only thing
 *    mentions would still buy here is client-side highlighting, a rendering
 *    concern the Android @-picker work can add later on its own.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp, type DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { requireAcceptedConvoyMember } from './convoyMembership';
import { toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import {
  CHAT_MESSAGES_PAGE_SIZE,
  CONVOY_CHAT_RETENTION_DAYS,
  EMPTY_MESSAGE_MESSAGE,
  NOT_DELIVERABLE_MESSAGE,
  acceptedConvoyMemberUids,
  buildChatMessageDocument,
  chatMessageExpiry,
  convoyChatNotificationId,
  isAlreadyExistsError,
  messagePreview,
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

// The accepted-member gate now lives in ./convoyMembership so
// chatchannels.reportMessage enforces the identical rule (a member of the
// convoy who hasn't accepted → failed-precondition; a total outsider or a
// missing convoy → not-found, so a convoy can't be probed).

// ---------------------------------------------------------------------------
// convoyChat.post
// ---------------------------------------------------------------------------

export interface PostConvoyResponse {
  messageId: string;
}

/**
 * The idempotent result of a keyed send that ALREADY committed, or null when
 * nothing is stored at that id (so the `create()` that just failed has nothing
 * to replay and the caller must surface that).
 *
 * Read ONLY after a `create()` lost the race, never speculatively: the write is
 * the guard, so a normal first-attempt send needs no read at all, and a retry —
 * whether it arrives after the original committed or alongside it — resolves
 * through this one path.
 *
 * A doc at this id from a DIFFERENT sender is an (astronomically unlikely) key
 * collision or a buggy client reusing a key: it must NOT be swallowed as this
 * caller's success, so surface already-exists and let it regenerate the key.
 */
async function replayCommittedSend(
  messageRef: DocumentReference,
  actorUid: string,
): Promise<PostConvoyResponse | null> {
  const existing = await messageRef.get();
  if (!existing.exists) {
    return null;
  }
  if (existing.data()?.senderUid !== actorUid) {
    throw new HttpsError('already-exists', 'Message id already used.');
  }
  return { messageId: messageRef.id };
}

export const post = onCall(CALLABLE_OPTS, async (request): Promise<PostConvoyResponse> => {
  const actor = await requireMemberActor(request);

  const parsed = parsePostConvoyInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { convoyId, text, clientId } = parsed.input;
  if (!text.trim()) {
    throw new HttpsError('invalid-argument', EMPTY_MESSAGE_MESSAGE);
  }

  const convoy = await requireAcceptedConvoyMember(convoyId, actor.uid);

  const senderProfile = await loadProfile(actor.uid);
  if (!senderProfile) {
    throw new HttpsError('failed-precondition', NOT_DELIVERABLE_MESSAGE);
  }

  // A client idempotency key is used VERBATIM as the message doc id, so a retry
  // of the same optimistic send lands on the same document (exactly-once) and the
  // client can reconcile its bubble by matching that id. Without a key we fall
  // back to an auto-id (legacy) doc.
  const messageRef =
    clientId !== undefined
      ? convoyMessagesRef(convoyId).doc(clientId)
      : convoyMessagesRef(convoyId).doc();

  // TTL: convoy messages are retained CONVOY_CHAT_RETENTION_DAYS days. The
  // field-scoped Firestore TTL policy on `expireAt` for the `messages` collection
  // group (see communityChat.ts) auto-deletes them after that.
  const expireAt = Timestamp.fromDate(
    chatMessageExpiry(new Date(), CONVOY_CHAT_RETENTION_DAYS),
  );

  // `create()`, NOT `set()`: the write IS the idempotency guard, and it is the
  // only one. A pre-read ("does this id exist yet?") cannot be the guard — two
  // concurrent retries of the same optimistic send both observe "missing" before
  // either writes, so with `set()` both would commit and BOTH would run the
  // notification fan-out below. With `create()` Firestore arbitrates: exactly one
  // wins, and the loser replays the winner's result with no side effects of its
  // own. Since the guard needs no read, the send costs a single write on the
  // normal path and only pays for a read when it actually loses a race.
  try {
    await messageRef.create(
      buildChatMessageDocument(
        { senderUid: actor.uid, text, senderProfile, expireAt, clientId },
        () => FieldValue.serverTimestamp(),
      ),
    );
  } catch (error) {
    if (clientId === undefined || !isAlreadyExistsError(error)) {
      throw error;
    }
    // Lost the race to a concurrent invocation of this same send. Whatever it
    // committed is the authoritative result; fall through to no notifications.
    const replay = await replayCommittedSend(messageRef, actor.uid);
    if (replay) {
      return replay;
    }
    // ALREADY_EXISTS but nothing readable there now (a TTL sweep between the two
    // reads is the only plausible cause). Surface rather than invent a result.
    throw new HttpsError('aborted', 'Message could not be committed, please retry.');
  }

  // Best-effort in-app fan-out to the OTHER accepted members (never fails the
  // post). The member list comes off the convoy doc the membership gate already
  // loaded, so this adds no read of its own. The 'convoy_chat' preference is
  // honored per-recipient inside writeInAppNotification rather than filtered
  // here, and the shared per-window notification id collapses a busy chat into
  // at most one notice per member per CONVOY_CHAT_NOTIFY_WINDOW_MS.
  //
  // Blocking is deliberately NOT filtered: a convoy's membership is already
  // block-gated at invite time (convoy/manageConvoy.ts), and the convoy chat
  // read surface itself applies no block filter — so filtering only the
  // notification would silently diverge from what the channel actually shows.
  const recipients = acceptedConvoyMemberUids(convoy, actor.uid);
  const senderName = senderProfile.displayName ?? 'En medlem';
  const convoyTitle = typeof convoy.title === 'string' && convoy.title ? convoy.title : null;
  const notificationId = convoyChatNotificationId(convoyId, new Date());
  await Promise.all(
    recipients.map((uid) =>
      writeInAppNotification(
        uid,
        {
          category: 'convoy_chat',
          title: convoyTitle ? `Nytt i konvojen: ${convoyTitle}` : 'Nytt i konvojchatten',
          previewText: `${senderName}: ${messagePreview(text)}`,
          actionType: 'open_notifications',
          relatedEntityId: convoyId,
        },
        notificationId,
      ).catch(() => undefined),
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

  await requireAcceptedConvoyMember(convoyId, actor.uid);

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
