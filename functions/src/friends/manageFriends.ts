/**
 * friend.sendRequest / friend.respondRequest / friend.remove / friend.list —
 * member-gated callables (contracts/functions/functions.json).
 *
 * Deployed via the `friend` export group (functions/src/index.ts) as
 * `friend-sendRequest`, `friend-respondRequest`, `friend-remove`,
 * `friend-list`. This is the
 * friend-GRAPH foundation only — direct messaging/DMs are a separate
 * follow-up and are intentionally NOT built here.
 *
 * Invariants:
 *  - Backend is the sole writer of friendRequests and users/{uid}/friends
 *    (firebase/firestore.rules grants owner reads, denies all client writes).
 *  - Established friendship (users/{uid}/friends/{friendUid}) — not request
 *    status — is the source of truth for "already friends", so re-friending
 *    after a decline or a removal always works.
 *  - Blocking is honoured in BOTH directions: if either party has blocked the
 *    other, a request is rejected with a neutral failed-precondition
 *    (NOT_ADDABLE) that never reveals who blocked whom.
 *  - Nickname (displayName) is NOT unique: sendRequest resolves an exact
 *    match; 0 → not-found, 1 → proceed, >1 → failed-precondition
 *    (AMBIGUOUS_NICKNAME) carrying a candidate list in the error details so
 *    the client can re-call with a resolved { toUid }.
 */

import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import type { DocumentSnapshot } from 'firebase-admin/firestore';
import { db } from '../firebase';
import { requireMemberActor } from '../shared/memberActor';
import { toUserAccessState } from '../shared/access';
import {
  ALREADY_FRIENDS_MESSAGE,
  AMBIGUOUS_NICKNAME_MESSAGE,
  NICKNAME_NOT_FOUND_MESSAGE,
  NOT_ADDABLE_MESSAGE,
  REQUEST_ALREADY_SENT_MESSAGE,
  REQUEST_NOT_FOUND_MESSAGE,
  REQUEST_NOT_PENDING_MESSAGE,
  SELF_REQUEST_MESSAGE,
  buildFriendRequestDocument,
  buildFriendshipDocument,
  friendRequestId,
  parseListInput,
  parseRemoveFriendInput,
  parseRespondRequestInput,
  parseSendRequestInput,
  toFriendRequestSummary,
  toFriendSummary,
  toProfileProjection,
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
 * soft-deleted (callers surface not-found so deletion can't be probed).
 */
async function loadProfile(uid: string): Promise<ProfileProjection | null> {
  const snap = await db.collection('users').doc(uid).get();
  if (!snap.exists || toUserAccessState(snap.data()).deleted) {
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
 */
async function resolveTarget(
  callerUid: string,
  input: { nickname?: string; toUid?: string },
): Promise<{ uid: string; profile: ProfileProjection }> {
  if (input.toUid !== undefined) {
    const profile = await loadProfile(input.toUid);
    if (!profile) {
      throw new HttpsError('not-found', NICKNAME_NOT_FOUND_MESSAGE);
    }
    if (input.toUid === callerUid) {
      throw new HttpsError('invalid-argument', SELF_REQUEST_MESSAGE);
    }
    return { uid: input.toUid, profile };
  }

  // Nickname path: displayName is NOT unique. Exact match, then filter out the
  // caller and soft-deleted accounts.
  const nickname = input.nickname as string;
  const query = await db
    .collection('users')
    .where('displayName', '==', nickname)
    .limit(AMBIGUOUS_CANDIDATE_LIMIT + 2)
    .get();

  const matches = query.docs.filter(
    (doc) => doc.id !== callerUid && !toUserAccessState(doc.data()).deleted,
  );

  if (matches.length === 0) {
    throw new HttpsError('not-found', NICKNAME_NOT_FOUND_MESSAGE);
  }
  if (matches.length > 1) {
    const candidates: NicknameCandidate[] = matches.slice(0, AMBIGUOUS_CANDIDATE_LIMIT).map((doc) => {
      const projection = toProfileProjection(doc.data());
      return { uid: doc.id, displayName: projection.displayName, avatarPath: projection.avatarPath };
    });
    throw new HttpsError('failed-precondition', AMBIGUOUS_NICKNAME_MESSAGE, {
      reason: 'AMBIGUOUS_NICKNAME',
      candidates,
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
    throw new HttpsError('invalid-argument', SELF_REQUEST_MESSAGE);
  }

  if (await isBlockedEitherWay(actor.uid, targetUid)) {
    // Neutral — never reveals which side blocked (privacy).
    throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE);
  }

  const callerProfile = await loadProfile(actor.uid);
  if (!callerProfile) {
    // The actor gate already loaded users/{caller}; a missing profile here is
    // an inconsistent state rather than a client error.
    throw new HttpsError('failed-precondition', NOT_ADDABLE_MESSAGE);
  }

  const now = new Date();
  const outgoingRef = friendRequestRef(actor.uid, targetUid);
  const incomingRef = friendRequestRef(targetUid, actor.uid);
  const callerFriendRef = friendshipRef(actor.uid, targetUid);
  const targetFriendRef = friendshipRef(targetUid, actor.uid);

  return db.runTransaction<SendRequestResult>(async (tx) => {
    const [alreadyFriend, outgoing, incoming] = await Promise.all([
      tx.get(callerFriendRef),
      tx.get(outgoingRef),
      tx.get(incomingRef),
    ]);

    if (alreadyFriend.exists) {
      throw new HttpsError('already-exists', ALREADY_FRIENDS_MESSAGE);
    }

    // The other party already has a pending request to the caller → befriend
    // immediately instead of stacking a mirror request (no stuck state).
    if (incoming.exists && incoming.data()?.status === 'pending') {
      tx.set(callerFriendRef, buildFriendshipDocument(targetUid, targetProfile, () => Timestamp.fromDate(now)));
      tx.set(targetFriendRef, buildFriendshipDocument(actor.uid, callerProfile, () => Timestamp.fromDate(now)));
      tx.set(incomingRef, { status: 'accepted', updatedAt: Timestamp.fromDate(now) }, { merge: true });
      return {
        status: 'friends',
        friend: toFriendSummary(targetUid, { displayName: targetProfile.displayName, avatarPath: targetProfile.avatarPath }, now.toISOString()),
      };
    }

    if (outgoing.exists && outgoing.data()?.status === 'pending') {
      throw new HttpsError('already-exists', REQUEST_ALREADY_SENT_MESSAGE);
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

  return db.runTransaction<RespondRequestResult>(async (tx) => {
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
      return { status: 'declined' };
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
      status: 'accepted',
      friend: toFriendSummary(fromUid, { displayName: fromProfile.displayName, avatarPath: fromProfile.avatarPath }, now.toISOString()),
    };
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

export const list = onCall(CALLABLE_OPTS, async (request): Promise<ListFriendsResult> => {
  const actor = await requireMemberActor(request);

  const parsed = parseListInput(request.data);
  if (!parsed.ok) {
    throw new HttpsError('invalid-argument', parsed.message);
  }

  const [friendsSnap, incomingSnap, outgoingSnap] = await Promise.all([
    db.collection('users').doc(actor.uid).collection('friends').get(),
    db
      .collection('friendRequests')
      .where('toUid', '==', actor.uid)
      .where('status', '==', 'pending')
      .get(),
    db
      .collection('friendRequests')
      .where('fromUid', '==', actor.uid)
      .where('status', '==', 'pending')
      .get(),
  ]);

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
