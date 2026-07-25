/**
 * friend.sendRequest / friend.respondRequest / friend.cancelRequest /
 * friend.remove / friend.list — member-gated callables
 * (contracts/functions/functions.json).
 *
 * Deployed via the `friend` export group (functions/src/index.ts) as
 * `friend-sendRequest`, `friend-respondRequest`, `friend-cancelRequest`,
 * `friend-remove`, `friend-list`. This is the
 * friend-GRAPH foundation only — direct messaging/DMs are a separate
 * follow-up and are intentionally NOT built here.
 *
 * Invariants:
 *  - Backend is the sole writer of friendRequests and users/{uid}/friends
 *    (firebase/firestore.rules grants owner reads, denies all client writes).
 *  - A pending request is withdrawable by its SENDER only, via cancelRequest,
 *    which addresses it by RECIPIENT and derives the doc id server-side — so a
 *    caller structurally cannot name (or probe) anyone else's request. Every
 *    non-cancellable outcome is the same silent no-op.
 *  - Established friendship (users/{uid}/friends/{friendUid}) — not request
 *    status — is the source of truth for "already friends", so re-friending
 *    after a decline or a removal always works.
 *  - Blocking is honoured in BOTH directions: if either party has blocked the
 *    other, a request is rejected with a neutral failed-precondition
 *    (NOT_ADDABLE) that never reveals who blocked whom.
 *  - Best-effort IN-APP notifications ('friend_request' category, honored
 *    per-recipient by writeInAppNotification) are written for: a new request
 *    (the invitee), and an ACCEPT (the original requester — from both
 *    respondRequest's accept and sendRequest's reverse-pending auto-accept).
 *    A DECLINE is deliberately silent: the requester is never told they were
 *    turned down.
 *  - Nickname (displayName) is NOT unique. sendRequest resolves it
 *    CASE-INSENSITIVELY by PREFIX against the denormalized `displayNameLower`
 *    key (toSearchKey in friends-core.ts), never against `displayName`:
 *      * matched:     'gt86_swe', 'GT86_SWE' and 'gt86' all resolve 'Gt86_swe'
 *                     (any case; any leading substring of the nickname).
 *      * NOT matched: '86_swe' / 'swe' — a mid-word or trailing substring.
 *                     Firestore supports no substring/contains operator, and
 *                     an exact+prefix range query is the whole capability here.
 *                     There is deliberately no fuzzy/edit-distance matching and
 *                     no external search service.
 *    An EXACT (case-insensitive) match always wins over longer prefix matches.
 *    Otherwise: 0 matches → not-found, 1 → proceed, >1 → failed-precondition
 *    (AMBIGUOUS_NICKNAME) carrying a candidate list in the error details so
 *    the client can re-call with a resolved { toUid }.
 *  - Every sendRequest failure carries a `details.reason` discriminator
 *    (friends-core.ts REASON_*) because the HttpsError code alone is ambiguous
 *    — 'already-exists' covers both already-friends and request-already-sent,
 *    'failed-precondition' covers both ambiguous-nickname and not-addable.
 *    NOT_ADDABLE stays opaque in both block directions (see friends-core.ts).
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { logger } from 'firebase-functions';
import { Timestamp } from 'firebase-admin/firestore';
import type { DocumentSnapshot, QuerySnapshot } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { isRestricted, toUserAccessState } from '../shared/access';
import { writeInAppNotification } from '../notifications/deliver';
import {
  ALREADY_FRIENDS_MESSAGE,
  AMBIGUOUS_NICKNAME_MESSAGE,
  BACKEND_UNAVAILABLE_MESSAGE,
  isMissingIndexError,
  NICKNAME_NOT_FOUND_MESSAGE,
  NOT_ADDABLE_MESSAGE,
  REASON_ALREADY_FRIENDS,
  REASON_AMBIGUOUS_NICKNAME,
  REASON_BACKEND_UNAVAILABLE,
  REASON_NICKNAME_NOT_FOUND,
  REASON_NOT_ADDABLE,
  REASON_REQUEST_ALREADY_SENT,
  REASON_SELF_REQUEST,
  REQUEST_ALREADY_SENT_MESSAGE,
  REQUEST_NOT_FOUND_MESSAGE,
  REQUEST_NOT_PENDING_MESSAGE,
  SELF_REQUEST_MESSAGE,
  buildFriendRequestDocument,
  buildFriendshipDocument,
  friendRequestId,
  parseCancelRequestInput,
  parseListInput,
  parseRemoveFriendInput,
  parseRespondRequestInput,
  parseSendRequestInput,
  prefixUpperBound,
  toFriendRequestSummary,
  toFriendSummary,
  toProfileProjection,
  toSearchKey,
  type FriendRequestSummary,
  type FriendSummary,
  type NicknameCandidate,
  type ProfileProjection,
} from './friends-core';

const CALLABLE_OPTS = {
  region: 'europe-west1',
  memory: '256MiB' as const,
  timeoutSeconds: 30,
  enforceAppCheck: process.env.FUNCTIONS_EMULATOR !== 'true',
};

/** Cap on candidates returned for an ambiguous nickname. */
const AMBIGUOUS_CANDIDATE_LIMIT = 10;

