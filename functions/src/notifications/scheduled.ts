/**
 * Notifications scheduled cleanup (Phase 9l).
 *
 * notifications-cleanupExpired (05:00 Europe/Stockholm, after the partner
 * insights jobs) enforces the mapping's retention windows over the whole
 * inbox via collection-group queries on `items`:
 *
 * - read notifications older than 7 days (readAt < cutoff) are deleted;
 * - unread notifications older than 30 days (createdAt < cutoff) are
 *   deleted.
 *
 * The runNotificationsCleanup runner is exported separately so emulator
 * tests can drive it deterministically (same pattern as the partner
 * insights runners).
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp, type Query } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { readRetentionCutoff, unreadRetentionCutoff } from './notifications-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

const CLEANUP_BATCH_SIZE = 500;

async function deleteInBatches(query: Query): Promise<number> {
  let deletedCount = 0;
  for (;;) {
    const expired = await query.limit(CLEANUP_BATCH_SIZE).get();
    if (expired.empty) {
      break;
    }
    const batch = db.batch();
    for (const doc of expired.docs) {
      batch.delete(doc.ref);
    }
    await batch.commit();
    deletedCount += expired.size;
    if (expired.size < CLEANUP_BATCH_SIZE) {
      break;
    }
  }
  return deletedCount;
}

/** Deletes read items past 7 days and unread items past 30 days. */
export async function runNotificationsCleanup(
  now: Date,
): Promise<{ deletedReadCount: number; deletedUnreadCount: number }> {
  const items = db.collectionGroup('items');

  const deletedReadCount = await deleteInBatches(
    items
      .where('read', '==', true)
      .where('readAt', '<', Timestamp.fromDate(readRetentionCutoff(now))),
  );
  const deletedUnreadCount = await deleteInBatches(
    items
      .where('read', '==', false)
      .where('createdAt', '<', Timestamp.fromDate(unreadRetentionCutoff(now))),
  );

  logger.info('Notifications cleanup complete', { deletedReadCount, deletedUnreadCount });
  return { deletedReadCount, deletedUnreadCount };
}

/** Daily retention sweep. */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '0 5 * * *',
  },
  withServerErrorReporting('notifications.cleanupExpired', async () => {
    await runNotificationsCleanup(new Date());
  }),
);
