/**
 * Live location TTL sweep (Phase 10).
 *
 * live-cleanupExpired (every 5 minutes — the mapping's cadence):
 * - sessions past expiresAt flip to status `expired` and their `latest`
 *   marker is removed;
 * - `latest` nodes whose recordedAt is older than 15 minutes are removed
 *   even if the session is nominally active (client went silent — the
 *   mapping's "15-minute maximum" position TTL).
 *
 * A full scan of liveLocation/ is deliberate: at MVP scale (tens of
 * concurrent sharers, lean nodes) it is cheaper and simpler than
 * maintaining index nodes. runLiveCleanup is exported for tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import { adminRtdb } from '../firebase';
import { isLatestStale, isSessionActive, type LiveSession } from './live-core';

interface LiveUserNode {
  session?: LiveSession;
  latest?: { recordedAt?: string };
}

export async function runLiveCleanup(
  now: Date,
): Promise<{ expiredSessions: number; removedMarkers: number }> {
  const snapshot = await adminRtdb.ref('liveLocation').get();
  const users = (snapshot.val() ?? {}) as Record<string, LiveUserNode>;

  let expiredSessions = 0;
  let removedMarkers = 0;
  const updates: Record<string, unknown> = {};

  for (const [uid, node] of Object.entries(users)) {
    const session = node.session;
    if (session && session.status === 'active' && !isSessionActive(session, now)) {
      updates[`${uid}/session/status`] = 'expired';
      updates[`${uid}/session/stoppedAt`] = now.toISOString();
      expiredSessions += 1;
      if (node.latest) {
        updates[`${uid}/latest`] = null;
        removedMarkers += 1;
      }
      continue;
    }
    // Numeric comparison — producers may emit non-canonical ISO strings.
    if (node.latest?.recordedAt && isLatestStale(node.latest.recordedAt, now)) {
      updates[`${uid}/latest`] = null;
      removedMarkers += 1;
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminRtdb.ref('liveLocation').update(updates);
  }

  logger.info('Live location cleanup complete', { expiredSessions, removedMarkers });
  return { expiredSessions, removedMarkers };
}

/** 5-minute TTL sweep (mapping cadence). */
export const cleanupExpired = onSchedule(
  {
    region: 'europe-west1',
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '*/5 * * * *',
  },
  async () => {
    await runLiveCleanup(new Date());
  },
);