/**
 * Raw page size for the displayName lookup in resolveTarget. The `.limit()` is
 * applied by Firestore BEFORE we drop the caller's own row and restricted
 * (suspended/deleted) same-name accounts, so the fetched page has to be large
 * enough to (a) still yield a full AMBIGUOUS_CANDIDATE_LIMIT candidate list and
 * (b) leave headroom for the caller + a few restricted duplicates that get
 * filtered out — otherwise a page filled by filtered-out rows could hide
 * additional ACTIVE matches beyond it and be mistaken for a unique match.
 * The +5 headroom covers the caller's row plus up to four restricted same-name
 * accounts while keeping the read bounded at 15 documents. If the raw page
 * SATURATES this cap we cannot prove uniqueness (more same-name rows may exist
 * beyond it), so resolveTarget deliberately degrades to AMBIGUOUS_NICKNAME
 * rather than risk resolving to the wrong person.
 */
export const NICKNAME_SCAN_LIMIT = AMBIGUOUS_CANDIDATE_LIMIT + 5;

/**
 * Bounded reads for friend.list. Each of the three queries (friends,
 * incoming/outgoing pending requests) is capped so the callable can never fan
 * out into an unbounded read as the friend graph grows — matching the fixed
 * `.limit(MAX_*)` convention used elsewhere (e.g. MAX_REPORT_SCAN in
 * events/moderateReports.ts). These are generous safety ceilings, not a UI
 * page size; cursor pagination (a pageToken/startAfter follow-up) can layer on
 * later without changing the response shape.
 */
const MAX_FRIENDS_RETURNED = 1000;
const MAX_PENDING_REQUESTS_RETURNED = 500;

// ---------------------------------------------------------------------------
// Firestore references
// ---------------------------------------------------------------------------

function friendRequestRef(fromUid: string, toUid: string) {
  return db.collection('friendRequests').doc(friendRequestId(fromUid, toUid));
}

function friendRequestRefById(requestId: string) {
  return db.collection('friendRequests').doc(requestId);
}

function friendshipRef(ownerUid: string, friendUid: string) {
  return db.collection('users').doc(ownerUid).collection('friends').doc(friendUid);
}

