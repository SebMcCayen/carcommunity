/**
 * Diagnostics scheduled cleanup (Phase 9n).
 *
 * - diagnostics-cleanupExpired (06:00 Europe/Stockholm on the 1st of each
 *   month — the mapping's monthly cadence) deletes diagnosticsReports older
 *   than the 90-day retention window and runs the rate-limit sweep.
 * - diagnostics-cleanupRateLimits runs hourly to enforce the 1-hour
 *   retention on diagnosticsRateLimits so the collection can't grow
 *   unbounded under high load or spoofed-IP attacks.
 *
 * The runDiagnosticsCleanup and runRateLimitCleanup runners are exported for
 * deterministic emulator tests (9j pattern).
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { Timestamp } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { diagnosticsRetentionCutoff } from './diagnostics-core';

const CLEANUP_BATCH_SIZE = 500;
/** Rate-limit documents older than this are safe to delete. */
const RATE_LIMIT_RETENTION_MS = 60 * 60 * 1000; // 1 hour

/** Deletes rate-limit counter documents older than 1 hour, batch by batch. */
export async function runRateLimitCleanup(now: Date): Promise<{ rateLimitDeleted: number }> {
  const rateLimitCutoff = Timestamp.fromMillis(now.getTime() - RATE_LIMIT_RETENTION_MS);
  let rateLimitDeleted = 0;
  for (;;) {
    const expired = await db
      .collection('diagnosticsRateLimits')
      .where('createdAt', '<', rateLimitCutoff)
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
    rateLimitDeleted += expired.size;
    if (expired.size < CLEANUP_BATCH_SIZE) {
      break;
    }
  }
  return { rateLimitDeleted };
}

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

  const { rateLimitDeleted } = await runRateLimitCleanup(now);
  logger.info('Diagnostics cleanup complete', { deletedCount, rateLimitDeleted });
  return { deletedCount };
}

/** Monthly retention sweep (mapping: "Clean old diagnostics — Monthly"). */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 300,
    schedule: '0 6 1 * *',
  },
  async () => {
    await runDiagnosticsCleanup(new Date());
  },
);

/**
 * Hourly rate-limit sweep — enforces the 1-hour retention on
 * `diagnosticsRateLimits` so the collection stays bounded even under
 * high request volume or IP-spoofing attacks.
 */
export const cleanupRateLimits = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '0 * * * *',
  },
  async () => {
    const { rateLimitDeleted } = await runRateLimitCleanup(new Date());
    logger.info('Diagnostics rate-limit cleanup complete', { rateLimitDeleted });
  },
);
