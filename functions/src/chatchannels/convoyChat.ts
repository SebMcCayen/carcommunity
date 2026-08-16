/**
 * convoyChat.post / convoyChat.list / convoyChat.markRead — member-gated
 * per-CONVOY chat callables (contracts/functions/functions.json).
 *
 * Deployed via the `convoyChat` export group (functions/src/index.ts) as
 * `convoyChat-post`, `convoyChat-list`, `convoyChat-markRead`. One of the THREE
 * product chats
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
 *  - Blocking is filtered in BOTH directions on `list` and on the post-time
 *    notification fan-out, off the `blockVisibility/{uid}.hiddenUids` mirror
 *    (one document read per call, never one per message). The channel's LIVE
 *    window is a direct Firestore listener on the client, which no security rule
 *    can filter per-document, so that window is filtered client-side against the
 *    same mirror — see blocking/block-visibility.ts.
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
 *  - UNREAD is a per-user LAST-READ MARKER, exactly as on the community channel,
 *    not a fan-out counter: `convoyChat.markRead` stamps
 *    `userPrivate/{uid}.convoyChatLastReadAt.{convoyId}` (owner-only readable)
 *    and the client counts unread messages itself against the bounded
 *    newest-message window it already listens to. A counter would mean a write
 *    per accepted member on every post — the very cost communityChat rejected.
 *    The map is capped (chat-core CONVOY_LAST_READ_MAX_ENTRIES) because, unlike
 *    community's single marker, it gains a key per convoy for a member's whole
 *    lifetime.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp, type DocumentReference } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { requireAcceptedConvoyMember } from './convoyMembership';
import { toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import { filterHiddenAuthors } from '../blocking/block-visibility';
import { loadHiddenUids } from '../blocking/blockVisibilityStore';
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
  parseMarkReadConvoyInput,
  parsePostConvoyInput,
  pruneConvoyLastRead,
  toChatMessageSummary,
  toProfileProjection,
  type ChatMessageSummary,
  type ProfileProjection,
} from './chat-core';
import { MAX_INSTANCES_MEMBER } from '../shared/instanceLimits';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  maxInstances: MAX_INSTANCES_MEMBER,
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

/**
 * The caller's own private document, which holds the per-convoy last-read map
 * alongside the community channel's single marker (owner-only readable).
 */
function userPrivateRef(uid: string) {
  return db.collection('userPrivate').doc(uid);
}

/** Field holding `{ [convoyId]: Timestamp }` — see convoyChat.markRead. */
const CONVOY_LAST_READ_FIELD = 'convoyChatLastReadAt';

/**
 * Field holding `{ [convoyId]: Timestamp }` — the newest message DELIVERED to
 * this member in each convoy, stamped by the `post` fan-out below (never by the
 * client). Owner-only readable, alongside CONVOY_LAST_READ_FIELD, so a single
 * `userPrivate/{uid}` listener lets the client derive an "any convoy unread"
 * aggregate — the Convoys tab dot and the map-shell chat dot — by comparing this
 * map against the last-read markers, with NO per-convoy message listener.
 *
 * It mirrors the last-read map exactly (a per-convoy Timestamp map) and is bounded
 * the same way: `markRead` prunes it to CONVOY_LAST_READ_MAX_ENTRIES off the same
 * document read it already does. It gains a key per convoy a member RECEIVES in,
 * so a member who receives but never opens any convoy would not prune it; the cap
 * is far above the convoys anyone is in at once, and opening ANY convoy prunes the
 * whole map, so it stays bounded in practice — the same self-healing contract the
 * last-read map documents.
 */
const CONVOY_LATEST_FIELD = 'convoyChatLatestAt';

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
  // Blocking IS filtered here, and must be: the convoy chat's READ surface now
  // hides a blocked pair from each other in both directions (see list below and
  // the client-side live-window filter), so notifying a member about a message
  // they will never be shown would be a notice that leads nowhere — and it would
  // announce the blocked party's presence to the very person hidden from them.
  // Membership is block-gated at invite time (convoy/manageConvoy.ts), but a
  // block can also happen AFTER both parties joined, which is this case.
  // One document read for the whole fan-out.
  const hidden = await loadHiddenUids(actor.uid);
  const recipients = acceptedConvoyMemberUids(convoy, actor.uid).filter(
    (uid) => !hidden.has(uid),
  );
  const senderName = senderProfile.displayName ?? 'En medlem';
  const convoyTitle = typeof convoy.title === 'string' && convoy.title ? convoy.title : null;
  const notificationId = convoyChatNotificationId(convoyId, new Date());
  await Promise.all(
    recipients.map((uid) =>
      Promise.all([
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
        // Light up the recipient's "any convoy unread" aggregate: stamp the newest
        // message time for THIS convoy on their private doc (CONVOY_LATEST_FIELD),
        // the exact set of recipients — accepted members minus the sender minus
        // blocked pairs — that get the notification. The owner-only readable map
        // drives the client's aggregate dot from ONE userPrivate listener, so no
        // per-convoy message listener is opened for it. A nested serverTimestamp
        // under merge writes only this convoy's key, so a concurrent stamp for a
        // different convoy cannot clobber it. Best-effort like the notification: a
        // failed stamp only means this one message does not pre-light the dot — the
        // per-convoy unread window still shows it on open.
        userPrivateRef(uid)
          .set(
            { [CONVOY_LATEST_FIELD]: { [convoyId]: FieldValue.serverTimestamp() } },
            { merge: true },
          )
          .catch(() => undefined),
      ]),
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

  // ONE block-visibility document read for the whole page, never one per
  // message (blocking/block-visibility.ts).
  const [messagesSnap, hidden] = await Promise.all([query.get(), loadHiddenUids(actor.uid)]);
  const docs = messagesSnap.docs;
  const hasMore = docs.length > CHAT_MESSAGES_PAGE_SIZE;
  const page = hasMore ? docs.slice(0, CHAT_MESSAGES_PAGE_SIZE) : docs;

  const pageMessages = page.map((doc) => {
    const createdAtIso = toIso(doc.data().createdAt) ?? new Date(0).toISOString();
    return toChatMessageSummary(doc.id, doc.data(), createdAtIso);
  });

  // Cursor off the RAW page, before the block filter — same reasoning as
  // communityChat.list: a cursor taken from the filtered list would stall
  // pagination whenever a page's tail is entirely hidden.
  const nextBefore =
    hasMore && pageMessages.length > 0 ? pageMessages[pageMessages.length - 1]!.createdAt : null;

  // Bidirectional: neither party of a blocked pair sees the other's messages,
  // even while both remain accepted members of the convoy.
  const messages = filterHiddenAuthors(pageMessages, (message) => message.senderUid, hidden);

  return { convoyId, messages, nextBefore, hasMore };
});