function blockRef(blockerUid: string, blockedUid: string) {
  return db.collection('userBlocks').doc(blockerUid).collection('blocked').doc(blockedUid);
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

function isoFrom(value: unknown, fallback: Date): string {
  return value instanceof Timestamp ? value.toDate().toISOString() : fallback.toISOString();
}

/**
 * Reads a users/{uid} profile. Returns null when the user is missing or
 * restricted — soft-deleted OR suspended (callers surface not-found so neither
 * deletion nor suspension can be probed, and no pending request can be created
 * for an account that respondRequest's accept guard would later reject).
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || isRestricted(toUserAccessState(snap.data()))) {
    return null;
  }
  return toProfileProjection(snap.data());
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
 * Resolves the sendRequest target to a concrete, addable uid + profile.
 * Throws the appropriate HttpsError for not-found / ambiguous nickname / self.
 *
 * MATCHING (see the module KDoc for the full contract): case-insensitive
 * PREFIX matching over the denormalized `displayNameLower` key. An exact
 * (case-insensitive) match always wins over longer prefix matches; otherwise a
 * single prefix match resolves and several become AMBIGUOUS_NICKNAME. Mid-word
 * substrings are NOT matched.
 */
async function resolveTarget(
  callerUid: string,
  input: { nickname?: string; toUid?: string },
): Promise<{ uid: string; profile: ProfileProjection }> {
  if (input.toUid !== undefined) {
    const profile = await loadProfile(input.toUid);
    if (!profile) {
      throw new HttpsError('not-found', NICKNAME_NOT_FOUND_MESSAGE, {
        reason: REASON_NICKNAME_NOT_FOUND,
      });
    }
    if (input.toUid === callerUid) {
      throw new HttpsError('invalid-argument', SELF_REQUEST_MESSAGE, {
        reason: REASON_SELF_REQUEST,
      });
    }
    return { uid: input.toUid, profile };
  }

  // Nickname path: displayName is NOT unique, and Firestore has no
  // case-insensitive operator — so we range-scan the denormalized lowercase key
  // `displayNameLower` as a PREFIX ([key, prefixUpperBound(key))), then filter
  // out the caller and restricted accounts — soft-deleted OR suspended — so a
  // request can't be created for, nor a restricted user surfaced by nickname
  // resolution as, a target that respondRequest's accept guard would later
  // reject.
  //
  // Ordering by `displayNameLower` ASC keeps the page deterministic and puts an
  // EXACT match first (within a prefix range the exact key is the shortest
  // string and equal keys are adjacent), so when the page saturates and we fall
  // back to the picker the intended member heads the candidate list. Single-
  // field range + orderBy on the SAME field needs no composite index (covered
  // by the automatic single-field index).
  const searchKey = toSearchKey(input.nickname as string);
  const query = await db
    .collection('users')
    .where('displayNameLower', '>=', searchKey)
    .where('displayNameLower', '<', prefixUpperBound(searchKey))
    .orderBy('displayNameLower', 'asc')
    .limit(NICKNAME_SCAN_LIMIT)
    .get();

  // Firestore applies `.limit()` BEFORE we filter, so a page that fills up to
  // the cap may hide further ACTIVE prefix matches beyond it. When the raw page
  // saturates the cap we cannot prove uniqueness → treat as ambiguous rather
  // than risk resolving to a single wrong target. (The exact match is exempt:
  // per the ordering argument above it is always on the page when it exists.)
  const saturated = query.size >= NICKNAME_SCAN_LIMIT;
  // Did the caller's own account match? Tracked before filtering it out so a
  // self-only match can surface the dedicated self error, not not-found.
  const callerMatched = query.docs.some((doc) => doc.id === callerUid);
  const matches = query.docs.filter(
    (doc) => doc.id !== callerUid && !isRestricted(toUserAccessState(doc.data())),
  );

  // An EXACT (case-insensitive) match is unambiguous by intent: someone typing
  // a member's whole nickname means THAT member, even when other members'
  // nicknames merely start with it ('gt86' must reach 'gt86' itself, not open a
  // picker just because 'gt86_swe' also exists). Only a duplicated exact name —
  // the pre-existing ambiguity this callable already had — stays ambiguous.
  //
  // Gated on !saturated for the SAME reason as the ambiguity check below: on a
  // saturated page we have not seen every row in the range, so a second ACTIVE
  // account with this exact name could sit beyond it and `exact.length === 1`
  // would not prove uniqueness. A saturated page therefore falls through to the
  // picker (which lists the exact match first — it sorts first in the range).
  const exact = matches.filter((doc) => doc.data()?.displayNameLower === searchKey);
  if (!saturated && exact.length === 1) {
    const only = exact[0]!;
    return { uid: only.id, profile: toProfileProjection(only.data()) };
  }

  // >1 match (several exact duplicates, or several members sharing the prefix),
  // OR a saturated page we can't prove is unique → ambiguous; the client shows
  // the candidate picker and re-calls with a resolved { toUid }.
  if (matches.length > 1 || saturated) {
    const candidates: NicknameCandidate[] = matches.slice(0, AMBIGUOUS_CANDIDATE_LIMIT).map((doc) => {
      const projection = toProfileProjection(doc.data());
      return { uid: doc.id, displayName: projection.displayName, avatarPath: projection.avatarPath };
    });
    throw new HttpsError('failed-precondition', AMBIGUOUS_NICKNAME_MESSAGE, {
      reason: REASON_AMBIGUOUS_NICKNAME,
      candidates,
    });
  }
  if (matches.length === 0) {
    // The nickname matched only the caller themselves (an unsaturated page with
    // no other active match) → dedicated self error, mirroring the by-uid path,
    // so the client shows "you can't friend yourself" instead of a generic
    // not-found. Otherwise it truly resolves to nobody addressable.
    if (callerMatched) {
      throw new HttpsError('invalid-argument', SELF_REQUEST_MESSAGE, {
        reason: REASON_SELF_REQUEST,
      });
    }
    throw new HttpsError('not-found', NICKNAME_NOT_FOUND_MESSAGE, {
      reason: REASON_NICKNAME_NOT_FOUND,
    });
  }
  const only = matches[0]!;
  return { uid: only.id, profile: toProfileProjection(only.data()) };
}

// ---------------------------------------------------------------------------
// friend.sendRequest
// ---------------------------------------------------------------------------

export type SendRequestResult =
  | { status: 'requested'; request: FriendRequestSummary }
  | { status: 'friends'; friend: FriendSummary };

export const sendRequest = onCall(CALLABLE_OPTS, async (request): Promise<SendRequestResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseSendRequestInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const { uid: targetUid, profile: targetProfile } = await resolveTarget(actor.uid, parsed.input);

  if (targetUid === actor.uid) {
    throw new HttpsError('invalid-argument', SELF_REQUEST_MESSAGE, { reason: REASON_SELF_REQUEST });
  }

  if (await isBlockedEitherWay(actor.uid, targetUid)) {
    // Neutral — never reveals which side blocked (privacy). The reason is the
    // same opaque NOT_ADDABLE in both directions.
    throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE, { reason: REASON_NOT_ADDABLE });
  }

  const callerProfile = await loadProfile(actor.uid);
  if (!callerProfile) {
    // The actor gate already loaded users/{caller}; a missing profile here is
    // an inconsistent state rather than a client error.
    throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE, { reason: REASON_NOT_ADDABLE });
  }

  const now = new Date();
  const outgoingRef = friendRequestRef(actor.uid, targetUid);
  const incomingRef = friendRequestRef(targetUid, actor.uid);
  const callerFriendRef = friendshipRef(actor.uid, targetUid);
  const targetFriendRef = friendshipRef(targetUid, actor.uid);

  const result = await db.runTransaction<SendRequestResult>(async (tx) => {
    // Reads must precede writes. Re-read the block state in BOTH directions
    // INSIDE the transaction: the outside-transaction check above is a cheap
    // fast-fail, but block state can change between it and these writes
    // (TOCTOU), so the authoritative guarantee lives here — guarding both the
    // new-request write and the reverse auto-accept path below.
    const [alreadyFriend, outgoing, incoming, callerBlockedTarget, targetBlockedCaller] =
      await Promise.all([
        tx.get(callerFriendRef),
        tx.get(outgoingRef),
        tx.get(incomingRef),
        tx.get(blockRef(actor.uid, targetUid)),
        tx.get(blockRef(targetUid, actor.uid)),
      ]);

    if (alreadyFriend.exists) {
      throw new HttpsError('already-exists', ALREADY_FRIENDS_MESSAGE, {
        reason: REASON_ALREADY_FRIENDS,
      });
    }

    // Blocking is honoured in BOTH directions. Neutral failed-precondition
    // (never reveals who blocked whom), matching respondRequest's accept path.
    if (callerBlockedTarget.exists || targetBlockedCaller.exists) {
      throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE, {
        reason: REASON_NOT_ADDABLE,
      });
    }

    // The other party already has a pending request to the caller → befriend
    // immediately instead of stacking a mirror request (no stuck state).
    if (incoming.exists && incoming.data()?.status === 'pending') {
      tx.set(callerFriendRef, buildFriendshipDocument(targetUid, targetProfile, () => Timestamp.fromDate(now)));
      tx.set(targetFriendRef, buildFriendshipDocument(actor.uid, callerProfile, () => Timestamp.fromDate(now)));
      tx.set(incomingRef, { status: 'accepted', updatedAt: Timestamp.fromDate(now) }, { merge: true });
      // Concurrency guard: under a "both users send at the same time" race BOTH
      // directional request docs can exist as pending. We accept the incoming
      // (target→caller) side above; the caller's own outgoing (caller→target)
      // doc — read in the same batch — must be resolved in the SAME transaction
      // too, using the identical status='accepted' merge the normal accept path
      // (respondRequest) applies, so friend.list (which only surfaces pending
      // docs) can't keep showing a stale outgoing/incoming request now that the
      // friendship exists. Guarded on the snapshot: in the non-race path the
      // outgoing doc doesn't exist and nothing is written.
      if (outgoing.exists) {
        tx.set(outgoingRef, { status: 'accepted', updatedAt: Timestamp.fromDate(now) }, { merge: true });
      }
      return {
        status: 'friends',
        friend: toFriendSummary(targetUid, { displayName: targetProfile.displayName, avatarPath: targetProfile.avatarPath }, now.toISOString()),
      };
    }

    if (outgoing.exists && outgoing.data()?.status === 'pending') {
      throw new HttpsError('already-exists', REQUEST_ALREADY_SENT_MESSAGE, {
        reason: REASON_REQUEST_ALREADY_SENT,
      });
    }

    // New request, or re-opening a previously declined/accepted (now unfriended)
    // record — upsert to pending with a fresh timestamp.
    tx.set(
      outgoingRef,
      buildFriendRequestDocument(actor.uid, targetUid, callerProfile, targetProfile, () => Timestamp.fromDate(now)),
    );
    return {
      status: 'requested',
      request: toFriendRequestSummary(
        outgoingRef.id,
        {
          fromUid: actor.uid,
          toUid: targetUid,
          fromDisplayName: callerProfile.displayName,
          fromAvatarPath: callerProfile.avatarPath,
          toDisplayName: targetProfile.displayName,
          toAvatarPath: targetProfile.avatarPath,
        },
        actor.uid,
        now.toISOString(),
      ),
    };
  });

  // Best-effort in-app notice for the OTHER party (never fails the request).
  // Both outcomes are the target's news to hear, under the same
  // 'friend_request' category — honored per-recipient inside
  // writeInAppNotification. Blocking needs no check: both the pre-transaction
  // gate and the in-transaction re-read reject a blocked pair before here.
  const callerName = callerProfile.displayName ?? 'En medlem';
  await writeInAppNotification(
    targetUid,
    result.status === 'friends'
      ? {
          // The reverse-pending auto-accept path: the target had a pending
          // request to the caller and is now friends — from their side the
          // caller accepted, so this mirrors respondRequest's accept notice.
          category: 'friend_request',
          title: 'Vänförfrågan accepterad',
          previewText: `${callerName} accepterade din vänförfrågan.`,
          actionType: 'open_profile',
          relatedEntityId: actor.uid,
        }
      : {
          category: 'friend_request',
          title: 'Ny vänförfrågan',
          previewText: `${callerName} vill bli din vän.`,
          actionType: 'open_notifications',
          relatedEntityId: actor.uid,
        },
  ).catch(() => undefined);

  return result;
});

