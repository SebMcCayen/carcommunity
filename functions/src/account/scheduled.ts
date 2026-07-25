/**
 * Account deletion hard-purge (Phase 9p stage 2).
 *
 * account-purgeDeleted (03:30 Europe/Stockholm, daily) processes pending
 * accountDeletionRequests older than the 30-day retention window:
 *
 * 1. Firestore document trees (users/{uid} incl. badges,
 *    userPrivate/{uid} incl. pushTokens, userLifecycle/{uid}
 *    (last-login + inactivity state), notifications/{uid} incl.
 *    items, pointsLedger/{uid} incl. entries) via recursiveDelete.
 * 2. Owned documents by query (vehicles, rides where userId == uid).
 * 3. Social-graph MIRRORS — the rows other users' documents carry about
 *    the deleted user, which no owned-doc purge can reach: the mirror
 *    friendship rows users/{otherUid}/friends/{uid}, the friendRequests
 *    documents in both directions, and convoy membership
 *    (memberUids/members/memberProfiles).
 * 4. Chat erasure: the user's 1:1 DM conversations (conversation doc +
 *    messages subcollection) wholesale, and the community + convoy
 *    channel messages the user authored (by senderUid).
 * 5. Cloud Storage prefixes (profileImages/, vehicleImages/,
 *    rideRoutes/ under the uid).
 * 6. The Firebase Auth user is deleted.
 * 7. The request record flips to `processed` (processedAt stamped) and
 *    is RETAINED as the proof-of-deletion record.
 *
 * This is the SINGLE purge routine for BOTH erasure paths: the
 * inactive-account sweep (account/inactivityCleanup.ts) calls
 * purgeUserData too, so anything added here covers both.
 *
 * Deliberately retained data is documented in deletion-core.ts
 * (moderation/audit history, hashed insight events, claim audit keys,
 * event-chat/RSVP community-context records).
 *
 * runAccountPurge is exported for deterministic emulator tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import type { Query } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, adminStorage, db } from '../firebase';
import {
  buildLeaveConvoyUpdate,
  computeConvoySummary,
  isConvoyMember,
} from '../convoy/convoy-core';
import {
  PURGE_CONVOY_MEMBERSHIP,
  PURGE_DOC_TREES,
  PURGE_FRIEND_MIRROR,
  PURGE_FRIEND_REQUEST_USER_FIELDS,
  PURGE_OWNED_COLLECTIONS,
  PURGE_STORAGE_PREFIXES,
  deletionRetentionCutoff,
} from './deletion-core';

const QUERY_BATCH_SIZE = 500;
/** Upper bound of accounts purged per sweep — keeps a backlog from
 * pushing the scheduled run past its timeout; the daily sweep drains
 * any remainder on subsequent runs (oldest first). */
const MAX_PURGES_PER_RUN = 25;

/**
 * Deletes every document a query matches, QUERY_BATCH_SIZE at a time. The query
 * is RE-RUN each page rather than cursored: the previous page's documents no
 * longer match once deleted, so the same query naturally walks the remainder.
 */
async function deleteMatching(query: Query): Promise<void> {
  for (;;) {
    const snap = await query.limit(QUERY_BATCH_SIZE).get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const docSnap of snap.docs) {
      batch.delete(docSnap.ref);
    }
    await batch.commit();
    if (snap.size < QUERY_BATCH_SIZE) break;
  }
}

async function deleteOwnedDocuments(collection: string, userField: string, uid: string) {
  await deleteMatching(db.collection(collection).where(userField, '==', uid));
}

/**
 * Erases the deleted user from the FRIEND GRAPH's mirror side — the half no
 * owned-doc purge can reach (see PURGE_FRIEND_MIRROR /
 * PURGE_FRIEND_REQUEST_USER_FIELDS in deletion-core.ts for the data shapes and
 * the index argument):
 *
 * 1. `users/{otherUid}/friends/{uid}` — every remaining friend's row naming the
 *    deleted user (with their denormalized displayName/avatarPath). Found by a
 *    collection-group query on `friendUid`, so the sweep does NOT depend on the
 *    deleted user's own (already purged) friend list and stays correct when a
 *    partially-failed purge is retried.
 * 2. `friendRequests/{requestId}` in BOTH directions — pair-keyed docs owned by
 *    neither side, which would otherwise keep appearing in the other party's
 *    pending incoming/outgoing lists.
 */
