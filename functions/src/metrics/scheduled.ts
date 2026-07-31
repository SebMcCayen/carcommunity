/**
 * Community growth metrics — daily snapshot job.
 *
 * `metrics-captureDaily` (02:30 Europe/Stockholm) writes ONE bounded document
 * to `metrics/{YYYY-MM-DD}` recording cumulative community totals as of that
 * instant. The admin web app reads the series and charts the trend (a page Seb
 * screenshots to show how the app is growing).
 *
 * COST — this is the whole point of the design (Seb: "make sure the saved data
 * is not costing much money or filling up space").
 *
 * Reads (per run) — every figure comes from a Firestore aggregation, so the job
 * NEVER reads the documents themselves:
 *   - count() : users, events, drives(rides), crownHuntClaims, active convoys,
 *     total convoys, vehicles, and the `friends` collection-group.
 *   - sum()   : rides.distanceMeters (total km).
 *   - brand distribution: ONE count() per KNOWN make id from the vehicle
 *     catalogue (bounded ~dozens of makes) plus the `other` bucket. This is
 *     O(catalogue), NOT O(vehicles): adding a million cars adds zero reads
 *     here — only adding makes to the catalogue would. Firestore bills a
 *     count() as ceil(matched / 1000) reads, so each make is ~1 read until a
 *     single brand passes 1000 cars.
 * Total ≈ (a dozen fixed aggregations) + (catalogue make count). For a
 * single-community app that is a few dozen reads once a day — negligible.
 *
 * Storage — one doc/day, ~365/year. The body is short integer fields plus one
 * bounded brand map (only non-zero makes, short ids, small ints), well under
 * 1 KB. That is < ~0.4 MB/year and is NEVER auto-deleted: the long-term trend
 * is the deliverable, so there is deliberately no TTL on this collection. If
 * the daily granularity is ever felt to be too much, the right move is a
 * roll-up to monthly (proposed in the PR), not deletion of Seb's history.
 *
 * Idempotent — the date is the doc id and `set()` (no merge) overwrites, so
 * re-running for the same day replaces that day's doc, never appends.
 *
 * `runMetricsSnapshot` is exported (with an injectable clock) so the emulator
 * test can drive it at a deterministic instant and assert the doc's contents.
 */

import { onSchedule } from 'firebase-functions/v2/scheduler';
import { AggregateField } from 'firebase-admin/firestore';
import { logger } from 'firebase-functions';
import { db } from '../firebase';
import { catalogueManufacturers } from '../garage/vehicle-catalogue';
import { MAX_INSTANCES_SCHEDULED } from '../shared/instanceLimits';
import { withServerErrorReporting } from '../errors/serverErrors';
import {
  METRICS_COLLECTION,
  METRICS_OTHER_MAKE_ID,
  snapshotDateId,
  type MetricsSnapshot,
} from './metrics-core';

/** Runs a count() aggregation; the query stays entirely server-side. */
async function countOf(query: FirebaseFirestore.Query): Promise<number> {
  const snap = await query.count().get();
  return snap.data().count;
}

/** Runs a sum() aggregation over one numeric field; nulls are ignored by Firestore. */
async function sumOf(query: FirebaseFirestore.Query, field: string): Promise<number> {
  const snap = await query.aggregate({ total: AggregateField.sum(field) }).get();
  const value = snap.data().total;
  return typeof value === 'number' ? value : 0;
}

/**
 * Computes the brand distribution with ONE count() per catalogue make id plus
 * the `other` bucket. Bounded by the catalogue, not the vehicle count. Only
 * non-zero buckets are returned so the stored map stays tiny.
 */
async function computeBrandDistribution(): Promise<Record<string, number>> {
  const vehicles = db.collection('vehicles');
  const makeIds = [
    ...catalogueManufacturers().map((m) => m.id),
    METRICS_OTHER_MAKE_ID,
  ];

  const counts = await Promise.all(
    makeIds.map(async (makeId) => ({
      makeId,
      count: await countOf(vehicles.where('makeId', '==', makeId)),
    })),
  );

  const distribution: Record<string, number> = {};
  for (const { makeId, count } of counts) {
    if (count > 0) {
      distribution[makeId] = count;
    }
  }
  return distribution;
}

/**
 * Captures one snapshot for the day containing `now` and writes it to
 * `metrics/{date}` (idempotent overwrite). Returns the written snapshot so the
 * test can assert it directly.
 */
export async function runMetricsSnapshot(now: Date): Promise<MetricsSnapshot> {
  const rides = db.collection('rides');
  const convoys = db.collection('convoys');

  const [
    totalUsers,
    convoysCreated,
    activeConvoys,
    totalDistanceMeters,
    drivesSaved,
    eventsHeld,
    crownsCollected,
    friendEdges,
    vehicleProfiles,
    brandDistribution,
  ] = await Promise.all([
    countOf(db.collection('users')),
    countOf(convoys),
    countOf(convoys.where('status', '==', 'active')),
    sumOf(rides, 'distanceMeters'),
    countOf(rides),
    countOf(db.collection('events')),
    countOf(db.collection('crownHuntClaims')),
    // `friends` is a per-user subcollection with a doc on BOTH sides of a
    // friendship, so the collection-group count is twice the connection count.
    countOf(db.collectionGroup('friends')),
    countOf(db.collection('vehicles')),
    computeBrandDistribution(),
  ]);

  const snapshot: MetricsSnapshot = {
    date: snapshotDateId(now),
    capturedAtMs: now.getTime(),
    totalUsers,
    convoysCreated,
    totalDistanceMeters,
    eventsHeld,
    drivesSaved,
    crownsCollected,
    friendConnections: Math.floor(friendEdges / 2),
    activeConvoys,
    vehicleProfiles,
    brandDistribution,
  };

  await db.collection(METRICS_COLLECTION).doc(snapshot.date).set(snapshot);

  logger.info('Metrics snapshot written', {
    date: snapshot.date,
    totalUsers,
    convoysCreated,
    eventsHeld,
    vehicleProfiles,
    brands: Object.keys(brandDistribution).length,
  });
  return snapshot;
}

export const captureDaily = onSchedule(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_SCHEDULED,
    timeZone: 'Europe/Stockholm',
    memory: '256MiB' as const,
    timeoutSeconds: 120,
    schedule: '30 2 * * *',
  },
  withServerErrorReporting('metrics.captureDaily', async () => {
    await runMetricsSnapshot(new Date());
  }),
);
