/**
 * Incidents TTL sweep (navigation feature).
 *
 * incidents-cleanupExpired (every 15 minutes): deletes incidents whose
 * `expiresAt` has passed — and their `confirmations` sub-collection with them
 * (recursiveDelete). Short-lived crowd-sourced markers must
 * disappear promptly; the read rule already hides expired docs (status +
 * `expiresAt > request.time`), and this sweep reclaims them.
 *
 * runIncidentsCleanup is exported so emulator tests can drive it
 * deterministically.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';

const CLEANUP_BATCH_SIZE = 400;

/** Deletes expired incidents, batch by batch. */
export async function runIncidentsCleanup(now: Date): Promise<{ deletedCount: number }> {
  let deletedCount = 0;
  for (;;) {
    const expired = await db
      .collection('incidents')
      .where('expiresAt', '<=', Timestamp.fromDate(now))
      .limit(CLEANUP_BATCH_SIZE)
      .get();
    if (expired.empty) {
      break;
    }
    // recursiveDelete, NOT a batched `delete` of the doc: deleting a Firestore
    // document does not touch its sub-collections, so a plain batch delete
    // would leave every `confirmations/{uid}` doc behind as an unreachable
    // orphan that nothing ever collects. recursiveDelete removes the incident
    // and its confirmation ledger together.
    await Promise.all(expired.docs.map((doc) => db.recursiveDelete(doc.ref)));
    deletedCount += expired.size;
    if (expired.size < CLEANUP_BATCH_SIZE) {
      break;
    }
  }

  logger.info('Incidents cleanup complete', { deletedCount });
  return { deletedCount };
}

/** 15-minute TTL sweep. */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '*/15 * * * *',
  },
  async () => {
    await runIncidentsCleanup(new Date());
  },
);
