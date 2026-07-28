/**
 * Account deletion hard-purge (Phase 9p stage 2).
 *
 * account-purgeDeleted (03:30 Europe/Stockholm, daily) processes pending
 * accountDeletionRequests older than the 30-day retention window:
 *
 * 1. Firestore document trees (users/{uid} incl. badges,
 *    userPrivate/{uid} incl. pushTokens, userLifecycle/{uid}
 *    (last-login + inactivity state), notifications/{uid} incl.
 *    items, pointsLedger/{uid} incl. entries, badgeProgress/{uid},
 *    userBlocks/{uid} incl. blocked, liveSessions/{uid} (the nearby-
 *    discovery doc: last coordinate + denormalized displayName)) via
 *    recursiveDelete.
 * 2. Owned documents by query (vehicles, rides where userId == uid).
 * 3. Social-graph MIRRORS — the rows other users' documents carry about
 *    the deleted user, which no owned-doc purge can reach: the mirror
 *    friendship rows users/{otherUid}/friends/{uid}, the friendRequests
 *    documents in both directions, the block rows
 *    userBlocks/{otherUid}/blocked/{uid} (plus the block graph's
 *    Realtime Database mirror under liveLocationBlocks/, on BOTH sides),
 *    and convoy membership (memberUids/members/memberProfiles, plus the
 *    stored summary's participantUids and the shared destination's
 *    setByDisplayName).
 * 4. Live-location state in Realtime Database: the liveLocation/{uid}
 *    subtree (whose `session` node — denormalized displayName, main-car
 *    snapshot, last recorded coordinate — is never deleted by stop /
 *    hide-me-now / the TTL sweep) and presence/{uid}.
 * 5. Chat erasure: the user's 1:1 DM conversations (conversation doc +
 *    messages subcollection) wholesale, and the community + convoy
 *    channel messages the user authored (by senderUid).
 * 6. Cloud Storage prefixes (profileImages/, vehicleImages/,
 *    rideRoutes/ under the uid).
 * 7. The Firebase Auth user is deleted.
 * 8. The request record flips to `processed` (processedAt stamped) and
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
import { adminAuth, adminRtdb, adminStorage, db } from '../firebase';
import {
  buildLeaveConvoyUpdate,
  computeConvoySummary,
  isConvoyMember,
} from '../convoy/convoy-core';
import {
  LIVE_LOCATION_BLOCKS_RTDB_ROOT,
  LIVE_LOCATION_RTDB_ROOTS,
  PURGE_BLOCK_MIRROR,
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

/**
 * Erases the deleted user from the BLOCK graph — the half the `userBlocks` doc
 * tree cannot reach, in both of the places the graph is stored (see
 * PURGE_BLOCK_MIRROR / LIVE_LOCATION_BLOCKS_RTDB_ROOT in deletion-core.ts for
 * the data shapes, the unblock-semantics argument, and the index argument):
 *
 * 1. RTDB `liveLocationBlocks/{uid}` — the deleted user's OWN outgoing edges,
 *    removed as one subtree. Done first and UNCONDITIONALLY, on a path derived
 *    from the uid alone: the Firestore rows that seeded it are purged with the
 *    `userBlocks` doc tree before this runs, so a retry after a partial purge
 *    has nothing left to read them from.
 * 2. `userBlocks/{otherUid}/blocked/{uid}` — every blocker's row naming the
 *    deleted user (with their denormalized displayName), found by a
 *    collection-group query on `blockedUserId`.
 * 3. RTDB `liveLocationBlocks/{otherUid}/{uid}` — the RTDB mirror of each row
 *    in (2). Removed BEFORE the Firestore row it was derived from, so a failure
 *    between the two leaves the row in place for the next run to find rather
 *    than orphaning the RTDB node; re-running just rewrites the same `null`.
 *
 * The PURGE_BLOCK_MIRROR.root guard keeps the collection-group query from ever
 * deleting a `blocked` subcollection that belongs to some other parent, the
 * same way CHANNEL_MESSAGE_ROOTS guards the message sweep.
 */
async function purgeBlockGraph(uid: string): Promise<void> {
  await adminRtdb.ref(`${LIVE_LOCATION_BLOCKS_RTDB_ROOT}/${uid}`).set(null);

  for (;;) {
    const snap = await db
      .collectionGroup(PURGE_BLOCK_MIRROR.collectionGroup)
      .where(PURGE_BLOCK_MIRROR.blockedField, '==', uid)
      .limit(QUERY_BATCH_SIZE)
      .get();
    const targets = snap.docs.filter(
      (docSnap) => docSnap.ref.parent.parent?.parent.id === PURGE_BLOCK_MIRROR.root,
    );
    // No mirror rows left to delete — stop (also prevents a re-fetch loop if
    // only non-userBlocks docs remain).
    if (targets.length === 0) break;

    const mirrorRemovals: Record<string, null> = {};
    for (const docSnap of targets) {
      // The blocker's uid is the PARENT document's id — a Firebase Auth uid, so
      // it can never contain the '/' that would re-target the multi-path update.
      mirrorRemovals[`${docSnap.ref.parent.parent!.id}/${uid}`] = null;
    }
    await adminRtdb.ref(LIVE_LOCATION_BLOCKS_RTDB_ROOT).update(mirrorRemovals);

    const batch = db.batch();
    for (const docSnap of targets) {
      batch.delete(docSnap.ref);
    }
    await batch.commit();
    if (snap.size < QUERY_BATCH_SIZE) break;
  }
}