// ---------------------------------------------------------------------------
// friend.respondRequest
// ---------------------------------------------------------------------------

export type RespondRequestResult =
  | { status: 'accepted'; friend: FriendSummary }
  | { status: 'declined' };

export const respondRequest = onCall(CALLABLE_OPTS, async (request): Promise<RespondRequestResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseRespondRequestInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { requestId, action } = parsed.input;

  const reqRef = friendRequestRefById(requestId);
  const now = new Date();

  // The transaction additionally reports who to tell and under what name; a
  // DECLINE reports nothing, so the requester is never told they were turned
  // down (see the notify block below).
  interface RespondOutcome {
    result: RespondRequestResult;
    notify?: { uid: string; accepterName: string | null };
  }

  const { result, notify } = await db.runTransaction<RespondOutcome>(async (tx) => {
    const reqSnap = await tx.get(reqRef);
    if (!reqSnap.exists) {
      throw new HttpsError('not-found', REQUEST_NOT_FOUND_MESSAGE);
    }
    const data = reqSnap.data()!;
    // Only the addressee may respond; not-found (never permission-denied) so
    // a request's existence can't be probed by a non-recipient.
    if (data.toUid !== actor.uid) {
      throw new HttpsError('not-found', REQUEST_NOT_FOUND_MESSAGE);
    }
    if (data.status !== 'pending') {
      throw new HttpsError('failed-precondition', REQUEST_NOT_PENDING_MESSAGE);
    }

    const fromUid = String(data.fromUid);

    if (action === 'decline') {
      tx.set(reqRef, { status: 'declined', updatedAt: Timestamp.fromDate(now) }, { merge: true });
      // No notify: a decline is deliberately silent. Telling the requester they
      // were turned down invites pressure on the decliner and leaks a choice
      // that is nobody else's business — the request simply stops being pending.
      return { result: { status: 'declined' } };
    }

    // action === 'accept' — read both live profiles (freshness) and the block
    // state in BOTH directions before writing. Reads must precede writes in a
    // Firestore transaction, so the block docs are read in the same batch.
    const [callerSnap, fromSnap, callerBlockedFrom, fromBlockedCaller] = await Promise.all([
      tx.get(db.collection('users').doc(actor.uid)),
      tx.get(db.collection('users').doc(fromUid)),
      tx.get(blockRef(actor.uid, fromUid)),
      tx.get(blockRef(fromUid, actor.uid)),
    ]);

    // Blocking is honoured in BOTH directions: if either party has blocked the
    // other since the request was sent, the friendship must not be created.
    // Neutral failed-precondition (never reveals who blocked whom), matching
    // sendRequest's block handling.
    if (callerBlockedFrom.exists || fromBlockedCaller.exists) {
      throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE);
    }

    // Both parties must still exist and be non-restricted (not soft-deleted or
    // suspended) at write time — either could have been deleted/suspended
    // between requireMemberActor and this transaction. Otherwise we would write
    // friendship docs with null projections for a ghost/restricted account.
    // Neutral failed-precondition (never reveals which side / why), matching the
    // block handling above.
    if (
      !callerSnap.exists ||
      !fromSnap.exists ||
      isRestricted(toUserAccessState(callerSnap.data())) ||
      isRestricted(toUserAccessState(fromSnap.data()))
    ) {
      throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE);
    }

    const callerProfile = toProfileProjection(callerSnap.data());
    const fromProfile = toProfileProjection(fromSnap.data());

    tx.set(
      friendshipRef(actor.uid, fromUid),
      buildFriendshipDocument(fromUid, fromProfile, () => Timestamp.fromDate(now)),
    );
    tx.set(
      friendshipRef(fromUid, actor.uid),
      buildFriendshipDocument(actor.uid, callerProfile, () => Timestamp.fromDate(now)),
    );
    tx.set(reqRef, { status: 'accepted', updatedAt: Timestamp.fromDate(now) }, { merge: true });

    return {
      result: {
        status: 'accepted',
        friend: toFriendSummary(fromUid, { displayName: fromProfile.displayName, avatarPath: fromProfile.avatarPath }, now.toISOString()),
      },
      notify: { uid: fromUid, accepterName: callerProfile.displayName },
    };
  });

  // Best-effort in-app notice for the REQUESTER that their request landed
  // (never fails the response). Accept only — see the decline path above. The
  // 'friend_request' preference is honored per-recipient inside
  // writeInAppNotification, and the accept path's in-transaction block re-read
  // means a blocked pair never reaches here.
  if (notify) {
    await writeInAppNotification(notify.uid, {
      category: 'friend_request',
      title: 'Vänförfrågan accepterad',
      previewText: `${notify.accepterName ?? 'En medlem'} accepterade din vänförfrågan.`,
      actionType: 'open_profile',
      relatedEntityId: actor.uid,
    }).catch(() => undefined);
  }

  return result;
});

