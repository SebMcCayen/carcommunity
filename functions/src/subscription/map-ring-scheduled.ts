import { Timestamp } from 'firebase-admin/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { db } from '../firebase';
import { reconcileSupporterBadge } from './supporter-badge';
import { runMapRingRefresh } from './map-ring-core';

/** Only due, private cosmetic deadlines are queried; historical receipts are not rescanned. */
export async function refreshMapRingsOnce(now = new Date()): Promise<number> {
  return runMapRingRefresh({
    queryDue: async (at, limit) => {
      const due = await db.collection('subscriptions')
        .where('mapRingRefreshAt', '<=', Timestamp.fromDate(at))
        .orderBy('mapRingRefreshAt').limit(limit).get();
      return due.docs.map((doc) => doc.id);
    },
    // Transaction re-reads current subscription + profile: a concurrent
    // renewal replaces its private deadline and must never lose the ring.
    refresh: reconcileSupporterBadge,
  }, now);
}

/** Best-effort expiry backstop; normal Play expiry/revoke notifications also clear the ring. */
export const refreshMapRings = onSchedule({
  region: 'europe-west1', schedule: 'every 5 minutes', timeZone: 'Europe/Stockholm',
  maxInstances: 1, cpu: 0.5, concurrency: 1, memory: '256MiB', timeoutSeconds: 120,
  retryCount: 3, minBackoffSeconds: 30,
}, async () => { await refreshMapRingsOnce(); });