async function deleteFriendGraphMirror(uid: string): Promise<void> {
  await deleteMatching(
    db
      .collectionGroup(PURGE_FRIEND_MIRROR.collectionGroup)
      .where(PURGE_FRIEND_MIRROR.friendField, '==', uid),
  );
  for (const userField of PURGE_FRIEND_REQUEST_USER_FIELDS) {
    await deleteOwnedDocuments('friendRequests', userField, uid);
  }
}

function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

/**
 * Removes the deleted user from every convoy they are a member of. Membership
 * lives on the CONVOY document (memberUids + members/memberProfiles, the last
 * holding displayName/avatarPath), so it survives the owned-doc purge exactly
 * like the friend mirror does.
 *
 * Three cases, in the order they are tested:
 * - Sole member (a convoy nobody else is in): the document is DELETED outright.
 *   Emptying it instead would leave an unreadable husk still naming the deleted
 *   uid in its summary.
 * - OWNER of a convoy that has not ended: the convoy is ENDED (the same
 *   status/endedAt/summary write convoy.end performs) as well as stripped.
 *   Every lifecycle transition is owner-gated with no succession rule, so
 *   merely removing the owner would strand the remaining members with an
 *   "active" convoy nobody can ever end. The summary is computed from the
 *   POST-removal membership so the deleted user is not listed as a participant.
 * - Otherwise: membership entries are stripped and the convoy is left alone.
 *
 * The strip uses arrayRemove/FieldValue.delete rather than rewriting the three
 * collections wholesale, so a concurrent invite adding OTHER members cannot be
 * clobbered. The transaction re-reads the document because the outer query
 * snapshot may be stale by the time the write lands.
 */
async function removeConvoyMemberships(uid: string): Promise<void> {
  for (;;) {
    const snap = await db
      .collection(PURGE_CONVOY_MEMBERSHIP.collection)
      .where(PURGE_CONVOY_MEMBERSHIP.memberField, 'array-contains', uid)
      .limit(QUERY_BATCH_SIZE)
      .get();
    if (snap.empty) break;

    let mutated = 0;
    for (const convoySnap of snap.docs) {
      await db.runTransaction(async (tx) => {
        const fresh = await tx.get(convoySnap.ref);
        // Already handled by a concurrent write / an earlier retry of this purge.
        if (!fresh.exists || !isConvoyMember(fresh.data(), uid)) return;
        const data = fresh.data()!;
        const remaining = buildLeaveConvoyUpdate(data, uid);

        if (remaining.memberUids.length === 0) {
          tx.delete(fresh.ref);
          mutated += 1;
          return;
        }

        // Dotted map paths are safe here: a Firebase Auth uid is alphanumeric
        // and can never contain the '.' that would re-interpret the path.
        const update: Record<string, unknown> = {
          [PURGE_CONVOY_MEMBERSHIP.memberField]: FieldValue.arrayRemove(uid),
          [`members.${uid}`]: FieldValue.delete(),
          [`memberProfiles.${uid}`]: FieldValue.delete(),
        };
        if (data.ownerUid === uid && data.status !== 'ended') {
          const endedAt = Timestamp.fromDate(new Date());
          update.status = 'ended';
          update.endedAt = endedAt;
          update.summary = computeConvoySummary(
            // Post-removal membership, so the deleted user is not recorded as a
            // participant of the drive their own erasure ended.
            { ...data, members: remaining.members },
            endedAt.toDate(),
            toDate,
          );
        }
        tx.update(fresh.ref, update);
        mutated += 1;
      });
    }

    // Defensive: the query re-runs each page and only terminates because the
    // updated convoys stop matching. If a whole page produced no write, stop
    // rather than spin.
    if (mutated === 0 || snap.size < QUERY_BATCH_SIZE) break;
  }
}

/** Top-level chat-channel roots whose authored messages the erasure purges. */
const CHANNEL_MESSAGE_ROOTS = new Set(['communityChat', 'convoyChats']);

/**
 * Erases the user's 1:1 DM conversations wholesale — the conversation document
 * AND its `messages` subcollection (recursiveDelete) — for every
 * conversations/{pairId} the user is a member of. A 1:1 conversation only exists
 * for its two participants, so removing it erases the user's DM footprint (the
 * other participant's copy of that thread goes too — correct for erasure).
 */