// ---------------------------------------------------------------------------
// friend.cancelRequest
// ---------------------------------------------------------------------------

export interface CancelRequestResult {
  /** True only when a PENDING request of the caller's was actually deleted. */
  cancelled: boolean;
}

/**
 * Withdraws the caller's own still-PENDING outgoing friend request to
 * { toUid } — the counterpart of sendRequest, so a request sent by mistake (or
 * to someone who simply never replies) is not permanent.
 *
 * AUTHORIZATION IS STRUCTURAL, not a check on client input: the doc id is
 * derived server-side as friendRequestId(caller, toUid), so the callable can
 * only ever address a request the CALLER sent. There is no id parameter to
 * point at somebody else's request, and consequently no way to use this
 * callable to probe whether a request between two other members exists.
 *
 * EVERY non-cancellable outcome is the SAME silent { cancelled: false } no-op —
 * no request, an already accepted/declined one, or self. It is deliberately not
 * an error:
 *  - Idempotency: a double-tap (or a retry after a dropped response) must not
 *    turn the second call into a user-visible failure when the post-state asked
 *    for is the post-state that exists.
 *  - No oracle: a request that was accepted a second ago is indistinguishable
 *    from one that never existed, so the response can never confirm activity on
 *    another member's account beyond what the caller already knows.
 * `cancelled` is bookkeeping for the client's optimistic UI; the authoritative
 * relationship state is whatever the following friend.list returns.
 *
 * The request document is DELETED rather than moved to a terminal status: a
 * withdrawn request should read as "never sent" (the recipient's pending list
 * simply loses the row), and sendRequest upserts the same deterministic id, so
 * the caller can send again later. Established friendship — not request status
 * — is the source of truth for "already friends", so deleting a pending request
 * can never disturb an existing friendship.
 *
 * NOT undone here: the best-effort in-app 'friend_request' notification
 * sendRequest wrote for the recipient. Deleting it would need a
 * category+relatedEntityId query over another user's notifications (a new
 * composite index) to unwrite a historical "someone wanted to be your friend"
 * notice; the actionable surface — the recipient's pending-request list — is
 * cleared by this delete, and acting on the stale notice cannot resurrect the
 * request (respondRequest not-founds it).
 */