/**
 * Removes the user's own LIVE-LOCATION state from Realtime Database — the
 * `liveLocation/{uid}` session/marker subtree and the `presence/{uid}` node (see
 * LIVE_LOCATION_RTDB_ROOTS in deletion-core.ts for what each carries and why the
 * session node would otherwise survive erasure forever).
 *
 * The Firestore half of this domain — the `liveSessions/{uid}` nearby-discovery
 * doc, which carries the last coordinate and the denormalized displayName — is
 * purged with the doc trees in purgeUserData, since it is a plain uid-keyed
 * document.
 *
 * Each root is removed at `{root}/{uid}` — a path built from the uid alone, so
 * there is nothing to read first and nothing a partial purge can orphan. Writing
 * `null` to a path that is already empty is a no-op, so this is idempotent.
 *
 * The roots are disjoint subtrees with no ordering between them — neither
 * removal reads anything the other writes — so they are issued in PARALLEL
 * rather than one await after another. A rejection still propagates and fails
 * the purge, leaving the request pending for the daily sweep to retry, exactly
 * as the sequential form did.
 */
async function purgeLiveLocationState(uid: string): Promise<void> {
  await Promise.all(
    LIVE_LOCATION_RTDB_ROOTS.map((root) => adminRtdb.ref(`${root}/${uid}`).set(null)),
  );
}

function toDate(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

/**
 * The stored end-of-convoy summary with `uid` removed from its participants, or
 * null when there is nothing to scrub.
 *
 * A convoy that was ALREADY `ended` before the purge carries a summary written
 * by convoy.end from the membership as it stood then — so it names the deleted
 * user in `participantUids` even after the membership maps are stripped.
 * `participantCount` is recomputed from the filtered list rather than left
 * alone, keeping the count == uids.length invariant every reader assumes (the
 * Android parser falls back to `participantUids.size` when the count is absent)
 * and matching what the owner branch's freshly computed summary yields.
 *
 * The membership sweep's `memberUids array-contains` query is sufficient to
 * find these: `participantUids` is a subset of the ACCEPTED members, and once
 * convoy.end has written the summary every membership mutation (leave, respond,
 * invite) rejects the convoy as `ended` — so a uid in a stored summary is still
 * in that convoy's memberUids.
 */
function scrubSummaryParticipants(summary: unknown, uid: string): Record<string, unknown> | null {
  if (!summary || typeof summary !== 'object') return null;
  const stored = summary as Record<string, unknown>;
  if (!Array.isArray(stored.participantUids)) return null;
  const participantUids = (stored.participantUids as unknown[]).filter(
    (participant) => participant !== uid,
  );
  if (participantUids.length === stored.participantUids.length) return null;
  return { participantUids, participantCount: participantUids.length };
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
 * Two further references to the deleted user live OUTSIDE the membership maps
 * and are scrubbed in the same atomic update:
 * - `summary.participantUids` on a convoy that was already `ended` — see
 *   scrubSummaryParticipants. (The owner branch above never needs this: it
 *   writes a fresh post-removal summary, and the two are mutually exclusive so
 *   the update never carries both `summary` and `summary.*` field paths.)
 * - `destination.setByDisplayName` when the deleted user set the shared
 *   destination — a denormalized display name exactly like memberProfiles'.
 *   Only the ATTRIBUTION is cleared: the coordinate + label are the group's
 *   record of where they were headed, and `setByUid` stays because
 *   toConvoyDestination/the Android parser DROP a destination with a blank
 *   setByUid, which would take the surviving members' destination with it.
 *
 * `ownerUid` is DELIBERATELY LEFT as the deleted uid (documented in
 * deletion-core.ts): the convoy is always `ended` by the time this returns, so
 * the field grants nothing (every owner-gated callable rejects an ended
 * convoy), it no longer resolves to anything (the user doc, profile and Auth
 * account are all gone, and the roster no longer lists them), and blanking it
 * would make the Android client discard the whole convoy row — deleting the
 * surviving members' record of their own drive.
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
        } else {
          // Already ended (by this owner earlier, or by someone else's convoy):
          // the stored summary still names the deleted user as a participant.
          const scrubbed = scrubSummaryParticipants(data.summary, uid);
          if (scrubbed) {
            update['summary.participantUids'] = scrubbed.participantUids;
            update['summary.participantCount'] = scrubbed.participantCount;
          }
        }
        const destination = data.destination;
        if (
          destination &&
          typeof destination === 'object' &&
          (destination as Record<string, unknown>).setByUid === uid
        ) {
          update['destination.setByDisplayName'] = null;
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
  await purgeBlockGraph(uid);
  await removeConvoyMemberships(uid);
  // Live-location state the doc-tree purge cannot reach: the RTDB session/marker
  // subtree (its `session` node is never deleted by stop/hide/sweep) and the
  // presence node. The Firestore discovery doc went with the doc trees above.
  //
  // Placed AFTER the three mirror sweeps so the call order matches the numbered
  // phases in this file's KDoc (3 = social-graph mirrors, 4 = live location).
  // The position carries no dependency either way: these paths are derived from
  // the uid alone and live in RTDB, which none of the mirror sweeps above reads
  // or writes except for their own liveLocationBlocks subtree.
  await purgeLiveLocationState(uid);
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