async function deleteDmConversations(uid: string): Promise<void> {
  for (;;) {
    const snap = await db
      .collection('conversations')
      .where('members', 'array-contains', uid)
      .limit(QUERY_BATCH_SIZE)
      .get();
    if (snap.empty) break;
    for (const convo of snap.docs) {
      await db.recursiveDelete(convo.ref);
    }
    if (snap.size < QUERY_BATCH_SIZE) break;
  }
}

/**
 * Erases the community + convoy channel messages the user AUTHORED, via a
 * `messages` collection-group query on senderUid (the field only community,
 * convoy, and DM messages carry — event chat keys on authorUserId, so it is
 * untouched). DM conversation messages are purged wholesale by
 * deleteDmConversations first; the CHANNEL_MESSAGE_ROOTS guard additionally
 * restricts deletes to the community/convoy roots so a stray DM message can
 * never be caught here. Batched in QUERY_BATCH_SIZE pages.
 */
async function deleteAuthoredChannelMessages(uid: string): Promise<void> {
  for (;;) {
    const snap = await db
      .collectionGroup('messages')
      .where('senderUid', '==', uid)
      .limit(QUERY_BATCH_SIZE)
      .get();
    const targets = snap.docs.filter((docSnap) =>
      CHANNEL_MESSAGE_ROOTS.has(docSnap.ref.parent.parent?.parent.id ?? ''),
    );
    // No community/convoy messages left to delete — stop (also prevents a
    // re-fetch loop if only non-channel docs remain).
    if (targets.length === 0) break;
    const batch = db.batch();
    for (const docSnap of targets) {
      batch.delete(docSnap.ref);
    }
    await batch.commit();
    if (snap.size < QUERY_BATCH_SIZE) break;
  }
}

/** Purges one user's data per the plan. Idempotent — safe to re-run. */
export async function purgeUserData(uid: string): Promise<void> {
  for (const collection of PURGE_DOC_TREES) {
    await db.recursiveDelete(db.collection(collection).doc(uid));
  }
  for (const { collection, userField } of PURGE_OWNED_COLLECTIONS) {
    await deleteOwnedDocuments(collection, userField, uid);
  }
  // Social-graph mirrors: the rows OTHER users' documents carry about this
  // user, which the doc-tree/owned-doc purges above cannot reach.
  await deleteFriendGraphMirror(uid);
  await removeConvoyMemberships(uid);
  // Chat erasure: DM conversations wholesale, then authored community + convoy
  // messages. DMs go first so no DM message survives into the channel sweep.
  await deleteDmConversations(uid);
  await deleteAuthoredChannelMessages(uid);
  for (const prefix of PURGE_STORAGE_PREFIXES(uid)) {
    try {
      await adminStorage.bucket().deleteFiles({ prefix });
    } catch (error) {
      // Storage cleanup is best-effort per prefix; the sweep re-runs daily.
      logger.warn('Storage purge failed for prefix', { prefix, error: String(error) });
    }
  }
  await adminAuth.deleteUser(uid).catch((error) => {
    // Already deleted on a previous partial run — idempotency.
    if ((error as { code?: string }).code !== 'auth/user-not-found') throw error;
  });
}

/** Processes pending requests past the retention window. */
export async function runAccountPurge(
  now: Date,
): Promise<{ purgedCount: number; purgedUids: string[] }> {
  const due = await db
    .collection('accountDeletionRequests')
    .where('status', '==', 'pending')
    .where('createdAt', '<', Timestamp.fromDate(deletionRetentionCutoff(now)))
    .orderBy('createdAt', 'asc')
    .limit(MAX_PURGES_PER_RUN)
    .get();

  const purgedUids: string[] = [];
  for (const requestSnap of due.docs) {
    const uid = requestSnap.id;
    try {
      await purgeUserData(uid);
      await requestSnap.ref.update({
        status: 'processed',
        processedAt: FieldValue.serverTimestamp(),
      });
      purgedUids.push(uid);
    } catch (error) {
      // Leave the request pending; the daily sweep retries. purgeUserData
      // is idempotent, so a partial purge completes on the next run.
      logger.error('Account purge failed; will retry next run', {
        uid,
        error: String(error),
      });
    }
  }

  logger.info('Account purge complete', { purgedCount: purgedUids.length });
  return { purgedCount: purgedUids.length, purgedUids };
}

/** Daily purge of due deletion requests. */
export const purgeDeleted = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '512MiB' as const,
    timeoutSeconds: 540,
    schedule: '30 3 * * *',
  },
  async () => {
    await runAccountPurge(new Date());
  },
);