export const cancelRequest = onCall(CALLABLE_OPTS, async (request): Promise<CancelRequestResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseCancelRequestInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { toUid } = parsed.input;

  // A self-request can never exist (sendRequest rejects it), so there is
  // nothing to cancel — answered as the same no-op rather than an error.
  if (toUid === actor.uid) {
    return { cancelled: false };
  }

  const requestRef = friendRequestRef(actor.uid, toUid);

  return db.runTransaction<CancelRequestResult>(async (tx) => {
    const snap = await tx.get(requestRef);
    if (!snap.exists) {
      return { cancelled: false };
    }
    const data = snap.data()!;
    // BOTH ends of the pair are re-asserted, not just fromUid. The id derivation
    // already implies them, so this is belt-and-braces against a document whose
    // BODY disagrees with its own id — written by some future path, or by a
    // botched migration. Asserting only the sender would still delete a doc that
    // is addressed to somebody else entirely, which is precisely the case this
    // guard exists to refuse.
    if (data.fromUid !== actor.uid || data.toUid !== toUid || data.status !== 'pending') {
      return { cancelled: false };
    }
    tx.delete(requestRef);
    return { cancelled: true };
  });
});

// ---------------------------------------------------------------------------
// friend.remove
// ---------------------------------------------------------------------------