// ---------------------------------------------------------------------------
// convoyChat.markRead
// ---------------------------------------------------------------------------

export interface MarkReadConvoyResponse {
  convoyId: string;
}

/**
 * Stamps the caller's last-read marker for ONE convoy at
 * `userPrivate/{uid}.convoyChatLastReadAt.{convoyId}`, evicting the oldest
 * markers past the cap. Idempotent; best-effort bookkeeping the client fires and
 * forgets.
 *
 * NO convoy-membership read, deliberately — and this is the one place in the
 * convoyChat domain that skips it. The write lands in the CALLER'S OWN private
 * document; it grants no access to anything (the chat's read gate is
 * firestore.rules' get() of the convoy doc, and post/list re-check membership
 * themselves), returns nothing but the id it was handed, and cannot probe a
 * convoy's existence — a marker for a convoy the caller is not in is inert. The
 * only thing an outsider could do with it is churn keys in their own capped map.
 * Community's markRead gates identically (active member, nothing more), and this
 * runs on the hot path — once on open and again on each incoming message while
 * the channel is open — so a membership read per call would be paid on every
 * message every watching member sees, for no security this doesn't already have.
 *
 * No read-BACK of the stamped value either: the client learns its marker from the
 * owner-readable `userPrivate/{uid}` listener that already drives the unread
 * count, so returning it would buy a second read nobody consumes. The single read
 * this does spend is the one the eviction needs.
 */
export const markRead = onCall(
  CALLABLE_OPTS,
  async (request): Promise<MarkReadConvoyResponse> => {
    const actor = await requireMemberActor(request);

    const parsed = parseMarkReadConvoyInput(request.data);
    if (!parsed.ok) {
      throw new HttpsError('invalid-argument', parsed.message);
    }
    const { convoyId } = parsed.input;

    const ref = userPrivateRef(actor.uid);
    const snap = await ref.get();
    const evicted = pruneConvoyLastRead(
      snap.data()?.[CONVOY_LAST_READ_FIELD],
      convoyId,
      Date.now(),
    );

    // Bound the parallel latest-message map the post fan-out grows, with the same
    // cap and eviction rule as the read marker. It gains a key per convoy the
    // member ever RECEIVES in (not just the ones they read), so without pruning it
    // could outgrow the read map; markRead is the one place already reading this
    // document, so the prune adds no read. The convoy just read is protected from
    // eviction — it is the one most likely to still matter.
    const evictedLatest = pruneConvoyLastRead(
      snap.data()?.[CONVOY_LATEST_FIELD],
      convoyId,
      Date.now(),
    );

    // A nested serverTimestamp sentinel under a merge: only the ONE convoy's key
    // is written (Firestore merges the map by field path), so a concurrent
    // markRead for a different convoy cannot clobber this one — which writing the
    // whole recomputed map back would. Evictions ride along as nested deletes for
    // the same reason: a merge alone can add keys but never remove them.
    //
    // NOT a transaction, deliberately: two overlapping calls for different
    // convoys can leave the map one over the cap, and the next call evicts it
    // straight back down (pruneConvoyLastRead prunes from an over-cap map in one
    // pass), so the map self-heals and cannot ratchet up. Wrapping this in a
    // transaction to make the cap momentarily exact would put a retrying
    // read-write on the hot path, and an aborted transaction is a silently
    // failed markRead — a badge left lit on a chat the member has read. See
    // pruneConvoyLastRead for the full reasoning.
    const update: Record<string, unknown> = { [convoyId]: FieldValue.serverTimestamp() };
    for (const key of evicted) {
      update[key] = FieldValue.delete();
    }
    const payload: Record<string, unknown> = { [CONVOY_LAST_READ_FIELD]: update };

    // Fold the latest-map evictions into the SAME merge write — nested deletes, so
    // they remove only the over-cap keys and never touch the entries the post
    // fan-out is still stamping. Only present when there is something to evict, so
    // markRead stays a single write on the steady-state path.
    if (evictedLatest.length > 0) {
      const latestUpdate: Record<string, unknown> = {};
      for (const key of evictedLatest) {
        latestUpdate[key] = FieldValue.delete();
      }
      payload[CONVOY_LATEST_FIELD] = latestUpdate;
    }
    await ref.set(payload, { merge: true });

    return { convoyId };
  },
);
