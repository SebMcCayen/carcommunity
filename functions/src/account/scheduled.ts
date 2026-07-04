/**
 * Account deletion hard-purge (Phase 9p stage 2).
 *
 * account-purgeDeleted (03:30 Europe/Stockholm, daily) processes pending
 * accountDeletionRequests older than the 30-day retention window:
 *
 * 1. Firestore document trees (users/{uid} incl. badges,
 *    userPrivate/{uid} incl. pushTokens, notifications/{uid} incl.
 *    items, pointsLedger/{uid} incl. entries) via recursiveDelete.
 * 2. Owned documents by query (vehicles, rides where userId == uid).
 * 3. Cloud Storage prefixes (profileImages/, vehicleImages/,
 *    rideRoutes/ under the uid).
 * 4. The Firebase Auth user is deleted.
 * 5. The request record flips to `processed` (processedAt stamped) and
 *    is RETAINED as the proof-of-deletion record.
 *
 * Deliberately retained data is documented in deletion-core.ts
 * (moderation/audit history, hashed insight events, claim audit keys,
 * community-context chat/RSVPs).
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

/** Purges one user's data per the plan. Idempotent — safe to re-run. */
export async function purgeUserData(uid: string): Promise<void> {
  for (const collection of PURGE_DOC_TREES) {
    await db.recursiveDelete(db.collection(collection).doc(uid));
  }
  for (const { collection, userField } of PURGE_OWNED_COLLECTIONS) {
    await deleteOwnedDocuments(collection, userField, uid);
  }
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