export interface RemoveFriendResult {
  removed: boolean;
}

export const remove = onCall(CALLABLE_OPTS, async (request): Promise<RemoveFriendResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseRemoveFriendInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }
  const { friendUid } = parsed.input;

  if (friendUid === actor.uid) {
    return { removed: false };
  }

  const callerRef = friendshipRef(actor.uid, friendUid);
  const otherRef = friendshipRef(friendUid, actor.uid);

  return db.runTransaction<RemoveFriendResult>(async (tx) => {
    const callerSnap = await tx.get(callerRef);
    // Delete BOTH sides (idempotent). removed reflects the caller's own side.
    tx.delete(callerRef);
    tx.delete(otherRef);
    return { removed: callerSnap.exists };
  });
});

// ---------------------------------------------------------------------------
// friend.list
// ---------------------------------------------------------------------------

export interface ListFriendsResult {
  friends: FriendSummary[];
  incoming: FriendRequestSummary[];
  outgoing: FriendRequestSummary[];
}

/**
 * Reads the caller's friend graph (friends + both pending-request directions).
 *
 * Every query is bounded so a large friend graph can't turn this callable into
 * an unbounded read (cost/latency guard). The caps are generous safety
 * ceilings; results are sorted in memory by the caller.
 *
 * A Firestore "requires an index" failure is translated into a specific,
 * retryable `unavailable` HttpsError instead of escaping as an opaque INTERNAL
 * — see isMissingIndexError() in friends-core.ts for why that mattered enough
 * to encode. The log line is the operator-facing half: it names the callable
 * and carries the original message, which embeds the console URL that creates
 * the missing index.
 */
