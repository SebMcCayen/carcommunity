/**
 * Diagnostics scheduled cleanup (Phase 9n).
 *
 * diagnostics-cleanupExpired (06:00 Europe/Stockholm on the 1st of each
 * month — the mapping's monthly cadence) deletes diagnosticsReports older
 * than the 90-day retention window. The runDiagnosticsCleanup runner is
 * exported for deterministic emulator tests (9j pattern).
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { diagnosticsRetentionCutoff } from './diagnostics-core';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';

const CLEANUP_BATCH_SIZE = 500;

/** Deletes reports past the 90-day retention window, batch by batch. */
export async function runDiagnosticsCleanup(now: Date): Promise<{ deletedCount: number }> {
  const cutoff = Timestamp.fromDate(diagnosticsRetentionCutoff(now));
  let deletedCount = 0;
  for (;;) {
    const expired = await db
      .collection('diagnosticsReports')
      .where('createdAt', '<', cutoff)
      .limit(CLEANUP_BATCH_SIZE)
      .get();
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

  logger.info('Diagnostics cleanup complete', { deletedCount });
  return { deletedCount };
}

/** Monthly retention sweep (mapping: "Clean old diagnostics — Monthly"). */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '0 6 1 * *',
  },
  withServerErrorReporting('diagnostics.cleanupExpired', async () => {
    await runDiagnosticsCleanup(new Date());
  }),
);
