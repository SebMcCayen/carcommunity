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
 * 3. Chat erasure: the user's 1:1 DM conversations (conversation doc +
 *    messages subcollection) wholesale, and the community + convoy
 *    channel messages the user authored (by senderUid).
 * 4. Cloud Storage prefixes (profileImages/, vehicleImages/,
 *    rideRoutes/ under the uid).
 * 5. The Firebase Auth user is deleted.
 * 6. The request record flips to `processed` (processedAt stamped) and
 *    is RETAINED as the proof-of-deletion record.
 *
 * Deliberately retained data is documented in deletion-core.ts
 * (moderation/audit history, hashed insight events, claim audit keys,
 * event-chat/RSVP community-context records).
 *
 * runAccountPurge is exported for deterministic emulator tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { adminAuth, adminStorage, db } from '../firebase';
import {
  PURGE_DOC_TREES,
  PURGE_OWNED_COLLECTIONS,
  PURGE_STORAGE_PREFIXES,
  deletionRetentionCutoff,
} from './deletion-core';

const QUERY_BATCH_SIZE = 500;
/** Upper bound of accounts purged per sweep — keeps a backlog from
 * pushing the scheduled run past its timeout; the daily sweep drains
 * any remainder on subsequent runs (oldest first). */
const MAX_PURGES_PER_RUN = 25;

async function deleteOwnedDocuments(collection: string, userField: string, uid: string) {
  for (;;) {
    const snap = await db
      .collection(collection)
      .where(userField, '==', uid)
      .limit(QUERY_BATCH_SIZE)
      .get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const docSnap of snap.docs) {
      batch.delete(docSnap.ref);
    }
    await batch.commit();
    if (snap.size < QUERY_BATCH_SIZE) break;
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