async function readFriendGraph(
  uid: string,
): Promise<[QuerySnapshot, QuerySnapshot, QuerySnapshot]> {
  try {
    return await Promise.all([
      // orderBy BEFORE limit so that, if a cap is ever hit, the truncation is
      // deterministic and keeps the SAME items the presented order surfaces
      // (rather than an arbitrary document-ID-ordered subset). The friend doc's
      // timestamp field is `createdAt` (friends-core buildFriendshipDocument);
      // the output's `friendsSince` is derived from it and sorted ascending, so
      // truncation keeps the oldest friendships — matching the presented order.
      // No where clause → covered by Firestore's automatic single-field index.
      db
        .collection('users')
        .doc(uid)
        .collection('friends')
        .orderBy('createdAt', 'asc')
        .limit(MAX_FRIENDS_RETURNED)
        .get(),
      // Pending requests are presented most-recent-first; orderBy createdAt desc
      // BEFORE limit so a hit cap keeps the NEWEST requests instead of an
      // arbitrary subset that could drop them. Needs the composite index
      // [toUid ASC, status ASC, createdAt DESC] (firebase/firestore.indexes.json).
      db
        .collection('friendRequests')
        .where('toUid', '==', uid)
        .where('status', '==', 'pending')
        .orderBy('createdAt', 'desc')
        .limit(MAX_PENDING_REQUESTS_RETURNED)
        .get(),
      // Same as incoming, for outgoing requests. Needs the composite index
      // [fromUid ASC, status ASC, createdAt DESC].
      db
        .collection('friendRequests')
        .where('fromUid', '==', uid)
        .where('status', '==', 'pending')
        .orderBy('createdAt', 'desc')
        .limit(MAX_PENDING_REQUESTS_RETURNED)
        .get(),
    ]);
  } catch (error) {
    if (isMissingIndexError(error)) {
      logger.error('friend.list is missing a Firestore composite index', {
        // The Firestore message embeds the console link that creates the index.
        firestoreMessage: (error as Error).message,
      });
      throw new HttpsError('unavailable', BACKEND_UNAVAILABLE_MESSAGE, {
        reason: REASON_BACKEND_UNAVAILABLE,
      });
    }
    throw error;
  }
}

export const list = onCall(CALLABLE_OPTS, async (request): Promise<ListFriendsResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseListInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const [friendsSnap, incomingSnap, outgoingSnap] = await readFriendGraph(actor.uid);

  const fallback = new Date(0);
  const toRequest = (doc: DocumentSnapshot): FriendRequestSummary =>
    toFriendRequestSummary(doc.id, doc.data()!, actor.uid, isoFrom(doc.data()?.createdAt, fallback));

  const friends = friendsSnap.docs
    .map((doc) => toFriendSummary(doc.id, doc.data(), isoFrom(doc.data()?.createdAt, fallback)))
    .sort((a, b) => a.friendsSince.localeCompare(b.friendsSince));

  const incoming = incomingSnap.docs.map(toRequest).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const outgoing = outgoingSnap.docs.map(toRequest).sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  return { friends, incoming, outgoing };
});
