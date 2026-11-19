/** Only server-confirmed redemptions contribute; this does not use recordInteraction. */
import { randomUUID } from 'node:crypto';
import { Timestamp } from 'firebase-admin/firestore';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { db } from '../firebase';
import { CPU_SCHEDULED } from '../shared/instanceLimits';
import { hash } from './portal-core';

export async function aggregatePortalStatistics() {
  // A durable processed marker is in the same transaction as both counters.
  // It is never TTL-deleted, so trigger replay/backfill cannot double-count.
  let done = false;
  while (!done) {
    const events = await db
      .collection('partnerRedemptionOutbox')
      .where('processed', '==', false)
      .limit(200)
      .get();
    done = events.size < 200;
    for (const event of events.docs)
      await db.runTransaction(async (tx) => {
        const s = await tx.get(event.ref);
        if (!s.exists || s.data()!.processed) return;
        const e = s.data()!;
        const refs = ['all', e.month].map((period) =>
          db.collection('partnerRedemptionStats').doc(hash(`${e.offerId}:${period}`)),
        );
        const snapshots = await Promise.all(refs.map((ref) => tx.get(ref)));
        refs.forEach((ref, i) =>
          tx.set(ref, {
            companyId: e.companyId,
            companyName: e.companyName,
            offerId: e.offerId,
            title: e.title,
            period: i === 0 ? 'all' : e.month,
            totalRedeemed: Number(snapshots[i]?.data()?.totalRedeemed ?? 0) + 1,
          }),
        );
        tx.update(s.ref, { processed: true });
      });
  }
}

/** One daily immutable snapshot prevents real-time and arbitrary time-range inference. */
export async function publishPortalStatistics(now = new Date()) {
  await aggregatePortalStatistics();
  const generation = randomUUID(),
    month = now.toISOString().slice(0, 7);
  const source = await db.collection('partnerRedemptionStats').get();
  await db
    .collection('partnerRedemptionStatsPublic')
    .doc(generation)
    .set({ createdAt: source.readTime });
  let batch = db.batch(),
    writes = 0;
  for (const s of source.docs) {
    const d = s.data();
    if (d.period !== 'all' && d.period >= month) continue;
    batch.set(
      db.collection('partnerRedemptionStatsPublic').doc(generation).collection('entries').doc(s.id),
      {
        companyId: d.companyId,
        companyName: d.companyName,
        offerId: d.offerId,
        title: d.title,
        period: d.period,
        totalRedeemed: d.totalRedeemed,
      },
    );
    if (++writes === 400) {
      await batch.commit();
      batch = db.batch();
      writes = 0;
    }
  }
  if (writes) await batch.commit();
  const state = db.collection('partnerRedemptionStatsState').doc('published');
  await db.runTransaction(async (tx) => {
    const previous = await tx.get(state);
    const priorRead = previous.data()?.sourceReadAt as Timestamp | undefined;
    if (priorRead && priorRead.toMillis() >= source.readTime.toMillis()) return;
    tx.set(state, { generation, updatedAt: source.readTime, sourceReadAt: source.readTime });
  });
  // Public generations are cache, not the durable totals or dedupe ledger.
  const old = await db
    .collection('partnerRedemptionStatsPublic')
    .where('createdAt', '<', Timestamp.fromMillis(now.getTime() - 7 * 86400000))
    .limit(5)
    .get();
  for (const generation of old.docs) {
    if ((await state.get()).data()?.generation !== generation.id)
      await db.recursiveDelete(generation.ref);
  }
}
export const publishStatistics = onSchedule(
  {
    cpu: CPU_SCHEDULED,
    schedule: 'every day 03:00',
    timeZone: 'Europe/Stockholm',
    region: 'europe-west1',
    maxInstances: 1,
    concurrency: 1,
    memory: '256MiB',
    timeoutSeconds: 540,
  },
  async () => {
    await publishPortalStatistics();
  },
);
