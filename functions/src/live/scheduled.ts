/**
 * Live location TTL sweep (Phase 10).
 *
 * live-cleanupExpired (every 5 minutes — the mapping's cadence):
 * - sessions past expiresAt flip to status `expired` and their `latest`
 *   marker is removed;
 * - `latest` nodes whose recordedAt is older than 15 minutes are removed
 *   even if the session is nominally active (client went silent — the
 *   mapping's "15-minute maximum" position TTL);
 * - the queryable nearby-discovery docs (Firestore liveSessions/{uid}) are
 *   swept too: any doc past its own expiresAt is deleted, so a sharer who
 *   went silent stops being discoverable by live.listNearby (which already
 *   HIDES expired docs, but they must not accumulate). This is the
 *   Firestore-side TTL, the counterpart to the RTDB marker removal above.
 *
 * A full scan of liveLocation/ is deliberate: at MVP scale (tens of
 * concurrent sharers, lean nodes) it is cheaper and simpler than
 * maintaining index nodes. runLiveCleanup is exported for tests.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions';
import { Timestamp } from 'firebase-admin/firestore';
import { adminRtdb, db } from '../firebase';
import { isLatestStale, isSessionActive, type LiveSession } from './live-core';

/** Max discovery docs deleted per sweep (bounded; the 5-min cadence catches up). */
const DISCOVERY_SWEEP_LIMIT = 400;

/**
 * Deletes nearby-discovery docs (liveSessions) whose expiresAt has passed, plus
 * any explicitly named uids (sessions the RTDB sweep just expired/removed).
 * Returns the number deleted. Bounded per run.
 */
export async function sweepDiscoveryDocs(now: Date, removedUids: Iterable<string>): Promise<number> {
  const toDelete = new Set<string>(removedUids);

  // Firestore-side TTL: docs past their own expiresAt. Bounded by the query
  // limit; the 5-minute cadence drains any backlog over successive runs.
  const expired = await db
    .collection('liveSessions')
    .where('expiresAt', '<=', Timestamp.fromDate(now))
    .limit(DISCOVERY_SWEEP_LIMIT)
    .get();
  for (const doc of expired.docs) toDelete.add(doc.id);

  if (toDelete.size === 0) return 0;
  // Chunk into batches (Firestore caps a write batch at 500). delete() on a
  // missing doc is a no-op, so naming an already-gone uid is harmless.
  const ids = [...toDelete];
  for (let i = 0; i < ids.length; i += 400) {
    const batch = db.batch();
    for (const id of ids.slice(i, i + 400)) {
      batch.delete(db.collection('liveSessions').doc(id));
    }
    await batch.commit();
  }
  return toDelete.size;
}

interface LiveUserNode {
  session?: LiveSession;
  latest?: { recordedAt?: string };
}

export async function runLiveCleanup(
  now: Date,
): Promise<{ expiredSessions: number; removedMarkers: number; removedDiscoveryDocs: number }> {
  const snapshot = await adminRtdb.ref('liveLocation').get();
  const users = (snapshot.val() ?? {}) as Record<string, LiveUserNode>;

  let expiredSessions = 0;
  let removedMarkers = 0;
  const updates: Record<string, unknown> = {};
  // Uids whose live marker was removed this sweep — their discovery doc must go
  // too, even if its own expiresAt has not been reached yet (a session that
  // expired on duration before the discovery TTL window elapsed).
  const removedUids = new Set<string>();

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
      removedUids.add(uid);
      continue;
    }
    // Numeric comparison — producers may emit non-canonical ISO strings.
    if (node.latest?.recordedAt && isLatestStale(node.latest.recordedAt, now)) {
      updates[`${uid}/latest`] = null;
      removedMarkers += 1;
      removedUids.add(uid);
    }
  }

  if (Object.keys(updates).length > 0) {
    await adminRtdb.ref('liveLocation').update(updates);
  }

  const removedDiscoveryDocs = await sweepDiscoveryDocs(now, removedUids);

  logger.info('Live location cleanup complete', {
    expiredSessions,
    removedMarkers,
    removedDiscoveryDocs,
  });
  return { expiredSessions, removedMarkers, removedDiscoveryDocs };
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
